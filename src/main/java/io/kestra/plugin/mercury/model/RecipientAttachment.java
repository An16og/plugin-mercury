package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecipientAttachment {

    @Schema(
        title = "Attachment file name",
        description = """
            Original name of the uploaded attachment file."""
    )
    private String fileName;

    @Schema(
        title = "Tax form type",
        description = """
            Type of tax form uploaded, if applicable. Possible values: w9, w8BEN, w8BENE, unknown."""
    )
    private String formType;

    @Schema(
        title = "Uploaded at timestamp",
        description = """
            Timestamp when the attachment was uploaded."""
    )
    private Instant uploadedAt;

    @Schema(
        title = "Download URL",
        description = """
            Presigned URL to download the attachment. Valid for 12 hours. Contains sensitive download tokens; never log this value."""
    )
    private String url;
}
