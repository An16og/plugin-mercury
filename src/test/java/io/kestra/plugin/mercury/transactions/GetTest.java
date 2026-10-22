package io.kestra.plugin.mercury.transactions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.mercury.model.Transaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class GetTest {

    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer wireMockServer;

    private static final String VALID_ACCOUNT_ID = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";
    private static final String VALID_TRANSACTION_ID = "e0e567d8-76a7-11e9-a22d-0b33e9de0755";

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
    void testGetTransactionSuccess() throws Exception {
        var transactionJson = """
            {
              "id": "%s",
              "accountId": "%s",
              "amount": -125.50,
              "bankDescription": "DEBIT CARD PURCHASE ACME STORE",
              "checkNumber": null,
              "cardId": "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121",
              "compliantWithReceiptPolicy": true,
              "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
              "counterpartyName": "Acme Store",
              "counterpartyNickname": "Acme Supplies",
              "createdAt": "2024-01-15T08:30:00Z",
              "dashboardLink": "https://app.mercury.com/transaction/%s",
              "estimatedDeliveryDate": "2024-01-15T08:30:00Z",
              "externalMemo": "Supplies for office",
              "failedAt": null,
              "feeId": null,
              "glAllocations": [],
              "hasGeneratedReceipt": true,
              "kind": "debitCardTransaction",
              "mercuryCategory": "Office Equipment",
              "note": "January restock",
              "postedAt": "2024-01-15T09:00:00Z",
              "reasonForFailure": null,
              "relatedTransactions": [],
              "requestId": null,
              "status": "sent",
              "trackingNumber": "RTP123456789",
              "attachments": []
            }
            """.formatted(VALID_TRANSACTION_ID, VALID_ACCOUNT_ID, VALID_TRANSACTION_ID);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + VALID_ACCOUNT_ID + "/transaction/" + VALID_TRANSACTION_ID))
            .withHeader("Authorization", equalTo("Bearer secret-token:valid-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(transactionJson)));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(VALID_ACCOUNT_ID))
            .transactionId(Property.ofValue(VALID_TRANSACTION_ID))
            .build();

        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getTransaction(), notNullValue());
        var tx = output.getTransaction();
        assertThat(tx.getId(), is(VALID_TRANSACTION_ID));
        assertThat(tx.getAccountId(), is(VALID_ACCOUNT_ID));
        assertThat(tx.getAmount(), is(new BigDecimal("-125.50")));
        assertThat(tx.getBankDescription(), is("DEBIT CARD PURCHASE ACME STORE"));
        assertThat(tx.getCardId(), is("5c65f4e7-83bb-4d18-c4d7-0a5b67b75121"));
        assertThat(tx.getCompliantWithReceiptPolicy(), is(true));
        assertThat(tx.getCounterpartyId(), is("c0e567d8-76a7-11e9-a22d-0b33e9de0755"));
        assertThat(tx.getCounterpartyName(), is("Acme Store"));
        assertThat(tx.getCounterpartyNickname(), is("Acme Supplies"));
        assertThat(tx.getCreatedAt(), is(Instant.parse("2024-01-15T08:30:00Z")));
        assertThat(tx.getDashboardLink(), is("https://app.mercury.com/transaction/" + VALID_TRANSACTION_ID));
        assertThat(tx.getEstimatedDeliveryDate(), is(Instant.parse("2024-01-15T08:30:00Z")));
        assertThat(tx.getExternalMemo(), is("Supplies for office"));
        assertThat(tx.getHasGeneratedReceipt(), is(true));
        assertThat(tx.getKind(), is("debitCardTransaction"));
        assertThat(tx.getMercuryCategory(), is("Office Equipment"));
        assertThat(tx.getNote(), is("January restock"));
        assertThat(tx.getPostedAt(), is(Instant.parse("2024-01-15T09:00:00Z")));
        assertThat(tx.getStatus(), is("sent"));
        assertThat(tx.getTrackingNumber(), is("RTP123456789"));
    }

    @Test
    void testGetTransactionNotFound404() {
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + VALID_ACCOUNT_ID + "/transaction/" + VALID_TRANSACTION_ID))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Transaction not found\"}")));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(VALID_ACCOUNT_ID))
            .transactionId(Property.ofValue(VALID_TRANSACTION_ID))
            .build();

        var runContext = runContextFactory.of();
        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(exception.getMessage(), containsString("Not found (404)"));
        assertThat(exception.getMessage(), containsString("The requested Mercury resource does not exist or is not visible to this token"));
    }

    @Test
    void testGetTransactionEmptyResponseBodyFails() {
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + VALID_ACCOUNT_ID + "/transaction/" + VALID_TRANSACTION_ID))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("")));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(VALID_ACCOUNT_ID))
            .transactionId(Property.ofValue(VALID_TRANSACTION_ID))
            .build();

        var runContext = runContextFactory.of();
        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(exception.getMessage(), is("Empty response from the Mercury API for transaction '" + VALID_TRANSACTION_ID + "'"));
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
            var task = Get.builder()
                .id(UUID.randomUUID().toString())
                .type(Get.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(invalidId))
                .transactionId(Property.ofValue(VALID_TRANSACTION_ID))
                .build();

            var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
            assertThat(ex.getMessage(), containsString("must be a valid UUID"));
        }

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testInvalidTransactionIdRejectedWithoutRequest() {
        var invalidTransactionIds = new String[]{
            "tx/123",
            "../admin/endpoint",
            "tx?query=1",
            "tx#fragment",
            "tx with spaces",
            "not-a-uuid"
        };

        var runContext = runContextFactory.of();

        for (var invalidId : invalidTransactionIds) {
            var task = Get.builder()
                .id(UUID.randomUUID().toString())
                .type(Get.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(VALID_ACCOUNT_ID))
                .transactionId(Property.ofValue(invalidId))
                .build();

            var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
            assertThat(ex.getMessage(), containsString("must be a valid UUID"));
        }

        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    void testTransactionIso8601MillisParsing() throws Exception {
        var json = """
            {
              "id": "%s",
              "createdAt": "2024-01-15T10:00:00.000Z"
            }
            """.formatted(VALID_TRANSACTION_ID);
        var transaction = JacksonMapper.ofJson().readValue(json, Transaction.class);
        assertThat(transaction.getCreatedAt(), is(Instant.parse("2024-01-15T10:00:00.000Z")));
    }

    @Test
    void testTokenNeverInToString() {
        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .apiToken(Property.ofValue("super-secret-token-12345"))
            .accountId(Property.ofValue(VALID_ACCOUNT_ID))
            .transactionId(Property.ofValue(VALID_TRANSACTION_ID))
            .build();

        assertThat(task.toString(), not(containsString("super-secret-token-12345")));
    }
}
