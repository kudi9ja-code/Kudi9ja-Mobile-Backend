package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.payin.DepositPurpose;
import com.quadrilateral.kudi9ja.domain.payin.PaystackPayment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Paying in through Paystack checkout. */
public final class PaystackDtos {

    private PaystackDtos() {
    }

    public record StartPaystackPayment(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "1", message = "Enter an amount above zero.")
            BigDecimal amount,

            DepositPurpose purpose,

            UUID loanId,

            /** Where to come back to after checkout. Ignored when the server has its own configured. */
            @Size(max = 500, message = "Keep the return address under 500 characters.")
            String callbackUrl) {
    }

    /** Send the customer to {@code authorizationUrl}; come back and confirm {@code reference}. */
    public record PaystackCheckout(String reference, String authorizationUrl, String accessCode, BigDecimal amount) {
    }

    public record PaystackPaymentView(
            UUID id,
            String reference,
            BigDecimal amount,
            DepositPurpose purpose,
            String purposeLabel,
            UUID loanId,
            PaystackPayment.Status status,
            String statusLabel,
            boolean credited,
            String channel,
            String cardType,
            String cardLast4,
            String cardBank,
            Instant createdAt,
            Instant paidAt) {

        public static PaystackPaymentView from(PaystackPayment p) {
            return new PaystackPaymentView(
                    p.getId(),
                    p.getReference(),
                    p.getAmount(),
                    p.getPurpose(),
                    p.getPurpose().label(),
                    p.getLoanId(),
                    p.getStatus(),
                    p.getStatus().label(),
                    p.isCredited(),
                    p.getChannel(),
                    p.getCardType(),
                    p.getCardLast4(),
                    p.getCardBank(),
                    p.getCreatedAt(),
                    p.getPaidAt());
        }
    }

    /** The panel's view: who paid, and what Paystack charged us for it. */
    public record AdminPaystackPayment(
            UUID id,
            UUID userId,
            String customerName,
            String customerRef,
            String reference,
            BigDecimal amount,
            DepositPurpose purpose,
            String purposeLabel,
            UUID loanId,
            PaystackPayment.Status status,
            String statusLabel,
            boolean credited,
            String channel,
            String cardType,
            String cardLast4,
            String cardBank,
            BigDecimal fees,
            String gatewayResponse,
            Instant createdAt,
            Instant paidAt) {

        public static AdminPaystackPayment from(PaystackPayment p) {
            return new AdminPaystackPayment(
                    p.getId(),
                    p.getUserId(),
                    p.getCustomerName(),
                    p.getCustomerRef(),
                    p.getReference(),
                    p.getAmount(),
                    p.getPurpose(),
                    p.getPurpose().label(),
                    p.getLoanId(),
                    p.getStatus(),
                    p.getStatus().label(),
                    p.isCredited(),
                    p.getChannel(),
                    p.getCardType(),
                    p.getCardLast4(),
                    p.getCardBank(),
                    p.getFees(),
                    p.getGatewayResponse(),
                    p.getCreatedAt(),
                    p.getPaidAt());
        }
    }
}
