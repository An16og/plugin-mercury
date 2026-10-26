package io.kestra.plugin.mercury.recipients;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mercury.AbstractMercuryConnection;
import io.kestra.plugin.mercury.model.Recipient;
import io.kestra.plugin.mercury.model.RecipientsResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Mercury recipients",
    description = """
        Retrieve payment recipients from Mercury with automatic cursor-based pagination.
        Notice: recipient records contain sensitive banking information such as account and routing numbers, IBANs, and presigned attachment URLs. This information is returned in output records for workflow consumption but is never logged."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all Mercury recipients into memory",
            full = true,
            code = """
                id: list_mercury_recipients
                namespace: company.team

                tasks:
                  - id: list_recipients
                    type: io.kestra.plugin.mercury.recipients.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Stream all Mercury recipients to internal storage",
            full = true,
            code = """
                id: store_mercury_recipients
                namespace: company.team

                tasks:
                  - id: store_recipients
                    type: io.kestra.plugin.mercury.recipients.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractMercuryConnection implements RunnableTask<List.Output> {

    private static final TypeReference<RecipientsResponse> RECIPIENTS_RESPONSE_TYPE_REF = new TypeReference<>() {};

    public enum Order {
        ASC,
        DESC
    }

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
            Maximum number of recipients to return per page. Minimum 1, maximum 1000. Default is 1000."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(1000) Integer> limit = Property.ofValue(1000);

    @Schema(
        title = "Sort order",
        description = """
            Sort order for recipients. Allowed values: ASC or DESC. Default is ASC."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<Order> order = Property.ofValue(Order.ASC);

    @Schema(
        title = "Maximum pages to retrieve",
        description = """
            Safety cap on the number of pages fetched during pagination. Minimum 1. Default is 100."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<@Min(1) Integer> pageCap = Property.ofValue(DEFAULT_MAX_PAGE_CAP);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(1000);
        if (rLimit < 1 || rLimit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got: %d".formatted(rLimit));
        }

        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: %d".formatted(rPageCap));
        }

        var effectiveLimit = rFetchType == FetchType.FETCH_ONE ? 1 : rLimit;
        var effectivePageCap = rFetchType == FetchType.FETCH_ONE ? 1 : rPageCap;
        var rOrder = runContext.render(this.order).as(Order.class).orElse(Order.ASC);

        try (var client = createClient(runContext)) {
            PageFetcher<Recipient> fetcher = cursor -> {
                var queryParams = new HashMap<String, Object>();
                queryParams.put("limit", effectiveLimit);
                queryParams.put("order", rOrder.name().toLowerCase(Locale.ROOT));
                if (cursor != null && !cursor.isBlank()) {
                    queryParams.put("start_after", cursor);
                }

                var response = request(client, runContext, "/recipients", queryParams, RECIPIENTS_RESPONSE_TYPE_REF);
                if (response == null) {
                    return new PageResult<>(java.util.List.of(), null);
                }

                var items = response.getRecipients() != null ? response.getRecipients() : java.util.List.<Recipient>of();
                var nextCursor = response.getPage() != null ? response.getPage().getNextPage() : null;
                return new PageResult<>(items, nextCursor);
            };

            return fetch(
                runContext,
                rFetchType,
                effectivePageCap,
                "recipients",
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

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The total number of recipients returned",
            description = """
                For FETCH this matches the size of rows, for FETCH_ONE 0 or 1, for STORE the count of stored records, and 0 for NONE."""
        )
        private final Integer size;

        @Schema(
            title = "The recipients fetched",
            description = """
                List of retrieved recipients. Only populated when fetchType is FETCH."""
        )
        private final java.util.List<Recipient> rows;

        @Schema(
            title = "The first recipient fetched",
            description = """
                The first recipient retrieved. Only populated when fetchType is FETCH_ONE."""
        )
        private final Recipient row;

        @Schema(
            title = "URI of stored results",
            description = """
                The URI of the file containing ION-formatted records in internal storage. Only populated when fetchType is STORE."""
        )
        private final URI uri;
    }
}
