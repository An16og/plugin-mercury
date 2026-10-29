package io.kestra.plugin.mercury;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMercuryList extends AbstractMercuryConnection {

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
        title = "Sort order",
        description = """
            Sort order of the returned records. Allowed values: ASC or DESC. Defaults to ASC for accounts, cards, and recipients; defaults to DESC for statements."""
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

    protected record ListParams(FetchType fetchType, int limit, int pageCap, Order order) {
    }

    protected Order defaultOrder() {
        return Order.ASC;
    }

    // FETCH_ONE asks for a single record on a single page.
    protected ListParams listParams(RunContext runContext, Property<Integer> limit, int defaultLimit) throws IllegalVariableEvaluationException {
        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rLimit = runContext.render(limit).as(Integer.class).orElse(defaultLimit);
        if (rLimit < 1 || rLimit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000, got: %d".formatted(rLimit));
        }

        var rPageCap = runContext.render(this.pageCap).as(Integer.class).orElse(DEFAULT_MAX_PAGE_CAP);
        if (rPageCap < 1) {
            throw new IllegalArgumentException("pageCap must be at least 1, got: %d".formatted(rPageCap));
        }

        var rOrder = runContext.render(this.order).as(Order.class).orElse(defaultOrder());
        var fetchOne = rFetchType == FetchType.FETCH_ONE;
        return new ListParams(rFetchType, fetchOne ? 1 : rLimit, fetchOne ? 1 : rPageCap, rOrder);
    }
}
