package io.kestra.plugin.mercury.cards;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mercury.AbstractMercuryList;
import io.kestra.plugin.mercury.model.Card;
import io.kestra.plugin.mercury.model.CardsResponse;
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
    title = "List Mercury cards",
    description = """
        Retrieve credit and debit cards across an organization from Mercury with automatic cursor-based pagination and filtering."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all Mercury cards into memory",
            full = true,
            code = """
                id: list_mercury_cards
                namespace: company.team

                tasks:
                  - id: list_cards
                    type: io.kestra.plugin.mercury.cards.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "List active virtual debit cards for a specific account",
            full = true,
            code = """
                id: list_active_virtual_cards
                namespace: company.team

                tasks:
                  - id: list_cards
                    type: io.kestra.plugin.mercury.cards.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    accountId: "4b54e3d6-72aa-4c07-b3c6-9f4a56a64010"
                    status: ACTIVE
                    cardType: VIRTUAL
                    kind: DEBIT
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Stream all Mercury cards to internal storage",
            full = true,
            code = """
                id: store_mercury_cards
                namespace: company.team

                tasks:
                  - id: store_cards
                    type: io.kestra.plugin.mercury.cards.List
                    apiToken: "{{ secret('MERCURY_API_TOKEN') }}"
                    fetchType: STORE
                """
        )
    }
)
public class List extends AbstractMercuryList implements RunnableTask<List.Output> {

    private static final TypeReference<CardsResponse> CARDS_RESPONSE_TYPE_REF = new TypeReference<>() {};

    public enum Status {
        ACTIVE,
        FROZEN,
        CANCELLED,
        INACTIVE,
        EXPIRED,
        SUSPENDED
    }

    public enum Type {
        VIRTUAL,
        PHYSICAL
    }

    public enum Kind {
        DEBIT,
        CREDIT
    }

    @Schema(
        title = "Results per page",
        description = """
            Maximum number of cards to return per page. Minimum 1, maximum 1000. Default is 500."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(1000) Integer> limit = Property.ofValue(500);

    @Schema(
        title = "Account ID filter",
        description = """
            Optional filter to retrieve cards associated with a specific Mercury bank account ID (UUID)."""
    )
    @PluginProperty(group = "processing")
    private Property<String> accountId;

    @Schema(
        title = "Card status filter",
        description = """
            Filter cards by lifecycle status: ACTIVE, FROZEN, CANCELLED, INACTIVE, EXPIRED, or SUSPENDED."""
    )
    @PluginProperty(group = "processing")
    private Property<Status> status;

    @Schema(
        title = "Card type filter",
        description = """
            Filter cards by type: VIRTUAL or PHYSICAL."""
    )
    @PluginProperty(group = "processing")
    private Property<Type> cardType;

    @Schema(
        title = "Card kind filter",
        description = """
            Filter cards by kind: DEBIT or CREDIT."""
    )
    @PluginProperty(group = "processing")
    private Property<Kind> kind;

    @Schema(
        title = "Cardholder user ID filter",
        description = """
            Filter cards by the cardholder's Mercury user ID."""
    )
    @PluginProperty(group = "processing")
    private Property<String> userId;

    @Schema(
        title = "Is agent card filter",
        description = """
            Filter cards by whether they are managed by the spend-management agent."""
    )
    @PluginProperty(group = "processing")
    private Property<Boolean> isAgentCard;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var params = listParams(runContext, this.limit, 500);

        var baseParams = new HashMap<String, Object>();
        baseParams.put("limit", params.limit());
        baseParams.put("order", params.order().name().toLowerCase(Locale.ROOT));
        runContext.render(this.accountId).as(String.class).filter(id -> !id.isBlank()).ifPresent(id -> baseParams.put("accountId", requireUuid(id, "accountId")));
        runContext.render(this.status).as(Status.class).ifPresent(s -> baseParams.put("status", s.name().toLowerCase(Locale.ROOT)));
        runContext.render(this.cardType).as(Type.class).ifPresent(t -> baseParams.put("type", t.name().toLowerCase(Locale.ROOT)));
        runContext.render(this.kind).as(Kind.class).ifPresent(k -> baseParams.put("kind", k.name().toLowerCase(Locale.ROOT)));
        runContext.render(this.userId).as(String.class).map(String::trim).filter(u -> !u.isEmpty()).ifPresent(u -> baseParams.put("userId", u));
        runContext.render(this.isAgentCard).as(Boolean.class).ifPresent(a -> baseParams.put("isAgentCard", a));

        try (var client = createClient(runContext)) {
            PageFetcher<Card> fetcher = cursor -> {
                var queryParams = new HashMap<>(baseParams);
                if (cursor != null && !cursor.isBlank()) {
                    queryParams.put("start_after", cursor);
                }

                var response = request(client, runContext, "/cards", queryParams, CARDS_RESPONSE_TYPE_REF);
                if (response == null) {
                    return new PageResult<>(java.util.List.of(), null);
                }

                var items = response.getCards() != null ? response.getCards() : java.util.List.<Card>of();
                var nextCursor = response.getPage() != null ? response.getPage().getNextPage() : null;
                return new PageResult<>(items, nextCursor);
            };

            return fetch(
                runContext,
                params.fetchType(),
                params.pageCap(),
                "cards",
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
            title = "The total number of cards returned",
            description = """
                For FETCH this matches the size of rows, for FETCH_ONE 0 or 1, for STORE the count of stored records, and 0 for NONE."""
        )
        private final Integer size;

        @Schema(
            title = "The cards fetched",
            description = """
                List of retrieved cards. Only populated when fetchType is FETCH."""
        )
        private final java.util.List<Card> rows;

        @Schema(
            title = "The first card fetched",
            description = """
                The first card retrieved. Only populated when fetchType is FETCH_ONE."""
        )
        private final Card row;

        @Schema(
            title = "URI of stored results",
            description = """
                The URI of the file containing ION-formatted records in internal storage. Only populated when fetchType is STORE."""
        )
        private final URI uri;
    }
}
