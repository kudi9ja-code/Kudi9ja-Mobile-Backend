package com.quadrilateral.kudi9ja.domain.payin;

import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.integration.paystack.PaystackGateway;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies what Paystack said about a payment — credits it, or records that it
 * failed — exactly once.
 *
 * <p>Its own bean so the transaction is real: the HTTP call to Paystack is made
 * before this runs, never while a row is locked. The row is taken under a write
 * lock, and {@code creditedAt} is checked under that lock, so the customer
 * returning from checkout and the webhook arriving at the same moment cannot
 * both credit the wallet.
 */
@Service
public class PaystackSettlement {

    private static final Logger log = LoggerFactory.getLogger(PaystackSettlement.class);

    private final PaystackPaymentRepository payments;
    private final LedgerService ledger;
    private final LoanRepository loans;
    private final LoanService loanService;
    private final NotificationService notifications;
    private final AuditService audit;

    public PaystackSettlement(
            PaystackPaymentRepository payments,
            LedgerService ledger,
            LoanRepository loans,
            LoanService loanService,
            NotificationService notifications,
            AuditService audit) {
        this.payments = payments;
        this.ledger = ledger;
        this.loans = loans;
        this.loanService = loanService;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public PaystackPayment apply(String reference, PaystackGateway.Charge charge) {
        PaystackPayment payment = payments.lockByReference(reference).orElse(null);
        if (payment == null || payment.isCredited()) {
            return payment;
        }

        payment.setChannel(truncate(charge.channel(), 40));
        payment.setCardType(truncate(charge.cardType(), 40));
        payment.setCardLast4(truncate(charge.last4(), 4));
        payment.setCardBank(truncate(charge.bank(), 120));
        payment.setGatewayResponse(truncate(charge.gatewayResponse(), 300));
        if (charge.feesKobo() != null) {
            payment.setFees(BigDecimal.valueOf(charge.feesKobo()).movePointLeft(2));
        }

        if (charge.succeeded()) {
            long expected = payment.getAmount().movePointRight(2).longValueExact();
            if (charge.amountKobo() != expected || !"NGN".equalsIgnoreCase(charge.currency())) {
                // Paystack says it succeeded, but not for what we asked. Nothing
                // is credited on a mismatch; a person looks at it.
                payment.setStatus(PaystackPayment.Status.FAILED);
                payment.setGatewayResponse("Paid " + charge.amountKobo() + " kobo in " + charge.currency()
                        + " against " + expected + " kobo asked. Held for review.");
                payments.save(payment);
                audit.recordSystem(
                        AuditCategory.COMPLIANCE,
                        "Paystack payment did not match",
                        "Payment " + reference + " for " + payment.getCustomerName() + " (" + payment.getCustomerRef()
                                + ") was confirmed by Paystack for " + charge.amountKobo() + " kobo in " + charge.currency()
                                + ", but " + Money.naira(payment.getAmount()) + " was asked. Nothing was credited.");
                log.error("Paystack amount mismatch on {}: {} kobo, expected {}", reference, charge.amountKobo(), expected);
                return payment;
            }

            Instant now = Instant.now();
            payment.setStatus(PaystackPayment.Status.SUCCESSFUL);
            payment.setPaidAt(charge.paidAt() == null ? now : charge.paidAt());
            payment.setCreditedAt(now);
            payments.save(payment);

            boolean forLoan = payment.getPurpose() == DepositPurpose.LOAN_REPAYMENT && payment.getLoanId() != null;
            ledger.credit(payment.getUserId(), payment.getAmount(), TxKind.DEPOSIT,
                    LedgerService.Entry.of(forLoan ? "Paid in for a loan repayment" : "Wallet funded", "Paystack")
                            .withReference(reference)
                            .related(LedgerService.Related.PAY_IN, payment.getId()));

            if (forLoan) {
                Loan loan = loans.findById(payment.getLoanId()).orElse(null);
                if (loan != null && loan.isOpen()) {
                    // Applies what is owed, notifies the borrower; anything over stays in the wallet.
                    loanService.applyRepayment(loan, payment.getAmount(), "Loan repayment through Paystack", true);
                } else {
                    notifications.push(
                            payment.getUserId(),
                            NotifyKind.GENERAL,
                            "Payment added to your wallet",
                            "That loan was already cleared, so your " + Money.naira(payment.getAmount())
                                    + " went into your wallet instead.",
                            payment.getAmount());
                }
            } else {
                notifications.push(
                        payment.getUserId(),
                        NotifyKind.GENERAL,
                        "Payment received",
                        Money.naira(payment.getAmount()) + " has been added to your wallet.",
                        payment.getAmount());
            }
            log.info("Credited Paystack payment {} of {} to {}", reference, payment.getAmount(), payment.getCustomerRef());
            return payment;
        }

        if (charge.finallyFailed()) {
            payment.setStatus("abandoned".equalsIgnoreCase(charge.status())
                    ? PaystackPayment.Status.ABANDONED
                    : PaystackPayment.Status.FAILED);
            payments.save(payment);
        }
        return payment;
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.length() <= max ? value : value.substring(0, max);
    }
}
