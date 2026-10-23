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
public class Recipient {

    @Schema(
        title = "Recipient ID",
        description = """
            The unique identifier (UUID) for this payment recipient."""
    )
    private String id;

    @Schema(
        title = "Recipient status",
        description = """
            Lifecycle status of the recipient. Possible values: active, deleted."""
    )
    private String status;

    @Schema(
        title = "Recipient name",
        description = """
            Legal or commercial name of the recipient individual or organization."""
    )
    private String name;

    @Schema(
        title = "Recipient nickname",
        description = """
            Optional user-assigned nickname or alias for the recipient."""
    )
    private String nickname;

    @Schema(
        title = "Recipient emails",
        description = """
            List of notification email addresses associated with the recipient."""
    )
    private List<String> emails;

    @Schema(
        title = "Contact email",
        description = """
            Primary contact email address for the recipient."""
    )
    private String contactEmail;

    @Schema(
        title = "Date last paid",
        description = """
            Timestamp when the most recent payment was dispatched to this recipient."""
    )
    private Instant dateLastPaid;

    @Schema(
        title = "Default payment method",
        description = """
            Default method used for payments to this recipient. Possible values: ach, check, domesticWire, internationalWire, realTimePayment."""
    )
    private String defaultPaymentMethod;

    @Schema(
        title = "Is business recipient",
        description = """
            Whether this recipient is registered as a business entity rather than an individual."""
    )
    private Boolean isBusiness;

    @Schema(
        title = "Recipient address",
        description = """
            Primary postal address of the recipient including recipient name."""
    )
    private Address address;

    @Schema(
        title = "Default address",
        description = """
            Default postal address of the recipient without recipient name."""
    )
    private AddressWithoutName defaultAddress;

    @Schema(
        title = "Check delivery information",
        description = """
            Postal address where physical checks are delivered."""
    )
    private CheckInfo checkInfo;

    @Schema(
        title = "Electronic routing information",
        description = """
            Bank routing details for ACH payments, including account and routing numbers. Sensitive banking details; never log."""
    )
    private ElectronicRoutingInfo electronicRoutingInfo;

    @Schema(
        title = "Domestic wire routing information",
        description = """
            Bank routing details for domestic Fedwire payments. Sensitive banking details; never log."""
    )
    private DomesticWireRoutingInfo domesticWireRoutingInfo;

    @Schema(
        title = "Real-time payment routing information",
        description = """
            Bank routing details for The Clearing House Real-Time Payments (RTP). Sensitive banking details; never log."""
    )
    private RealTimePaymentRoutingInfo realTimePaymentRoutingInfo;

    @Schema(
        title = "International wire routing information",
        description = """
            Bank routing details for international SWIFT/wire transfers, including IBAN, SWIFT code, and country-specific data. Sensitive banking details; never log."""
    )
    private InternationalWireRoutingInfo internationalWireRoutingInfo;

    @Schema(
        title = "Recipient attachments",
        description = """
            List of uploaded tax documents and verification files associated with this recipient. Contains presigned download URLs valid for 12 hours; never log."""
    )
    private List<RecipientAttachment> attachments;

    @Schema(
        title = "Invite ID",
        description = """
            Identifier slug of the recipient onboarding invite, if created via recipient invitation."""
    )
    private String inviteId;
}
