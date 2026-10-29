package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class Statement {

    @Schema(
        title = "Statement ID",
        description = """
            Unique identifier (UUID) of the statement."""
    )
    private String id;

    @Schema(
        title = "Start date",
        description = """
            Start date and time of the statement period in UTC."""
    )
    private Instant startDate;

    @Schema(
        title = "End date",
        description = """
            End date and time of the statement period in UTC."""
    )
    private Instant endDate;

    @Schema(
        title = "Account number",
        description = """
            Bank account number for the depository account associated with this statement. Sensitive banking details; never log."""
    )
    private String accountNumber;

    @Schema(
        title = "Routing number",
        description = """
            American Bankers Association (ABA) routing transit number for the depository account. Sensitive banking details; never log."""
    )
    private String routingNumber;

    @Schema(
        title = "Company legal name",
        description = """
            Legal name of the entity that owns the depository account."""
    )
    private String companyLegalName;

    @Schema(
        title = "Company legal address",
        description = """
            Registered legal address of the company associated with the account. Sensitive personal or business data; never log."""
    )
    private Recipient.Address companyLegalAddress;

    @Schema(
        title = "Employer identification number",
        description = """
            Federal Employer Identification Number (EIN) or tax identification number. Sensitive tax details; never log."""
    )
    private String ein;

    @Schema(
        title = "Ending balance",
        description = """
            Ending ledger balance of the account for the statement period in USD."""
    )
    private BigDecimal endingBalance;

    @Schema(
        title = "Download URL",
        description = """
            Download URL for the statement document. May contain signed or credentialed parameters; never log."""
    )
    private String downloadUrl;

    @Schema(
        title = "Transactions",
        description = """
            List of transaction summary records included in this statement period."""
    )
    private List<TransactionEntry> transactions;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionEntry {

        @Schema(
            title = "Transaction ID",
            description = """
                Unique identifier (UUID) of the transaction."""
        )
        private String id;

        @Schema(
            title = "Creation timestamp",
            description = """
                UTC timestamp when the transaction was initiated or created."""
        )
        private Instant createdAt;

        @Schema(
            title = "Posted timestamp",
            description = """
                UTC timestamp when the transaction was posted to the ledger, or null if pending."""
        )
        private Instant postedAt;
    }
}
