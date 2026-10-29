package io.kestra.plugin.mercury.statements;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mercury.AbstractMercuryList;
import io.kestra.plugin.mercury.model.Statement;
import io.kestra.plugin.mercury.model.StatementsResponse;
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

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Mercury statements",
    description = """
        Retrieve statements for a specific Mercury depository bank account with automatic cursor-based pagination and filtering."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all statements for an account into memory",
            full = true,
            code = """
                id: list_mercury_statements
                namespace: company.team

                tasks:
                  - id: list_statements
                    type: io.kestra.plugin.mercury.statements.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List statements filtered by period start date range",
            full = true,
            code = """
                id: list_statements_filtered
                namespace: company.team

                tasks:
                  - id: list_statements
                    type: io.kestra.plugin.mercury.statements.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                    start: "2024-01-01"
                    end: "2024-03-31"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Stream all statements for an account to internal storage",
            full = true,
            code = """
                id: store_mercury_statements
                namespace: company.team

                tasks:
                  - id: store_statements
                    type: io.kestra.plugin.mercury.statements.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractMercuryList implements RunnableTask<List.Output> {

    private static final TypeReference<StatementsResponse> STATEMENTS_RESPONSE_TYPE_REF = new TypeReference<>() {};

    @Schema(
        title = "Account ID",
        description = """
            The unique identifier (UUID) of the Mercury bank account whose statements to list. Depository accounts only; credit and treasury accounts are not supported."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> accountId;

    @Schema(
        title = "Results per page",
        description = """
            Maximum number of statements to return per page. Minimum 1, maximum 1000. Default is 1000."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(1000) Integer> limit = Property.ofValue(1000);

    @Schema(
        title = "Sort order",
        description = """
            Sort order of the returned records. Allowed values: ASC or DESC. Default is DESC."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Order> order = Property.ofValue(Order.DESC);

    @Schema(
        title = "Start date filter",
        description = """
            Earliest date to filter statements by period start date. Format: YYYY-MM-DD. Mercury enforces a maximum range of 3 months between start and end."""
    )
    @PluginProperty(group = "processing")
    private Property<String> start;

    @Schema(
        title = "End date filter",
        description = """
            Latest date to filter statements by period start date. Format: YYYY-MM-DD. Mercury defaults to the current date if omitted or set to a future date. Mercury enforces a maximum range of 3 months between start and end."""
    )
    @PluginProperty(group = "processing")
    private Property<String> end;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rAccountId = runContext.render(this.accountId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("accountId is required"));
        var accountId = requireUuid(rAccountId, "accountId");

        var params = listParams(runContext, this.limit, 1000);
        var rOrder = runContext.render(this.order).as(Order.class).orElse(Order.DESC);

        var rStart = runContext.render(this.start).as(String.class).orElse(null);
        if (rStart != null && !rStart.isBlank()) {
            validateDateString(rStart, "start");
        }

        var rEnd = runContext.render(this.end).as(String.class).orElse(null);
        if (rEnd != null && !rEnd.isBlank()) {
            validateDateString(rEnd, "end");
        }

        var baseParams = new HashMap<String, Object>();
        baseParams.put("limit", params.limit());
        baseParams.put("order", rOrder.name().toLowerCase(Locale.ROOT));
        if (rStart != null && !rStart.isBlank()) {
            baseParams.put("start", rStart.trim());
        }
        if (rEnd != null && !rEnd.isBlank()) {
            baseParams.put("end", rEnd.trim());
        }

        try (var client = createClient(runContext)) {
            PageFetcher<Statement> fetcher = cursor -> {
                var queryParams = new HashMap<>(baseParams);
                if (cursor != null && !cursor.isBlank()) {
                    queryParams.put("start_after", cursor);
                }

                var response = request(
                    client,
                    runContext,
                    "/account/" + accountId + "/statements",
                    queryParams,
                    STATEMENTS_RESPONSE_TYPE_REF
                );
                if (response == null) {
                    return new PageResult<>(java.util.List.of(), null);
                }

                var items = response.getStatements() != null ? response.getStatements() : java.util.List.<Statement>of();
                var nextCursor = response.getPage() != null ? response.getPage().getNextPage() : null;
                return new PageResult<>(items, nextCursor);
            };

            return fetch(
                runContext,
                params.fetchType(),
                params.pageCap(),
                "statements",
                fetcher,
                (size, rows, row, uri) -> Output.builder()
                    .size(size)
                    .rows(rows)
                    .row(row)
                    .uri(uri)
                    .build()
            );
        }
    }

    private void validateDateString(String value, String fieldName) {
        var trimmed = value.trim();
        try {
            LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "Invalid " + fieldName + " '" + value + "': must be in YYYY-MM-DD format (e.g., '2024-01-15')", e
            );
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The total number of statements returned",
            description = """
                For FETCH this matches the size of rows, for FETCH_ONE 0 or 1, for STORE the count of stored records, and 0 for NONE."""
        )
        private final Integer size;

        @Schema(
            title = "The statements fetched",
            description = """
                List of retrieved statements. Only populated when fetchType is FETCH."""
        )
        private final java.util.List<Statement> rows;

        @Schema(
            title = "The first statement fetched",
            description = """
                The first statement retrieved. Only populated when fetchType is FETCH_ONE."""
        )
        private final Statement row;

        @Schema(
            title = "URI of stored results",
            description = """
                The URI of the file containing ION-formatted records in internal storage. Only populated when fetchType is STORE."""
        )
        private final URI uri;
    }
}
