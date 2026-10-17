package io.kestra.plugin.mercury.accounts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.mercury.model.Account;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListTest {

    @Inject
    private RunContextFactory runContextFactory;

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

    @Test
    void testSinglePageFetch() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Checking Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 100.50,
                  "availableBalance": 100.50,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com/account/4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                },
                {
                  "id": "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121",
                  "accountNumber": "3333",
                  "routingNumber": "4444",
                  "name": "Savings Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "savings",
                  "currentBalance": 5000.00,
                  "availableBalance": 5000.00,
                  "canSendRealTimePayments": false,
                  "createdAt": "2024-01-02T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com/account/5c65f4e7-83bb-4d18-c4d7-0a5b67b75121"
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .withHeader("Authorization", equalTo("Bearer secret-token:valid-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());

        Account first = output.getRows().get(0);
        assertThat(first.getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
        assertThat(first.getName(), is("Checking Account"));
        assertThat(first.getCurrentBalance(), is(new BigDecimal("100.50")));
        assertThat(first.getCanSendRealTimePayments(), is(true));

        Account second = output.getRows().get(1);
        assertThat(second.getId(), is("5c65f4e7-83bb-4d18-c4d7-0a5b67b75121"));
        assertThat(second.getKind(), is("savings"));
    }

    @Test
    void testMultiPageWalk() throws Exception {
        String page1 = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Account 1",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                "previousPage": null
              }
            }
            """;

        String page2 = """
            {
              "accounts": [
                {
                  "id": "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121",
                  "accountNumber": "3333",
                  "routingNumber": "4444",
                  "name": "Account 2",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "savings",
                  "currentBalance": 20.00,
                  "availableBalance": 20.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-02T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("MultiPageWalk")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1))
            .willSetStateTo("Page2"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .inScenario("MultiPageWalk")
            .whenScenarioStateIs("Page2")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page2)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
        assertThat(output.getRows().get(1).getId(), is("5c65f4e7-83bb-4d18-c4d7-0a5b67b75121"));
    }

    @Test
    void testRepeatedCursorGuard() throws Exception {
        String pageWithSameCursor = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Account 1",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": "looping-cursor",
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pageWithSameCursor)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
    }

    @Test
    void testEmptyPage() throws Exception {
        String emptyResponse = """
            {
              "accounts": [],
              "page": {
                "nextPage": null,
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(emptyResponse)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), hasSize(0));
    }

    @Test
    void testPageCapReachedWithNextCursorFails() {
        String pageJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Account 1",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121",
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pageJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .pageCap(Property.ofValue(1))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Pagination reached the maximum pageCap of 1 but more accounts remain"));
    }

    @Test
    void testPageCapReachedWithoutNextCursorSucceeds() throws Exception {
        String pageJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Account 1",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pageJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .pageCap(Property.ofValue(1))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
    }

    @Test
    void testFetchOne() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "First Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121",
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testStoreIon() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Stored Account 1",
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
              "page": {
                "nextPage": null,
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getUri().toString(), containsString(".ion"));

        try (InputStream is = runContext.storage().getFile(output.getUri());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            List<Object> records = FileSerde.readAll(reader).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(1));
            Map<?, ?> record1 = (Map<?, ?>) records.get(0);
            assertThat(record1.get("id"), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
        }
    }

    @Test
    void testFetchNone() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Account 1",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.NONE))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testLimitOutOfRangeRejected() {
        var taskTooHigh = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(5000))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException exHigh = assertThrows(IllegalArgumentException.class, () -> taskTooHigh.run(runContext));
        assertThat(exHigh.getMessage(), containsString("limit must be between 1 and 1000"));

        var taskTooLow = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(0))
            .build();

        IllegalArgumentException exLow = assertThrows(IllegalArgumentException.class, () -> taskTooLow.run(runContext));
        assertThat(exLow.getMessage(), containsString("limit must be between 1 and 1000"));
    }

    @Test
    void testPageCapOutOfRangeRejected() {
        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(0))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pageCap must be at least 1"));
    }

    @Test
    void testOrderEnum() throws Exception {
        String responseJson = """
            {
              "accounts": [],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .withQueryParam("order", equalTo("desc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .order(Property.ofValue(io.kestra.plugin.mercury.accounts.List.Order.DESC))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/accounts"))
            .withQueryParam("order", equalTo("desc")));
    }

    @Test
    void testUnknownFieldsIgnored() throws Exception {
        String responseWithUnknownFields = """
            {
              "extraRootField": "foo",
              "accounts": [
                {
                  "id": "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010",
                  "accountNumber": "1111",
                  "routingNumber": "2222",
                  "name": "Account 1",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 10.00,
                  "availableBalance": 10.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "unknownBrandNewField": 42,
                  "extraNestedObject": { "k": "v" }
                }
              ],
              "page": {
                "nextPage": null,
                "previousPage": null,
                "futurePageCursor": "abc"
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseWithUnknownFields)));

        var task = io.kestra.plugin.mercury.accounts.List.builder()
            .id(UUID.randomUUID().toString())
            .type(io.kestra.plugin.mercury.accounts.List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        io.kestra.plugin.mercury.accounts.List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRows().get(0).getId(), is("4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"));
    }
}
