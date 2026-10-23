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
public class MerchantInfo {

    @Schema(
        title = "Merchant ID",
        description = """
            Unique identifier (UUID) of the locked merchant."""
    )
    private String id;

    @Schema(
        title = "Merchant name",
        description = """
            Display name of the locked merchant."""
    )
    private String name;
}
