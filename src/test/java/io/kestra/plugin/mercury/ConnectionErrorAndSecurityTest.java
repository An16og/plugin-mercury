package io.kestra.plugin.mercury;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mercury.accounts.Get;
import io.kestra.plugin.mercury.accounts.List;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ConnectionErrorAndSecurityTest {

    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer wireMockServer;
    private final CopyOnWriteArrayList<String> capturedLogMessages = new CopyOnWriteArrayList<>();
    private Object rootLogger;
    private Object appenderProxy;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        capturedLogMessages.clear();
        try {
            Class<?> appenderInterface = Class.forName("ch.qos.logback.core.Appender");
            appenderProxy = Proxy.newProxyInstance(
                appenderInterface.getClassLoader(),
                new Class<?>[]{appenderInterface},
                (proxy, method, args) -> {
                    String methodName = method.getName();
                    if ("doAppend".equals(methodName) && args != null && args.length > 0) {
                        Object event = args[0];
                        if (event != null) {
                            try {
                                Method getFormattedMsg = event.getClass().getMethod("getFormattedMessage");
                                Object msg = getFormattedMsg.invoke(event);
                                if (msg != null) {
                                    capturedLogMessages.add(msg.toString());
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        return null;
                    } else if ("isStarted".equals(methodName)) {
                        return true;
                    } else if ("getName".equals(methodName)) {
                        return "TestLogAppender";
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType.equals(boolean.class)) {
                        return true;
                    } else if (returnType.equals(int.class)) {
                        return 0;
                    }
                    return null;
                }
            );

            rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            Method addAppenderMethod = rootLogger.getClass().getMethod("addAppender", appenderInterface);
            addAppenderMethod.invoke(rootLogger, appenderProxy);
        } catch (Exception ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && appenderProxy != null) {
            try {
                Class<?> appenderInterface = Class.forName("ch.qos.logback.core.Appender");
                Method detachAppenderMethod = rootLogger.getClass().getMethod("detachAppender", appenderInterface);
                detachAppenderMethod.invoke(rootLogger, appenderProxy);
            } catch (Exception ignored) {
            }
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void test401UnauthorizedMessage() {
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Unauthorized\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:invalid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Authentication failed (401)"));
        assertThat(ex.getMessage(), containsString("Invalid or expired Mercury API token"));
    }

    @Test
    void test403ForbiddenMessage() {
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Forbidden\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token-no-scope"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Access forbidden (403)"));
        assertThat(ex.getMessage(), containsString("The API token does not have permission for this resource"));
    }

    @Test
    void test404NotFoundMessage() {
        String missingUuid = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + missingUuid))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Not Found\"}")));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(missingUuid))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Not found (404)"));
        assertThat(ex.getMessage(), containsString("The requested Mercury resource does not exist or is not visible to this token"));
    }

    @Test
    void test429WithRetryAfterZeroThenSuccess() throws Exception {
        String successJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Retried Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 100.00,
                  "availableBalance": 100.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitRetryAfterZero")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "0")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Too many requests\"}"))
            .willSetStateTo("RetriedState"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitRetryAfterZero")
            .whenScenarioStateIs("RetriedState")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(successJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
    }

    @Test
    void test429WithRetryAfterAboveCapFailsWith429Message() {
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "60")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Rate limited for 60s\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Rate limited (429): Mercury asked to wait 60000 ms (Retry-After), above the 30000 ms cap. Try again later."));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/accounts")));
    }

    @Test
    void test429WithRetryAfterZeroExactRequestCount() throws Exception {
        String successJson = """
            {
              "accounts": [],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("ExactRetryCountScenario")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "0")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Rate limited\"}"))
            .willSetStateTo("SuccessState"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("ExactRetryCountScenario")
            .whenScenarioStateIs("SuccessState")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(successJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/accounts")));
    }

    @Test
    void testRedirectBlockedWithClearMessageAndTargetReceivesNoRequest() {
        var targetServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        targetServer.start();
        try {
            targetServer.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(200)));

            wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
                .willReturn(aResponse()
                    .withStatus(302)
                    .withHeader("Location", targetServer.baseUrl() + "/accounts")));

            var task = List.builder()
                .id(UUID.randomUUID().toString())
                .type(List.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .build();

            RunContext runContext = runContextFactory.of();
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

            assertThat(ex.getMessage(), containsString("Unexpected redirect (302)"));
            assertThat(ex.getMessage(), containsString("HTTP redirects are disabled to prevent credential leakage"));

            targetServer.verify(0, getRequestedFor(anyUrl()));
        } finally {
            targetServer.stop();
        }
    }

    @Test
    void test429WithHttpDateThenSuccess() throws Exception {
        String successJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Date Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 100.00,
                  "availableBalance": 100.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        String pastDateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().minusSeconds(1));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitHttpDate")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", pastDateHeader)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Rate limited with HTTP-date\"}"))
            .willSetStateTo("DateRetriedState"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitHttpDate")
            .whenScenarioStateIs("DateRetriedState")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(successJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
    }

    @Test
    void test429WithoutRetryAfterThenSuccess() throws Exception {
        String successJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Backoff Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 50.00,
                  "availableBalance": 50.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitBackoff")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Rate limit exceeded\"}"))
            .willSetStateTo("BackoffRetriedState"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitBackoff")
            .whenScenarioStateIs("BackoffRetriedState")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(successJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
    }

    @Test
    void test429ExhaustionMessage() {
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "0")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Continuous rate limit\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Rate limited (429)"));
        assertThat(ex.getMessage(), containsString("All retry attempts exhausted"));
        wireMockServer.verify(5, getRequestedFor(urlPathEqualTo("/accounts")));
    }

    @Test
    void test500ServerErrorMessage() {
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Internal Server Error\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Mercury server error (500)"));
        assertThat(ex.getMessage(), containsString("Retry later or contact Mercury support"));
    }

    @Test
    void testHttpNonLoopbackBaseUrlRejectedBeforeRequest() {
        String nonLoopbackUrl = "http://api.mercury.com/api/v1";
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(nonLoopbackUrl))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Insecure HTTP baseUrl is only permitted for loopback hosts"));
        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testLoopbackHttpAccepted() throws Exception {
        String responseJson = """
            {
              "accounts": [],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue("http://127.0.0.1:" + wireMockServer.port()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);
        assertThat(output.getSize(), is(0));
    }

    @Test
    void testHttpsBaseUrlAccepted() {
        // Validation only test - verifies validateBaseUrl does not throw for HTTPS
        AbstractMercuryConnection.validateBaseUrl("https://api.mercury.com/api/v1");
        AbstractMercuryConnection.validateBaseUrl("https://api-sandbox.mercury.com/api/v1");
    }

    @Test
    void testSecurityTokenNeverInToString() {
        String sensitiveToken = "secret-token:very-confidential-token-string-xyz987";
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue(sensitiveToken))
            .baseUrl(Property.ofValue("https://api.mercury.com/api/v1"))
            .build();

        String taskString = task.toString();
        assertThat(taskString, not(containsString(sensitiveToken)));
        assertThat(taskString, not(containsString("very-confidential-token-string-xyz987")));
    }

    @Test
    void testSecurityTokenNeverInLogs() throws Exception {
        String sensitiveToken = "secret-token:super-secret-log-token-456";
        String responseJson = """
            {
              "accounts": [],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue(sensitiveToken))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        task.run(runContext);

        for (String formattedMessage : capturedLogMessages) {
            if (formattedMessage != null) {
                assertThat(formattedMessage, not(containsString(sensitiveToken)));
                assertThat(formattedMessage, not(containsString("super-secret-log-token-456")));
            }
        }
    }

    @Test
    void testSecurityTokenNeverInExceptionMessages() {
        String sensitiveToken = "secret-token:sensitive-in-error-123";
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Unauthorized\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue(sensitiveToken))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), not(containsString(sensitiveToken)));
        assertThat(ex.getMessage(), not(containsString("sensitive-in-error-123")));
    }
}
