package io.kestra.plugin.mercury.statements;

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
import io.kestra.plugin.mercury.AbstractMercuryList;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.kestra.plugin.mercury.WireMockTestUtils.statementsPageJson;
import static io.kestra.plugin.mercury.WireMockTestUtils.stubGet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListTest {

    private static final String ACCOUNT_ID = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";

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

    private static final String FULL_STATEMENT_JSON = """
        {
          "id": "11111111-2222-3333-4444-555555555555",
          "startDate": "2024-01-01T00:00:00Z",
          "endDate": "2024-01-31T23:59:59Z",
          "accountNumber": "1234567890",
          "routingNumber": "021000021",
          "companyLegalName": "Acme Corp LLC",
          "companyLegalAddress": {
            "name": "Acme Operations",
            "address1": "123 Market St",
            "address2": "Suite 400",
            "city": "San Francisco",
            "region": "CA",
            "postalCode": "94105",
            "country": "US"
          },
          "ein": "12-3456789",
          "endingBalance": 12500.75,
          "downloadUrl": "https://api.mercury.com/download/statement-1111.pdf",
          "transactions": [
            {
              "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
              "createdAt": "2024-01-05T14:30:00Z",
              "postedAt": "2024-01-06T09:15:00Z"
            },
            {
              "id": "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee",
              "createdAt": "2024-01-10T11:00:00Z",
              "postedAt": null
            }
          ]
        }
        """;

    @Test
    void testSinglePageFetchFullPayload() throws Exception {
        var responseJson = statementsPageJson(FULL_STATEMENT_JSON, null, null);
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc"), Map.of("Authorization", "Bearer secret-token:valid-token"), responseJson);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), hasSize(1));

        var statement = output.getRows().getFirst();
        assertThat(statement.getId(), is("11111111-2222-3333-4444-555555555555"));
        assertThat(statement.getStartDate(), is(Instant.parse("2024-01-01T00:00:00Z")));
        assertThat(statement.getEndDate(), is(Instant.parse("2024-01-31T23:59:59Z")));
        assertThat(statement.getAccountNumber(), is("1234567890"));
        assertThat(statement.getRoutingNumber(), is("021000021"));
        assertThat(statement.getCompanyLegalName(), is("Acme Corp LLC"));
        assertThat(statement.getEin(), is("12-3456789"));
        assertThat(statement.getEndingBalance(), is(new BigDecimal("12500.75")));
        assertThat(statement.getDownloadUrl(), is("https://api.mercury.com/download/statement-1111.pdf"));

        var addr = statement.getCompanyLegalAddress();
        assertThat(addr, notNullValue());
        assertThat(addr.getName(), is("Acme Operations"));
        assertThat(addr.getAddress1(), is("123 Market St"));
        assertThat(addr.getAddress2(), is("Suite 400"));
        assertThat(addr.getCity(), is("San Francisco"));
        assertThat(addr.getRegion(), is("CA"));
        assertThat(addr.getPostalCode(), is("94105"));
        assertThat(addr.getCountry(), is("US"));

        assertThat(statement.getTransactions(), hasSize(2));
        var tx1 = statement.getTransactions().getFirst();
        assertThat(tx1.getId(), is("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertThat(tx1.getCreatedAt(), is(Instant.parse("2024-01-05T14:30:00Z")));
        assertThat(tx1.getPostedAt(), is(Instant.parse("2024-01-06T09:15:00Z")));

        var tx2 = statement.getTransactions().get(1);
        assertThat(tx2.getId(), is("ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee"));
        assertThat(tx2.getCreatedAt(), is(Instant.parse("2024-01-10T11:00:00Z")));
        assertThat(tx2.getPostedAt(), nullValue());
    }

    @Test
    void testCursorWalk() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        var statementPage1 = """
            {
              "id": "11111111-0000-0000-0000-000000000001",
              "startDate": "2024-02-01T00:00:00Z",
              "endDate": "2024-02-29T23:59:59Z",
              "accountNumber": "1234567890",
              "routingNumber": "021000021",
              "companyLegalName": "Acme Corp LLC",
              "endingBalance": 20000.00,
              "downloadUrl": "https://api.mercury.com/download/1.pdf"
            }
            """;
        var statementPage2 = """
            {
              "id": "11111111-0000-0000-0000-000000000002",
              "startDate": "2024-01-01T00:00:00Z",
              "endDate": "2024-01-31T23:59:59Z",
              "accountNumber": "1234567890",
              "routingNumber": "021000021",
              "companyLegalName": "Acme Corp LLC",
              "endingBalance": 10000.00,
              "downloadUrl": "https://api.mercury.com/download/2.pdf"
            }
            """;

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc"),
            statementsPageJson(statementPage1, "cursor-page-2", null));

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc", "start_after", "cursor-page-2"),
            statementsPageJson(statementPage2, null, "cursor-page-1"));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().getFirst().getId(), is("11111111-0000-0000-0000-000000000001"));
        assertThat(output.getRows().get(1).getId(), is("11111111-0000-0000-0000-000000000002"));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(path)).withQueryParam("start_after", absent()));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(path)).withQueryParam("start_after", equalTo("cursor-page-2")));
    }

    @Test
    void testPageCapTruncationFailsWhenMoreRemain() {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc"),
            statementsPageJson(FULL_STATEMENT_JSON, "more-pages-exist", null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .pageCap(Property.ofValue(1))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Pagination reached the maximum pageCap of 1 but more statements remain"));
    }

    @Test
    void testFetchOneSendsLimitOne() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1", "order", "desc"),
            statementsPageJson(FULL_STATEMENT_JSON, null, null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is("11111111-2222-3333-4444-555555555555"));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(path)).withQueryParam("limit", equalTo("1")));
    }

    @Test
    void testStoreWritesIon() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc"),
            statementsPageJson(FULL_STATEMENT_JSON, null, null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), notNullValue());

        try (var is = runContext.storage().getFile(output.getUri())) {
            var records = FileSerde.readAll(is).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(1));
            @SuppressWarnings("unchecked")
            var map = (Map<String, Object>) records.getFirst();
            assertThat(map.get("id"), is("11111111-2222-3333-4444-555555555555"));
            assertThat(map.get("accountNumber"), is("1234567890"));
        }
    }

    @Test
    void testNoneFetchType() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc"),
            statementsPageJson(FULL_STATEMENT_JSON, null, null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
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
    void testDefaultOrderIsDesc() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "desc"),
            statementsPageJson(FULL_STATEMENT_JSON, null, null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var runContext = runContextFactory.of();
        task.run(runContext);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(path))
            .withQueryParam("order", equalTo("desc")));
    }

    @Test
    void testExplicitOrderAsc() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path, Map.of("limit", "1000", "order", "asc"),
            statementsPageJson(FULL_STATEMENT_JSON, null, null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .order(Property.ofValue(AbstractMercuryList.Order.ASC))
            .build();

        var runContext = runContextFactory.of();
        task.run(runContext);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(path))
            .withQueryParam("order", equalTo("asc")));
    }

    @Test
    void testStartAndEndReachQuery() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        stubGet(wireMockServer, path,
            Map.of("limit", "1000", "order", "desc", "start", "2024-01-01", "end", "2024-03-31"),
            statementsPageJson(FULL_STATEMENT_JSON, null, null));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .start(Property.ofValue("2024-01-01"))
            .end(Property.ofValue("2024-03-31"))
            .build();

        var runContext = runContextFactory.of();
        task.run(runContext);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(path))
            .withQueryParam("start", equalTo("2024-01-01"))
            .withQueryParam("end", equalTo("2024-03-31")));
    }

    @Test
    void testInvalidDateStringsRejectedWithNoRequest() {
        var taskInvalidStart = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .start(Property.ofValue("not-a-date"))
            .build();

        var runContext = runContextFactory.of();
        var exStart = assertThrows(IllegalArgumentException.class, () -> taskInvalidStart.run(runContext));
        assertThat(exStart.getMessage(), containsString("Invalid start 'not-a-date': must be in YYYY-MM-DD format"));

        var taskInvalidEnd = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .end(Property.ofValue("2024/01/15"))
            .build();

        var exEnd = assertThrows(IllegalArgumentException.class, () -> taskInvalidEnd.run(runContext));
        assertThat(exEnd.getMessage(), containsString("Invalid end '2024/01/15': must be in YYYY-MM-DD format"));

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testInvalidAccountIdRejectedWithNoRequest() {
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("not-a-uuid"))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Invalid accountId 'not-a-uuid': must be a valid UUID"));

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testApi400SurfacesInErrorMessage() {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        wireMockServer.stubFor(get(urlPathEqualTo(path))
            .willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Date range between start and end cannot exceed 3 months\"}")));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .start(Property.ofValue("2024-01-01"))
            .end(Property.ofValue("2024-06-30"))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Mercury API error (400)"));
        assertThat(ex.getMessage(), containsString("Date range between start and end cannot exceed 3 months"));
    }

    @Test
    void testUnknownFieldsIgnored() throws Exception {
        var path = "/account/" + ACCOUNT_ID + "/statements";

        var responseWithUnknowns = """
            {
              "statements": [
                {
                  "id": "11111111-2222-3333-4444-555555555555",
                  "startDate": "2024-01-01T00:00:00Z",
                  "endDate": "2024-01-31T23:59:59Z",
                  "accountNumber": "1234567890",
                  "routingNumber": "021000021",
                  "companyLegalName": "Acme Corp LLC",
                  "endingBalance": 5000.00,
                  "downloadUrl": "https://api.mercury.com/download/test.pdf",
                  "unexpectedStatementField": "ignoredValue",
                  "transactions": [
                    {
                      "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                      "createdAt": "2024-01-05T14:30:00Z",
                      "extraTxProperty": true
                    }
                  ]
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": null,
                "extraCursorData": 42
              },
              "extraRootField": "foo"
            }
            """;

        stubGet(wireMockServer, path, responseWithUnknowns);

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
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
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();
        assertThat(modelValidator.isValid(defaultTask).isEmpty(), is(true));

        var configuredTask = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(250))
            .pageCap(Property.ofValue(10))
            .order(Property.ofValue(AbstractMercuryList.Order.DESC))
            .start(Property.ofValue("2024-01-01"))
            .end(Property.ofValue("2024-03-31"))
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
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidLimitZero).isPresent(), is(true));

        var invalidLimitTooHigh = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(1001))
            .build();
        assertThat(modelValidator.isValid(invalidLimitTooHigh).isPresent(), is(true));

        var invalidPageCapZero = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .pageCap(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidPageCapZero).isPresent(), is(true));
    }

    @Test
    void testFlowYamlValidationContainingListWithEnumStrings() {
        var flowYaml = """
            id: flow_validating_statements_list
            namespace: io.kestra.tests
            tasks:
              - id: list_statements
                type: io.kestra.plugin.mercury.statements.List
                apiToken: "secret-token:valid-token"
                accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                order: DESC
                limit: 500
                pageCap: 10
                start: "2024-01-01"
                end: "2024-03-31"
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
