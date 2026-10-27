package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
            Default postal address of the recipient."""
    )
    private Address defaultAddress;

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
    private BankRouting electronicRoutingInfo;

    @Schema(
        title = "Domestic wire routing information",
        description = """
            Bank routing details for domestic Fedwire payments. Sensitive banking details; never log."""
    )
    private BankRouting domesticWireRoutingInfo;

    @Schema(
        title = "Real-time payment routing information",
        description = """
            Bank routing details for The Clearing House Real-Time Payments (RTP). Sensitive banking details; never log."""
    )
    private BankRouting realTimePaymentRoutingInfo;

    @Schema(
        title = "International wire routing information",
        description = """
            Bank routing details for international SWIFT/wire transfers, including IBAN, SWIFT code, and country-specific data. Sensitive banking details; never log."""
    )
    private InternationalWireRouting internationalWireRoutingInfo;

    @Schema(
        title = "Recipient attachments",
        description = """
            List of uploaded tax documents and verification files associated with this recipient. Contains presigned download URLs valid for 12 hours; never log."""
    )
    private List<Attachment> attachments;

    @Schema(
        title = "Invite ID",
        description = """
            Identifier slug of the recipient onboarding invite, if created via recipient invitation."""
    )
    private String inviteId;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {

        @Schema(
            title = "Address name",
            description = """
                Name associated with this address, if present."""
        )
        private String name;

        @Schema(
            title = "Address line 1",
            description = """
                Primary street address line."""
        )
        private String address1;

        @Schema(
            title = "Address line 2",
            description = """
                Secondary street address line (apartment, suite, unit, etc.)."""
        )
        private String address2;

        @Schema(
            title = "City",
            description = """
                City of the address."""
        )
        private String city;

        @Schema(
            title = "Region",
            description = """
                State, province, or region code."""
        )
        private String region;

        @Schema(
            title = "Postal code",
            description = """
                Postal or ZIP code."""
        )
        private String postalCode;

        @Schema(
            title = "Country code",
            description = """
                Two-letter ISO 3166-1 alpha-2 country code."""
        )
        private String country;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CheckInfo {

        @Schema(
            title = "Check delivery address",
            description = """
                The recipient postal address where physical checks are mailed."""
        )
        private Address address;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankRouting {

        @Schema(
            title = "Account number",
            description = """
                Bank account number for ACH, domestic wire or real-time payment (RTP) transfers."""
        )
        private String accountNumber;

        @Schema(
            title = "Routing number",
            description = """
                Nine-digit ABA routing transit number."""
        )
        private String routingNumber;

        @Schema(
            title = "Electronic account type",
            description = """
                Account classification, set for ACH routing only. Allowed values: businessChecking, businessSavings, personalChecking, personalSavings, businessGeneralLedger, personalGeneralLedger, businessLoan, personalLoan."""
        )
        private String electronicAccountType;

        @Schema(
            title = "Bank name",
            description = """
                Name of the recipient financial institution."""
        )
        private String bankName;

        @Schema(
            title = "Bank address",
            description = """
                Postal address of the recipient financial institution."""
        )
        private Address address;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InternationalWireRouting {

        @Schema(
            title = "International bank account number",
            description = """
                International Bank Account Number (IBAN) for the recipient."""
        )
        private String iban;

        @Schema(
            title = "SWIFT or BIC code",
            description = """
                Society for Worldwide Interbank Financial Telecommunication (SWIFT) or Business Identifier Code (BIC)."""
        )
        private String swiftCode;

        @Schema(
            title = "Country-specific banking details",
            description = """
                Generic key-value map containing country-specific payment routing fields (e.g., IFSC for India, BSB for Australia, transit/bank code for Canada, legal ID for Brazil/Chile/Colombia)."""
        )
        private Map<String, Object> countrySpecific;

        @Schema(
            title = "Correspondent bank information",
            description = """
                Details of the intermediary or correspondent bank, if required for routing."""
        )
        private CorrespondentBank correspondentInfo;

        @Schema(
            title = "Beneficiary bank details",
            description = """
                Bank name, city, state, and country associated with the SWIFT code."""
        )
        private BankDetails bankDetails;

        @Schema(
            title = "Bank contact email address",
            description = """
                Contact email for the recipient bank."""
        )
        private String emailAddress;

        @Schema(
            title = "Bank contact phone number",
            description = """
                Contact telephone number for the recipient bank."""
        )
        private String phoneNumber;

        @Schema(
            title = "Beneficiary bank address",
            description = """
                Postal address of the recipient financial institution."""
        )
        private Address address;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CorrespondentBank {

        @Schema(
            title = "Correspondent bank name",
            description = """
                Name of the intermediary or correspondent bank."""
        )
        private String bankName;

        @Schema(
            title = "Correspondent routing number",
            description = """
                Routing or transit number of the correspondent bank."""
        )
        private String routingNumber;

        @Schema(
            title = "Correspondent SWIFT code",
            description = """
                SWIFT/BIC code of the correspondent bank."""
        )
        private String swiftCode;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankDetails {

        @Schema(
            title = "Bank name",
            description = """
                Name of the financial institution associated with the SWIFT code."""
        )
        private String bankName;

        @Schema(
            title = "Bank city and state",
            description = """
                City and state or province of the financial institution."""
        )
        private String bankCityState;

        @Schema(
            title = "Bank country code",
            description = """
                Two-letter ISO 3166-1 alpha-2 country code of the financial institution."""
        )
        private String bankCountry;
    }

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attachment {

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
}
