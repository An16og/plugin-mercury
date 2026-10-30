package io.kestra.plugin.mercury.transactions;

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
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.AbstractMercuryTrigger;
import io.kestra.plugin.mercury.model.Transaction;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
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

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger execution on new Mercury transactions",
    description = """
        Periodically polls the Mercury API for new transactions in a specific bank account, deduplicating records across polling intervals using a lookback window and the namespace KV store."""
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger flow execution on new Mercury transactions into memory",
            full = true,
            code = """
                id: trigger_mercury_transactions
                namespace: company.team

                tasks:
                  - id: log_transaction
                    type: io.kestra.plugin.core.log.Log
                    message: "New transaction received: {{ trigger.rows }}"

                triggers:
                  - id: watch_transactions
                    type: io.kestra.plugin.mercury.transactions.TransactionTrigger
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                """
        ),
        @Example(
            title = "Trigger flow execution on new Mercury transactions streamed to storage",
            full = true,
            code = """
                id: trigger_mercury_transactions_store
                namespace: company.team

                tasks:
                  - id: log_stored
                    type: io.kestra.plugin.core.log.Log
                    message: "Stored transactions URI: {{ trigger.uri }}"

                triggers:
                  - id: watch_transactions_store
                    type: io.kestra.plugin.mercury.transactions.TransactionTrigger
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                    fetchType: STORE
                """
        )
    }
)
public class TransactionTrigger extends AbstractMercuryTrigger implements TriggerOutput<List.Output> {

    private static final int MAX_SEEN_IDS = 10_000;

    @Schema(
        title = "Account ID",
        description = """
            The unique identifier (UUID) of the Mercury account whose transactions to monitor."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> accountId;

    @Schema(
        title = "Fetch mode",
        description = """
            Controls how results are returned: FETCH to return all rows in memory, FETCH_ONE for the first row, STORE to stream records as ION into internal storage, or NONE to skip collecting outputs. Default is FETCH."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Results per page",
        description = """
            Maximum number of transactions to return per page. Minimum 1, maximum 1000. Default is 1000."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(1000) Integer> limit = Property.ofValue(1000);

    @Schema(
        title = "Maximum pages to retrieve",
        description = """
            Safety cap on the number of pages fetched during pagination. Minimum 1. Default is 100."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<@Min(1) Integer> pageCap = Property.ofValue(AbstractMercuryConnection.DEFAULT_MAX_PAGE_CAP);

    @Schema(
        title = "Transaction status filter",
        description = """
            Filter transactions by status: PENDING, SENT, CANCELLED, FAILED, REVERSED, or BLOCKED."""
    )
    @PluginProperty(group = "processing")
    private Property<List.Status> status;

    @Schema(
        title = "Search term filter",
        description = """
            Search term to filter transactions by description, note, recipient memo, or counterparty name."""
    )
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Schema(
        title = "Lookback window",
        description = """
            Safety lookback duration for overlapping polls to catch delayed or out-of-order transactions. Minimum 1 minute. Default is PT24H (24 hours)."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Duration> lookback = Property.ofValue(Duration.ofHours(24));

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rAccountId = runContext.render(this.accountId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("accountId is required"));
        var accountId = requireUuid(rAccountId, "accountId");

        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(1000);
        if (rLimit < 1 || rLimit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got: " + rLimit);
        }

        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(AbstractMercuryConnection.DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: " + rPageCap);
        }

        var rLookback = runContext.render(this.lookback).as(Duration.class).orElse(Duration.ofHours(24));
        if (rLookback.isNegative() || rLookback.isZero()) {
            throw new IllegalArgumentException("lookback must be a positive duration");
        }

        var namespace = context.getNamespace();
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("TriggerContext namespace cannot be null or blank");
        }

        var flowId = context.getFlowId();
        var triggerId = context.getTriggerId();
        var rStatus = runContext.render(this.status).as(List.Status.class).orElse(null);

        var key = "mercury_tx_" + flowId.length() + "_" + flowId + "_"
            + triggerId.length() + "_" + triggerId + "_"
            + accountId.length() + "_" + accountId
            + (rStatus != null ? "_" + rStatus.name() : "");

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

        var now = Instant.now();
        var isFirstPoll = (state == null || state.getWatermark() == null);
        var startTime = isFirstPoll ? now.minus(rLookback) : state.getWatermark().minus(rLookback);

        var effectiveLimit = rFetchType == FetchType.FETCH_ONE ? 1 : rLimit;
        var effectivePageCap = rFetchType == FetchType.FETCH_ONE ? 1 : rPageCap;

        var listTask = List.builder()
            .id(this.getId() + "_list")
            .type(List.class.getName())
            .baseUrl(this.getBaseUrl())
            .apiToken(this.getApiToken())
            .accountId(Property.ofValue(accountId))
            .start(Property.ofValue(startTime.toString()))
            .end(Property.ofValue(now.toString()))
            .limit(Property.ofValue(effectiveLimit))
            .pageCap(Property.ofValue(effectivePageCap))
            .status(this.status)
            .search(this.search)
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var listOutput = listTask.run(runContext);
        var allFetched = listOutput.getRows() != null ? listOutput.getRows() : java.util.List.<Transaction>of();

        if (isFirstPoll) {
            var seenIds = new LinkedHashSet<String>();
            for (var tx : allFetched) {
                if (tx.getId() != null) {
                    seenIds.add(tx.getId());
                }
            }
            while (seenIds.size() > MAX_SEEN_IDS) {
                var it = seenIds.iterator();
                it.next();
                it.remove();
            }
            var baselineState = State.builder()
                .watermark(now)
                .seenIds(seenIds)
                .build();
            kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(baselineState)));
            return Optional.empty();
        }

        var previousSeenIds = state.getSeenIds() != null ? state.getSeenIds() : Set.<String>of();
        var newTransactions = allFetched.stream()
            .filter(tx -> tx.getId() != null && !previousSeenIds.contains(tx.getId()))
            .toList();

        if (newTransactions.isEmpty()) {
            return Optional.empty();
        }

        record OutputAndEmitted(List.Output output, java.util.List<String> emittedIds) {}
        var result = switch (rFetchType) {
            case FETCH -> new OutputAndEmitted(
                List.Output.builder().size(newTransactions.size()).rows(newTransactions).build(),
                newTransactions.stream().map(Transaction::getId).toList()
            );
            case FETCH_ONE -> {
                var single = newTransactions.getFirst();
                yield new OutputAndEmitted(
                    List.Output.builder().size(1).row(single).build(),
                    java.util.List.of(single.getId())
                );
            }
            case STORE -> {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (var outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                    for (var tx : newTransactions) {
                        FileSerde.write(outputStream, tx);
                    }
                }
                var uri = runContext.storage().putFile(tempFile);
                yield new OutputAndEmitted(
                    List.Output.builder().size(newTransactions.size()).uri(uri).build(),
                    newTransactions.stream().map(Transaction::getId).toList()
                );
            }
            case NONE -> new OutputAndEmitted(
                List.Output.builder().size(0).build(),
                newTransactions.stream().map(Transaction::getId).toList()
            );
        };

        var execution = TriggerService.generateExecution(this, conditionContext, context, result.output());

        var updatedSeenIds = new LinkedHashSet<>(previousSeenIds);
        updatedSeenIds.addAll(result.emittedIds());
        while (updatedSeenIds.size() > MAX_SEEN_IDS) {
            var it = updatedSeenIds.iterator();
            it.next();
            it.remove();
        }

        var updatedState = State.builder()
            .watermark(now)
            .seenIds(updatedSeenIds)
            .build();
        kv.put(key, new KVValueAndMetadata(null, JacksonMapper.ofJson().writeValueAsString(updatedState)));

        return Optional.of(execution);
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class State {
        private Instant watermark;
        @Builder.Default
        private LinkedHashSet<String> seenIds = new LinkedHashSet<>();
    }
}
