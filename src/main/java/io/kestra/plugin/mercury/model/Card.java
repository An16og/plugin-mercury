package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.time.Instant;
import java.util.List;

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
    private CardExpiration expiration;

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
    private List<CardBudget> budgets;

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
    private MerchantInfo merchantLock;

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
}
