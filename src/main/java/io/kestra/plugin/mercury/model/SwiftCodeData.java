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
public class SwiftCodeData {

    @Schema(
        title = "Bank name",
        description = """
            Name of the financial institution associated with the SWIFT code."""
    )
    private String bankName;

    @Schema(
        title = "Bank city and state",
        description = """
            City and state or province of the financial institution."""
    )
    private String bankCityState;

    @Schema(
        title = "Bank country code",
        description = """
            Two-letter ISO 3166-1 alpha-2 country code of the financial institution."""
    )
    private String bankCountry;
}
