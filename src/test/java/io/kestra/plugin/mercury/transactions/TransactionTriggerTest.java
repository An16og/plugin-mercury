package io.kestra.plugin.mercury.transactions;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class TransactionTriggerTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private ModelValidator modelValidator;

    private WireMockServer wireMockServer;

    private static final String ACCOUNT_ID = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";
    private static final String NAMESPACE = "company.team";
    private static final String FLOW_ID = "flow-transactions";
    private static final String TRIGGER_ID = "watch-transactions";

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        try {
            var conditionContext = buildConditionContext();
            var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
            for (var entry : kv.listAll()) {
                kv.delete(entry.key());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private ConditionContext buildConditionContext(TransactionTrigger trigger, TriggerContext triggerContext) {
        var flow = Flow.builder()
            .id(FLOW_ID)
            .namespace(NAMESPACE)
            .tenantId("default")
            .revision(1)
            .build();
        var defaultRunContext = (DefaultRunContext) runContextFactory.of(flow, trigger);
        var runContext = runContextFactory.initializer().forScheduler(defaultRunContext, triggerContext, trigger);
        return ConditionContext.builder()
            .flow(flow)
            .runContext(runContext)
            .build();
    }

    private ConditionContext buildConditionContext() {
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);
        var dummyTrigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();
        return buildConditionContext(dummyTrigger, triggerContext);
    }

    private TriggerContext buildTriggerContext(String flowId, String triggerId) {
        return TriggerContext.builder()
            .tenantId("default")
            .namespace(NAMESPACE)
            .flowId(flowId)
            .triggerId(triggerId)
            .build();
    }

    private String transactionJson(String id, String createdAt) {
        return """
            {
              "id": "%s",
              "accountId": "%s",
              "amount": 100.50,
              "counterpartyId": "c0e567d8-76a7-11e9-a22d-0b33e9de0755",
              "counterpartyName": "Supplier Inc",
              "status": "sent",
              "kind": "outgoingPayment",
              "compliantWithReceiptPolicy": true,
              "hasGeneratedReceipt": false,
              "createdAt": "%s",
              "estimatedDeliveryDate": "2024-01-16T12:00:00Z",
              "postedAt": "2024-01-15T11:00:00Z",
              "dashboardLink": "https://app.mercury.com/transaction/%s",
              "glAllocations": [],
              "attachments": [],
              "relatedTransactions": []
            }
            """.formatted(id, ACCOUNT_ID, createdAt, id);
    }

    @Test
    void testModelValidatorValidForDefaultAndConfiguredTrigger() {
        var defaultTrigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();
        assertThat(modelValidator.isValid(defaultTrigger).isEmpty(), is(true));

        var configuredTrigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue("https://api.mercury.com/api/v1"))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.STORE))
            .limit(Property.ofValue(500))
            .pageCap(Property.ofValue(10))
            .status(Property.ofValue(List.Status.SENT))
            .search(Property.ofValue("Supplier"))
            .lookback(Property.ofValue(Duration.ofHours(12)))
            .build();
        assertThat(modelValidator.isValid(configuredTrigger).isEmpty(), is(true));
    }

    @Test
    void testModelValidatorReportsInvalidConstantValues() {
        var invalidLimitZero = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidLimitZero).isPresent(), is(true));

        var invalidLimitTooHigh = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .limit(Property.ofValue(1001))
            .build();
        assertThat(modelValidator.isValid(invalidLimitTooHigh).isPresent(), is(true));

        var invalidPageCapZero = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .pageCap(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidPageCapZero).isPresent(), is(true));
    }

    @Test
    void testFlowYamlValidationContainingTrigger() {
        var flowYaml = """
            id: flow_mercury_trigger
            namespace: company.team
            tasks:
              - id: log_task
                type: io.kestra.plugin.core.log.Log
                message: "Trigger fired"
            triggers:
              - id: mercury_tx_trigger
                type: io.kestra.plugin.mercury.transactions.TransactionTrigger
                apiToken: "secret-token:valid-token"
                accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
            """;
        var flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void testPluginExamplesFlowValidation() {
        var plugin = TransactionTrigger.class.getAnnotation(Plugin.class);
        assertThat(plugin, notNullValue());
        for (Example example : plugin.examples()) {
            var code = String.join("\n", example.code());
            var flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }
    }

    @Test
    void testFirstPollBaselineEmitsNothingAndSavesState() throws Exception {
        var tx1 = transactionJson("tx-001", "2024-01-15T10:00:00Z");
        var tx2 = transactionJson("tx-002", "2024-01-15T11:00:00Z");
        var responseBody = """
            {
              "total": 2,
              "transactions": [%s, %s]
            }
            """.formatted(tx1, tx2);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));

        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        var executionOpt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(executionOpt.isEmpty(), is(true));

        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var expectedKey = "mercury_tx_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + TRIGGER_ID.length() + "_" + TRIGGER_ID + "_"
            + ACCOUNT_ID.length() + "_" + ACCOUNT_ID;

        var valOpt = kv.getValue(expectedKey);
        assertThat(valOpt.isPresent(), is(true));

        var state = JacksonMapper.ofJson().readValue((String) valOpt.get().value(), TransactionTrigger.State.class);
        assertThat(state.getWatermark(), notNullValue());
        assertThat(state.getSeenIds(), containsInAnyOrder("tx-001", "tx-002"));
    }

    @Test
    void testSecondPollWithNewTransactionEmitsExecution() throws Exception {
        var tx1 = transactionJson("tx-001", "2024-01-15T10:00:00Z");
        var tx2 = transactionJson("tx-002", "2024-01-15T11:00:00Z");
        var tx3 = transactionJson("tx-003", "2024-01-15T12:00:00Z");

        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        // Pre-populate state as if first poll happened
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_tx_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + TRIGGER_ID.length() + "_" + TRIGGER_ID + "_"
            + ACCOUNT_ID.length() + "_" + ACCOUNT_ID;

        var seen = new LinkedHashSet<String>();
        seen.add("tx-001");
        seen.add("tx-002");
        var initialState = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofMinutes(10)))
            .seenIds(seen)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(initialState)));

        // Second poll returns tx1, tx2, tx3
        var responseBody = """
            {
              "total": 3,
              "transactions": [%s, %s, %s]
            }
            """.formatted(tx1, tx2, tx3);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));

        var executionOpt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(executionOpt.isPresent(), is(true));

        var execution = executionOpt.get();
        var triggerObj = execution.getTrigger();
        assertThat(triggerObj, notNullValue());
        var variables = triggerObj.getVariables();
        assertThat(variables.get("size"), is(1));

        @SuppressWarnings("unchecked")
        var rows = (java.util.List<java.util.Map<String, Object>>) variables.get("rows");
        assertThat(rows, hasSize(1));
        assertThat(rows.getFirst().get("id"), is("tx-003"));

        // Verify updated state has all 3 IDs
        var updatedVal = kv.getValue(key);
        var updatedState = JacksonMapper.ofJson().readValue((String) updatedVal.get().value(), TransactionTrigger.State.class);
        assertThat(updatedState.getSeenIds(), containsInAnyOrder("tx-001", "tx-002", "tx-003"));
    }

    @Test
    void testQuietPollAdvancesWatermarkAndKeepsSeenIds() throws Exception {
        var tx1 = transactionJson("tx-001", "2024-01-15T10:00:00Z");

        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = TransactionTrigger.stateKey(FLOW_ID, TRIGGER_ID, ACCOUNT_ID, null);

        var seen = new LinkedHashSet<String>();
        seen.add("tx-001");
        var watermark = Instant.parse("2024-01-15T10:30:00Z");
        var initialState = TransactionTrigger.State.builder()
            .watermark(watermark)
            .seenIds(seen)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(initialState)));

        var responseBody = """
            {
              "total": 1,
              "transactions": [%s]
            }
            """.formatted(tx1);

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(responseBody)));

        var executionOpt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(executionOpt.isEmpty(), is(true));

        // A successful quiet poll must still advance the watermark so the fetched window does not grow
        var val = kv.getValue(key);
        var state = JacksonMapper.ofJson().readValue((String) val.get().value(), TransactionTrigger.State.class);
        assertThat(state.getWatermark().isAfter(watermark), is(true));
        assertThat(state.getSeenIds(), contains("tx-001"));
    }

    @Test
    void testMidstreamFailureDoesNotAdvanceWatermark() throws Exception {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_tx_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + TRIGGER_ID.length() + "_" + TRIGGER_ID + "_"
            + ACCOUNT_ID.length() + "_" + ACCOUNT_ID;

        var initialWatermark = Instant.parse("2024-01-15T09:00:00Z");
        var seen = new LinkedHashSet<String>();
        seen.add("tx-old");
        var initialState = TransactionTrigger.State.builder()
            .watermark(initialWatermark)
            .seenIds(seen)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(initialState)));

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("{\"error\": \"Internal server error\"}")));

        assertThrows(Exception.class, () -> trigger.evaluate(conditionContext, triggerContext));

        var val = kv.getValue(key);
        var state = JacksonMapper.ofJson().readValue((String) val.get().value(), TransactionTrigger.State.class);
        assertThat(state.getWatermark(), is(initialWatermark));
        assertThat(state.getSeenIds(), contains("tx-old"));
    }

    @Test
    void testStatePersistenceAcrossTriggerInstances() throws Exception {
        var tx1 = transactionJson("tx-001", "2024-01-15T10:00:00Z");
        var tx2 = transactionJson("tx-002", "2024-01-15T11:00:00Z");

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 1,
                      "transactions": [%s]
                    }
                    """.formatted(tx1))));

        var trigger1 = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        // Instance 1 baseline poll
        var opt1 = trigger1.evaluate(conditionContext, triggerContext);
        assertThat(opt1.isEmpty(), is(true));

        // Stub new transaction
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 2,
                      "transactions": [%s, %s]
                    }
                    """.formatted(tx1, tx2))));

        // Instance 2 with same flow and trigger ID
        var trigger2 = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var opt2 = trigger2.evaluate(conditionContext, triggerContext);
        assertThat(opt2.isPresent(), is(true));
        var vars = opt2.get().getTrigger().getVariables();
        assertThat(vars.get("size"), is(1));
    }

    @Test
    void testKeyCollisionPrevention() {
        var key1 = TransactionTrigger.stateKey("a-b", "c", ACCOUNT_ID, null);
        var key2 = TransactionTrigger.stateKey("a", "b-c", ACCOUNT_ID, null);

        assertThat(key1, is(not(key2)));
        assertThat(key1, is("mercury_tx_3_a-b_1_c_36_" + ACCOUNT_ID));
        assertThat(key2, is("mercury_tx_1_a_3_b-c_36_" + ACCOUNT_ID));
        assertThat(TransactionTrigger.stateKey("a", "b", ACCOUNT_ID, List.Status.SENT), endsWith("_SENT"));
    }

    @Test
    void testStateIsStoredUnderTheRealKey() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total\": 0, \"transactions\": []}")));

        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("  " + ACCOUNT_ID + "  "))
            .build();

        var conditionContext = buildConditionContext();
        trigger.evaluate(conditionContext, buildTriggerContext(FLOW_ID, TRIGGER_ID));

        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        assertThat(kv.getValue(TransactionTrigger.stateKey(FLOW_ID, TRIGGER_ID, ACCOUNT_ID, null)).isPresent(), is(true));
    }

    @Test
    void testUnreadableStateFailsThePollInsteadOfResettingIt() throws Exception {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var conditionContext = buildConditionContext();
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = TransactionTrigger.stateKey(FLOW_ID, TRIGGER_ID, ACCOUNT_ID, null);
        var corrupted = "{not valid json";
        kv.put(key, new KVValueAndMetadata(null, corrupted));

        assertThrows(Exception.class, () -> trigger.evaluate(conditionContext, buildTriggerContext(FLOW_ID, TRIGGER_ID)));

        // No HTTP call and the stored state is untouched: the next poll retries instead of treating it as a first poll
        assertThat(wireMockServer.getAllServeEvents().isEmpty(), is(true));
        assertThat(kv.getValue(key).get().value(), is(corrupted));
    }

    @Test
    void testLookbackWindowDeduplicationAndCap() throws Exception {
        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_tx_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + TRIGGER_ID.length() + "_" + TRIGGER_ID + "_"
            + ACCOUNT_ID.length() + "_" + ACCOUNT_ID;

        // Pre-populate with 10,000 IDs
        var initialSeen = new LinkedHashSet<String>();
        for (var i = 0; i < 10_000; i++) {
            initialSeen.add("tx-id-" + i);
        }
        var state = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofHours(1)))
            .seenIds(initialSeen)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(state)));

        // Return an existing one plus a new one
        var existingTx = transactionJson("tx-id-500", "2024-01-15T10:00:00Z");
        var newTx = transactionJson("tx-id-brand-new", "2024-01-15T11:00:00Z");

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 2,
                      "transactions": [%s, %s]
                    }
                    """.formatted(existingTx, newTx))));

        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .build();

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isPresent(), is(true));

        var updatedVal = kv.getValue(key);
        var updatedState = JacksonMapper.ofJson().readValue((String) updatedVal.get().value(), TransactionTrigger.State.class);
        assertThat(updatedState.getSeenIds().size(), is(10_000));
        assertThat(updatedState.getSeenIds().contains("tx-id-brand-new"), is(true));
        // Oldest ID (tx-id-0) must have been pruned
        assertThat(updatedState.getSeenIds().contains("tx-id-0"), is(false));
    }

    @Test
    void testStatusFilterTransitionEmittedOnce() throws Exception {
        var triggerId = "watch-sent-" + UUID.randomUUID();
        var trigger = TransactionTrigger.builder()
            .id(triggerId)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .status(Property.ofValue(List.Status.SENT))
            .build();

        var triggerContext = buildTriggerContext(FLOW_ID, triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        // Baseline: no SENT transactions
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("status", equalTo("sent"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"total\": 0, \"transactions\": []}")));

        var opt1 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt1.isEmpty(), is(true));

        // Poll 2: transaction transitioned to SENT
        var sentTx = transactionJson("tx-transitioned", "2024-01-15T12:00:00Z");
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("status", equalTo("sent"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 1,
                      "transactions": [%s]
                    }
                    """.formatted(sentTx))));

        var opt2 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt2.isPresent(), is(true));
        assertThat(opt2.get().getTrigger().getVariables().get("size"), is(1));

        // Poll 3: same SENT transaction returned -> must NOT emit again
        var opt3 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt3.isEmpty(), is(true));
    }

    @Test
    void testInvalidAccountIdRejectedWithoutHttp() {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("not-a-valid-uuid"))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(conditionContext, triggerContext));
        assertThat(wireMockServer.getAllServeEvents().isEmpty(), is(true));
    }

    @Test
    void testFetchTypeStoreStreamsIonFileToStorage() throws Exception {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        // Pre-populate baseline state
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_tx_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + TRIGGER_ID.length() + "_" + TRIGGER_ID + "_"
            + ACCOUNT_ID.length() + "_" + ACCOUNT_ID;
        var baseline = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofHours(1)))
            .seenIds(new LinkedHashSet<>())
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baseline)));

        var newTx = transactionJson("tx-store-1", "2024-01-15T12:00:00Z");
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 1,
                      "transactions": [%s]
                    }
                    """.formatted(newTx))));

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isPresent(), is(true));

        var vars = opt.get().getTrigger().getVariables();
        assertThat(vars.get("size"), is(1));
        var uriStr = (String) vars.get("uri");
        assertThat(uriStr, notNullValue());

        // Verify stored file content
        var runContext = conditionContext.getRunContext();
        try (var is = runContext.storage().getFile(java.net.URI.create(uriStr));
             var reader = new BufferedReader(new InputStreamReader(is))) {
            var records = FileSerde.readAll(reader).collectList().block();
            assertThat(records, notNullValue());
            assertThat(records, hasSize(1));
            var record1 = (java.util.Map<?, ?>) records.getFirst();
            assertThat(record1.get("id"), is("tx-store-1"));
        }
    }

    @Test
    void testFetchTypeFetchOneEmitsSingleRow() throws Exception {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);

        // Pre-populate baseline state
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_tx_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + TRIGGER_ID.length() + "_" + TRIGGER_ID + "_"
            + ACCOUNT_ID.length() + "_" + ACCOUNT_ID;
        var baseline = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofHours(1)))
            .seenIds(new LinkedHashSet<>())
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baseline)));

        var tx1 = transactionJson("tx-one-1", "2024-01-15T12:00:00Z");
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "total": 1,
                      "transactions": [%s]
                    }
                    """.formatted(tx1))));

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isPresent(), is(true));

        var vars = opt.get().getTrigger().getVariables();
        assertThat(vars.get("size"), is(1));
        @SuppressWarnings("unchecked")
        var row = (java.util.Map<String, Object>) vars.get("row");
        assertThat(row, notNullValue());
        assertThat(row.get("id"), is("tx-one-1"));
    }

    @Test
    void testFetchOneFetchesAllNewTransactionsAndMarksThemSeen() throws Exception {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var conditionContext = buildConditionContext();
        var triggerContext = buildTriggerContext(FLOW_ID, TRIGGER_ID);
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = TransactionTrigger.stateKey(FLOW_ID, TRIGGER_ID, ACCOUNT_ID, null);
        var baseline = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofMinutes(10)))
            .seenIds(new LinkedHashSet<>())
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baseline)));

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                    {"total": 3, "transactions": [%s, %s, %s]}
                    """.formatted(
                    transactionJson("tx-3", "2024-01-15T12:00:00Z"),
                    transactionJson("tx-2", "2024-01-15T11:00:00Z"),
                    transactionJson("tx-1", "2024-01-15T10:00:00Z")))));

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isPresent(), is(true));

        // The API is asked for the full window, not a single row
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("limit", equalTo("1000")));

        var vars = opt.get().getTrigger().getVariables();
        assertThat(vars.get("size"), is(3));
        @SuppressWarnings("unchecked")
        var row = (java.util.Map<String, Object>) vars.get("row");
        assertThat(row.get("id"), is("tx-3"));

        var state = JacksonMapper.ofJson().readValue((String) kv.getValue(key).get().value(), TransactionTrigger.State.class);
        assertThat(state.getSeenIds(), containsInAnyOrder("tx-1", "tx-2", "tx-3"));
    }

    @Test
    void testFetchOnePagesThroughTheWindow() throws Exception {
        var trigger = TransactionTrigger.builder()
            .id(TRIGGER_ID)
            .type(TransactionTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .limit(Property.ofValue(2))
            .build();

        var conditionContext = buildConditionContext();
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = TransactionTrigger.stateKey(FLOW_ID, TRIGGER_ID, ACCOUNT_ID, null);
        var baseline = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofMinutes(10)))
            .seenIds(new LinkedHashSet<>())
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baseline)));

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total\": 3, \"transactions\": [%s, %s]}".formatted(
                    transactionJson("tx-3", "2024-01-15T12:00:00Z"), transactionJson("tx-2", "2024-01-15T11:00:00Z")))));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .withQueryParam("offset", equalTo("2"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total\": 3, \"transactions\": [%s]}".formatted(
                    transactionJson("tx-1", "2024-01-15T10:00:00Z")))));

        var opt = trigger.evaluate(conditionContext, buildTriggerContext(FLOW_ID, TRIGGER_ID));
        assertThat(opt.isPresent(), is(true));
        assertThat(opt.get().getTrigger().getVariables().get("size"), is(3));
    }

    @Test
    void testExampleExpressionsRenderWithTriggerOutputs() throws Exception {
        var conditionContext = buildConditionContext();
        var runContext = conditionContext.getRunContext();
        var kv = runContext.namespaceKv(NAMESPACE);
        var key = TransactionTrigger.stateKey(FLOW_ID, TRIGGER_ID, ACCOUNT_ID, null);
        var baseline = TransactionTrigger.State.builder()
            .watermark(Instant.now().minus(Duration.ofMinutes(10)))
            .seenIds(new LinkedHashSet<>())
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baseline)));

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID + "/transactions"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"total\": 1, \"transactions\": [%s]}".formatted(transactionJson("tx-1", "2024-01-15T10:00:00Z")))));

        for (var fetchType : new FetchType[]{FetchType.FETCH, FetchType.STORE}) {
            kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baseline)));
            var trigger = TransactionTrigger.builder()
                .id(TRIGGER_ID)
                .type(TransactionTrigger.class.getName())
                .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
                .apiToken(Property.ofValue("secret-token:valid-token"))
                .accountId(Property.ofValue(ACCOUNT_ID))
                .fetchType(Property.ofValue(fetchType))
                .build();
            var vars = trigger.evaluate(conditionContext, buildTriggerContext(FLOW_ID, TRIGGER_ID))
                .orElseThrow().getTrigger().getVariables();
            var scope = java.util.Map.<String, Object>of("trigger", vars);

            assertThat(runContext.render("{{ trigger.account.id }}", scope), is(ACCOUNT_ID));
            assertThat(runContext.render("{{ trigger.size }}", scope), is("1"));
            if (fetchType == FetchType.FETCH) {
                assertThat(runContext.render("{{ trigger.transaction.counterpartyName }}", scope), is("Supplier Inc"));
                assertThat(runContext.render("{{ trigger.rows[0].id }}", scope), is("tx-1"));
            } else {
                assertThat(runContext.render("{{ trigger.uri }}", scope), startsWith("kestra://"));
            }
        }

        // Every {{ trigger.* }} path used in the examples must be one of the paths rendered above
        var plugin = TransactionTrigger.class.getAnnotation(Plugin.class);
        var used = java.util.regex.Pattern.compile("\\{\\{ (trigger\\.[A-Za-z.\\[\\]0-9]+) \\}\\}");
        var allowed = java.util.Set.of("trigger.size", "trigger.transaction.counterpartyName", "trigger.account.id", "trigger.uri");
        for (var example : plugin.examples()) {
            var m = used.matcher(String.join("\n", example.code()));
            while (m.find()) {
                assertThat(m.group(1), is(in(allowed)));
            }
        }
    }
}
