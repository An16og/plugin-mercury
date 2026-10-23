package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class CardBudget {

    @Schema(
        title = "Budget ID",
        description = """
            Unique identifier (UUID) for this budget."""
    )
    private String id;

    @Schema(
        title = "Budget name",
        description = """
            Display name of the budget."""
    )
    private String name;

    @Schema(
        title = "Budget amount in cents",
        description = """
            The cardholder's spend limit for the current budget cycle, in cents."""
    )
    private Long amountCents;

    @Schema(
        title = "Remaining budget amount in cents",
        description = """
            Current-cycle limit minus recorded spend, clamped at zero, in cents."""
    )
    private Long remainingAmountCents;
}
