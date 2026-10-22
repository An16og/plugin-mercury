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
public class TransactionsResponse {

    @Schema(
        title = "Total transactions count",
        description = """
            Total number of transactions matching the filter criteria."""
    )
    private Integer total;

    @Schema(
        title = "Transactions list",
        description = """
            List of transactions returned in the current page."""
    )
    private List<Transaction> transactions;
}
