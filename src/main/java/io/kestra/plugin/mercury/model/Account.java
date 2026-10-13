package io.kestra.plugin.mercury.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class Account {
    private String id;
    private String accountNumber;
    private String routingNumber;
    private String name;
    private String nickname;
    private String legalBusinessName;
    private String status;
    private String type;
    private String kind;
    private BigDecimal currentBalance;
    private BigDecimal availableBalance;
    private Boolean canReceiveTransactions;
    private Boolean canSendRealTimePayments;
    private String createdAt;
    private String dashboardLink;
}
