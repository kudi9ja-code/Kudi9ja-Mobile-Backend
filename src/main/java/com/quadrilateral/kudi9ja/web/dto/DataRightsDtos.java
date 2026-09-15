package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Answering a data subject: what we hold about them, and closing the account.
 *
 * <p>These are obligations under the NDPA 2023 rather than features. The
 * export answers the rights of access and portability; the closure answers the
 * right to erasure, as far as the AML rules allow it to be answered.
 */
public final class DataRightsDtos {

    private DataRightsDtos() {
    }

    // ─────────────────────────────────────────────────────────── the export

    /**
     * Everything Kudi9ja holds about one customer.
     *
     * <p>Structured rather than prose, and complete rather than summarised:
     * portability means the data has to be usable somewhere else, and a PDF of
     * highlights is not.
     *
     * <p>Two deliberate omissions, both of which the document says out loud
     * rather than leaving the reader to notice:
     *
     * <ul>
     *   <li><b>No hashes.</b> Not the password, passcode, PIN or security
     *       answer. A hash is not information about the customer — it is a
     *       credential — and handing one out turns a stolen session into an
     *       offline cracking job.
     *   <li><b>The BVN and NIN are masked</b> to their last four digits, as
     *       everywhere else in this API. The customer supplied them and already
     *       has them; returning them in full would make every live session a
     *       way to harvest an identity number, and would tell the customer
     *       nothing they did not already know. That we hold them, and that they
     *       were verified, <i>is</i> information, and it is here.
     * </ul>
     */
    public record DataExport(
            About about,
            Profile profile,
            WalletExport wallet,
            List<Transaction> transactions,
            List<SavingsPlan> savingsPlans,
            List<Loan> loans,
            List<PayIn> payIns,
            List<Withdrawal> withdrawals,
            List<Circle> thriftCircles,
            List<Notice> notifications,
            List<Agreement> legalAcceptances,
            List<SignIn> sessions,
            List<RecordAccess> recordAccesses,
            AdminAccess adminAccess) {
    }

    /**
     * What this document is, who produced it, and what the customer can do
     * next.
     *
     * <p>An export that arrives as a bare dump of rows leaves the person
     * holding it none the wiser about their rights. The NDPA gives them seven
     * of them and names a regulator; this section is where they are told.
     */
    public record About(
            String document,
            Instant generatedAt,
            UUID subjectId,
            String subjectName,
            String controllerLegalName,
            String controllerRcNumber,
            String dataProtectionContact,
            int responseDays,
            String retentionNote,
            List<String> yourRights,
            String regulator) {
    }

    public record Profile(
            String customerRef,
            String fullName,
            String email,
            String phone,
            LocalDate dateOfBirth,
            String gender,
            String address,
            String state,
            String bvnLast4,
            String ninLast4,
            boolean bvnVerified,
            boolean ninVerified,
            String payoutBank,
            String payoutAccountNumber,
            String payoutAccountName,
            boolean payoutVerified,
            String kycTier,
            boolean emailVerified,
            boolean phoneVerified,
            String securityQuestion,
            String themeMode,
            AccountStatus accountStatus,
            Instant createdAt,
            Instant lastActiveAt,
            Instant closedAt,
            Instant retainUntil) {
    }

    public record WalletExport(
            BigDecimal balance,
            long transactionCount,
            Instant openedAt,
            String note) {
    }

    public record Transaction(
            UUID id,
            String kind,
            BigDecimal amount,
            String description,
            String counterparty,
            String reference,
            String status,
            BigDecimal balanceAfter,
            Instant date) {
    }

    /**
     * @param annualRate the rate this plan was opened on, which is the rate it
     *                   keeps. A later change to the rate card never rewrites a
     *                   running plan, and the export shows the plan's own terms
     *                   rather than today's.
     */
    public record SavingsPlan(
            UUID id,
            String title,
            String type,
            BigDecimal principal,
            int lockDays,
            BigDecimal annualRate,
            BigDecimal interestPaid,
            BigDecimal targetAmount,
            BigDecimal bonusRate,
            boolean bonusPaid,
            int contributions,
            int missedContributions,
            String status,
            Instant startDate,
            Instant maturityDate,
            Instant closedAt,
            String closureNote) {
    }

    /**
     * @param decisionReasons why a loan was declined or reduced. Here because
     *                        the Privacy Policy gives the customer the right
     *                        to demand that a person looks again at a
     *                        decision — a right they cannot use without
     *                        knowing what the decision rested on.
     */
    public record Loan(
            UUID id,
            BigDecimal principal,
            int tenureMonths,
            BigDecimal flatRate,
            BigDecimal totalInterest,
            BigDecimal totalRepayable,
            BigDecimal processingFee,
            BigDecimal amountRepaid,
            BigDecimal rebateGranted,
            BigDecimal outstanding,
            String purpose,
            String status,
            Instant requestedAt,
            Instant disbursedAt,
            Instant dueDate,
            Instant settledAt,
            String decisionReasons,
            String writeOffNote) {
    }

    public record PayIn(
            UUID id,
            BigDecimal amount,
            String reference,
            String purpose,
            String senderName,
            String senderBank,
            String status,
            Instant claimedAt,
            Instant reviewedAt,
            String note) {
    }

    public record Withdrawal(
            UUID id,
            BigDecimal amount,
            String bank,
            String destinationAccount,
            String reference,
            String status,
            Instant requestedAt,
            Instant reviewedAt,
            String note) {
    }

    /**
     * A circle this customer is in.
     *
     * <p>Only their own contributions are listed. The other members are real
     * people with their own rights, and one member's subject access request is
     * not a route to everybody else's payment history — so they appear as a
     * count rather than as a list.
     */
    public record Circle(
            UUID id,
            String name,
            BigDecimal contribution,
            String frequency,
            int memberCount,
            int currentRound,
            Instant startDate,
            Instant joinedAt,
            Instant leftAt,
            List<CircleContribution> yourContributions) {
    }

    public record CircleContribution(int round, BigDecimal amount, Instant paidAt) {
    }

    public record Notice(
            String kind,
            String title,
            String body,
            BigDecimal amount,
            Instant date,
            boolean read,
            Instant clearedAt) {
    }

    /**
     * Which version of each document was accepted, when, and on what device.
     *
     * <p>The Terms rely on this as evidence under the Evidence Act 2011, so the
     * customer is entitled to see exactly what is being relied on.
     */
    public record Agreement(
            String document,
            String version,
            Instant acceptedAt,
            String device,
            String ipAddress) {
    }

    public record SignIn(
            String device,
            String ipAddress,
            Instant startedAt,
            Instant lastSeenAt,
            Instant endedAt,
            String endedReason) {
    }

    /**
     * That somebody at Kudi9ja opened this record, and when.
     *
     * <p>The Privacy Policy commits to being able to say a record was accessed,
     * which is why this section exists at all. It deliberately does <b>not</b>
     * name the member of staff: that is another person's data, and in a
     * disputed case it is also a safety question. A customer who needs the name
     * can ask, and it is in the audit log.
     */
    public record RecordAccess(String action, String category, Instant date) {
    }

    public record AdminAccess(boolean hasPanelAccess, String role, Instant grantedAt) {
    }

    // ────────────────────────────────────────────────────────── the closure

    /**
     * Closing an account.
     *
     * <p>Two gates, and they prove different things. The <b>code</b> proves the
     * person holds the email address; the <b>password</b> proves they are the
     * account holder rather than somebody who picked up an unlocked phone.
     * Closing an account is not reversible by the customer, so a live session
     * alone is not enough.
     *
     * <p>The reason is optional and is recorded. Nobody has to explain
     * themselves to leave.
     */
    public record CloseAccountRequest(
            @NotBlank(message = "Enter the code we sent you.")
            String code,

            @NotBlank(message = "Your password is needed to close your account.")
            String password,

            @Size(max = 500, message = "Keep the reason under 500 characters.")
            String reason) {
    }

    /**
     * What stands in the way of closing.
     *
     * <p>Returned before the customer commits to anything, so the answer to
     * "why can I not close my account" is a list they can act on rather than a
     * refusal at the end.
     */
    public record ClosureEligibility(
            boolean canClose,
            List<String> blockers,
            BigDecimal walletBalance,
            BigDecimal lockedInSavings,
            BigDecimal outstandingOnLoans,
            String retentionNote) {
    }

    /**
     * @param retainUntil when the retained record may finally be erased. Told
     *                    to the customer plainly: "deleted" that quietly means
     *                    "kept indefinitely" is the version of this that erodes
     *                    trust.
     */
    public record ClosureResponse(
            AccountStatus status,
            Instant closedAt,
            Instant retainUntil,
            int sessionsEnded,
            List<String> redacted,
            List<String> retained,
            String message) {
    }
}
