package io.kestra.plugin.mercury;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
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
    private TestLogAppender testLogAppender;
    private Logger rootLogger;

    static class TestLogAppender extends AppenderBase<ILoggingEvent> {
        final CopyOnWriteArrayList<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject);
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        testLogAppender = new TestLogAppender();
        testLogAppender.start();
        rootLogger.addAppender(testLogAppender);
    }

    @AfterEach
    void tearDown() {
        if (rootLogger != null && testLogAppender != null) {
            rootLogger.detachAppender(testLogAppender);
            testLogAppender.stop();
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
        wireMockServer.stubFor(get(urlPathEqualTo("/account/missing-uuid"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Not Found\"}")));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("missing-uuid"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Not found (404)"));
        assertThat(ex.getMessage(), containsString("The requested Mercury resource does not exist or is not visible to this token"));
    }

    @Test
    void test429WithRetryAfterThenSuccess() throws Exception {
        String successJson = """
            {
              "accounts": [
                {
                  "id": "acc-retried",
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
            .inScenario("RateLimitRetryAfter")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(429)
                .withHeader("Retry-After", "0")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Too many requests\"}"))
            .willSetStateTo("RetriedState"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("RateLimitRetryAfter")
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
        assertThat(output.getRows().get(0).getId(), is("acc-retried"));
    }

    @Test
    void test429WithoutRetryAfterThenSuccess() throws Exception {
        String successJson = """
            {
              "accounts": [
                {
                  "id": "acc-backoff",
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
            .initialBackoffMs(10L)
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).getId(), is("acc-backoff"));
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
            .initialBackoffMs(5L)
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(ex.getMessage(), containsString("Rate limited (429)"));
        assertThat(ex.getMessage(), containsString("All retry attempts exhausted"));
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

        for (ILoggingEvent event : testLogAppender.events) {
            String formattedMessage = event.getFormattedMessage();
            if (formattedMessage != null) {
                assertThat(formattedMessage, not(containsString(sensitiveToken)));
                assertThat(formattedMessage, not(containsString("super-secret-log-token-456")));
            }
        }
    }
}
