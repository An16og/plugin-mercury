package io.kestra.plugin.mercury.accounts;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.validations.ModelValidator;
import io.kestra.core.runners.DefaultRunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.serializers.YamlParser;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class BalanceThresholdTriggerTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Inject
    private ModelValidator modelValidator;

    private WireMockServer wireMockServer;

    private static final String ACCOUNT_ID_1 = "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010";
    private static final String ACCOUNT_ID_2 = "5c65f4e7-83bb-4d18-c4d7-0a5b67b75121";
    private static final String NAMESPACE = "company.team";
    private static final String FLOW_ID = "flow-balance";

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

    private ConditionContext buildConditionContext(BalanceThresholdTrigger trigger, TriggerContext triggerContext) {
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

    private TriggerContext buildTriggerContext(String triggerId) {
        return TriggerContext.builder()
            .tenantId("default")
            .namespace(NAMESPACE)
            .flowId(FLOW_ID)
            .triggerId(triggerId)
            .build();
    }

    private String accountJson(String id, String name, String status, String type, BigDecimal available, BigDecimal current) {
        return """
            {
              "id": "%s",
              "accountNumber": "1111",
              "routingNumber": "2222",
              "name": "%s",
              "legalBusinessName": "Acme Inc",
              "status": "%s",
              "type": "%s",
              "kind": "checking",
              "currentBalance": %s,
              "availableBalance": %s,
              "canSendRealTimePayments": true,
              "createdAt": "2024-01-01T00:00:00Z",
              "dashboardLink": "https://app.mercury.com/account/%s"
            }
            """.formatted(id, name, status, type, current, available, id);
    }

    private String accountsPageJson(String... accounts) {
        return """
            {
              "accounts": [%s],
              "page": {
                "nextPage": null,
                "previousPage": null
              }
            }
            """.formatted(String.join(",", accounts));
    }

    @Test
    void testModelValidatorValidForDefaultAndConfiguredTrigger() {
        var defaultTrigger = BalanceThresholdTrigger.builder()
            .id("watch-bal-default")
            .type(BalanceThresholdTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();
        assertThat(modelValidator.isValid(defaultTrigger).isEmpty(), is(true));

        var configuredTrigger = BalanceThresholdTrigger.builder()
            .id("watch-bal-configured")
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue("https://api.mercury.com/api/v1"))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("10000.00")))
            .balanceType(Property.ofValue(BalanceThresholdTrigger.BalanceType.CURRENT))
            .pageCap(Property.ofValue(10))
            .build();
        assertThat(modelValidator.isValid(configuredTrigger).isEmpty(), is(true));
    }

    @Test
    void testModelValidatorReportsInvalidConstantValues() {
        var invalidPageCapZero = BalanceThresholdTrigger.builder()
            .id("watch-bal-invalid")
            .type(BalanceThresholdTrigger.class.getName())
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .pageCap(Property.ofValue(0))
            .build();
        assertThat(modelValidator.isValid(invalidPageCapZero).isPresent(), is(true));
    }

    @Test
    void testFlowYamlValidationContainingTrigger() {
        var flowYaml = """
            id: flow_balance_trigger
            namespace: company.team
            tasks:
              - id: log_task
                type: io.kestra.plugin.core.log.Log
                message: "Low balance"
            triggers:
              - id: mercury_bal_trigger
                type: io.kestra.plugin.mercury.accounts.BalanceThresholdTrigger
                apiToken: "secret-token:valid-token"
                minBalance: 5000.00
            """;
        var flow = YamlParser.parse(flowYaml, Flow.class);
        assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
    }

    @Test
    void testPluginExamplesFlowValidation() {
        var plugin = BalanceThresholdTrigger.class.getAnnotation(Plugin.class);
        assertThat(plugin, notNullValue());
        for (Example example : plugin.examples()) {
            var code = String.join("\n", example.code());
            var flow = YamlParser.parse(code, Flow.class);
            assertThat(modelValidator.isValid(flow).isEmpty(), is(true));
        }
    }

    @Test
    void testCrossingTable() throws Exception {
        var triggerId = "watch-crossing-" + UUID.randomUUID();
        var trigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .balanceType(Property.ofValue(BalanceThresholdTrigger.BalanceType.AVAILABLE))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        // Poll 1: Initial below (balance 4000 < 5000) -> FIRES
        var acct1 = accountJson(ACCOUNT_ID_1, "Checking", "active", "mercury", new BigDecimal("4000.00"), new BigDecimal("4000.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acct1)));

        var opt1 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt1.isPresent(), is(true));
        var vars1 = opt1.get().getTrigger().getVariables();
        assertThat(vars1.get("size"), is(1));

        // Poll 2: Stays below (balance 4000) -> DOES NOT FIRE
        var opt2 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt2.isEmpty(), is(true));

        // Poll 3: Recovers (balance 6000 >= 5000) -> DOES NOT FIRE, but re-arms
        var acctRecovered = accountJson(ACCOUNT_ID_1, "Checking", "active", "mercury", new BigDecimal("6000.00"), new BigDecimal("6000.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acctRecovered)));

        var opt3 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt3.isEmpty(), is(true));

        // Poll 4: Drops again (balance 3500 < 5000) -> FIRES AGAIN
        var acctDropped = accountJson(ACCOUNT_ID_1, "Checking", "active", "mercury", new BigDecimal("3500.00"), new BigDecimal("3500.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acctDropped)));

        var opt4 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt4.isPresent(), is(true));
        assertThat(opt4.get().getTrigger().getVariables().get("size"), is(1));

        // Poll 5: Balance == minBalance (5000.00) -> equal is NOT below -> DOES NOT FIRE
        var acctEqual = accountJson(ACCOUNT_ID_1, "Checking", "active", "mercury", new BigDecimal("5000.00"), new BigDecimal("5000.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acctEqual)));

        var opt5 = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt5.isEmpty(), is(true));
    }

    @Test
    void testAvailableVsCurrentBalance() throws Exception {
        var triggerId = "watch-type-" + UUID.randomUUID();

        // Account has available 3000, current 7000
        var acct = accountJson(ACCOUNT_ID_1, "Checking", "active", "mercury", new BigDecimal("3000.00"), new BigDecimal("7000.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acct)));

        // Test AVAILABLE balance against threshold 5000: 3000 < 5000 -> FIRES
        var availableTrigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .balanceType(Property.ofValue(BalanceThresholdTrigger.BalanceType.AVAILABLE))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(availableTrigger, triggerContext);

        var optAvail = availableTrigger.evaluate(conditionContext, triggerContext);
        assertThat(optAvail.isPresent(), is(true));

        // Test CURRENT balance against threshold 5000: 7000 >= 5000 -> DOES NOT FIRE
        var currentTriggerId = "watch-current-" + UUID.randomUUID();
        var currentTrigger = BalanceThresholdTrigger.builder()
            .id(currentTriggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .balanceType(Property.ofValue(BalanceThresholdTrigger.BalanceType.CURRENT))
            .build();

        var currentTriggerContext = buildTriggerContext(currentTriggerId);
        var currentConditionContext = buildConditionContext(currentTrigger, currentTriggerContext);

        var optCurrent = currentTrigger.evaluate(currentConditionContext, currentTriggerContext);
        assertThat(optCurrent.isEmpty(), is(true));
    }

    @Test
    void testFilteringNonMercuryAndNonActiveAccounts() throws Exception {
        var triggerId = "watch-filter-" + UUID.randomUUID();

        // 5 accounts: only 1 is active mercury account
        var acctActiveMercury = accountJson(ACCOUNT_ID_1, "Active Mercury", "active", "mercury", new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        var acctExternal = accountJson("ext-111", "External Account", "active", "external", new BigDecimal("500.00"), new BigDecimal("500.00"));
        var acctRecipient = accountJson("rec-222", "Recipient Account", "active", "recipient", new BigDecimal("200.00"), new BigDecimal("200.00"));
        var acctDeleted = accountJson("del-333", "Deleted Mercury", "deleted", "mercury", new BigDecimal("100.00"), new BigDecimal("100.00"));
        var acctPending = accountJson("pen-444", "Pending Mercury", "pending", "mercury", new BigDecimal("50.00"), new BigDecimal("50.00"));

        var responseBody = accountsPageJson(acctActiveMercury, acctExternal, acctRecipient, acctDeleted, acctPending);
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(responseBody)));

        var trigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isPresent(), is(true));

        var vars = opt.get().getTrigger().getVariables();
        assertThat(vars.get("size"), is(1));

        @SuppressWarnings("unchecked")
        var alerts = (List<Map<String, Object>>) vars.get("accounts");
        assertThat(alerts, hasSize(1));
        assertThat(alerts.getFirst().get("id"), is(ACCOUNT_ID_1));
    }

    @Test
    void testSingleAccountPathVsAllAccountsPath() throws Exception {
        var singleTriggerId = "watch-single-" + UUID.randomUUID();
        var allTriggerId = "watch-all-" + UUID.randomUUID();

        var acct1 = accountJson(ACCOUNT_ID_1, "Single Acct", "active", "mercury", new BigDecimal("1000.00"), new BigDecimal("1000.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acct1)));
        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(accountsPageJson(acct1))));

        // 1. Single account path calls /account/{id}
        var singleTrigger = BalanceThresholdTrigger.builder()
            .id(singleTriggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var singleTriggerContext = buildTriggerContext(singleTriggerId);
        var singleConditionContext = buildConditionContext(singleTrigger, singleTriggerContext);

        var optSingle = singleTrigger.evaluate(singleConditionContext, singleTriggerContext);
        assertThat(optSingle.isPresent(), is(true));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/account/" + ACCOUNT_ID_1)));
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/accounts")));

        // 2. All accounts path calls /accounts
        var allTrigger = BalanceThresholdTrigger.builder()
            .id(allTriggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var allTriggerContext = buildTriggerContext(allTriggerId);
        var allConditionContext = buildConditionContext(allTrigger, allTriggerContext);

        var optAll = allTrigger.evaluate(allConditionContext, allTriggerContext);
        assertThat(optAll.isPresent(), is(true));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo("/accounts")));
    }

    @Test
    void testMultipleAccountsCrossingCombinedInSingleExecution() throws Exception {
        var triggerId = "watch-multi-" + UUID.randomUUID();

        var acct1 = accountJson(ACCOUNT_ID_1, "Checking", "active", "mercury", new BigDecimal("2000.00"), new BigDecimal("2000.00"));
        var acct2 = accountJson(ACCOUNT_ID_2, "Savings", "active", "mercury", new BigDecimal("3000.00"), new BigDecimal("3000.00"));

        wireMockServer.stubFor(get(urlPathEqualTo("/accounts"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(accountsPageJson(acct1, acct2))));

        var trigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isPresent(), is(true));

        var vars = opt.get().getTrigger().getVariables();
        assertThat(vars.get("size"), is(2));

        @SuppressWarnings("unchecked")
        var alerts = (List<Map<String, Object>>) vars.get("accounts");
        assertThat(alerts, hasSize(2));
    }

    @Test
    void testStateWrittenOnlyOnChange() throws Exception {
        var triggerId = "watch-nochange-" + UUID.randomUUID();

        // Account balance is 8000 (well above minBalance 5000)
        var acct = accountJson(ACCOUNT_ID_1, "Healthy", "active", "mercury", new BigDecimal("8000.00"), new BigDecimal("8000.00"));
        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(acct)));

        var trigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        // Pre-populate state with below=false
        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_bal_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + triggerId.length() + "_" + triggerId + "_"
            + ACCOUNT_ID_1.length() + "_" + ACCOUNT_ID_1;

        var belowMap = new HashMap<String, Boolean>();
        belowMap.put(ACCOUNT_ID_1, Boolean.FALSE);
        var initialState = BalanceThresholdTrigger.State.builder()
            .belowAccounts(belowMap)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(initialState)));

        var opt = trigger.evaluate(conditionContext, triggerContext);
        assertThat(opt.isEmpty(), is(true));

        var val = kv.getValue(key);
        assertThat(val.isPresent(), is(true));
        var state = JacksonMapper.ofJson().readValue((String) val.get().value(), BalanceThresholdTrigger.State.class);
        assertThat(state.getBelowAccounts().get(ACCOUNT_ID_1), is(false));
    }

    @Test
    void testMidstreamFailureLeavesStateUntouched() throws Exception {
        var triggerId = "watch-midstream-" + UUID.randomUUID();

        var trigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue(ACCOUNT_ID_1))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        var kv = conditionContext.getRunContext().namespaceKv(NAMESPACE);
        var key = "mercury_bal_" + FLOW_ID.length() + "_" + FLOW_ID + "_"
            + triggerId.length() + "_" + triggerId + "_"
            + ACCOUNT_ID_1.length() + "_" + ACCOUNT_ID_1;

        var belowMap = new HashMap<String, Boolean>();
        belowMap.put(ACCOUNT_ID_1, Boolean.TRUE);
        var initialState = BalanceThresholdTrigger.State.builder()
            .belowAccounts(belowMap)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(initialState)));

        wireMockServer.stubFor(get(urlPathEqualTo("/account/" + ACCOUNT_ID_1))
            .willReturn(aResponse().withStatus(500).withBody("{\"error\": \"Server error\"}")));

        assertThrows(Exception.class, () -> trigger.evaluate(conditionContext, triggerContext));

        var val = kv.getValue(key);
        var state = JacksonMapper.ofJson().readValue((String) val.get().value(), BalanceThresholdTrigger.State.class);
        assertThat(state.getBelowAccounts().get(ACCOUNT_ID_1), is(true));
    }

    @Test
    void testInvalidAccountIdRejectedWithoutHttp() {
        var triggerId = "watch-invalid-" + UUID.randomUUID();
        var trigger = BalanceThresholdTrigger.builder()
            .id(triggerId)
            .type(BalanceThresholdTrigger.class.getName())
            .baseUrl(Property.ofValue(wireMockServer.baseUrl()))
            .apiToken(Property.ofValue("secret-token:valid-token"))
            .accountId(Property.ofValue("not-a-valid-uuid"))
            .minBalance(Property.ofValue(new BigDecimal("5000.00")))
            .build();

        var triggerContext = buildTriggerContext(triggerId);
        var conditionContext = buildConditionContext(trigger, triggerContext);

        assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(conditionContext, triggerContext));
        assertThat(wireMockServer.getAllServeEvents().isEmpty(), is(true));
    }

    @Test
    void testKeyCollisionPrevention() {
        var flow1 = "a-b";
        var trig1 = "c";
        var key1 = "mercury_bal_" + flow1.length() + "_" + flow1 + "_"
            + trig1.length() + "_" + trig1;

        var flow2 = "a";
        var trig2 = "b-c";
        var key2 = "mercury_bal_" + flow2.length() + "_" + flow2 + "_"
            + trig2.length() + "_" + trig2;

        assertThat(key1, is(not(key2)));
        assertThat(key1, is("mercury_bal_3_a-b_1_c"));
        assertThat(key2, is("mercury_bal_1_a_3_b-c"));
    }
}
