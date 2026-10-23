package io.kestra.plugin.mercury.recipients;

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
import io.kestra.plugin.mercury.model.Recipient;
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

    private static final String FULL_RECIPIENT_JSON = """
        {
          "id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
          "status": "active",
          "name": "Acme Supplier Inc",
          "nickname": "Acme",
          "emails": ["invoices@acme.com", "billing@acme.com"],
          "contactEmail": "contact@acme.com",
          "dateLastPaid": "2024-03-15T14:30:00Z",
          "defaultPaymentMethod": "ach",
          "isBusiness": true,
          "address": {
            "name": "Acme Supplier Inc",
            "address1": "123 Market St",
            "address2": "Suite 400",
            "city": "San Francisco",
            "region": "CA",
            "postalCode": "94105",
            "country": "US"
          },
          "defaultAddress": {
            "address1": "123 Market St",
            "address2": null,
            "city": "San Francisco",
            "region": "CA",
            "postalCode": "94105",
            "country": "US"
          },
          "checkInfo": {
            "address": {
              "address1": "123 Market St",
              "address2": null,
              "city": "San Francisco",
              "region": "CA",
              "postalCode": "94105",
              "country": "US"
            }
          },
          "electronicRoutingInfo": {
            "accountNumber": "9876543210",
            "routingNumber": "121000358",
            "electronicAccountType": "businessChecking",
            "bankName": "First Republic Bank",
            "address": {
              "address1": "111 Pine St",
              "address2": null,
              "city": "San Francisco",
              "region": "CA",
              "postalCode": "94111",
              "country": "US"
            }
          },
          "domesticWireRoutingInfo": {
            "accountNumber": "9876543210",
            "routingNumber": "121000358",
            "bankName": "First Republic Bank",
            "address": {
              "address1": "111 Pine St",
              "city": "San Francisco",
              "region": "CA",
              "postalCode": "94111",
              "country": "US"
            }
          },
          "realTimePaymentRoutingInfo": {
            "accountNumber": "9876543210",
            "routingNumber": "121000358",
            "bankName": "First Republic Bank",
            "address": {
              "address1": "111 Pine St",
              "city": "San Francisco",
              "region": "CA",
              "postalCode": "94111",
              "country": "US"
            }
          },
          "internationalWireRoutingInfo": {
            "iban": "GB29NWBK60161331926819",
            "swiftCode": "NWBKGB2L",
            "countrySpecific": {
              "ifscCode": "HDFC0001234",
              "bsbCode": "123-456"
            },
            "correspondentInfo": {
              "bankName": "Correspondent Bank Corp",
              "routingNumber": "026009593",
              "swiftCode": "CORRUS33"
            },
            "bankDetails": {
              "bankName": "NatWest Bank",
              "bankCityState": "London",
              "bankCountry": "GB"
            },
            "emailAddress": "swift@natwest.com",
            "phoneNumber": "+442071234567",
            "address": {
              "address1": "135 Bishopsgate",
              "city": "London",
              "region": "Greater London",
              "postalCode": "EC2M 3UR",
              "country": "GB"
            }
          },
          "attachments": [
            {
              "fileName": "w9_form.pdf",
              "formType": "w9",
              "uploadedAt": "2024-01-10T09:00:00Z",
              "url": "https://mercury-attachments.s3.amazonaws.com/w9_form.pdf?token=presigned123"
            },
            {
              "fileName": "vendor_doc.pdf",
              "formType": null,
              "uploadedAt": "2024-01-11T10:00:00Z",
              "url": "https://mercury-attachments.s3.amazonaws.com/vendor_doc.pdf?token=presigned456"
            }
          ],
          "inviteId": "inv_123456"
        }
        """;

    @Test
    void testSinglePageFetchFullPayload() throws Exception {
        String responseJson = "{\n" +
            "  \"total\": 1,\n" +
            "  \"recipients\": [" + FULL_RECIPIENT_JSON + "],\n" +
            "  \"page\": { \"nextPage\": null, \"previousPage\": null }\n" +
            "}";

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .withHeader("Authorization", equalTo("Bearer secret-token:valid-token"))
            .withQueryParam("limit", equalTo("1000"))
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

        Recipient recipient = output.getRows().get(0);
        assertThat(recipient.getId(), is("7c9e6679-7425-40de-944b-e07fc1f90ae7"));
        assertThat(recipient.getStatus(), is("active"));
        assertThat(recipient.getName(), is("Acme Supplier Inc"));
        assertThat(recipient.getNickname(), is("Acme"));
        assertThat(recipient.getEmails(), contains("invoices@acme.com", "billing@acme.com"));
        assertThat(recipient.getContactEmail(), is("contact@acme.com"));
        assertThat(recipient.getDateLastPaid(), is(Instant.parse("2024-03-15T14:30:00Z")));
        assertThat(recipient.getDefaultPaymentMethod(), is("ach"));
        assertThat(recipient.getIsBusiness(), is(true));

        // Nested address
        assertThat(recipient.getAddress(), notNullValue());
        assertThat(recipient.getAddress().getName(), is("Acme Supplier Inc"));
        assertThat(recipient.getAddress().getCity(), is("San Francisco"));
        assertThat(recipient.getDefaultAddress(), notNullValue());
        assertThat(recipient.getCheckInfo(), notNullValue());
        assertThat(recipient.getCheckInfo().getAddress().getCity(), is("San Francisco"));

        // Routing blocks
        assertThat(recipient.getElectronicRoutingInfo(), notNullValue());
        assertThat(recipient.getElectronicRoutingInfo().getAccountNumber(), is("9876543210"));
        assertThat(recipient.getElectronicRoutingInfo().getElectronicAccountType(), is("businessChecking"));
        assertThat(recipient.getDomesticWireRoutingInfo(), notNullValue());
        assertThat(recipient.getDomesticWireRoutingInfo().getRoutingNumber(), is("121000358"));
        assertThat(recipient.getRealTimePaymentRoutingInfo(), notNullValue());

        // International wire
        assertThat(recipient.getInternationalWireRoutingInfo(), notNullValue());
        assertThat(recipient.getInternationalWireRoutingInfo().getIban(), is("GB29NWBK60161331926819"));
        assertThat(recipient.getInternationalWireRoutingInfo().getCountrySpecific(), notNullValue());
        assertThat(recipient.getInternationalWireRoutingInfo().getCountrySpecific().get("ifscCode"), is("HDFC0001234"));
        assertThat(recipient.getInternationalWireRoutingInfo().getCorrespondentInfo().getSwiftCode(), is("CORRUS33"));
        assertThat(recipient.getInternationalWireRoutingInfo().getBankDetails().getBankName(), is("NatWest Bank"));

        // Attachments
        assertThat(recipient.getAttachments(), hasSize(2));
        assertThat(recipient.getAttachments().get(0).getFormType(), is("w9"));
        assertThat(recipient.getAttachments().get(0).getUploadedAt(), is(Instant.parse("2024-01-10T09:00:00Z")));
        assertThat(recipient.getAttachments().get(1).getFormType(), nullValue());
        assertThat(recipient.getInviteId(), is("inv_123456"));
    }

    @Test
    void testMultiPageCursorWalk() throws Exception {
        String page1 = """
            {
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\",
                  \"status\": \"active\",
                  \"name\": \"Recipient 1\",
                  \"emails\": [\"r1@test.com\"],
                  \"defaultPaymentMethod\": \"ach\",
                  \"attachments\": []
                }
              ],
              \"page\": {
                \"nextPage\": \"8d0f778a-8536-41ef-a55c-f18fd20a1bf8\",
                \"previousPage\": null
              }
            }
            """;

        String page2 = """
            {
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"8d0f778a-8536-41ef-a55c-f18fd20a1bf8\",
                  \"status\": \"active\",
                  \"name\": \"Recipient 2\",
                  \"emails\": [\"r2@test.com\"],
                  \"defaultPaymentMethod\": \"check\",
                  \"attachments\": []
                }
              ],
              \"page\": {
                \"nextPage\": null,
                \"previousPage\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\"
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .withQueryParam("limit", equalTo("1000"))
            .withQueryParam("order", equalTo("asc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page1)));

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .withQueryParam("limit", equalTo("1000"))
            .withQueryParam("order", equalTo("asc"))
            .withQueryParam("start_after", equalTo("8d0f778a-8536-41ef-a55c-f18fd20a1bf8"))
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
        assertThat(output.getRows().get(0).getName(), is("Recipient 1"));
        assertThat(output.getRows().get(1).getName(), is("Recipient 2"));

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/recipients")).withoutQueryParam("start_after"));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/recipients"))
            .withQueryParam("start_after", equalTo("8d0f778a-8536-41ef-a55c-f18fd20a1bf8")));
    }

    @Test
    void testEmptyPageStopsPagination() throws Exception {
        String emptyResponse = """
            {
              \"total\": 0,
              \"recipients\": [],
              \"page\": { \"nextPage\": \"8d0f778a-8536-41ef-a55c-f18fd20a1bf8\", \"previousPage\": null }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
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
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/recipients")));
    }

    @Test
    void testRepeatedCursorStopsPagination() throws Exception {
        String page = """
            {
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\",
                  \"status\": \"active\",
                  \"name\": \"Recipient 1\",
                  \"emails\": [],
                  \"defaultPaymentMethod\": \"ach\",
                  \"attachments\": []
                }
              ],
              \"page\": {
                \"nextPage\": \"loop-cursor-123\",
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .inScenario("RepeatedCursor")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(page))
            .willSetStateTo("NextPage"));

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .inScenario("RepeatedCursor")
            .whenScenarioStateIs("NextPage")
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
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/recipients")));
    }

    @Test
    void testPageCapReachedWithMoreRemainingFails() {
        String pageWithNext = """
            {
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\",
                  \"status\": \"active\",
                  \"name\": \"Recipient 1\",
                  \"emails\": [],
                  \"defaultPaymentMethod\": \"ach\",
                  \"attachments\": []
                }
              ],
              \"page\": {
                \"nextPage\": \"8d0f778a-8536-41ef-a55c-f18fd20a1bf8\",
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pageWithNext)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .pageCap(Property.ofValue(1))
            .build();

        RunContext runContext = runContextFactory.of();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("Pagination reached the maximum pageCap of 1 but more recipients remain"));
    }

    @Test
    void testPageCapReachedWithoutMoreRemainingSucceeds() throws Exception {
        String pageWithoutNext = """
            {
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\",
                  \"status\": \"active\",
                  \"name\": \"Recipient 1\",
                  \"emails\": [],
                  \"defaultPaymentMethod\": \"ach\",
                  \"attachments\": []
                }
              ],
              \"page\": {
                \"nextPage\": null,
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pageWithoutNext)));

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
        String responseJson = """
            {
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\",
                  \"status\": \"active\",
                  \"name\": \"First Recipient\",
                  \"emails\": [\"first@test.com\"],
                  \"defaultPaymentMethod\": \"ach\",
                  \"attachments\": []
                }
              ],
              \"page\": {
                \"nextPage\": \"next-id\",
                \"previousPage\": null
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
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
        assertThat(output.getRow().getId(), is("7c9e6679-7425-40de-944b-e07fc1f90ae7"));
        assertThat(output.getRows(), nullValue());
        assertThat(output.getUri(), nullValue());

        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/recipients")).withQueryParam("limit", equalTo("1")));
    }

    @Test
    void testStoreIon() throws Exception {
        String responseJson = "{\n" +
            "  \"total\": 1,\n" +
            "  \"recipients\": [" + FULL_RECIPIENT_JSON + "],\n" +
            "  \"page\": { \"nextPage\": null, \"previousPage\": null }\n" +
            "}";

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
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
            assertThat(record.get("id"), is("7c9e6679-7425-40de-944b-e07fc1f90ae7"));
            assertThat(record.get("name"), is("Acme Supplier Inc"));
        }
    }

    @Test
    void testFetchNone() throws Exception {
        String responseJson = "{\n" +
            "  \"total\": 1,\n" +
            "  \"recipients\": [" + FULL_RECIPIENT_JSON + "],\n" +
            "  \"page\": { \"nextPage\": null, \"previousPage\": null }\n" +
            "}";

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
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
    void testOrderEnumQueryParameter() throws Exception {
        String emptyJson = "{\"total\": 0, \"recipients\": [], \"page\": {\"nextPage\": null, \"previousPage\": null}}";

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
            .withQueryParam("order", equalTo("desc"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(emptyJson)));

        var task = List.builder()
            .id(UUID.randomUUID().toString())
            .type(List.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .order(Property.ofValue(List.Order.DESC))
            .build();

        RunContext runContext = runContextFactory.of();
        List.Output output = task.run(runContext);

        assertThat(output.getSize(), is(0));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/recipients")).withQueryParam("order", equalTo("desc")));
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
              \"extraTopLevel\": \"unexpected\",
              \"total\": 1,
              \"recipients\": [
                {
                  \"id\": \"7c9e6679-7425-40de-944b-e07fc1f90ae7\",
                  \"status\": \"active\",
                  \"name\": \"Recipient With Unknowns\",
                  \"emails\": [\"u@test.com\"],
                  \"defaultPaymentMethod\": \"ach\",
                  \"attachments\": [],
                  \"futureBrandNewField\": 99999,
                  \"unexpectedNestedObject\": { \"nested\": \"val\" }
                }
              ],
              \"page\": {
                \"nextPage\": null,
                \"previousPage\": null,
                \"unknownPageField\": true
              }
            }
            """;

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
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
        assertThat(output.getRows().get(0).getName(), is("Recipient With Unknowns"));
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
            .limit(Property.ofValue(500))
            .pageCap(Property.ofValue(10))
            .order(Property.ofValue(List.Order.DESC))
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
    void testFlowYamlValidationContainingList() {
        String flowYaml = """
            id: flow_validating_recipients_list
            namespace: io.kestra.tests
            tasks:
              - id: list_recipients
                type: io.kestra.plugin.mercury.recipients.List
                apiToken: "secret-token:valid-token"
                limit: 250
                order: DESC
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
    void testSecurityTokenAndSensitiveDataNeverInLogsOrToString() throws Exception {
        String sensitiveToken = "secret-token:very-secret-recipient-key-abc123xyz";
        String responseJson = "{\n" +
            "  \"total\": 1,\n" +
            "  \"recipients\": [" + FULL_RECIPIENT_JSON + "],\n" +
            "  \"page\": { \"nextPage\": null, \"previousPage\": null }\n" +
            "}";

        wireMockServer.stubFor(get(urlPathEqualTo("/recipients"))
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
        assertThat(taskString, not(containsString("very-secret-recipient-key-abc123xyz")));

        RunContext runContext = runContextFactory.of();
        task.run(runContext);

        String allLogs = String.join("\n", capturedLogMessages);
        assertThat(allLogs, not(containsString(sensitiveToken)));
        assertThat(allLogs, not(containsString("very-secret-recipient-key-abc123xyz")));
        assertThat(allLogs, not(containsString("9876543210"))); // account number
        assertThat(allLogs, not(containsString("121000358"))); // routing number
        assertThat(allLogs, not(containsString("GB29NWBK60161331926819"))); // IBAN
        assertThat(allLogs, not(containsString("invoices@acme.com"))); // emails
        assertThat(allLogs, not(containsString("presigned123"))); // presigned url token
    }
}
