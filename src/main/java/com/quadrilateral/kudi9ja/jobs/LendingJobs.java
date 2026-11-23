package com.quadrilateral.kudi9ja.jobs;

import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.LateChargeService;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The lending sweeps.
 *
 * <p>The late-charge job adds 0.18% of the principal for each day a loan is
 * past its final due date. It runs hourly and on startup, so the charges begin
 * the day this is deployed; each loan records the last day charged, so no day
 * is ever charged twice.
 */
@Component
@ConditionalOnProperty(prefix = "kudi9ja.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LendingJobs {

    private static final Logger log = LoggerFactory.getLogger(LendingJobs.class);

    private final LoanService loans;
    private final LateChargeService lateCharges;
    private final AuditService audit;
    private final Kudi9jaProperties properties;

    public LendingJobs(
            LoanService loans, LateChargeService lateCharges, AuditService audit, Kudi9jaProperties properties) {
        this.loans = loans;
        this.lateCharges = lateCharges;
        this.audit = audit;
        this.properties = properties;
    }

    /** Marks loans past their due date with a balance still outstanding. */
    @Scheduled(cron = "0 25 * * * *", zone = "Africa/Lagos")
    public void sweepOverdue() {
        try {
            int overdue = loans.sweepOverdue(Instant.now());
            if (overdue > 0) {
                audit.recordSystem(
                        AuditCategory.LOAN,
                        "Overdue sweep",
                        overdue + (overdue == 1 ? " loan" : " loans")
                                + " passed the due date with a balance outstanding.");
            }
        } catch (RuntimeException e) {
            log.error("Overdue sweep failed", e);
        }
    }

    /**
     * Adds the daily late charge to loans past their final due date. Hourly,
     * and once as soon as the server is up, so a deploy starts it at once.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Scheduled(cron = "0 35 * * * *", zone = "Africa/Lagos")
    public void addLateCharges() {
        try {
            int charged = lateCharges.accrue(Instant.now());
            if (charged > 0) {
                audit.recordSystem(
                        AuditCategory.LOAN,
                        "Late charges",
                        "A late charge of 0.18% a day of the amount borrowed was added to "
                                + charged + (charged == 1 ? " loan" : " loans") + " past the final due date.");
            }
        } catch (RuntimeException e) {
            log.error("Late charges failed", e);
        }
    }

    /**
     * Reminds borrowers of an instalment coming up.
     *
     * <p>Runs at 9am Lagos time: inside the 8am–8pm contact window the Privacy
     * Policy commits to, and early enough in the day to be acted on.
     */
    @Scheduled(cron = "0 0 9 * * *", zone = "Africa/Lagos")
    public void sendRepaymentReminders() {
        try {
            int days = properties.jobs().repaymentReminderDays();
            int sent = loans.remindUpcomingRepayments(Instant.now(), days);
            if (sent > 0) {
                audit.recordSystem(
                        AuditCategory.LOAN,
                        "Repayment reminders",
                        sent + " borrower(s) were reminded of an instalment due within "
                                + days + (days == 1 ? " day." : " days."));
            }
        } catch (RuntimeException e) {
            log.error("Repayment reminders failed", e);
        }
    }
}
