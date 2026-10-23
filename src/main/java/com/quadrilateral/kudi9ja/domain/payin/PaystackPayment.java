package com.quadrilateral.kudi9ja.domain.payin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One payment taken through Paystack checkout.
 *
 * <p>Opened when the customer starts paying, and credited at most once — when
 * Paystack confirms it, by the customer returning from checkout or by the
 * webhook, whichever arrives first. {@code creditedAt} is what makes the second
 * arrival a no-op.
 */
@Entity
@Table(
        name = "paystack_payment",
        indexes = {
                @Index(name = "ix_paystack_payment_user", columnList = "user_id, created_at"),
                @Index(name = "ix_paystack_payment_status", columnList = "status, created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class PaystackPayment {

    public enum Status {
        INITIALIZED, SUCCESSFUL, FAILED, ABANDONED;

        public String label() {
            return switch (this) {
                case INITIALIZED -> "Waiting for payment";
                case SUCCESSFUL -> "Paid";
                case FAILED -> "Failed";
                case ABANDONED -> "Abandoned";
            };
        }
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "customer_name", nullable = false, length = 160, updatable = false)
    private String customerName;

    @Column(name = "customer_ref", nullable = false, length = 16, updatable = false)
    private String customerRef;

    @Column(name = "reference", nullable = false, length = 64, unique = true, updatable = false)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private DepositPurpose purpose;

    @Column(name = "loan_id", updatable = false)
    private UUID loanId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.INITIALIZED;

    @Column(name = "authorization_url", length = 500)
    private String authorizationUrl;

    @Column(name = "channel", length = 40)
    private String channel;

    @Column(name = "card_type", length = 40)
    private String cardType;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "card_bank", length = 120)
    private String cardBank;

    @Column(name = "fees", precision = 19, scale = 2)
    private BigDecimal fees;

    @Column(name = "gateway_response", length = 300)
    private String gatewayResponse;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    public boolean isCredited() {
        return creditedAt != null;
    }
}
