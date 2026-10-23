package io.kestra.plugin.mercury.cards;

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

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
public class List extends AbstractMercuryConnection implements RunnableTask<List.Output> {

    private static final TypeReference<CardsResponse> CARDS_RESPONSE_TYPE_REF = new TypeReference<>() {};

    public enum Order {
        ASC,
        DESC
    }

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
            Maximum number of cards to return per page. Minimum 1, maximum 1000. Default is 500."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<@Min(1) @Max(1000) Integer> limit = Property.ofValue(500);

    @Schema(
        title = "Sort order",
        description = """
            Sort order for cards. Allowed values: ASC or DESC. Default is ASC."""
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
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(500);
        if (rLimit < 1 || rLimit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got: " + rLimit);
        }

        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: " + rPageCap);
        }

        var effectiveLimit = rFetchType == FetchType.FETCH_ONE ? 1 : rLimit;
        var effectivePageCap = rFetchType == FetchType.FETCH_ONE ? 1 : rPageCap;
        var rOrder = runContext.render(this.order).as(Order.class).orElse(Order.ASC);

        var rAccountId = runContext.render(this.accountId).as(String.class).orElse(null);
        var validatedAccountId = (rAccountId != null && !rAccountId.isBlank())
            ? requireUuid(rAccountId, "accountId")
            : null;

        var rStatus = runContext.render(this.status).as(Status.class).orElse(null);
        var rCardType = runContext.render(this.cardType).as(Type.class).orElse(null);
        var rKind = runContext.render(this.kind).as(Kind.class).orElse(null);
        var rUserId = runContext.render(this.userId).as(String.class).orElse(null);
        var trimmedUserId = (rUserId != null && !rUserId.isBlank()) ? rUserId.trim() : null;
        var rIsAgentCard = runContext.render(this.isAgentCard).as(Boolean.class).orElse(null);

        try (var client = createClient(runContext)) {
            PageFetcher<Card> fetcher = cursor -> {
                var queryParams = new HashMap<String, Object>();
                queryParams.put("limit", effectiveLimit);
                queryParams.put("order", rOrder.name().toLowerCase(Locale.ROOT));
                if (cursor != null && !cursor.isBlank()) {
                    queryParams.put("start_after", cursor);
                }
                if (validatedAccountId != null) {
                    queryParams.put("accountId", validatedAccountId);
                }
                if (rStatus != null) {
                    queryParams.put("status", rStatus.name().toLowerCase(Locale.ROOT));
                }
                if (rCardType != null) {
                    queryParams.put("type", rCardType.name().toLowerCase(Locale.ROOT));
                }
                if (rKind != null) {
                    queryParams.put("kind", rKind.name().toLowerCase(Locale.ROOT));
                }
                if (trimmedUserId != null) {
                    queryParams.put("userId", trimmedUserId);
                }
                if (rIsAgentCard != null) {
                    queryParams.put("isAgentCard", rIsAgentCard);
                }

                var response = request(client, runContext, "/cards", queryParams, CARDS_RESPONSE_TYPE_REF);
                if (response == null) {
                    return new PageResult<>(java.util.List.of(), null);
                }

                var items = response.getCards() != null ? response.getCards() : java.util.List.<Card>of();
                var nextCursor = response.getPage() != null ? response.getPage().getNextPage() : null;
                return new PageResult<>(items, nextCursor);
            };

            return switch (rFetchType) {
                case FETCH -> {
                    var rows = new ArrayList<Card>();
                    paginate(runContext, effectivePageCap, "cards", fetcher, card -> {
                        rows.add(card);
                        return true;
                    });
                    yield Output.builder()
                        .size(rows.size())
                        .rows(rows)
                        .build();
                }
                case FETCH_ONE -> {
                    var firstCard = new AtomicReference<Card>();
                    paginate(runContext, effectivePageCap, "cards", fetcher, card -> {
                        firstCard.set(card);
                        return false;
                    });
                    var found = firstCard.get();
                    yield Output.builder()
                        .size(found != null ? 1 : 0)
                        .row(found)
                        .build();
                }
                case STORE -> {
                    var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                    var storedCount = new AtomicInteger(0);
                    try (var output = new BufferedOutputStream(new FileOutputStream(tempFile), FileSerde.BUFFER_SIZE)) {
                        paginate(runContext, effectivePageCap, "cards", fetcher, card -> {
                            try {
                                FileSerde.write(output, JacksonMapper.toMap(card));
                                storedCount.incrementAndGet();
                                return true;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed writing card record to ION storage", e);
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
                    paginate(runContext, effectivePageCap, "cards", fetcher, card -> true);
                    yield Output.builder()
                        .size(0)
                        .build();
                }
            };
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
