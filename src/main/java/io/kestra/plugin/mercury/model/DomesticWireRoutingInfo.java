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
public class DomesticWireRoutingInfo {

    @Schema(
        title = "Account number",
        description = """
            Bank account number for domestic wire transfers."""
    )
    private String accountNumber;

    @Schema(
        title = "Routing number",
        description = """
            Nine-digit Fedwire routing transit number."""
    )
    private String routingNumber;

    @Schema(
        title = "Bank name",
        description = """
            Name of the recipient financial institution."""
    )
    private String bankName;

    @Schema(
        title = "Bank address",
        description = """
            Postal address of the recipient financial institution."""
    )
    private AddressWithoutName address;
}
