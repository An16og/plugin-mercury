package io.kestra.plugin.mercury.transactions;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.model.Transaction;
import io.kestra.plugin.mercury.model.TransactionsResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Mercury transactions",
    description = """
        Retrieve transactions for a specific Mercury bank account with automatic offset-based pagination and filtering."""
)
@Plugin(
    examples = {
        @Example(
            title = "List Mercury transactions into memory",
            full = true,
            code = """
                id: list_mercury_transactions
                namespace: company.team

                tasks:
                  - id: list_transactions
                    type: io.kestra.plugin.mercury.transactions.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Stream Mercury transactions to internal storage",
            full = true,
            code = """
                id: store_mercury_transactions
                namespace: company.team

                tasks:
                  - id: store_transactions
                    type: io.kestra.plugin.mercury.transactions.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "d0e567d8-76a7-11e9-a22d-0b33e9de0755"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractMercuryConnection implements RunnableTask<List.Output> {

    private static final TypeReference<TransactionsResponse> TRANSACTIONS_RESPONSE_TYPE_REF = new TypeReference<>() {};

    public enum Order {
        ASC,
        DESC;

        @JsonValue
        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    public enum Status {
        PENDING,
        SENT,
        CANCELLED,
        FAILED,
        REVERSED,
        BLOCKED;

        @JsonValue
        @Override
        public String toString() {
            return name().toLowerCase();
        }
    }

    @Schema(
        title = "Account ID",
        description = """
            The unique identifier (UUID) of the Mercury account whose transactions to list."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> accountId;

    @Schema(
        title = "Fetch mode",
        description = """
            Controls how results are returned: FETCH to return all rows in memory, FETCH_ONE for the first row, STORE to stream records as ION into internal storage, or NONE to skip collecting outputs."""
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
        title = "Sort order",
        description = """
            Sort order for transactions. Allowed values: ASC or DESC. Defaults to DESC in the Mercury API when omitted."""
    )
    @PluginProperty(group = "processing")
    private Property<Order> order;

    @Schema(
        title = "Maximum pages to retrieve",
        description = """
            Safety cap on the number of pages fetched during pagination. Minimum 1. Default is 100."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<@Min(1) Integer> pageCap = Property.ofValue(DEFAULT_MAX_PAGE_CAP);

    @Schema(
        title = "Start date filter",
        description = """
            Earliest date to filter transactions. Format: YYYY-MM-DD or ISO 8601 string. Mercury defaults to 30 days prior to the current date when omitted."""
    )
    @PluginProperty(group = "processing")
    private Property<String> start;

    @Schema(
        title = "End date filter",
        description = """
            Latest date to filter transactions. Format: YYYY-MM-DD or ISO 8601 string. Mercury defaults to the current date when omitted."""
    )
    @PluginProperty(group = "processing")
    private Property<String> end;

    @Schema(
        title = "Transaction status filter",
        description = """
            Filter transactions by status: PENDING, SENT, CANCELLED, FAILED, REVERSED, or BLOCKED."""
    )
    @PluginProperty(group = "processing")
    private Property<Status> status;

    @Schema(
        title = "Search term filter",
        description = """
            Search term to filter transactions by description, note, recipient memo, or counterparty name."""
    )
    @PluginProperty(group = "processing")
    private Property<String> search;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAccountId = runContext.render(this.accountId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("accountId is required"));

        try {
            UUID.fromString(rAccountId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid accountId '" + rAccountId + "': must be a valid UUID", e);
        }

        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(1000);
        if (rLimit < 1 || rLimit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got: " + rLimit);
        }

        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: " + rPageCap);
        }

        var rOrder = runContext.render(this.order).as(Order.class).orElse(null);
        var rStatus = runContext.render(this.status).as(Status.class).orElse(null);
        var rSearch = runContext.render(this.search).as(String.class).orElse(null);

        var rStart = runContext.render(this.start).as(String.class).orElse(null);
        if (rStart != null && !rStart.isBlank()) {
            validateDateOrIsoString(rStart, "start");
        }

        var rEnd = runContext.render(this.end).as(String.class).orElse(null);
        if (rEnd != null && !rEnd.isBlank()) {
            validateDateOrIsoString(rEnd, "end");
        }

        try (var client = createClient(runContext)) {
            OffsetPageFetcher<Transaction> fetcher = offset -> {
                var queryParams = new HashMap<String, Object>();
                queryParams.put("limit", rLimit);
                queryParams.put("offset", offset);

                if (rOrder != null) {
                    queryParams.put("order", rOrder.name().toLowerCase());
                }
                if (rStart != null && !rStart.isBlank()) {
                    queryParams.put("start", rStart.trim());
                }
                if (rEnd != null && !rEnd.isBlank()) {
                    queryParams.put("end", rEnd.trim());
                }
                if (rStatus != null) {
                    queryParams.put("status", rStatus.name().toLowerCase());
                }
                if (rSearch != null && !rSearch.isBlank()) {
                    queryParams.put("search", rSearch.trim());
                }

                var response = request(
                    client,
                    runContext,
                    "/account/" + rAccountId.trim() + "/transactions",
                    queryParams,
                    TRANSACTIONS_RESPONSE_TYPE_REF
                );

                if (response == null) {
                    return new OffsetPageResult<>(java.util.List.of(), 0);
                }

                var items = response.getTransactions() != null ? response.getTransactions() : java.util.List.<Transaction>of();
                var total = response.getTotal() != null ? response.getTotal() : 0;
                return new OffsetPageResult<>(items, total);
            };

            return switch (rFetchType) {
                case FETCH -> {
                    var rows = new ArrayList<Transaction>();
                    paginateOffset(runContext, rPageCap, fetcher, tx -> {
                        rows.add(tx);
                        return true;
                    });
                    yield Output.builder()
                        .size(rows.size())
                        .rows(rows)
                        .build();
                }
                case FETCH_ONE -> {
                    var firstTx = new AtomicReference<Transaction>();
                    paginateOffset(runContext, rPageCap, fetcher, tx -> {
                        firstTx.set(tx);
                        return false;
                    });
                    var found = firstTx.get();
                    yield Output.builder()
                        .size(found != null ? 1 : 0)
                        .row(found)
                        .build();
                }
                case STORE -> {
                    var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                    var storedCount = new AtomicInteger(0);
                    try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                        paginateOffset(runContext, rPageCap, fetcher, tx -> {
                            try {
                                FileSerde.write(output, JacksonMapper.toMap(tx));
                                storedCount.incrementAndGet();
                                return true;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed writing transaction record to ION storage", e);
                            }
                        });
                    }
                    var uri = runContext.storage().putFile(tempFile);
                    yield Output.builder()
                        .size(storedCount.get())
                        .uri(uri)
                        .build();
                }
                case NONE -> {
                    paginateOffset(runContext, rPageCap, fetcher, tx -> true);
                    yield Output.builder()
                        .size(0)
                        .build();
                }
            };
        }
    }

    private void validateDateOrIsoString(String value, String fieldName) {
        var trimmed = value.trim();
        try {
            LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
            return;
        } catch (DateTimeParseException ignored) {
        }
        try {
            Instant.parse(trimmed);
            return;
        } catch (DateTimeParseException ignored) {
        }
        try {
            OffsetDateTime.parse(trimmed);
            return;
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException(
            "Invalid " + fieldName + " '" + value + "': must be in YYYY-MM-DD or ISO 8601 format (e.g., '2024-01-15' or '2024-01-15T08:30:00Z')"
        );
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The total number of transactions returned",
            description = """
                For FETCH this matches the size of rows, for FETCH_ONE 0 or 1, for STORE the count of stored records, and 0 for NONE."""
        )
        private final Integer size;

        @Schema(
            title = "The transactions fetched",
            description = """
                List of retrieved transactions. Only populated when fetchType is FETCH."""
        )
        private final java.util.List<Transaction> rows;

        @Schema(
            title = "The first transaction fetched",
            description = """
                The first transaction retrieved. Only populated when fetchType is FETCH_ONE."""
        )
        private final Transaction row;

        @Schema(
            title = "URI of stored results",
            description = """
                The URI of the file containing ION-formatted records in internal storage. Only populated when fetchType is STORE."""
        )
        private final URI uri;
    }
}
