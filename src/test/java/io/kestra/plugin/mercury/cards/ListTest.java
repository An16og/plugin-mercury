package io.kestra.plugin.mercury.cards;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.YamlParser;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.kestra.plugin.mercury.WireMockTestUtils.cardsPageJson;
import static io.kestra.plugin.mercury.WireMockTestUtils.stubGet;
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

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
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
        var responseJson = cardsPageJson(FULL_CARD_JSON, null, null);

        stubGet(wireMockServer, "/cards", Map.of("limit", "500", "order", "asc"), Map.of("Authorization", "Bearer secret-token:valid-token"), responseJson);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), hasSize(1));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());

        var card = output.getRows().getFirst();
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

        assertThat(card.getExpiration(), notNullValue());
        assertThat(card.getExpiration().getMonth(), is(8));
        assertThat(card.getExpiration().getYear(), is(2028));

        assertThat(card.getBudgets(), hasSize(1));
        assertThat(card.getBudgets().getFirst().getAmountCents(), is(100000L));
        assertThat(card.getBudgets().getFirst().getRemainingAmountCents(), is(75000L));

        assertThat(card.getCategoryLocks(), contains("Airlines", "Lodging"));
        assertThat(card.getIsAgentCard(), is(true));
        assertThat(card.getNickname(), is("Jane Virtual Card"));

        assertThat(card.getMerchantLock(), notNullValue());
        assertThat(card.getMerchantLock().getName(), is("Delta Air Lines"));

        assertThat(card.getSpendLimit(), notNullValue());
        assertThat(card.getSpendLimit().getAmountCents(), is(250000L));
        assertThat(card.getSpendLimit().getAtmAmountCents(), is(50000L));
        assertThat(card.getSpendLimit().getInterval(), is("monthly"));
    }

    @Test
    void testMultiPageCursorWalk() throws Exception {
        var card1Json = """
            {
              "id": "11111111-2222-3333-4444-555555555555",
              "accountId": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
              "createdAt": "2024-01-15T10:00:00Z",
              "updatedAt": "2024-02-01T12:00:00Z",
              "lastFour": "1111",
              "nameOnCard": "Card 1",
              "userId": "u1",
              "status": "active",
              "type": "virtual",
              "kind": "debit",
              "expiration": { "month": 1, "year": 2028 },
              "spendLimitType": "cardLimit",
              "budgets": [],
              "categoryLocks": [],
              "isAgentCard": false
            }
            """;
        var card2Json = """
            {
              "id": "22222222-3333-4444-5555-666666666666",
              "accountId": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
              "createdAt": "2024-01-16T10:00:00Z",
              "updatedAt": "2024-02-02T12:00:00Z",
              "lastFour": "2222",
              "nameOnCard": "Card 2",
              "userId": "u2",
              "status": "active",
              "type": "physical",
              "kind": "credit",
              "expiration": { "month": 2, "year": 2029 },
              "spendLimitType": "cardLimit",
              "budgets": [],
              "categoryLocks": [],
              "isAgentCard": false
            }
            """;

        var page1 = cardsPageJson(card1Json, "22222222-3333-4444-5555-666666666666", null);
        var page2 = cardsPageJson(card2Json, null, "11111111-2222-3333-4444-555555555555");

        stubGet(wireMockServer, "/cards", Map.of("limit", "500", "order", "asc"), page1);

        stubGet(wireMockServer, "/cards", Map.of("limit", "500", "order", "asc", "start_after", "22222222-3333-4444-5555-666666666666"), page2);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().getFirst().getNameOnCard(), is("Card 1"));
        assertThat(output.getRows().get(1).getNameOnCard(), is("Card 2"));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards")).withoutQueryParam("start_after"));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards"))
            .withQueryParam("start_after", equalTo("22222222-3333-4444-5555-666666666666")));
    }

    @Test
    void testPageCapReachedWithMoreRemainingFails() {
        var cardJson = """
            {
              "id": "11111111-2222-3333-4444-555555555555",
              "accountId": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
              "createdAt": "2024-01-15T10:00:00Z",
              "updatedAt": "2024-02-01T12:00:00Z",
              "lastFour": "1111",
              "nameOnCard": "Card 1",
              "userId": "u1",
              "status": "active",
              "type": "virtual",
              "kind": "debit",
              "expiration": { "month": 1, "year": 2028 },
              "spendLimitType": "cardLimit",
              "budgets": [],
              "categoryLocks": [],
              "isAgentCard": false
            }
            """;
        var page = cardsPageJson(cardJson, "next-card-cursor", null);
        stubGet(wireMockServer, "/cards", page);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(1))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Pagination reached the maximum pageCap of 1 but more cards remain"));
    }

    @Test
    void testFetchOneSendsLimitOne() throws Exception {
        var responseJson = cardsPageJson(FULL_CARD_JSON, "next-id", null);

        stubGet(wireMockServer, "/cards", Map.of("limit", "1"), responseJson);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is("11111111-2222-3333-4444-555555555555"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards")).withQueryParam("limit", equalTo("1")));
    }

    @Test
    void testStoreIon() throws Exception {
        var responseJson = cardsPageJson(FULL_CARD_JSON, null, null);
        stubGet(wireMockServer, "/cards", responseJson);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getUri().toString(), containsString(".ion"));

        try (var is = runContext.storage().getFile(output.getUri())) {
            var records = FileSerde.readAll(is).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(1));
            var record = (Map<?, ?>) records.getFirst();
            assertThat(record.get("id"), is("11111111-2222-3333-4444-555555555555"));
            assertThat(record.get("lastFour"), is("4242"));
        }
    }

    @Test
    void testFetchNone() throws Exception {
        var responseJson = cardsPageJson(FULL_CARD_JSON, null, null);
        stubGet(wireMockServer, "/cards", responseJson);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testAllFiltersReachQueryStringLowercased() throws Exception {
        var responseJson = cardsPageJson("", null, null);

        stubGet(wireMockServer, "/cards", Map.of("accountId", "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010", "status", "active", "type", "virtual", "kind", "debit", "userId", "usr_12345", "isAgentCard", "true", "limit", "500", "order", "desc"), responseJson);

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

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

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
        var responseJson = cardsPageJson("", null, null);

        stubGet(wireMockServer, "/cards", Map.of("limit", "500", "order", "asc"), responseJson);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

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

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Invalid accountId 'not-a-valid-uuid': must be a valid UUID"));
        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testRenderedPropertiesReachQueryAndAreValidated() throws Exception {
        stubGet(wireMockServer, "/cards", Map.of("accountId", "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010", "limit", "10", "order", "asc"), cardsPageJson("", null, null));

        var runContext = runContextFactory.of(Map.of("accountId", "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010", "limit", 10, "badLimit", 0));
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofExpression("{{ accountId }}"))
            .limit(Property.ofExpression("{{ limit }}"))
            .build();

        task.run(runContext);
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/cards"))
            .withQueryParam("accountId", equalTo("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"))
            .withQueryParam("limit", equalTo("10")));

        var invalid = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofExpression("{{ badLimit }}"))
            .build();
        var ex = assertThrows(IllegalArgumentException.class, () -> invalid.run(runContext));
        assertThat(ex.getMessage(), containsString("limit must be between 1 and 1000"));
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

        var runContext = runContextFactory.of();
        var exHigh = assertThrows(IllegalArgumentException.class, () -> taskTooHigh.run(runContext));
        assertThat(exHigh.getMessage(), containsString("limit must be between 1 and 1000"));

        var taskTooLow = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(0))
            .build();

        var exLow = assertThrows(IllegalArgumentException.class, () -> taskTooLow.run(runContext));
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

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pageCap must be at least 1"));
    }

    @Test
    void testUnknownFieldsIgnored() throws Exception {
        var responseWithUnknowns = """
            {
              "extraTopLevel": 12345,
              "cards": [
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
                  "expiration": { "month": 8, "year": 2028 },
                  "spendLimitType": "cardLimit",
                  "budgets": [],
                  "categoryLocks": [],
                  "isAgentCard": false,
                  "unexpectedCardProperty": "abc",
                  "futureObject": { "nested": true }
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": null,
                "extraCursorField": 999
              }
            }
            """;

        stubGet(wireMockServer, "/cards", responseWithUnknowns);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().getFirst().getId(), is("11111111-2222-3333-4444-555555555555"));
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
        var flowYaml = """
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
        var flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void testPluginExamplesFlowValidation() {
        var plugin = List.class.getAnnotation(Plugin.class);
        assertThat(plugin, notNullValue());
        for (var example : plugin.examples()) {
            var code = String.join("\n", example.code());
            var flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }
    }
}
