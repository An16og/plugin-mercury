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
public class CardExpiration {

    @Schema(
        title = "Expiration month",
        description = """
            Two-digit calendar month when the card expires (1-12)."""
    )
    private Integer month;

    @Schema(
        title = "Expiration year",
        description = """
            Four-digit calendar year when the card expires (2000-2999)."""
    )
    private Integer year;
}
