package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.Installment;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two things an admin may do to a loan: ask for it, or give up on it.
 *
 * <p>Neither moves money. Repayment is the customer's action, from their wallet
 * or by a bank transfer claimed as a loan repayment, and it lives on
 * {@code LoanService}. What is here is collections conduct and the decision to
 * stop pursuing a debt, both of which are governed by the documents rather than
 * by operational preference:
 *
 * <ul>
 *   <li><b>Contact only between 8am and 8pm.</b> The Privacy Policy commits to
 *       it, so the server refuses a reminder outside the window rather than
 *       relying on an admin to check the clock.
 *   <li><b>Every collections contact is logged.</b> Same document, same reason.
 *   <li><b>Never the borrower's contacts, family or employer.</b> There is no
 *       endpoint that could â€” a reminder goes to the borrower's own
 *       notification feed and nowhere else.
 *   <li><b>The amount owed never grows.</b> No late fee, no penalty interest,
 *       nothing here that adds to a balance. The Lending Agreement says so in
 *       those words.
 * </ul>
 */
@Service
public class AdminLoanService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final LoanRepository loans;
    private final UserRepository users;
    private final AdminAccessService access;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Kudi9jaProperties properties;

    public AdminLoanService(
            LoanRepository loans,
            UserRepository users,
            AdminAccessService access,
            NotificationService notifications,
            AuditService audit,
            Kudi9jaProperties properties) {
        this.loans = loans;
        this.users = users;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.properties = properties;
    }

    /**
     * The lending book, or one status of it.
     *
     * <p>Without a status this is every loan ever written, not only the open
     * ones. The panel shows a book and totals it — disbursed, collected, fees —
     * and a book that quietly omits the loans that were repaid reports a
     * smaller company than the one that exists. Filtering to a closed status
     * used to return nothing at all for the same reason.
     */
    @Transactional(readOnly = true)
    public Page<AdminDtos.AdminLoanRow> queue(LoanStatus status, Pageable pageable) {
        access.requireCanView();
        Instant now = Instant.now();
        List<Loan> rows = status == null
                ? loans.findAllByOrderByRequestedAtDesc()
                : loans.findByStatusOrderByRequestedAtDesc(status);

        List<AdminDtos.AdminLoanRow> mapped = rows.stream().map(loan -> toRow(loan, now)).toList();
        int from = Math.min((int) pageable.getOffset(), mapped.size());
        int to = Math.min(from + pageable.getPageSize(), mapped.size());
        return new org.springframework.data.domain.PageImpl<>(
                mapped.subList(from, to), pageable, mapped.size());
    }

    /**
     * Nudges a borrower about what is due.
     *
     * <p>Refused outside 8amâ€“8pm Lagos time. The window is a promise the
     * Privacy Policy makes to borrowers, and a promise enforced by a server is
     * worth more than one enforced by an admin remembering.
     */
    @Transactional
    public Loan remind(UUID loanId, String note) {
        AdminUser actor = access.requireCanActOnLoans();
        Loan loan = require(loanId);
        User borrower = borrowerOf(loan);

        if (!loan.getStatus().isOpen()) {
            throw new ApiException(
                    ErrorCode.LOAN_CLOSED,
                    "That loan is " + loan.getStatus().label().toLowerCase() + ". There is nothing to chase.");
        }
        requireWithinContactHours();

        Instant now = Instant.now();
        Optional<Installment> due = loan.nextInstallment(now);
        String amount = due.map(i -> Money.naira(i.outstanding())).orElse(Money.naira(loan.outstanding()));

        notifications.push(
                borrower.getId(),
                NotifyKind.REPAYMENT_DUE,
                "A reminder about your loan",
                due.map(i -> "Instalment " + i.number() + " of " + amount + " is due "
                                + describeDue(i, now) + ". ")
                        .orElse("Your loan has " + amount + " outstanding. ")
                        + "You can repay from your wallet in the app."
                        + (note == null || note.isBlank() ? "" : " " + note.trim()),
                due.map(Installment::outstanding).orElse(loan.outstanding()));

        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Repayment reminder sent",
                actor.getName() + " reminded " + borrower.getFullName()
                        + " (" + borrower.getCustomerRef() + ") about "
                        + Money.naira(loan.outstanding()) + " outstanding on a "
                        + Money.naira(loan.getPrincipal()) + " loan."
                        + (note == null || note.isBlank() ? "" : " Note: " + note.trim()),
                loan.getId(),
                "Loan " + loan.getId());

        return loan;
    }

    /**
     * Stops pursuing a debt.
     *
     * <p>The row is closed rather than deleted, and the amount repaid is left
     * exactly as it stands â€” writing a loan off says the company will not chase
     * it, not that it was never lent. That distinction matters for the credit
     * score, which counts loans repaid and not loans forgiven, and for the
     * five-year retention the AML rules require.
     */
    @Transactional
    public Loan writeOff(UUID loanId, String reason) {
        AdminUser actor = access.requireCanActOnLoans();
        Loan loan = require(loanId);
        User borrower = borrowerOf(loan);

        if (!loan.getStatus().isOpen()) {
            throw new ApiException(
                    ErrorCode.LOAN_CLOSED,
                    "That loan is already " + loan.getStatus().label().toLowerCase() + ".");
        }

        java.math.BigDecimal forgiven = loan.outstanding();
        loan.setStatus(LoanStatus.WRITTEN_OFF);
        loan.setWriteOffNote(reason.trim());
        loan.setSettledAt(Instant.now());
        Loan saved = loans.save(loan);

        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Loan written off",
                actor.getName() + " wrote off " + Money.naira(forgiven) + " outstanding on "
                        + borrower.getFullName() + "'s (" + borrower.getCustomerRef() + ") "
                        + Money.naira(saved.getPrincipal()) + " loan. Reason: " + reason.trim(),
                saved.getId(),
                "Loan " + saved.getId());

        // The borrower is told, because a debt that is no longer being chased
        // is something they are entitled to know rather than to guess at.
        notifications.push(
                borrower.getId(),
                NotifyKind.GENERAL,
                "Your loan has been closed",
                "We have closed the loan of " + Money.naira(saved.getPrincipal())
                        + " and will not be pursuing the " + Money.naira(forgiven)
                        + " that was outstanding. Nothing further is owed on it.",
                forgiven);

        return saved;
    }

    private void requireWithinContactHours() {
        LocalTime nowInLagos = LocalTime.now(LAGOS);
        int start = properties.compliance().collectionsStartHour();
        int end = properties.compliance().collectionsEndHour();

        if (nowInLagos.getHour() < start || nowInLagos.getHour() >= end) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN,
                    "Collections contact is only made between " + start + ":00 and " + end + ":00 Lagos time. "
                            + "It is " + nowInLagos.withNano(0) + " there now.");
        }
    }

    private static String describeDue(Installment installment, Instant now) {
        long days = ChronoUnit.DAYS.between(now, installment.dueDate());
        if (days > 1) {
            return "in " + days + " days";
        }
        if (days == 1) {
            return "tomorrow";
        }
        if (days == 0) {
            return "today";
        }
        return Math.abs(days) + (Math.abs(days) == 1 ? " day ago" : " days ago");
    }

    private AdminDtos.AdminLoanRow toRow(Loan loan, Instant now) {
        User borrower = users.findById(loan.getUserId()).orElse(null);
        long overdueDays = loan.getDueDate() == null || !now.isAfter(loan.getDueDate())
                ? 0
                : ChronoUnit.DAYS.between(loan.getDueDate(), now);

        return new AdminDtos.AdminLoanRow(
                loan.getId(),
                loan.getUserId(),
                borrower == null ? "Unknown" : borrower.getFullName(),
                borrower == null ? "" : borrower.getCustomerRef(),
                loan.getPrincipal(),
                loan.outstanding(),
                loan.getAmountRepaid(),
                loan.getProcessingFee(),
                loan.getTenureMonths(),
                loan.getPurpose(),
                loan.getStatus().name(),
                loan.getStatus().label(),
                loan.getDisbursedAt(),
                loan.getDueDate(),
                overdueDays);
    }

    private Loan require(UUID loanId) {
        return loans.findById(loanId).orElseThrow(() -> ApiException.notFound("That loan"));
    }

    private User borrowerOf(Loan loan) {
        return users.findById(loan.getUserId())
                .orElseThrow(() -> ApiException.notFound("The borrower on that loan"));
    }

    private AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
