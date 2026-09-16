package com.quadrilateral.kudi9ja.domain.compliance;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditEntry;
import com.quadrilateral.kudi9ja.domain.audit.AuditRepository;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.kyc.OtpPurpose;
import com.quadrilateral.kudi9ja.domain.kyc.OtpService;
import com.quadrilateral.kudi9ja.domain.legal.LegalAcceptance;
import com.quadrilateral.kudi9ja.domain.legal.LegalAcceptanceRepository;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.notification.DeviceTokenRepository;
import com.quadrilateral.kudi9ja.domain.notification.NotificationRepository;
import com.quadrilateral.kudi9ja.domain.payin.DepositStatus;
import com.quadrilateral.kudi9ja.domain.payin.PayInClaim;
import com.quadrilateral.kudi9ja.domain.payin.PayInClaimRepository;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalRequest;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalRequestRepository;
import com.quadrilateral.kudi9ja.domain.review.AppReviewRepository;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalStatus;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlan;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlanRepository;
import com.quadrilateral.kudi9ja.domain.savings.SavingsStatus;
import com.quadrilateral.kudi9ja.domain.thrift.ThriftCircle;
import com.quadrilateral.kudi9ja.domain.thrift.ThriftCircleRepository;
import com.quadrilateral.kudi9ja.domain.thrift.ThriftContribution;
import com.quadrilateral.kudi9ja.domain.thrift.ThriftContributionRepository;
import com.quadrilateral.kudi9ja.domain.thrift.ThriftMember;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.AuthService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransaction;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import com.quadrilateral.kudi9ja.security.auth.UserSession;
import com.quadrilateral.kudi9ja.security.auth.UserSessionRepository;
import com.quadrilateral.kudi9ja.security.crypto.SecretHasher;
import com.quadrilateral.kudi9ja.web.dto.DataRightsDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rights a customer has over their own data, and what happens when they
 * leave.
 *
 * <p>These come from the NDPA 2023 and from the Privacy Policy shipped in the
 * app, which is binding on the company. Two of them are built here.
 *
 * <p><b>Access and portability.</b> {@link #export} answers "what do you hold
 * about me" completely and in a form another system could read. It is produced
 * on the spot rather than within the thirty days the Act allows: the data is
 * all in one database and there is nothing to gather, so a deadline would only
 * be a delay.
 *
 * <p><b>Erasure.</b> {@link #close} is the hard one, because it collides with
 * the AML rules. Identity and transaction records must be kept for at least
 * five years after the relationship ends, and a deletion request cannot
 * override that. So closing an account is a <b>redaction, not a deletion</b>:
 * the credentials are destroyed immediately and the record is kept until its
 * retention runs out, at which point {@link #erase} finally removes it. The
 * customer is told both halves of that in plain words — a "delete" that
 * silently means "keep indefinitely" is the version of this that destroys
 * trust.
 *
 * <p>Closing is refused while anything is unfinished. Money in the wallet, a
 * running plan, an open loan, a payment under review, a live thrift circle:
 * every one of those would either strand the customer's money or somebody
 * else's, and none of them is improved by being abandoned.
 */
@Service
public class DataRightsService {

    private static final Logger log = LoggerFactory.getLogger(DataRightsService.class);

    /**
     * The rights the NDPA gives a data subject, listed in the export itself.
     *
     * <p>An export that arrives as a bare dump of rows leaves the person
     * holding it none the wiser about what they may now ask for.
     */
    private static final List<String> DATA_SUBJECT_RIGHTS = List.of(
            "Access — ask what we hold about you. This document is that answer.",
            "Rectification — have anything wrong put right.",
            "Erasure — ask us to delete your data, subject to the records we are "
                    + "required by law to keep.",
            "Restriction — ask us to stop using your data while a dispute is settled.",
            "Objection — object to a particular use of your data.",
            "Portability — take this file to another provider.",
            "Withdraw consent — where we relied on your consent, take it back.",
            "Human review — ask a person to look again at any automated decision "
                    + "that went against you, including a declined or reduced loan.");

    private final UserRepository users;
    private final AdminUserRepository admins;
    private final LedgerService ledger;
    private final WalletTransactionRepository transactions;
    private final SavingsPlanRepository plans;
    private final LoanRepository loans;
    private final PayInClaimRepository claims;
    private final WithdrawalRequestRepository withdrawals;
    private final ThriftCircleRepository circles;
    private final ThriftContributionRepository contributions;
    private final NotificationRepository notifications;
    private final DeviceTokenRepository devices;
    private final AppReviewRepository reviews;
    private final LegalAcceptanceRepository acceptances;
    private final UserSessionRepository sessions;
    private final AuditRepository auditEntries;
    private final AuthService auth;
    private final OtpService otps;
    private final SecretHasher hasher;
    private final AuditService audit;
    private final Kudi9jaProperties properties;

    public DataRightsService(
            UserRepository users,
            AdminUserRepository admins,
            LedgerService ledger,
            WalletTransactionRepository transactions,
            SavingsPlanRepository plans,
            LoanRepository loans,
            PayInClaimRepository claims,
            WithdrawalRequestRepository withdrawals,
            ThriftCircleRepository circles,
            ThriftContributionRepository contributions,
            NotificationRepository notifications,
            DeviceTokenRepository devices,
            AppReviewRepository reviews,
            LegalAcceptanceRepository acceptances,
            UserSessionRepository sessions,
            AuditRepository auditEntries,
            AuthService auth,
            OtpService otps,
            SecretHasher hasher,
            AuditService audit,
            Kudi9jaProperties properties) {
        this.users = users;
        this.admins = admins;
        this.ledger = ledger;
        this.transactions = transactions;
        this.plans = plans;
        this.loans = loans;
        this.claims = claims;
        this.withdrawals = withdrawals;
        this.circles = circles;
        this.contributions = contributions;
        this.notifications = notifications;
        this.devices = devices;
        this.reviews = reviews;
        this.acceptances = acceptances;
        this.sessions = sessions;
        this.auditEntries = auditEntries;
        this.auth = auth;
        this.otps = otps;
        this.hasher = hasher;
        this.audit = audit;
        this.properties = properties;
    }

    // ── Access and portability ─────────────────────────────────────────────

    /**
     * Everything held about one customer.
     *
     * <p>No extra credential is asked for. The caller already holds a live
     * session, and a live session can already read the profile, the ledger, the
     * plans and the loans one endpoint at a time — this gathers the same data
     * into one document, so a second gate here would be friction rather than
     * security. What it does instead is <b>write the request to the audit
     * log</b>, so an export that was not the customer's doing leaves a trace.
     */
    @Transactional
    public DataRightsDtos.DataExport export(UUID userId) {
        User user = require(userId);
        Instant now = Instant.now();

        audit.record(
                new AuditService.Actor(user.getId(), user.getFullName(), user.getEmail()),
                AuditCategory.DATA_ACCESS,
                "Data export produced",
                user.getFullName() + " (" + user.getCustomerRef() + ") requested a copy of "
                        + "everything held about them, and it was produced.",
                user.getId(),
                user.getCustomerRef());

        log.info("Produced a data export for {}", Masks.email(user.getEmail()));

        return new DataRightsDtos.DataExport(
                about(user, now),
                profileOf(user),
                walletOf(user),
                transactionsOf(user),
                plansOf(user),
                loansOf(user),
                payInsOf(user),
                withdrawalsOf(user),
                circlesOf(user),
                noticesOf(user),
                reviewOf(user),
                agreementsOf(user),
                signInsOf(user),
                recordAccessesOf(user),
                adminAccessOf(user));
    }

    private DataRightsDtos.About about(User user, Instant now) {
        Kudi9jaProperties.Company company = properties.company();
        int years = properties.compliance().recordRetentionYears();

        return new DataRightsDtos.About(
                "Your Kudi9ja data — everything we hold about you",
                now,
                user.getId(),
                user.getFullName(),
                company.legalName(),
                company.rcNumber(),
                company.privacyEmail(),
                properties.compliance().dataRequestResponseDays(),
                "We keep identity and transaction records for at least " + years
                        + " years after your relationship with us ends. That is required by the "
                        + "anti-money-laundering rules and a deletion request cannot override it. "
                        + "Everything else is removed when you close your account.",
                DATA_SUBJECT_RIGHTS,
                "Nigeria Data Protection Commission (NDPC)");
    }

    private DataRightsDtos.Profile profileOf(User user) {
        return new DataRightsDtos.Profile(
                user.getCustomerRef(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getDateOfBirth(),
                user.getGender(),
                user.getAddress(),
                user.getState(),
                // Masked here as everywhere else. See DataExport for why.
                Masks.lastFour(user.getBvn()),
                Masks.lastFour(user.getNin()),
                user.getBvnVerifiedAt() != null,
                user.getNinVerifiedAt() != null,
                user.getPayoutBank(),
                user.getPayoutAccountNumber(),
                user.getPayoutAccountName(),
                user.getPayoutVerifiedAt() != null,
                user.getKycTier().label(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                // The question, never the answer. The answer is a credential.
                user.getSecurityQuestion(),
                user.getThemeMode().name(),
                user.getAccountStatus(),
                user.getCreatedAt(),
                user.getLastActiveAt(),
                user.getClosedAt(),
                user.getRetainUntil());
    }

    private DataRightsDtos.WalletExport walletOf(User user) {
        return new DataRightsDtos.WalletExport(
                ledger.balanceOf(user.getId()),
                transactions.countByUserId(user.getId()),
                user.getCreatedAt(),
                "Your Kudi9ja wallet is not a bank account and is not insured by the Nigeria "
                        + "Deposit Insurance Corporation. The balance below is the running total "
                        + "of the transactions that follow it.");
    }

    private List<DataRightsDtos.Transaction> transactionsOf(User user) {
        return transactions.findByUserIdOrderBySequenceAsc(user.getId()).stream()
                .map(DataRightsService::toTransaction)
                .toList();
    }

    private static DataRightsDtos.Transaction toTransaction(WalletTransaction tx) {
        return new DataRightsDtos.Transaction(
                tx.getId(),
                tx.getKind().name(),
                tx.getAmount(),
                tx.getDescription(),
                tx.getCounterparty(),
                tx.getReference(),
                tx.getStatus().name(),
                tx.getBalanceAfter(),
                tx.getOccurredAt());
    }

    private List<DataRightsDtos.SavingsPlan> plansOf(User user) {
        return plans.findByUserIdOrderByStartDateDesc(user.getId()).stream()
                .map(plan -> new DataRightsDtos.SavingsPlan(
                        plan.getId(),
                        plan.getTitle(),
                        plan.getType().name(),
                        plan.getPrincipal(),
                        plan.getLockDays(),
                        plan.getAnnualRate(),
                        plan.getInterestPaid(),
                        plan.getTargetAmount(),
                        plan.getBonusRate(),
                        plan.isBonusPaid(),
                        plan.getContributions(),
                        plan.getMissedContributions(),
                        plan.getStatus().name(),
                        plan.getStartDate(),
                        plan.getMaturityDate(),
                        plan.getClosedAt(),
                        plan.getClosureNote()))
                .toList();
    }

    private List<DataRightsDtos.Loan> loansOf(User user) {
        return loans.findByUserIdOrderByRequestedAtDesc(user.getId()).stream()
                .map(loan -> new DataRightsDtos.Loan(
                        loan.getId(),
                        loan.getPrincipal(),
                        loan.getTenureMonths(),
                        loan.getFlatRate(),
                        loan.totalInterest(),
                        loan.totalRepayable(),
                        loan.getProcessingFee(),
                        loan.getAmountRepaid(),
                        loan.getRebateGranted(),
                        loan.outstanding(),
                        loan.getPurpose(),
                        loan.getStatus().name(),
                        loan.getRequestedAt(),
                        loan.getDisbursedAt(),
                        loan.getDueDate(),
                        loan.getSettledAt(),
                        // What an automated decision rested on. Without these
                        // the right to a human review cannot be exercised.
                        loan.getDecisionReasons(),
                        loan.getWriteOffNote()))
                .toList();
    }

    private List<DataRightsDtos.PayIn> payInsOf(User user) {
        return claims.findByUserIdOrderByClaimedAtDesc(user.getId()).stream()
                .map(claim -> new DataRightsDtos.PayIn(
                        claim.getId(),
                        claim.getAmount(),
                        claim.getReference(),
                        claim.getPurpose().name(),
                        claim.getSenderName(),
                        claim.getSenderBank(),
                        claim.getStatus().name(),
                        claim.getClaimedAt(),
                        claim.getReviewedAt(),
                        claim.getNote()))
                .toList();
    }

    private List<DataRightsDtos.Withdrawal> withdrawalsOf(User user) {
        return withdrawals.findByUserIdOrderByRequestedAtDesc(user.getId()).stream()
                .map(request -> new DataRightsDtos.Withdrawal(
                        request.getId(),
                        request.getAmount(),
                        request.getBank(),
                        Masks.accountTail(request.getDestinationAccount()),
                        request.getReference(),
                        request.getStatus().name(),
                        request.getRequestedAt(),
                        request.getReviewedAt(),
                        request.getNote()))
                .toList();
    }

    /**
     * The circles this customer is in, and their own payments into them.
     *
     * <p>The other members appear as a count. They are real people with their
     * own rights, and one member's subject access request must not become a
     * route to everybody else's payment history.
     */
    private List<DataRightsDtos.Circle> circlesOf(User user) {
        List<DataRightsDtos.Circle> result = new ArrayList<>();

        for (ThriftCircle circle : circles.findForMember(user.getId())) {
            Optional<ThriftMember> seat = circle.memberFor(user.getId());

            List<DataRightsDtos.CircleContribution> mine =
                    contributions.findByCircleIdAndUserIdOrderByRoundAsc(circle.getId(), user.getId())
                            .stream()
                            .map(DataRightsService::toCircleContribution)
                            .toList();

            result.add(new DataRightsDtos.Circle(
                    circle.getId(),
                    circle.getName(),
                    circle.getContribution(),
                    circle.getFrequency().name(),
                    circle.size(),
                    circle.getCurrentRound(),
                    circle.getStartDate(),
                    seat.map(ThriftMember::getJoinedAt).orElse(null),
                    seat.map(ThriftMember::getLeftAt).orElse(null),
                    mine));
        }
        return result;
    }

    private static DataRightsDtos.CircleContribution toCircleContribution(ThriftContribution c) {
        return new DataRightsDtos.CircleContribution(c.getRound(), c.getAmount(), c.getPaidAt());
    }

    private List<DataRightsDtos.Notice> noticesOf(User user) {
        return notifications.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(notice -> new DataRightsDtos.Notice(
                        notice.getKind().name(),
                        notice.getTitle(),
                        notice.getBody(),
                        notice.getAmount(),
                        notice.getCreatedAt(),
                        notice.isRead(),
                        notice.getClearedAt()))
                .toList();
    }

    /** What they said about the app, if anything. Public while it stands. */
    private DataRightsDtos.Review reviewOf(User user) {
        return reviews.findByUserId(user.getId())
                .map(review -> new DataRightsDtos.Review(
                        review.getDisplayName(),
                        review.getRating(),
                        review.getComment(),
                        review.getCreatedAt(),
                        review.getUpdatedAt()))
                .orElse(null);
    }

    private List<DataRightsDtos.Agreement> agreementsOf(User user) {
        return acceptances.findByUserIdOrderByAcceptedAtDesc(user.getId()).stream()
                .map(DataRightsService::toAgreement)
                .toList();
    }

    private static DataRightsDtos.Agreement toAgreement(LegalAcceptance acceptance) {
        return new DataRightsDtos.Agreement(
                acceptance.getKind().name(),
                acceptance.getDocumentVersion(),
                acceptance.getAcceptedAt(),
                acceptance.getDevice(),
                acceptance.getIpAddress());
    }

    private List<DataRightsDtos.SignIn> signInsOf(User user) {
        return sessions.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(DataRightsService::toSignIn)
                .toList();
    }

    private static DataRightsDtos.SignIn toSignIn(UserSession session) {
        return new DataRightsDtos.SignIn(
                session.getDeviceLabel(),
                session.getIpAddress(),
                session.getCreatedAt(),
                session.getLastSeenAt(),
                session.getRevokedAt(),
                session.getRevokedReason());
    }

    /**
     * When somebody at Kudi9ja opened this record.
     *
     * <p>Capped: an export is a document a person reads, and a customer of long
     * standing could otherwise have thousands of these drowning everything
     * else. The full history is in the audit log, which is append-only.
     */
    private List<DataRightsDtos.RecordAccess> recordAccessesOf(User user) {
        return auditEntries
                .search(AuditCategory.DATA_ACCESS, user.getId(), null, null, null,
                        PageRequest.of(0, 500))
                .getContent()
                .stream()
                .map(DataRightsService::toRecordAccess)
                .toList();
    }

    private static DataRightsDtos.RecordAccess toRecordAccess(AuditEntry entry) {
        // The member of staff is deliberately not named. See RecordAccess.
        return new DataRightsDtos.RecordAccess(
                entry.getAction(), entry.getCategory().label(), entry.getOccurredAt());
    }

    private DataRightsDtos.AdminAccess adminAccessOf(User user) {
        return admins.findByEmailIgnoreCase(user.getEmail())
                .map(grant -> new DataRightsDtos.AdminAccess(
                        grant.isActive(), grant.getRole().label(), grant.getAddedAt()))
                .orElseGet(() -> new DataRightsDtos.AdminAccess(false, null, null));
    }

    // ── Erasure ────────────────────────────────────────────────────────────

    /**
     * What stands in the way of closing, asked before the customer commits.
     *
     * <p>Returned as a list they can act on. "Your account cannot be closed" on
     * its own is the answer that sends somebody to support.
     */
    @Transactional(readOnly = true)
    public DataRightsDtos.ClosureEligibility closureEligibility(UUID userId) {
        User user = require(userId);
        List<String> blockers = blockersFor(user);

        return new DataRightsDtos.ClosureEligibility(
                blockers.isEmpty(),
                blockers,
                ledger.balanceOf(userId),
                lockedInSavings(userId),
                outstandingOnLoans(userId),
                retentionNote());
    }

    /**
     * Closes the account.
     *
     * <p>Credentials are destroyed now; the record is kept until its retention
     * runs out. Both halves are reported back, because a customer is entitled
     * to know which is which.
     */
    @Transactional
    public DataRightsDtos.ClosureResponse close(
            UUID userId, DataRightsDtos.CloseAccountRequest request) {

        User user = require(userId);

        if (user.getAccountStatus() == AccountStatus.CLOSED) {
            throw new ApiException(ErrorCode.ACCOUNT_CLOSED, "That account is already closed.");
        }

        // Password first, then the code. Both are checked before anything is
        // touched: a half-closed account is worse than a refused one.
        if (user.getPasswordHash() == null || !hasher.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "That password is not right.");
        }
        otps.verify(user.getEmail(), OtpPurpose.ACCOUNT_CLOSURE, request.code());

        List<String> blockers = blockersFor(user);
        if (!blockers.isEmpty()) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "Your account cannot be closed yet: " + String.join(" ", blockers),
                    java.util.Map.of("blockers", blockers));
        }

        Instant now = Instant.now();
        Instant retainUntil = now.plus(
                properties.compliance().recordRetentionYears() * 365L, ChronoUnit.DAYS);

        // Redacted: the things that are credentials or preferences rather than
        // records. None of these is needed to satisfy an AML enquiry, and each
        // one kept is one more thing that can leak.
        user.setPasswordHash(closedPlaceholder());
        user.setPasscodeHash(null);
        user.setPinHash(null);
        user.setSecurityQuestion(null);
        user.setSecurityAnswerHash(null);
        user.setBiometricsEnabled(false);
        user.setAutoDebit(false);

        user.setAccountStatus(AccountStatus.CLOSED);
        user.setClosedAt(now);
        user.setRetainUntil(retainUntil);
        user.setStatusNote(request.reason() == null || request.reason().isBlank()
                ? "Closed at the customer's request."
                : "Closed at the customer's request. Reason given: " + request.reason().trim());
        users.save(user);

        // A closed account cannot sign in, and every live session goes with it.
        int ended = auth.signOutEverywhere(userId, "Account closed at the customer's request");

        // Panel access goes too. Somebody who has left should not still be able
        // to open other people's records if the account is ever reopened.
        admins.findByEmailIgnoreCase(user.getEmail()).ifPresent(grant -> {
            refuseClosingTheLastOwner(grant);
            admins.delete(grant);
        });

        notifications.clearAll(userId, now);

        // Every phone this account was reachable on. A handset still registered
        // after closure would keep buzzing about an account nobody can open.
        int devicesDropped = devices.deleteByUserId(userId);
        log.debug("Dropped {} device registration(s) on closure", devicesDropped);

        // Their review goes too. It is public, it is theirs, and nothing the
        // law requires us to keep is in it.
        reviews.deleteByUserId(userId);

        audit.record(
                new AuditService.Actor(user.getId(), user.getFullName(), user.getEmail()),
                AuditCategory.COMPLIANCE,
                "Account closed at the customer's request",
                user.getFullName() + " (" + user.getCustomerRef() + ") closed their account. "
                        + "Credentials were destroyed; identity and transaction records are "
                        + "retained until " + Dates.lagosDate(retainUntil) + " as the "
                        + "anti-money-laundering rules require."
                        + (request.reason() == null || request.reason().isBlank()
                                ? ""
                                : " Reason given: " + request.reason().trim()),
                user.getId(),
                user.getCustomerRef());

        log.info("Closed the account for {}, retained until {}",
                Masks.email(user.getEmail()), retainUntil);

        return new DataRightsDtos.ClosureResponse(
                AccountStatus.CLOSED,
                now,
                retainUntil,
                ended,
                List.of(
                        "Your password, passcode and transaction PIN",
                        "Your security question and its answer",
                        "Your saved preferences",
                        "Your notifications"),
                List.of(
                        "Your name, date of birth, BVN and NIN",
                        "Your address and contact details",
                        "Every transaction on your wallet",
                        "Your savings plans and loans",
                        "Which version of each agreement you accepted"),
                "Your account is closed and you have been signed out everywhere. "
                        + "We have destroyed everything we are free to destroy. The records listed "
                        + "as retained are kept until " + Dates.lagosDate(retainUntil)
                        + " because the anti-money-laundering rules require it, and are then "
                        + "erased. Nobody at Kudi9ja will contact you again except about those "
                        + "obligations.");
    }

    /**
     * Finally erases the records whose retention has run out.
     *
     * <p>Called by the daily job. This is the half of the promise that makes
     * "retained for five years" mean something: without it, a closed account is
     * kept indefinitely and a customer who asked to be forgotten never is.
     *
     * <p>The ledger and the loan book are left alone. They are the transaction
     * records the AML rules are actually about, they no longer name anybody
     * once the identity is gone, and deleting a row from an append-only ledger
     * would break the guarantee the rest of this system rests on. What goes is
     * everything that ties those rows to a person.
     */
    @Transactional
    public int erase(Instant now) {
        List<User> due = users.findDueForErasure(now);

        for (User user : due) {
            String reference = user.getCustomerRef();

            user.setFullName("Erased");
            user.setEmail("erased+" + user.getId() + ".invalid");
            user.setPhone(null);
            user.setDateOfBirth(null);
            user.setGender(null);
            user.setBvn(null);
            user.setNin(null);
            user.setAddress(null);
            user.setState(null);
            user.setPayoutBank(null);
            user.setPayoutAccountNumber(null);
            user.setPayoutAccountName(null);
            user.setPasswordHash(closedPlaceholder());
            user.setStatusNote("Erased after the retention period ended.");
            user.setRetainUntil(null);
            users.save(user);

            audit.recordSystem(
                    AuditCategory.COMPLIANCE,
                    "Retained record erased",
                    "The retention period for " + reference + " ended, and the identity held "
                            + "against it was erased. The transaction record remains and no "
                            + "longer names anybody.");
        }

        if (!due.isEmpty()) {
            log.info("Erased {} closed account(s) whose retention period had ended", due.size());
        }
        return due.size();
    }

    /** Sends the code that a closure is confirmed with. */
    @Transactional
    public OtpService.Issued startClosure(UUID userId) {
        User user = require(userId);
        if (user.getAccountStatus() == AccountStatus.CLOSED) {
            throw new ApiException(ErrorCode.ACCOUNT_CLOSED, "That account is already closed.");
        }
        return otps.issue(user.getEmail(), OtpPurpose.ACCOUNT_CLOSURE, user.getFullName(), userId);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Everything unfinished, phrased as something the customer can act on.
     *
     * <p>Each of these would either strand the customer's money or somebody
     * else's. None is improved by being abandoned, and an account closed on top
     * of one is a support case rather than a resolution.
     */
    private List<String> blockersFor(User user) {
        UUID userId = user.getId();
        List<String> blockers = new ArrayList<>();

        BigDecimal balance = ledger.balanceOf(userId);
        if (Money.isPositive(balance)) {
            blockers.add("Your wallet still holds " + Money.naira(balance)
                    + " — withdraw it to your bank account first.");
        }

        BigDecimal locked = lockedInSavings(userId);
        if (Money.isPositive(locked)) {
            blockers.add("You have " + Money.naira(locked)
                    + " in savings that has not been released yet.");
        }

        BigDecimal owed = outstandingOnLoans(userId);
        if (Money.isPositive(owed)) {
            blockers.add("You still owe " + Money.naira(owed) + " on a loan.");
        }

        long pendingClaims = claims
                .findByUserIdAndStatusOrderByClaimedAtDesc(userId, DepositStatus.PENDING).size();
        if (pendingClaims > 0) {
            blockers.add(pendingClaims == 1
                    ? "A payment you claimed is still being reviewed."
                    : pendingClaims + " payments you claimed are still being reviewed.");
        }

        long pendingWithdrawals = withdrawals.findByUserIdOrderByRequestedAtDesc(userId).stream()
                .filter(request -> request.getStatus() == WithdrawalStatus.PENDING)
                .count();
        if (pendingWithdrawals > 0) {
            blockers.add(pendingWithdrawals == 1
                    ? "A withdrawal is still being reviewed."
                    : pendingWithdrawals + " withdrawals are still being reviewed.");
        }

        long activeCircles = circles.countActiveForMember(userId);
        if (activeCircles > 0) {
            blockers.add(activeCircles == 1
                    ? "You are in a thrift circle that is still running — leave it first."
                    : "You are in " + activeCircles + " thrift circles that are still running.");
        }

        admins.findByEmailIgnoreCase(user.getEmail())
                .filter(grant -> grant.isActive() && grant.getRole() == AdminRole.OWNER)
                .filter(grant -> admins.countByRoleAndActiveTrue(AdminRole.OWNER) <= 1)
                .ifPresent(grant -> blockers.add(
                        "You are the only active owner of the admin panel. Make somebody else an "
                                + "owner before closing your account."));

        return blockers;
    }

    /** Money still inside a plan: running, or matured but not yet taken out. */
    private BigDecimal lockedInSavings(UUID userId) {
        return Money.of(plans
                .findByUserIdAndStatusInOrderByMaturityDateAsc(
                        userId, List.of(SavingsStatus.ACTIVE, SavingsStatus.MATURED))
                .stream()
                .map(SavingsPlan::getPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private BigDecimal outstandingOnLoans(UUID userId) {
        return Money.of(loans
                .findByUserIdAndStatusIn(userId, List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE))
                .stream()
                .map(Loan::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /**
     * The last active owner cannot leave by closing their account either.
     *
     * <p>The blocker list already says so, so reaching this is a race — two
     * owners closing at the same moment — rather than a surprise.
     */
    private void refuseClosingTheLastOwner(AdminUser grant) {
        if (grant.isActive()
                && grant.getRole() == AdminRole.OWNER
                && admins.countByRoleAndActiveTrue(AdminRole.OWNER) <= 1) {
            throw new ApiException(
                    ErrorCode.LAST_OWNER,
                    "You are the only active owner of the admin panel. Make somebody else an "
                            + "owner before closing your account.");
        }
    }

    private String retentionNote() {
        return "Closing your account destroys your password, passcode, PIN and preferences at "
                + "once. Your identity and transaction records are kept for "
                + properties.compliance().recordRetentionYears()
                + " years afterwards, because the anti-money-laundering rules require it, and are "
                + "erased when that period ends.";
    }

    /**
     * A hash no password can produce.
     *
     * <p>The column is not null, and leaving the old hash behind would leave a
     * credential on a closed account. This is not a hash of anything.
     */
    private static String closedPlaceholder() {
        return "closed-" + UUID.randomUUID();
    }

    private User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("That account"));
    }
}
