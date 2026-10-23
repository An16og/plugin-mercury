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
public class InternationalWireCorrespondentInfo {

    @Schema(
        title = "Correspondent bank name",
        description = """
            Name of the intermediary or correspondent bank."""
    )
    private String bankName;

    @Schema(
        title = "Correspondent routing number",
        description = """
            Routing or transit number of the correspondent bank."""
    )
    private String routingNumber;

    @Schema(
        title = "Correspondent SWIFT code",
        description = """
            SWIFT/BIC code of the correspondent bank."""
    )
    private String swiftCode;
}
