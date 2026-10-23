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
public class CardsResponse {

    @Schema(
        title = "Cards list",
        description = """
            List of cards returned in the current page."""
    )
    private List<Card> cards;

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
                Identifier of the last card in this page, used as start_after to fetch the next page."""
        )
        private String nextPage;

        @Schema(
            title = "Previous page cursor",
            description = """
                Identifier of the first card in this page, used as end_before to fetch the previous page."""
        )
        private String previousPage;
    }
}
