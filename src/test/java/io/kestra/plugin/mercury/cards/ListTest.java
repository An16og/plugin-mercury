package io.kestra.plugin.mercury.cards;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.mercury.model.Card;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private ModelValidator modelValidator;

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

    private static final String FULL_CARD_JSON = """
        {
          "id": "11111111-2222-3333-4444-555555555555",
          "accountId": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
          "createdAt": "2024-01-15T10:00:00Z",
          "updatedAt": "2024-02-01T12:00:00Z",
          "lastFour": "4242",
          "nameOnCard": "Jane Doe",
          "userId": "usr_998877",
          "status": "active",
          "type": "virtual",
          "kind": "debit",
          "expiration": {
            "month": 8,
            "year": 2028
          },
          "spendLimitType": "cardLimit",
          "budgets": [
            {
              "id": "bbbbbbbb-1111-2222-3333-444444444444",
              "name": "Travel Budget",
              "amountCents": 100000,
              "remainingAmountCents": 75000
            }
          ],
          "categoryLocks": ["Airlines", "Lodging"],
          "isAgentCard": true,
          "nickname": "Jane Virtual Card",
          "merchantLock": {
            "id": "mmmmmmmm-1111-2222-3333-444444444444",
            "name": "Delta Air Lines"
          },
          "physicalCardStatus": null,
          "spendLimit": {
            "amountCents": 250000,
            "atmAmountCents": 50000,
            "interval": "monthly"
          }
        }
        """;

    @Test
    void testSinglePageFetchFullPayload() throws Exception {
        String responseJson = "{\n" +
            "  \"cards\": [" + FULL_CARD_JSON + "],\n" +
            "  \"page\": { \"nextPage\": null, \"previousPage\": null }\n" +
            "}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .withHeader("Authorization", equalTo("Bearer secret-token:valid-token"))
            .withQueryParam("limit", equalTo("500"))
            .withQueryParam("order", equalTo("asc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());

        Card card = output.getRows().get(0);
        assertThat(card.getId(), is("11111111-2222-3333-4444-555555555555"));
        assertThat(card.getAccountId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
        assertThat(card.getCreatedAt(), is(Instant.parse("2024-01-15T10:00:00Z")));
        assertThat(card.getUpdatedAt(), is(Instant.parse("2024-02-01T12:00:00Z")));
        assertThat(card.getLastFour(), is("4242"));
        assertThat(card.getNameOnCard(), is("Jane Doe"));
        assertThat(card.getUserId(), is("usr_998877"));
        assertThat(card.getStatus(), is("active"));
        assertThat(card.getType(), is("virtual"));
        assertThat(card.getKind(), is("debit"));

        // Expiration
        assertThat(card.getExpiration(), notNullValue());
        assertThat(card.getExpiration().getMonth(), is(8));
        assertThat(card.getExpiration().getYear(), is(2028));

        // Budgets in cents
        assertThat(card.getBudgets(), hasSize(1));
        assertThat(card.getBudgets().get(0).getAmountCents(), is(100000L));
        assertThat(card.getBudgets().get(0).getRemainingAmountCents(), is(75000L));

        // Category locks
        assertThat(card.getCategoryLocks(), contains("Airlines", "Lodging"));
        assertThat(card.getIsAgentCard(), is(true));
        assertThat(card.getNickname(), is("Jane Virtual Card"));

        // Merchant lock
        assertThat(card.getMerchantLock(), notNullValue());
        assertThat(card.getMerchantLock().getName(), is("Delta Air Lines"));

        // Spend limit in cents
        assertThat(card.getSpendLimit(), notNullValue());
        assertThat(card.getSpendLimit().getAmountCents(), is(250000L));
        assertThat(card.getSpendLimit().getAtmAmountCents(), is(50000L));
        assertThat(card.getSpendLimit().getInterval(), is("monthly"));
    }

    @Test
    void testMultiPageCursorWalk() throws Exception {
        String page1 = """
            {
              \"cards\": [
                {
                  \"id\": \"11111111-2222-3333-4444-555555555555\",
                  \"accountId\": \"4b54e3d6-72aa-4c07-b3c6-9f4a56a64010\",
                  \"createdAt\": \"2024-01-15T10:00:00Z\",
                  \"updatedAt\": \"2024-02-01T12:00:00Z\",
                  \"lastFour\": \"1111\",
                  \"nameOnCard\": \"Card 1\",
                  \"userId\": \"u1\",
                  \"status\": \"active\",
                  \"type\": \"virtual\",
                  \"kind\": \"debit\",
                  \"expiration\": { \"month\": 1, \"year\": 2028 },
                  \"spendLimitType\": \"cardLimit\",
                  \"budgets\": [],
                  \"categoryLocks\": [],
                  \"isAgentCard\": false
                }
              ],
              \"page\": {
                \"nextPage\": \"22222222-3333-4444-5555-666666666666\",
                \"previousPage\": null
              }
            }
            """;

        String page2 = """
            {
              \"cards\": [
                {
                  \"id\": \"22222222-3333-4444-5555-666666666666\",
                  \"accountId\": \"4b54e3d6-72aa-4c07-b3c6-9f4a56a64010\",
                  \"createdAt\": \"2024-01-16T10:00:00Z\",
                  \"updatedAt\": \"2024-02-02T12:00:00Z\",
                  \"lastFour\": \"2222\",
                  \"nameOnCard\": \"Card 2\",
                  \"userId\": \"u2\",
                  \"status\": \"active\",
                  \"type\": \"physical\",
                  \"kind\": \"credit\",
                  \"expiration\": { \"month\": 2, \"year\": 2029 },
                  \"spendLimitType\": \"cardLimit\",
                  \"budgets\": [],
                  \"categoryLocks\": [],
                  \"isAgentCard\": false
                }
              ],
              \"page\": {
                \"nextPage\": null,
                \"previousPage\": \"11111111-2222-3333-4444-555555555555\"
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .withQueryParam("limit", equalTo("500"))
            .withQueryParam("order", equalTo("asc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1)));

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .withQueryParam("limit", equalTo("500"))
            .withQueryParam("order", equalTo("asc"))
            .withQueryParam("start_after", equalTo("22222222-3333-4444-5555-666666666666"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page2)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getNameOnCard(), is("Card 1"));
        assertThat(output.getRows().get(1).getNameOnCard(), is("Card 2"));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards")).withoutQueryParam("start_after"));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards"))
            .withQueryParam("start_after", equalTo("22222222-3333-4444-5555-666666666666")));
    }

    @Test
    void testEmptyPageStopsPagination() throws Exception {
        String emptyResponse = "{\"cards\": [], \"page\": {\"nextPage\": \"cursor-123\", \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(emptyResponse)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards")));
    }

    @Test
    void testRepeatedCursorStopsPagination() throws Exception {
        String page = """
            {
              \"cards\": [
                {
                  \"id\": \"11111111-2222-3333-4444-555555555555\",
                  \"accountId\": \"4b54e3d6-72aa-4c07-b3c6-9f4a56a64010\",
                  \"createdAt\": \"2024-01-15T10:00:00Z\",
                  \"updatedAt\": \"2024-02-01T12:00:00Z\",
                  \"lastFour\": \"1111\",
                  \"nameOnCard\": \"Card 1\",
                  \"userId\": \"u1\",
                  \"status\": \"active\",
                  \"type\": \"virtual\",
                  \"kind\": \"debit\",
                  \"expiration\": { \"month\": 1, \"year\": 2028 },
                  \"spendLimitType\": \"cardLimit\",
                  \"budgets\": [],
                  \"categoryLocks\": [],
                  \"isAgentCard\": false
                }
              ],
              \"page\": {
                \"nextPage\": \"repeat-card-cursor\",
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .inScenario("RepeatedCursorCards")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page))
            .willSetStateTo("NextState"));

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .inScenario("RepeatedCursorCards")
            .whenScenarioStateIs("NextState")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/cards")));
    }

    @Test
    void testPageCapReachedWithMoreRemainingFails() {
        String page = """
            {
              \"cards\": [
                {
                  \"id\": \"11111111-2222-3333-4444-555555555555\",
                  \"accountId\": \"4b54e3d6-72aa-4c07-b3c6-9f4a56a64010\",
                  \"createdAt\": \"2024-01-15T10:00:00Z\",
                  \"updatedAt\": \"2024-02-01T12:00:00Z\",
                  \"lastFour\": \"1111\",
                  \"nameOnCard\": \"Card 1\",
                  \"userId\": \"u1\",
                  \"status\": \"active\",
                  \"type\": \"virtual\",
                  \"kind\": \"debit\",
                  \"expiration\": { \"month\": 1, \"year\": 2028 },
                  \"spendLimitType\": \"cardLimit\",
                  \"budgets\": [],
                  \"categoryLocks\": [],
                  \"isAgentCard\": false
                }
              ],
              \"page\": {
                \"nextPage\": \"next-card-cursor\",
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(1))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Pagination reached the maximum pageCap of 1 but more cards remain"));
    }

    @Test
    void testPageCapReachedWithoutMoreRemainingSucceeds() throws Exception {
        String page = """
            {
              \"cards\": [
                {
                  \"id\": \"11111111-2222-3333-4444-555555555555\",
                  \"accountId\": \"4b54e3d6-72aa-4c07-b3c6-9f4a56a64010\",
                  \"createdAt\": \"2024-01-15T10:00:00Z\",
                  \"updatedAt\": \"2024-02-01T12:00:00Z\",
                  \"lastFour\": \"1111\",
                  \"nameOnCard\": \"Card 1\",
                  \"userId\": \"u1\",
                  \"status\": \"active\",
                  \"type\": \"virtual\",
                  \"kind\": \"debit\",
                  \"expiration\": { \"month\": 1, \"year\": 2028 },
                  \"spendLimitType\": \"cardLimit\",
                  \"budgets\": [],
                  \"categoryLocks\": [],
                  \"isAgentCard\": false
                }
              ],
              \"page\": {
                \"nextPage\": null,
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(1))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);
        assertThat(output.getSize(), is(1));
    }

    @Test
    void testFetchOneSendsLimitOne() throws Exception {
        String responseJson = "{\"cards\": [" + FULL_CARD_JSON + "], \"page\": {\"nextPage\": \"next-id\", \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .withQueryParam("limit", equalTo("1"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is("11111111-2222-3333-4444-555555555555"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards")).withQueryParam("limit", equalTo("1")));
    }

    @Test
    void testStoreIon() throws Exception {
        String responseJson = "{\"cards\": [" + FULL_CARD_JSON + "], \"page\": {\"nextPage\": null, \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getUri().toString(), containsString(".ion"));

        try (InputStream is = runContext.storage().getFile(output.getUri());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            java.util.List<Object> records = FileSerde.readAll(reader).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(1));
            Map<?, ?> record = (Map<?, ?>) records.get(0);
            assertThat(record.get("id"), is("11111111-2222-3333-4444-555555555555"));
            assertThat(record.get("lastFour"), is("4242"));
        }
    }

    @Test
    void testFetchNone() throws Exception {
        String responseJson = "{\"cards\": [" + FULL_CARD_JSON + "], \"page\": {\"nextPage\": null, \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testAllFiltersReachQueryStringLowercased() throws Exception {
        String responseJson = "{\"cards\": [], \"page\": {\"nextPage\": null, \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .withQueryParam("accountId", equalTo("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"))
            .withQueryParam("status", equalTo("active"))
            .withQueryParam("type", equalTo("virtual"))
            .withQueryParam("kind", equalTo("debit"))
            .withQueryParam("userId", equalTo("usr_12345"))
            .withQueryParam("isAgentCard", equalTo("true"))
            .withQueryParam("limit", equalTo("500"))
            .withQueryParam("order", equalTo("desc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"))
            .status(Property.ofValue(List.Status.ACTIVE))
            .cardType(Property.ofValue(List.Type.VIRTUAL))
            .kind(Property.ofValue(List.Kind.DEBIT))
            .userId(Property.ofValue("  usr_12345  "))
            .isAgentCard(Property.ofValue(true))
            .order(Property.ofValue(List.Order.DESC))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards"))
            .withQueryParam("accountId", equalTo("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"))
            .withQueryParam("status", equalTo("active"))
            .withQueryParam("type", equalTo("virtual"))
            .withQueryParam("kind", equalTo("debit"))
            .withQueryParam("userId", equalTo("usr_12345"))
            .withQueryParam("isAgentCard", equalTo("true"))
            .withQueryParam("limit", equalTo("500"))
            .withQueryParam("order", equalTo("desc")));
    }

    @Test
    void testOmittedFiltersSendNothing() throws Exception {
        String responseJson = "{\"cards\": [], \"page\": {\"nextPage\": null, \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .withQueryParam("limit", equalTo("500"))
            .withQueryParam("order", equalTo("asc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards")));
    }

    @Test
    void testInvalidAccountIdRejectedWithoutRequest() {
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("not-a-valid-uuid"))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Invalid accountId 'not-a-valid-uuid': must be a valid UUID"));
        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testLimitOutOfRangeRejected() {
        var taskTooHigh = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(1001))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException exHigh = assertThrows(IllegalArgumentException.class, () -> taskTooHigh.run(runContext));
        assertThat(exHigh.getMessage(), containsString("limit must be between 1 and 1000"));

        var taskTooLow = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(0))
            .build();

        IllegalArgumentException exLow = assertThrows(IllegalArgumentException.class, () -> taskTooLow.run(runContext));
        assertThat(exLow.getMessage(), containsString("limit must be between 1 and 1000"));
    }

    @Test
    void testPageCapOutOfRangeRejected() {
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(0))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pageCap must be at least 1"));
    }

    @Test
    void testUnknownFieldsIgnored() throws Exception {
        String responseWithUnknowns = """
            {
              \"extraTopLevel\": 12345,
              \"cards\": [
                {
                  \"id\": \"11111111-2222-3333-4444-555555555555\",
                  \"accountId\": \"4b54e3d6-72aa-4c07-b3c6-9f4a56a64010\",
                  \"createdAt\": \"2024-01-15T10:00:00Z\",
                  \"updatedAt\": \"2024-02-01T12:00:00Z\",
                  \"lastFour\": \"4242\",
                  \"nameOnCard\": \"Jane Doe\",
                  \"userId\": \"usr_998877\",
                  \"status\": \"active\",
                  \"type\": \"virtual\",
                  \"kind\": \"debit\",
                  \"expiration\": { \"month\": 8, \"year\": 2028 },
                  \"spendLimitType\": \"cardLimit\",
                  \"budgets\": [],
                  \"categoryLocks\": [],
                  \"isAgentCard\": false,
                  \"unexpectedCardProperty\": \"abc\",
                  \"futureObject\": { \"nested\": true }
                }
              ],
              \"page\": {
                \"nextPage\": null,
                \"previousPage\": null,
                \"extraCursorField\": 999
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseWithUnknowns)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).getId(), is("11111111-2222-3333-4444-555555555555"));
    }

    @Test
    void testModelValidatorValidForDefaultAndConfiguredList() {
        var defaultTask = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();
        assertThat(modelValidator.isValid(defaultTask).isEmpty(), is(true));

        var configuredTask = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(250))
            .pageCap(Property.ofValue(10))
            .order(Property.ofValue(List.Order.DESC))
            .status(Property.ofValue(List.Status.ACTIVE))
            .cardType(Property.ofValue(List.Type.PHYSICAL))
            .kind(Property.ofValue(List.Kind.CREDIT))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();
        assertThat(modelValidator.isValid(configuredTask).isEmpty(), is(true));
    }

    @Test
    void testModelValidatorReportsInvalidConstantValues() {
        var invalidLimitZero = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidLimitZero).isPresent(), is(true));

        var invalidLimitTooHigh = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(1001))
            .build();
        assertThat(modelValidator.isValid(invalidLimitTooHigh).isPresent(), is(true));

        var invalidPageCapZero = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidPageCapZero).isPresent(), is(true));
    }

    @Test
    void testFlowYamlValidationContainingListWithEnumStrings() {
        String flowYaml = """
            id: flow_validating_cards_list
            namespace: io.kestra.tests
            tasks:
              - id: list_cards
                type: io.kestra.plugin.mercury.cards.List
                apiToken: "secret-token:valid-token"
                accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                status: ACTIVE
                cardType: VIRTUAL
                kind: DEBIT
                order: DESC
                limit: 250
                pageCap: 10
            """;
        Flow flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void testPluginExamplesFlowValidation() {
        var plugin = List.class.getAnnotation(Plugin.class);
        assertThat(plugin, notNullValue());
        for (Example example : plugin.examples()) {
            String code = String.join("\n", example.code());
            Flow flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }
    }

    @Test
    void testSecurityTokenNeverInLogsOrToString() throws Exception {
        String sensitiveToken = "secret-token:very-secret-card-key-987654321";
        String responseJson = "{\"cards\": [" + FULL_CARD_JSON + "], \"page\": {\"nextPage\": null, \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/cards"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue(sensitiveToken))
            .build();

        String taskString = task.toString();
        assertThat(taskString, not(containsString(sensitiveToken)));
        assertThat(taskString, not(containsString("very-secret-card-key-987654321")));

        RunContext runContext = runContextFactory.of();
        task.run(runContext);

        String allLogs = String.join("\n", capturedLogMessages);
        assertThat(allLogs, not(containsString(sensitiveToken)));
        assertThat(allLogs, not(containsString("very-secret-card-key-987654321")));
    }
}
