package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Repayments received from customers, for the admin panel. */
public final class RepaymentDtos {

    private RepaymentDtos() {
    }

    /** How a repayment reached the loan. */
    public enum Source {
        PAYSTACK("Paystack"),
        WALLET("Wallet"),
        BANK_TRANSFER("Bank transfer"),
        RECORDED("Recorded by hand");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Row(
            /** The ledger row or the hand-recorded entry. */
            UUID id,
            Instant paidAt,
            BigDecimal amount,
            Source source,
            String sourceLabel,
            /** The customer's account; null for a loan entered by hand whose borrower has not signed up. */
            UUID customerId,
            String customerName,
            String customerRef,
            UUID loanId,
            UUID importedLoanId,
            String loanPurpose,
            String reference,
            String description,
            /** Who recorded it, for money taken outside the app. */
            String recordedBy) {
    }

    /** Totals for everything the filters match, not only the page shown. */
    public record Summary(
            BigDecimal total,
            long count,
            BigDecimal today,
            long todayCount,
            BigDecimal thisMonth,
            long thisMonthCount,
            BigDecimal viaPaystack,
            BigDecimal fromWallet,
            BigDecimal bankTransfer,
            BigDecimal recordedByHand) {
    }

    public record History(PageResponse<Row> page, Summary summary) {
    }
}
