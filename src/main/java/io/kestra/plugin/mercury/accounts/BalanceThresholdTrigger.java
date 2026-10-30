
package io.kestra.plugin.mercury.accounts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.AbstractMercuryTrigger;
import io.kestra.plugin.mercury.model.Account;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger execution on Mercury balance threshold",
    description = """
        Periodically polls Mercury bank account balances and triggers an execution when an account drops below a configured minimum threshold."""
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger flow execution when any Mercury account drops below minimum balance",
            full = true,
            code = """
                id: trigger_mercury_balance_threshold
                namespace: company.team

                tasks:
                  - id: alert_low_balance
                    type: io.kestra.plugin.core.log.Log
                    message: "Accounts below minimum balance: {{ trigger.accounts }}"

                triggers:
                  - id: watch_balance
                    type: io.kestra.plugin.mercury.accounts.BalanceThresholdTrigger
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    minBalance: 5000.00
                """
        ),
        @Example(
            title = "Trigger flow execution when a specific Mercury account drops below minimum balance",
            full = true,
            code = """
                id: trigger_mercury_account_balance
                namespace: company.team

                tasks:
                  - id: alert_account_balance
                    type: io.kestra.plugin.core.log.Log
                    message: "Account {{ trigger.accounts[0].name }} dropped below {{ trigger.accounts[0].minBalance }}"

                triggers:
                  - id: watch_specific_account
                    type: io.kestra.plugin.mercury.accounts.BalanceThresholdTrigger
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                    minBalance: 10000.00
                    balanceType: AVAILABLE
                """
        )
    }
)
public class BalanceThresholdTrigger extends AbstractMercuryTrigger implements TriggerOutput<BalanceThresholdTrigger.Output> {

    public enum BalanceType {
        AVAILABLE,
        CURRENT
    }

    @Schema(
        title = "Minimum balance threshold",
        description = """
            The minimum balance threshold in USD. When an account's balance drops below this amount, the trigger fires."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<BigDecimal> minBalance;

    @Schema(
        title = "Balance type to evaluate",
        description = """
            The balance type to compare against the threshold: AVAILABLE or CURRENT. Default is AVAILABLE."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<BalanceType> balanceType = Property.ofValue(BalanceType.AVAILABLE);

    @Schema(
        title = "Account ID",
        description = """
            Optional unique identifier (UUID) of a specific Mercury account to monitor. When omitted, all active Mercury accounts are evaluated."""
    )
    @PluginProperty(group = "main")
    private Property<String> accountId;

    @Schema(
        title = "Maximum pages to retrieve",
        description = """
            Safety cap on the number of pages fetched when listing all accounts. Minimum 1. Default is 100."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<@Min(1) Integer> pageCap = Property.ofValue(AbstractMercuryConnection.DEFAULT_MAX_PAGE_CAP);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rMinBalance = runContext.render(this.minBalance).as(BigDecimal.class)
            .orElseThrow(() -> new IllegalArgumentException("minBalance is required"));

        var rBalanceType = runContext.render(this.balanceType).as(BalanceType.class).orElse(BalanceType.AVAILABLE);
        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(AbstractMercuryConnection.DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: " + rPageCap);
        }

        var rAccountId = runContext.render(this.accountId).as(String.class).orElse(null);
        var accountId = (rAccountId != null && !rAccountId.isBlank())
            ? requireUuid(rAccountId.trim(), "accountId")
            : null;

        var namespace = context.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("TriggerContext namespace cannot be null or blank");
        }

        var flowId = context.getFlowId();
        var triggerId = context.getTriggerId();
        var key = "mercury_bal_" + flowId.length() + "_" + flowId + "_"
            + triggerId.length() + "_" + triggerId
            + (accountId != null ? "_" + accountId.length() + "_" + accountId : "");

        var kv = runContext.namespaceKv(namespace);
        State state = null;
        try {
            var kvValueOpt = kv.getValue(key);
            if (kvValueOpt.isPresent() && kvValueOpt.get().value() != null) {
                var raw = kvValueOpt.get().value();
                if (raw instanceof String rawStr && !rawStr.isBlank()) {
                    state = JacksonMapper.ofJson().readValue(rawStr, State.class);
                } else if (raw != null) {
                    state = JacksonMapper.ofJson().convertValue(raw, State.class);
                }
            }
        } catch (Exception ignored) {
            state = null;
        }

        java.util.List<Account> accountsToCheck;
        if (accountId != null) {
            var getTask = Get.builder()
                .id(this.getId() + "_get")
                .type(Get.class.getName())
                .baseUrl(this.getBaseUrl())
                .apiToken(this.getApiToken())
                .accountId(Property.ofValue(accountId))
                .build();
            var getOutput = getTask.run(runContext);
            var single = getOutput.getAccount();
            accountsToCheck = single != null ? java.util.List.of(single) : java.util.List.of();
        } else {
            var listTask = List.builder()
                .id(this.getId() + "_list")
                .type(List.class.getName())
                .baseUrl(this.getBaseUrl())
                .apiToken(this.getApiToken())
                .fetchType(Property.ofValue(FetchType.FETCH))
                .pageCap(Property.ofValue(rPageCap))
                .build();
            var listOutput = listTask.run(runContext);
            accountsToCheck = listOutput.getRows() != null ? listOutput.getRows() : java.util.List.of();
        }

        var eligibleAccounts = accountsToCheck.stream()
            .filter(a -> a.getId() != null)
            .filter(a -> a.getType() != null && a.getType().equalsIgnoreCase("mercury"))
            .filter(a -> a.getStatus() != null && a.getStatus().equalsIgnoreCase("active"))
            .toList();

        var previousBelowMap = (state != null && state.getBelowAccounts() != null)
            ? state.getBelowAccounts()
            : Map.<String, Boolean>of();

        var updatedBelowMap = new HashMap<>(previousBelowMap);
        var newlyCrossedAlerts = new ArrayList<AccountAlert>();

        for (var account : eligibleAccounts) {
            var balance = switch (rBalanceType) {
                case AVAILABLE -> account.getAvailableBalance() != null ? account.getAvailableBalance() : BigDecimal.ZERO;
                case CURRENT -> account.getCurrentBalance() != null ? account.getCurrentBalance() : BigDecimal.ZERO;
            };

            var isBelow = balance.compareTo(rMinBalance) < 0;
            var wasBelow = previousBelowMap.getOrDefault(account.getId(), Boolean.FALSE);

            if (isBelow) {
                updatedBelowMap.put(account.getId(), Boolean.TRUE);
                if (!wasBelow) {
                    newlyCrossedAlerts.add(AccountAlert.builder()
                        .id(account.getId())
                        .name(account.getName())
                        .balance(balance)
                        .minBalance(rMinBalance)
                        .build());
                }
            } else {
                updatedBelowMap.put(account.getId(), Boolean.FALSE);
            }
        }

        var stateChanged = !updatedBelowMap.equals(previousBelowMap);

        if (!newlyCrossedAlerts.isEmpty()) {
            var output = Output.builder()
                .size(newlyCrossedAlerts.size())
                .accounts(newlyCrossedAlerts)
                .build();

            var execution = TriggerService.generateExecution(this, conditionContext, context, output);

            var newState = State.builder()
                .belowAccounts(updatedBelowMap)
                .build();
            kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(newState)));

            return Optional.of(execution);
        }

        if (stateChanged) {
            var newState = State.builder()
                .belowAccounts(updatedBelowMap)
                .build();
            kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(newState)));
        }

        return Optional.empty();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Number of accounts below threshold",
            description = """
                The count of accounts that have newly dropped below the minimum balance threshold."""
        )
        private final Integer size;

        @Schema(
            title = "Accounts below threshold",
            description = """
                List of accounts that have newly dropped below the minimum balance threshold."""
        )
        private final java.util.List<AccountAlert> accounts;
    }

    @Builder
    @Getter
    @Jacksonized
    public static class AccountAlert {
        @Schema(
            title = "Account ID",
            description = """
                The unique identifier (UUID) of the Mercury account."""
        )
        private final String id;

        @Schema(
            title = "Account name",
            description = """
                The name of the Mercury account."""
        )
        private final String name;

        @Schema(
            title = "Account balance",
            description = """
                The balance of the account evaluated against the threshold."""
        )
        private final BigDecimal balance;

        @Schema(
            title = "Minimum balance threshold",
            description = """
                The configured minimum balance threshold in USD."""
        )
        private final BigDecimal minBalance;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {
        @Builder.Default
        private Map<String, Boolean> belowAccounts = new HashMap<>();
    }
}
