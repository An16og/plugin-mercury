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
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class Transaction {

    @Schema(
        title = "Transaction ID",
        description = """
            The unique identifier (UUID) for this transaction."""
    )
    private String id;

    @Schema(
        title = "Account ID",
        description = """
            The external-facing account identifier for the Mercury account that owns this transaction."""
    )
    private String accountId;

    @Schema(
        title = "Transaction amount",
        description = """
            The amount of the transaction in USD. Can be negative for outgoing payments or fees, and positive for incoming deposits."""
    )
    private BigDecimal amount;

    @Schema(
        title = "Bank description",
        description = """
            Bank description text as reported on bank statements."""
    )
    private String bankDescription;

    @Schema(
        title = "Check number",
        description = """
            Check number present for check deposits and mailed checks; null otherwise."""
    )
    private String checkNumber;

    @Schema(
        title = "Card ID",
        description = """
            ID of the card behind this transaction, present on card payments and refunds (debit or credit); null otherwise."""
    )
    private String cardId;

    @Schema(
        title = "Category data",
        description = """
            Expense category details for transaction classification."""
    )
    private Map<String, Object> categoryData;

    @Schema(
        title = "Compliant with receipt policy",
        description = """
            Whether the transaction complies with the company's receipt policy."""
    )
    private Boolean compliantWithReceiptPolicy;

    @Schema(
        title = "Counterparty ID",
        description = """
            The unique identifier for the counterparty."""
    )
    private String counterpartyId;

    @Schema(
        title = "Counterparty name",
        description = """
            Name of the counterparty involved in the transaction."""
    )
    private String counterpartyName;

    @Schema(
        title = "Counterparty nickname",
        description = """
            Nickname assigned to the counterparty, if any."""
    )
    private String counterpartyNickname;

    @Schema(
        title = "Created at",
        description = """
            Timestamp when the transaction record was created, in ISO 8601 UTC format."""
    )
    private Instant createdAt;

    @Schema(
        title = "Credit account period ID",
        description = """
            ID of the credit account statement period, if applicable."""
    )
    private String creditAccountPeriodId;

    @Schema(
        title = "Currency exchange info",
        description = """
            Information on currency conversions for international transactions."""
    )
    private Map<String, Object> currencyExchangeInfo;

    @Schema(
        title = "Dashboard link",
        description = """
            The direct URL to view the transaction in the Mercury dashboard."""
    )
    private String dashboardLink;

    @Schema(
        title = "Transaction details",
        description = """
            Transaction method details such as card info, electronic routing, or wire routing info."""
    )
    private Map<String, Object> details;

    @Schema(
        title = "Estimated delivery date",
        description = """
            The estimated delivery timestamp for the transaction, in ISO 8601 UTC format."""
    )
    private Instant estimatedDeliveryDate;

    @Schema(
        title = "External memo",
        description = """
            External memo attached to the payment."""
    )
    private String externalMemo;

    @Schema(
        title = "Failed at",
        description = """
            Timestamp when the transaction failed, if applicable, in ISO 8601 UTC format."""
    )
    private Instant failedAt;

    @Schema(
        title = "Fee ID",
        description = """
            ID of the associated fee transaction, if any."""
    )
    private String feeId;

    @Schema(
        title = "General ledger code name",
        description = """
            Deprecated GL code name. Preserved for backwards compatibility."""
    )
    private String generalLedgerCodeName;

    @Schema(
        title = "General ledger allocations",
        description = """
            GL code allocations assigned via accounting software integrations."""
    )
    private List<Map<String, Object>> glAllocations;

    @Schema(
        title = "Has generated receipt",
        description = """
            Whether a receipt was automatically generated."""
    )
    private Boolean hasGeneratedReceipt;

    @Schema(
        title = "Transaction kind",
        description = """
            Kind of transaction, such as outgoingPayment, externalTransfer, internalTransfer, or creditCardTransaction."""
    )
    private String kind;

    @Schema(
        title = "Merchant details",
        description = """
            Merchant information for card transactions, including MCC, merchant ID, and local currency data."""
    )
    private Map<String, Object> merchant;

    @Schema(
        title = "Mercury category",
        description = """
            High-level merchant category assigned by Mercury."""
    )
    private String mercuryCategory;

    @Schema(
        title = "User note",
        description = """
            Internal user note attached to the transaction."""
    )
    private String note;

    @Schema(
        title = "Posted at",
        description = """
            Timestamp when the transaction settled and posted to the ledger, in ISO 8601 UTC format."""
    )
    private Instant postedAt;

    @Schema(
        title = "Reason for failure",
        description = """
            Description of why the transaction failed, if applicable."""
    )
    private String reasonForFailure;

    @Schema(
        title = "Related transactions",
        description = """
            Related transactions such as refunds, fee reversals, or chargebacks."""
    )
    private List<Map<String, Object>> relatedTransactions;

    @Schema(
        title = "Request ID",
        description = """
            ID returned from /account/:id/request-send-money if created via a payment request."""
    )
    private String requestId;

    @Schema(
        title = "Transaction status",
        description = """
            Current status of the transaction. Possible values: pending, sent, cancelled, failed, reversed, blocked."""
    )
    private String status;

    @Schema(
        title = "Tracking number",
        description = """
            Tracking number for wire, RTP, or ACH payments; null otherwise."""
    )
    private String trackingNumber;

    @Schema(
        title = "Attachments",
        description = """
            File attachments associated with the transaction, such as receipts and invoices."""
    )
    private List<Map<String, Object>> attachments;
}
