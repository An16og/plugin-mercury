package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class InternationalWireRoutingInfo {

    @Schema(
        title = "International bank account number",
        description = """
            International Bank Account Number (IBAN) for the recipient."""
    )
    private String iban;

    @Schema(
        title = "SWIFT or BIC code",
        description = """
            Society for Worldwide Interbank Financial Telecommunication (SWIFT) or Business Identifier Code (BIC)."""
    )
    private String swiftCode;

    @Schema(
        title = "Country-specific banking details",
        description = """
            Generic key-value map containing country-specific payment routing fields (e.g., IFSC for India, BSB for Australia, transit/bank code for Canada, legal ID for Brazil/Chile/Colombia)."""
    )
    private Map<String, Object> countrySpecific;

    @Schema(
        title = "Correspondent bank information",
        description = """
            Details of the intermediary or correspondent bank, if required for routing."""
    )
    private InternationalWireCorrespondentInfo correspondentInfo;

    @Schema(
        title = "Beneficiary bank details",
        description = """
            Bank name, city, state, and country associated with the SWIFT code."""
    )
    private SwiftCodeData bankDetails;

    @Schema(
        title = "Bank contact email address",
        description = """
            Contact email for the recipient bank."""
    )
    private String emailAddress;

    @Schema(
        title = "Bank contact phone number",
        description = """
            Contact telephone number for the recipient bank."""
    )
    private String phoneNumber;

    @Schema(
        title = "Beneficiary bank address",
        description = """
            Postal address of the recipient financial institution."""
    )
    private Address address;
}
