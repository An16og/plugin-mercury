package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class Account {

    @Schema(
        title = "Account ID",
        description = "The unique identifier (UUID) for the Mercury account."
    )
    private String id;

    @Schema(
        title = "Account Number",
        description = "The account number for the Mercury account."
    )
    private String accountNumber;

    @Schema(
        title = "Routing Number",
        description = "The routing number for the Mercury account."
    )
    private String routingNumber;

    @Schema(
        title = "Account Name",
        description = "The name of the account."
    )
    private String name;

    @Schema(
        title = "Account Nickname",
        description = "An optional nickname for the account."
    )
    private String nickname;

    @Schema(
        title = "Legal Business Name",
        description = "The legal name of the business associated with the account."
    )
    private String legalBusinessName;

    @Schema(
        title = "Account Status",
        description = "The status of the account. Possible values: active, deleted, pending, archived."
    )
    private String status;

    @Schema(
        title = "Account Type",
        description = "The type of account. Possible values: mercury, external, recipient."
    )
    private String type;

    @Schema(
        title = "Account Kind",
        description = "The kind of account, such as checking or savings."
    )
    private String kind;

    @Schema(
        title = "Current Balance",
        description = "The current ledger balance of the account in USD (multiple of 0.01)."
    )
    private BigDecimal currentBalance;

    @Schema(
        title = "Available Balance",
        description = "The available balance of the account in USD (multiple of 0.01)."
    )
    private BigDecimal availableBalance;

    @Schema(
        title = "Can Receive Transactions",
        description = "Whether the account can receive incoming transactions."
    )
    private Boolean canReceiveTransactions;

    @Schema(
        title = "Can Send Real-Time Payments",
        description = "Whether this account's partner bank supports sending real-time payments (RTP)."
    )
    private Boolean canSendRealTimePayments;

    @Schema(
        title = "Created At",
        description = "The timestamp when the account was created, in ISO 8601 UTC format (yyyy-MM-ddTHH:mm:ssZ)."
    )
    private String createdAt;

    @Schema(
        title = "Dashboard Link",
        description = "The direct URL to view the account in the Mercury dashboard."
    )
    private String dashboardLink;
}
