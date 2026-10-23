package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecipientsResponse {

    @Schema(
        title = "Current page total",
        description = """
            Number of recipients returned in the current page (not the overall count across all pages)."""
    )
    private Integer total;

    @Schema(
        title = "Recipients list",
        description = """
            List of recipients returned in the current page."""
    )
    private List<Recipient> recipients;

    @Schema(
        title = "Pagination cursors",
        description = """
            Cursor identifiers for navigating between pages."""
    )
    private Page page;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Page {

        @Schema(
            title = "Next page cursor",
            description = """
                Identifier of the last recipient in this page, used as start_after to fetch the next page."""
        )
        private String nextPage;

        @Schema(
            title = "Previous page cursor",
            description = """
                Identifier of the first recipient in this page, used as end_before to fetch the previous page."""
        )
        private String previousPage;
    }
}
