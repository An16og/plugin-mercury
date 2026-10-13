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
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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
                  "id": "acc-1",
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
                  "dashboardLink": "https://app.mercury.com/account/acc-1"
                },
                {
                  "id": "acc-2",
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
                  "dashboardLink": "https://app.mercury.com/account/acc-2"
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

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), nullValue());

        Account first = output.getRows().get(0);
        assertThat(first.getId(), is("acc-1"));
        assertThat(first.getName(), is("Checking Account"));
        assertThat(first.getCurrentBalance(), is(new BigDecimal("100.50")));
        assertThat(first.getCanSendRealTimePayments(), is(true));

        Account second = output.getRows().get(1);
        assertThat(second.getId(), is("acc-2"));
        assertThat(second.getKind(), is("savings"));
    }

    @Test
    void testMultiPageWalk() throws Exception {
        String page1 = """
            {
              "accounts": [
                {
                  "id": "acc-1",
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
                "nextPage": "cursor-to-page-2",
                "previousPage": null
              }
            }
            """;

        String page2 = """
            {
              "accounts": [
                {
                  "id": "acc-2",
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
                "previousPage": "cursor-to-page-1"
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .withoutQueryParams()
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1)));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .withQueryParam("start_after", equalTo("cursor-to-page-2"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page2)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).getId(), is("acc-1"));
        assertThat(output.getRows().get(1).getId(), is("acc-2"));
    }

    @Test
    void testRepeatedCursorGuard() throws Exception {
        String pageWithSameCursor = """
            {
              "accounts": [
                {
                  "id": "acc-1",
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

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        // Guard must prevent infinite loop and stop after seeing repeated cursor
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

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        assertThat(output.getRows(), hasSize(0));
    }

    @Test
    void testPageCap() throws Exception {
        for (int i = 1; i <= 5; i++) {
            String pageJson = """
                {
                  "accounts": [
                    {
                      "id": "acc-%d",
                      "accountNumber": "1111",
                      "routingNumber": "2222",
                      "name": "Account %d",
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
                    "nextPage": "cursor-%d",
                    "previousPage": null
                  }
                }
                """.formatted(i, i, i + 1);

            if (i == 1) {
                wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
                    .withoutQueryParams()
                    .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(pageJson)));
            } else {
                wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
                    .withQueryParam("start_after", equalTo("cursor-" + i))
                    .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(pageJson)));
            }
        }

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .pageCap(Property.ofValue(2))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), hasSize(2));
    }

    @Test
    void testFetchOne() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "acc-first",
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
                },
                {
                  "id": "acc-second",
                  "accountNumber": "2222",
                  "routingNumber": "3333",
                  "name": "Second Account",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 20.00,
                  "availableBalance": 20.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-02T00:00:00Z",
                  "dashboardLink": "https://app.mercury.com"
                }
              ],
              "page": {
                "nextPage": "cursor-2",
                "previousPage": null
              }
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
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(1));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().getId(), is("acc-first"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());
    }

    @Test
    void testStoreIon() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "acc-store-1",
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
                },
                {
                  "id": "acc-store-2",
                  "accountNumber": "2222",
                  "routingNumber": "3333",
                  "name": "Stored Account 2",
                  "legalBusinessName": "Acme Inc",
                  "status": "active",
                  "type": "mercury",
                  "kind": "checking",
                  "currentBalance": 200.00,
                  "availableBalance": 200.00,
                  "canSendRealTimePayments": true,
                  "createdAt": "2024-01-02T00:00:00Z",
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

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getRow(), nullValue());
        assertThat(output.getUri(), notNullValue());
        assertThat(output.getUri().toString(), containsString(".ion"));

        // Verify stored contents can be read back
        try (InputStream is = runContext.storage().getFile(output.getUri());
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            List<Object> records = FileSerde.readAll(reader).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(2));
            Map<?, ?> record1 = (Map<?, ?>) records.get(0);
            assertThat(record1.get("id"), is("acc-store-1"));
        }
    }

    @Test
    void testFetchNone() throws Exception {
        String responseJson = """
            {
              "accounts": [
                {
                  "id": "acc-1",
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
    void testLimitBoundsClamped() throws Exception {
        String responseJson = """
            {
              "accounts": [],
              "page": { "nextPage": null, "previousPage": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .withQueryParam("limit", equalTo("1000"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseJson)));

        // Provide 5000 which exceeds Mercury's 1000 max -> should clamp to 1000
        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .limit(Property.ofValue(5000))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/accounts"))
            .withQueryParam("limit", equalTo("1000")));
    }

    @Test
    void testUnknownFieldsIgnored() throws Exception {
        String responseWithUnknownFields = """
            {
              "extraRootField": "foo",
              "accounts": [
                {
                  "id": "acc-1",
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
        assertThat(output.getRows().get(0).getId(), is("acc-1"));
    }
}
