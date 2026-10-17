package io.kestra.plugin.mercury.accounts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
    void testGetAccountSuccess() throws Exception {
        String validUuid = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";
        String accountJson = """
            {
              "id": "%s",
              "accountNumber": "1234567890",
              "routingNumber": "987654321",
              "name": "Mercury Checking",
              "nickname": "Primary Ops",
              "legalBusinessName": "Acme Global, Inc.",
              "status": "active",
              "type": "mercury",
              "kind": "checking",
              "currentBalance": 12500.75,
              "availableBalance": 12450.25,
              "canReceiveTransactions": true,
              "canSendRealTimePayments": true,
              "createdAt": "2024-01-15T08:30:00Z",
              "dashboardLink": "https://app.mercury.com/account/%s"
            }
            """.formatted(validUuid, validUuid);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + validUuid))
            .withHeader("Authorization", equalTo("Bearer secret-token:valid-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(accountJson)));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(validUuid))
            .build();

        RunContext runContext = runContextFactory.of();
        Get.Output output = task.run(runContext);

        assertThat(output.getAccount(), notNullValue());
        assertThat(output.getAccount().getId(), is(validUuid));
        assertThat(output.getAccount().getName(), is("Mercury Checking"));
        assertThat(output.getAccount().getNickname(), is("Primary Ops"));
        assertThat(output.getAccount().getLegalBusinessName(), is("Acme Global, Inc."));
        assertThat(output.getAccount().getStatus(), is("active"));
        assertThat(output.getAccount().getType(), is("mercury"));
        assertThat(output.getAccount().getKind(), is("checking"));
        assertThat(output.getAccount().getCurrentBalance(), is(new BigDecimal("12500.75")));
        assertThat(output.getAccount().getAvailableBalance(), is(new BigDecimal("12450.25")));
        assertThat(output.getAccount().getCanReceiveTransactions(), is(true));
        assertThat(output.getAccount().getCanSendRealTimePayments(), is(true));
        assertThat(output.getAccount().getCreatedAt(), is("2024-01-15T08:30:00Z"));
        assertThat(output.getAccount().getDashboardLink(), is("https://app.mercury.com/account/" + validUuid));
    }

    @Test
    void testGetAccountNotFound404() {
        String validUuid = "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121";
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + validUuid))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"Account not found\"}")));

        var task = Get.builder()
            .id(UUID.randomUUID().toString())
            .type(Get.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(validUuid))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));

        assertThat(exception.getMessage(), containsString("Not found (404)"));
        assertThat(exception.getMessage(), containsString("The requested Mercury resource does not exist or is not visible to this token"));
    }

    @Test
    void testInvalidAccountIdRejectedWithoutRequest() {
        String[] invalidAccountIds = {
            "acc/123",
            "../etc/passwd",
            "acc?query=1",
            "acc#fragment",
            "acc with spaces",
            "not-a-uuid"
        };

        RunContext runContext = runContextFactory.of();

        for (String invalidId : invalidAccountIds) {
            var task = Get.builder()
                .id(UUID.randomUUID().toString())
                .type(Get.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(invalidId))
                .build();

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
            assertThat(ex.getMessage(), containsString("must be a valid UUID"));
        }

        // Verify that NO requests were sent to WireMock for any invalid ID
        wireMockServer.verify(0, getRequestedFor(anyUrl()));
    }
}
