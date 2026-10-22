package io.kestra.plugin.mercury.transactions;

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
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.mercury.model.Transaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.Instant;
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

    @Inject
    private ModelValidator modelValidator;

    private WireMockServer wireMockServer;

    private static final String ACCOUNT_ID = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";

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
        var responseJson = """
            {
              "total": 2,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 250.75,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Acme Supplier",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-15T10:00:00Z",
                  "estimatedDeliveryDate": "2024-01-16T12:00:00Z",
                  "postedAt": "2024-01-15T11:00:00Z",
                  "dashboardLink": "https://app.mercury.com/transaction/e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                },
                {
                  "id": "f0e567d8-76a7-11e9-a22d-0b33e9de0756",
                  "accountId": "%s",
                  "amount": -50.00,
                  "counterpartyId": "c1e567d8-76a7-11e9-a22d-0b33e9de0756",
                  "counterpartyName": "Coffee Shop",
                  "status": "sent",
                  "kind": "creditCardTransaction",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": true,
                  "createdAt": "2024-01-15T14:30:00Z",
                  "estimatedDeliveryDate": "2024-01-15T14:30:00Z",
                  "postedAt": "2024-01-15T15:00:00Z",
                  "dashboardLink": "https://app.mercury.com/transaction/f0e567d8-76a7-11e9-a22d-0b33e9de0756",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID, ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withHeader("Authorization", equalTo("Bearer secret-token:valid-token"))
            .withQueryParam("limit", equalTo("1000"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());

        var first = output.getRows().get(0);
        assertThat(first.getId(), is("e0e567d8-76a7-11e9-a22d-0b33e9de0755"));
        assertThat(first.getAccountId(), is(ACCOUNT_ID));
        assertThat(first.getAmount(), is(new BigDecimal("250.75")));
        assertThat(first.getCounterpartyName(), is("Acme Supplier"));
        assertThat(first.getStatus(), is("sent"));
        assertThat(first.getKind(), is("outgoingPayment"));
        assertThat(first.getCreatedAt(), is(Instant.parse("2024-01-15T10:00:00Z")));

        var second = output.getRows().get(1);
        assertThat(second.getId(), is("f0e567d8-76a7-11e9-a22d-0b33e9de0756"));
        assertThat(second.getAmount(), is(new BigDecimal("-50.00")));
    }

    @Test
    void testMultiPageOffsetWalk() throws Exception {
        var page1 = """
            {
              "total": 2,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 100.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Vendor 1",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        var page2 = """
            {
              "total": 2,
              "transactions": [
                {
                  "id": "f0e567d8-76a7-11e9-a22d-0b33e9de0756",
                  "accountId": "%s",
                  "amount": 200.00,
                  "counterpartyId": "c1e567d8-76a7-11e9-a22d-0b33e9de0756",
                  "counterpartyName": "Vendor 2",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-02T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-02T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("limit", equalTo("1"))
            .inScenario("OffsetPagination")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1))
            .willSetStateTo("Page2"));

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("offset", equalTo("1"))
            .withQueryParam("limit", equalTo("1"))
            .inScenario("OffsetPagination")
            .whenScenarioStateIs("Page2")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page2)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(1))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getId(), is("e0e567d8-76a7-11e9-a22d-0b33e9de0755"));
        assertThat(output.getRows().get(1).getId(), is("f0e567d8-76a7-11e9-a22d-0b33e9de0756"));

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("offset", equalTo("0")));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("offset", equalTo("1")));
    }

    @Test
    void testStopAtTotal() throws Exception {
        var responseJson = """
            {
              "total": 1,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 10.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Acme Inc",
                  "status": "pending",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": false,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-02T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

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
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions")));
    }

    @Test
    void testEmptyPage() throws Exception {
        var responseJson = """
            {
              "total": 0,
              "transactions": []
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), hasSize(0));
    }

    @Test
    void testEmptyPageWithNonZeroTotalStops() throws Exception {
        var emptyPageWithNonZeroTotal = """
            {
              "total": 10,
              "transactions": []
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(emptyPageWithNonZeroTotal)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions")));
    }

    @Test
    void testPageCapReachedWithMoreRemainingFails() {
        var page1 = """
            {
              "total": 2,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 10.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Acme Inc",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1)));

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
        assertThat(ex.getMessage(), containsString("Pagination reached the maximum pageCap of 1 but more transactions remain"));
    }

    @Test
    void testPageCapReachedWithoutMoreRemainingSucceeds() throws Exception {
        var page1 = """
            {
              "total": 1,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 10.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Acme Inc",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .pageCap(Property.ofValue(1))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(1));
    }

    @Test
    void testFetchOne() throws Exception {
        var responseJson = """
            {
              "total": 5,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 99.99,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "First Counterparty",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("limit", equalTo("1"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

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
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is("e0e567d8-76a7-11e9-a22d-0b33e9de0755"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("limit", equalTo("1"))
            .withQueryParam("offset", equalTo("0")));
    }

    @Test
    void testFetchNone() throws Exception {
        var responseJson = """
            {
              "total": 1,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 10.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Acme Inc",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

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
    void testStoreIon() throws Exception {
        var responseJson = """
            {
              "total": 1,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 500.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Stored Vendor",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-15T10:00:00Z",
                  "estimatedDeliveryDate": "2024-01-15T10:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "glAllocations": [],
                  "attachments": [],
                  "relatedTransactions": []
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

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
        assertThat(output.getUri().toString(), containsString(".ion"));

        try (InputStream is = runContext.storage().getFile(output.getUri());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            var records = FileSerde.readAll(reader).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(1));
            var record1 = (Map<?, ?>) records.get(0);
            assertThat(record1.get("id"), is("e0e567d8-76a7-11e9-a22d-0b33e9de0755"));
            assertThat(record1.get("counterpartyName"), is("Stored Vendor"));
            assertThat(record1.get("createdAt"), notNullValue());
        }
    }

    @Test
    void testFiltersReachQueryString() throws Exception {
        var responseJson = """
            {
              "total": 0,
              "transactions": []
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("limit", equalTo("50"))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("start", equalTo("2024-01-01"))
            .withQueryParam("end", equalTo("2024-01-31"))
            .withQueryParam("status", equalTo("sent"))
            .withQueryParam("search", equalTo("Payroll"))
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
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(50))
            .start(Property.ofValue("2024-01-01"))
            .end(Property.ofValue("2024-01-31"))
            .status(Property.ofValue(List.Status.SENT))
            .search(Property.ofValue("Payroll"))
            .order(Property.ofValue(List.Order.DESC))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("limit", equalTo("50"))
            .withQueryParam("offset", equalTo("0"))
            .withQueryParam("start", equalTo("2024-01-01"))
            .withQueryParam("end", equalTo("2024-01-31"))
            .withQueryParam("status", equalTo("sent"))
            .withQueryParam("search", equalTo("Payroll"))
            .withQueryParam("order", equalTo("desc")));
    }

    @Test
    void testFiltersOmittedByDefault() throws Exception {
        var responseJson = """
            {
              "total": 0,
              "transactions": []
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("limit", equalTo("1000"))
            .withQueryParam("offset", equalTo("0"))
            .withoutQueryParam("start")
            .withoutQueryParam("end")
            .withoutQueryParam("status")
            .withoutQueryParam("search")
            .withoutQueryParam("order"));
    }

    @Test
    void testStatusEnumValuesWireCasing() throws Exception {
        var responseJson = """
            {
              "total": 0,
              "transactions": []
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var runContext = runContextFactory.of();

        var expectedWireCasing = Map.of(
            List.Status.PENDING, "pending",
            List.Status.SENT, "sent",
            List.Status.CANCELLED, "cancelled",
            List.Status.FAILED, "failed",
            List.Status.REVERSED, "reversed",
            List.Status.BLOCKED, "blocked"
        );

        for (var entry : expectedWireCasing.entrySet()) {
            wireMockServer.resetRequests();

            var task = List.builder()
                .id(UUID.randomUUID().toString())
                .type(List.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(ACCOUNT_ID))
                .status(Property.ofValue(entry.getKey()))
                .build();

            task.run(runContext);

            wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
                .withQueryParam("status", equalTo(entry.getValue())));
        }

        wireMockServer.resetRequests();
        var taskWithoutStatus = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        taskWithoutStatus.run(runContext);

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withoutQueryParam("status"));
    }

    @Test
    void testFlowYamlWithUppercaseStatusAndOrder() throws Exception {
        var responseJson = """
            {
              "total": 0,
              "transactions": []
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("status", equalTo("sent"))
            .withQueryParam("order", equalTo("desc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var flowYaml = """
            id: flow_validating_uppercase_enums
            namespace: io.kestra.tests
            tasks:
              - id: list_transactions
                type: io.kestra.plugin.mercury.transactions.List
                baseUrl: "%s"
                apiToken: "secret-token:valid-token"
                accountId: "%s"
                status: SENT
                order: DESC
            """.formatted(wireMockServer.baseUrl(), ACCOUNT_ID);

        var flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));

        var task = (List) flow.getTasks().get(0);
        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("status", equalTo("sent"))
            .withQueryParam("order", equalTo("desc")));
    }

    @Test
    void testIso8601TimestampWithZAccepted() throws Exception {
        var responseJson = """
            {
              "total": 0,
              "transactions": []
            }
            """;

        var startTimestamp = "2024-01-15T10:00:00Z";
        var endTimestamp = "2024-01-16T12:00:00Z";

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("start", equalTo(startTimestamp))
            .withQueryParam("end", equalTo(endTimestamp))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .start(Property.ofValue(startTimestamp))
            .end(Property.ofValue(endTimestamp))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("start", equalTo(startTimestamp))
            .withQueryParam("end", equalTo(endTimestamp)));
    }

    @Test
    void testInvalidAccountIdRejectedWithoutRequest() {
        var invalidAccountIds = new String[]{
            "acc/123",
            "../etc/passwd",
            "acc?query=1",
            "acc#fragment",
            "acc with spaces",
            "not-a-uuid"
        };

        var runContext = runContextFactory.of();

        for (var invalidId : invalidAccountIds) {
            var task = List.builder()
                .id(UUID.randomUUID().toString())
                .type(List.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(invalidId))
                .build();

            var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
            assertThat(ex.getMessage(), containsString("must be a valid UUID"));
        }

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testInvalidDateFiltersRejectedWithoutRequest() {
        var invalidDates = new String[]{
            "not-a-date",
            "2024/01/01",
            "01-15-2024",
            "2024-13-45"
        };

        var runContext = runContextFactory.of();

        for (var invalidDate : invalidDates) {
            var taskStart = List.builder()
                .id(UUID.randomUUID().toString())
                .type(List.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(ACCOUNT_ID))
                .start(Property.ofValue(invalidDate))
                .build();

            var exStart = assertThrows(IllegalArgumentException.class, () -> taskStart.run(runContext));
            assertThat(exStart.getMessage(), containsString("must be in YYYY-MM-DD or ISO 8601 format"));

            var taskEnd = List.builder()
                .id(UUID.randomUUID().toString())
                .type(List.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(ACCOUNT_ID))
                .end(Property.ofValue(invalidDate))
                .build();

            var exEnd = assertThrows(IllegalArgumentException.class, () -> taskEnd.run(runContext));
            assertThat(exEnd.getMessage(), containsString("must be in YYYY-MM-DD or ISO 8601 format"));
        }

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testLimitOutOfRangeRejected() {
        var taskTooHigh = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
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
            .accountId(Property.ofValue(ACCOUNT_ID))
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
            .accountId(Property.ofValue(ACCOUNT_ID))
            .pageCap(Property.ofValue(0))
            .build();

        var runContext = runContextFactory.of();
        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("pageCap must be at least 1"));
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
            .limit(Property.ofValue(500))
            .pageCap(Property.ofValue(10))
            .build();
        assertThat(modelValidator.isValid(configuredTask).isEmpty(), is(true));
    }

    @Test
    void testFlowYamlValidationContainingList() {
        var flowYaml = """
            id: flow_validating_transactions_list
            namespace: io.kestra.tests
            tasks:
              - id: list_transactions
                type: io.kestra.plugin.mercury.transactions.List
                apiToken: "secret-token:valid-token"
                accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                limit: 250
                pageCap: 10
            """;
        var flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void testPluginExamplesFlowValidation() {
        var listPlugin = List.class.getAnnotation(Plugin.class);
        assertThat(listPlugin, notNullValue());
        for (Example example : listPlugin.examples()) {
            var code = String.join("\n", example.code());
            var flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }

        var getPlugin = Get.class.getAnnotation(Plugin.class);
        assertThat(getPlugin, notNullValue());
        for (Example example : getPlugin.examples()) {
            var code = String.join("\n", example.code());
            var flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }
    }

    @Test
    void testUnknownFieldsIgnored() throws Exception {
        var responseWithUnknownFields = """
            {
              "unknownTopLevel": "foo",
              "total": 1,
              "transactions": [
                {
                  "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "accountId": "%s",
                  "amount": 10.00,
                  "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
                  "counterpartyName": "Acme Inc",
                  "status": "sent",
                  "kind": "outgoingPayment",
                  "compliantWithReceiptPolicy": true,
                  "hasGeneratedReceipt": false,
                  "createdAt": "2024-01-01T00:00:00Z",
                  "estimatedDeliveryDate": "2024-01-01T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com",
                  "unknownNestedField": 999,
                  "extraObject": { "k": "v" }
                }
              ]
            }
            """.formatted(ACCOUNT_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseWithUnknownFields)));

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
        assertThat(output.getRows().get(0).getId(), is("e0e567d8-76a7-11e9-a22d-0b33e9de0755"));
    }

    @Test
    void testTimestampsParseToInstant() throws Exception {
        var json = """
            {
              "id": "e0e567d8-76a7-11e9-a22d-0b33e9de0755",
              "createdAt": "2024-01-15T10:00:00.000Z",
              "postedAt": "2024-01-15T11:30:00Z",
              "estimatedDeliveryDate": "2024-01-16T12:00:00Z",
              "failedAt": "2024-01-17T08:00:00Z"
            }
            """;
        var transaction = JacksonMapper.ofJson().readValue(json, Transaction.class);
        assertThat(transaction.getCreatedAt(), is(Instant.parse("2024-01-15T10:00:00.000Z")));
        assertThat(transaction.getPostedAt(), is(Instant.parse("2024-01-15T11:30:00Z")));
        assertThat(transaction.getEstimatedDeliveryDate(), is(Instant.parse("2024-01-16T12:00:00Z")));
        assertThat(transaction.getFailedAt(), is(Instant.parse("2024-01-17T08:00:00Z")));
    }

    @Test
    void testTokenNeverInToString() {
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .apiToken(Property.ofValue("super-secret-token-12345"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        assertThat(task.toString(), not(containsString("super-secret-token-12345")));
    }
}
