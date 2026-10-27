package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
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
public class Card {

    @Schema(
        title = "Card ID",
        description = """
            The unique identifier (UUID) for this card."""
    )
    private String id;

    @Schema(
        title = "Account ID",
        description = """
            The unique identifier (UUID) of the Mercury bank account associated with this card."""
    )
    private String accountId;

    @Schema(
        title = "Created at timestamp",
        description = """
            Timestamp when the card was issued."""
    )
    private Instant createdAt;

    @Schema(
        title = "Updated at timestamp",
        description = """
            Timestamp of the most recent modification to the card or its settings."""
    )
    private Instant updatedAt;

    @Schema(
        title = "Last four digits",
        description = """
            Last four digits of the primary account number (PAN). Note that full PAN and CVC are never returned by the list cards endpoint."""
    )
    private String lastFour;

    @Schema(
        title = "Name on card",
        description = """
            Cardholder name embossed or printed on the card."""
    )
    private String nameOnCard;

    @Schema(
        title = "User ID",
        description = """
            Identifier of the Mercury user who owns and is assigned to the card."""
    )
    private String userId;

    @Schema(
        title = "Card status",
        description = """
            Current lifecycle state of the card. Possible values: active, frozen, cancelled, inactive, expired, suspended."""
    )
    private String status;

    @Schema(
        title = "Card type",
        description = """
            Physical form factor of the card. Possible values: virtual, physical."""
    )
    private String type;

    @Schema(
        title = "Card kind",
        description = """
            Funding kind of the card. Possible values: debit, credit."""
    )
    private String kind;

    @Schema(
        title = "Card expiration",
        description = """
            Month and year the card expires."""
    )
    private Expiration expiration;

    @Schema(
        title = "Spend limit type",
        description = """
            Whether card-level limits or budgets govern purchase spending. Possible values: cardLimit, budgets."""
    )
    private String spendLimitType;

    @Schema(
        title = "Budgets",
        description = """
            Active budget limits linked to this card and cardholder, in cents. Empty under cardLimit or when no budget is linked."""
    )
    private List<Budget> budgets;

    @Schema(
        title = "Category locks",
        description = """
            List of Mercury spend category names locked on this card. Empty when no category restrictions apply."""
    )
    private List<String> categoryLocks;

    @Schema(
        title = "Is agent card",
        description = """
            Whether the card is managed by the agentic spend-management agent."""
    )
    private Boolean isAgentCard;

    @Schema(
        title = "Card nickname",
        description = """
            Optional user-assigned label or nickname for the card."""
    )
    private String nickname;

    @Schema(
        title = "Merchant lock",
        description = """
            Merchant lock applied to this card, restricting purchases to a single vendor. Null when not locked to a merchant."""
    )
    private MerchantLock merchantLock;

    @Schema(
        title = "Physical card status",
        description = """
            Activation state of a physical card. Null for virtual cards. Possible values: inactive, active, locked."""
    )
    private String physicalCardStatus;

    @Schema(
        title = "Spend limit",
        description = """
            Card-level spending controls in cents. Null when limits are unavailable or governed by budgets."""
    )
    private SpendLimit spendLimit;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Expiration {

        @Schema(
            title = "Expiration month",
            description = """
                Two-digit calendar month when the card expires (1-12)."""
        )
        private Integer month;

        @Schema(
            title = "Expiration year",
            description = """
                Four-digit calendar year when the card expires (2000-2999)."""
        )
        private Integer year;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Budget {

        @Schema(
            title = "Budget ID",
            description = """
                Unique identifier (UUID) for this budget."""
        )
        private String id;

        @Schema(
            title = "Budget name",
            description = """
                Display name of the budget."""
        )
        private String name;

        @Schema(
            title = "Budget amount in cents",
            description = """
                The cardholder's spend limit for the current budget cycle, in cents."""
        )
        private Long amountCents;

        @Schema(
            title = "Remaining budget amount in cents",
            description = """
                Current-cycle limit minus recorded spend, clamped at zero, in cents."""
        )
        private Long remainingAmountCents;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MerchantLock {

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

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpendLimit {

        @Schema(
            title = "Spend limit in cents",
            description = """
                Maximum total spend allowed per rolling interval, in cents."""
        )
        private Long amountCents;

        @Schema(
            title = "ATM limit in cents",
            description = """
                Maximum ATM cash withdrawal allowed per rolling interval, in cents. Null for virtual cards and credit cards."""
        )
        private Long atmAmountCents;

        @Schema(
            title = "Spend limit interval",
            description = """
                Rolling time window the spend limit applies to. Possible values: daily, weekly, monthly, yearly."""
        )
        private String interval;
    }
}
