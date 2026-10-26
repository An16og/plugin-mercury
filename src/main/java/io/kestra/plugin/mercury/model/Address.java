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
public class Address {

    @Schema(
        title = "Recipient address name",
        description = """
            Name associated with this address, if present."""
    )
    private String name;

    @Schema(
        title = "Address line 1",
        description = """
            Primary street address line."""
    )
    private String address1;

    @Schema(
        title = "Address line 2",
        description = """
            Secondary street address line (apartment, suite, unit, etc.)."""
    )
    private String address2;

    @Schema(
        title = "City",
        description = """
            City of the address."""
    )
    private String city;

    @Schema(
        title = "Region",
        description = """
            State, province, or region code."""
    )
    private String region;

    @Schema(
        title = "Postal code",
        description = """
            Postal or ZIP code."""
    )
    private String postalCode;

    @Schema(
        title = "Country code",
        description = """
            Two-letter ISO 3166-1 alpha-2 country code."""
    )
    private String country;
}
