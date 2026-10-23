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
public class SpendLimit {

    @Schema(
        title = "Spend limit in cents",
        description = """
            Maximum total spend allowed per rolling interval, in cents."""
    )
    private Long amountCents;

    @Schema(
        title = "ATM limit in cents",
        description = """
            Maximum ATM cash withdrawal allowed per rolling interval, in cents. Null for virtual cards and credit cards."""
    )
    private Long atmAmountCents;

    @Schema(
        title = "Spend limit interval",
        description = """
            Rolling time window the spend limit applies to. Possible values: daily, weekly, monthly, yearly."""
    )
    private String interval;
}
