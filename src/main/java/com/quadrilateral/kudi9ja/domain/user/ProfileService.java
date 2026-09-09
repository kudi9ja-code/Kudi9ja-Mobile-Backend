package com.quadrilateral.kudi9ja.domain.user;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.kyc.KycService;
import com.quadrilateral.kudi9ja.domain.kyc.OtpPurpose;
import com.quadrilateral.kudi9ja.domain.kyc.OtpService;
import com.quadrilateral.kudi9ja.domain.loan.CreditScoreService;
import com.quadrilateral.kudi9ja.domain.loan.Installment;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlanRepository;
import com.quadrilateral.kudi9ja.domain.savings.SavingsStatus;
import com.quadrilateral.kudi9ja.domain.thrift.ThriftService;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import com.quadrilateral.kudi9ja.security.crypto.SecretHasher;
import com.quadrilateral.kudi9ja.web.dto.UserDtos;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer's own account: what they see of it, and what they may change.
 *
 * <p>The line between the two is drawn by where a fact came from. Name, date of
 * birth, BVN and NIN were checked against the issuing institutions, so they are
 * not editable here — letting a customer retype them afterwards would undo the
 * verification, and they change through support with evidence instead. The
 * payout account is where money leaves, so it has its own path behind a
 * one-time code, the transaction PIN, and a fresh name enquiry. Everything
 * else — phone, address, theme — is a preference, and changes freely.
 */
@Service
public class ProfileService {

    private final UserRepository users;
    private final AdminUserRepository admins;
    private final LedgerService ledger;
    private final WalletTransactionRepository transactions;
    private final SavingsPlanRepository plans;
    private final LoanRepository loans;
    private final ThriftService thrift;
    private final CreditScoreService creditScores;
    private final NotificationService notifications;
    private final OtpService otps;
    private final KycService kyc;
    private final SecretHasher hasher;
    private final AuditService audit;

    public ProfileService(
            UserRepository users,
            AdminUserRepository admins,
            LedgerService ledger,
            WalletTransactionRepository transactions,
            SavingsPlanRepository plans,
            LoanRepository loans,
            ThriftService thrift,
            CreditScoreService creditScores,
            NotificationService notifications,
            OtpService otps,
            KycService kyc,
            SecretHasher hasher,
            AuditService audit) {
        this.users = users;
        this.admins = admins;
        this.ledger = ledger;
        this.transactions = transactions;
        this.plans = plans;
        this.loans = loans;
        this.thrift = thrift;
        this.creditScores = creditScores;
        this.notifications = notifications;
        this.otps = otps;
        this.kyc = kyc;
        this.hasher = hasher;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public UserDtos.ProfileResponse profile(UUID userId) {
        User user = require(userId);
        return UserDtos.ProfileResponse.from(user, panelRole(user));
    }

    /**
     * Whether the panel appears.
     *
     * <p>Asked of the database on this request rather than read off the token.
     * A grant can be suspended between one request and the next, and a claim
     * the client holds is a claim the client can forge.
     */
    @Transactional(readOnly = true)
    public boolean holdsPanelAccess(User user) {
        return panelRole(user) != null;
    }

    /**
     * What this account may do in the panel, or null if it holds no grant.
     *
     * <p>Read on the same terms as {@link #holdsPanelAccess}, and for the same
     * reason: the app needs it to label the entrance and hide controls, and the
     * server re-reads it on every admin request regardless of what the app was
     * told here.
     */
    @Transactional(readOnly = true)
    public AdminRole panelRole(User user) {
        return admins.findByEmailIgnoreCaseAndActiveTrue(user.getEmail())
                .map(AdminUser::getRole)
                .orElse(null);
    }

    /**
     * The preferences a customer may change about themselves.
     *
     * <p>Each field is optional: the client sends only what changed, and a null
     * leaves the stored value alone. The theme is here because it belongs on
     * the account rather than the device — the choice should follow the
     * customer when they change phones.
     */
    @Transactional
    public UserDtos.ProfileResponse update(UUID userId, UserDtos.UpdateProfileRequest request) {
        User user = require(userId);
        StringBuilder changes = new StringBuilder();

        if (request.phone() != null && !request.phone().equals(user.getPhone())) {
            users.findByPhone(request.phone())
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw new ApiException(
                                ErrorCode.PHONE_TAKEN,
                                "Another account already uses that number.");
                    });
            changes.append("phone ").append(com.quadrilateral.kudi9ja.common.util.Masks.phone(user.getPhone()))
                    .append(" → ").append(com.quadrilateral.kudi9ja.common.util.Masks.phone(request.phone()))
                    .append("; ");
            user.setPhone(request.phone());
            // The number changed, so the old verification no longer says
            // anything about the new one.
            user.setPhoneVerified(false);
        }

        if (request.address() != null && !request.address().isBlank()) {
            user.setAddress(request.address().trim());
            changes.append("address updated; ");
        }

        if (request.state() != null && !request.state().isBlank()) {
            if (!NigerianStates.isKnown(request.state())) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Choose one of the 36 states or the FCT.");
            }
            user.setState(request.state().trim());
            changes.append("state → ").append(request.state().trim()).append("; ");
        }

        if (request.themeMode() != null) {
            user.setThemeMode(request.themeMode());
        }
        if (request.hideBalance() != null) {
            user.setHideBalance(request.hideBalance());
        }
        if (request.autoDebit() != null) {
            user.setAutoDebit(request.autoDebit());
        }
        if (request.biometricsEnabled() != null) {
            // A device unlock, recorded so the app knows what to offer on the
            // next sign-in. It is not an authentication factor and no biometric
            // template ever reaches the server.
            user.setBiometricsEnabled(request.biometricsEnabled());
        }

        User saved = users.save(user);
        if (!changes.isEmpty()) {
            audit.record(
                    new AuditService.Actor(saved.getId(), saved.getFullName(), saved.getEmail()),
                    AuditCategory.CUSTOMER,
                    "Profile updated",
                    saved.getFullName() + " changed: " + changes.toString().trim(),
                    saved.getId(),
                    saved.getCustomerRef());
        }
        return UserDtos.ProfileResponse.from(saved, panelRole(saved));
    }

    /** Sends the code that a payout-account change is confirmed with. */
    @Transactional
    public OtpService.Issued startPayoutChange(UUID userId) {
        User user = require(userId);
        return otps.issue(user.getEmail(), OtpPurpose.PAYOUT_CHANGE, user.getFullName(), user.getId());
    }

    /**
     * Changes where money leaves to.
     *
     * <p>Three gates, because this is the field an attacker with a live session
     * actually wants: the one-time code proves they hold the email, the PIN
     * proves they are at the phone, and the name enquiry proves the account
     * belongs to the customer rather than to whoever is asking. The Terms
     * promise payouts only to an account in the customer's own name, and this
     * is where that promise is kept.
     */
    @Transactional
    public UserDtos.ProfileResponse changePayoutAccount(
            UUID userId, UserDtos.ChangePayoutRequest request) {

        User user = require(userId);

        if (user.getPinHash() == null || !hasher.matches(request.pin(), user.getPinHash())) {
            throw new ApiException(ErrorCode.PIN_INVALID, "That PIN is not right.");
        }
        otps.verify(user.getEmail(), OtpPurpose.PAYOUT_CHANGE, request.code());

        String resolvedName = kyc.verifyPayoutAccount(
                request.bank(), request.accountNumber(), user.getFullName());

        String wasBank = user.getPayoutBank();
        String wasAccount = user.getPayoutAccountNumber();

        user.setPayoutBank(request.bank().trim());
        user.setPayoutAccountNumber(request.accountNumber().trim());
        user.setPayoutAccountName(resolvedName);
        user.setPayoutVerifiedAt(Instant.now());
        User saved = users.save(user);

        audit.record(
                new AuditService.Actor(saved.getId(), saved.getFullName(), saved.getEmail()),
                AuditCategory.CUSTOMER,
                "Payout account changed",
                saved.getFullName() + " moved their payout account from "
                        + describeAccount(wasBank, wasAccount) + " → "
                        + describeAccount(saved.getPayoutBank(), saved.getPayoutAccountNumber())
                        + ", resolved as " + resolvedName + ".",
                saved.getId(),
                saved.getCustomerRef());

        // Told rather than merely recorded: if this was not the customer, this
        // notification is how they find out in time to stop the next payout.
        notifications.push(
                saved.getId(),
                NotifyKind.SECURITY,
                "Your payout account changed",
                "Withdrawals will now go to "
                        + describeAccount(saved.getPayoutBank(), saved.getPayoutAccountNumber())
                        + ". If this was not you, contact support@kudi9ja.com immediately.");

        return UserDtos.ProfileResponse.from(saved, panelRole(saved));
    }

    /**
     * Everything the dashboard leads with, computed here.
     *
     * <p>The client used to work these out on the device. It displays them now:
     * a balance the client can compute is a balance the client can lie about.
     */
    @Transactional(readOnly = true)
    public UserDtos.DashboardResponse dashboard(UUID userId) {
        User user = require(userId);

        BigDecimal balance = ledger.balanceOf(userId);
        BigDecimal totalSaved = Money.of(plans.totalSaved(userId));
        BigDecimal totalOwed = openLoans(userId).stream()
                .map(Loan::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        CreditScoreService.Assessment assessment = creditScores.assess(userId);

        return new UserDtos.DashboardResponse(
                balance,
                totalSaved,
                Money.of(totalOwed),
                // What the customer would have if everything settled today. A
                // locked plan is still theirs; an open loan is still owed.
                Money.of(balance.add(totalSaved).subtract(totalOwed)),
                Money.of(transactions.totalInterestEarned(userId)),
                thrift.committed(userId),
                assessment.score(),
                assessment.band(),
                plans.findByUserIdAndStatusInOrderByMaturityDateAsc(userId, List.of(SavingsStatus.ACTIVE)).size(),
                (int) openLoans(userId).size(),
                (int) thrift.activeCircleCount(userId),
                notifications.unreadCount(userId),
                user.isHideBalance(),
                nextRepayment(userId).orElse(null));
    }

    /**
     * The next instalment falling due across every open loan.
     *
     * <p>Derived rather than stored: the schedule is a function of what has been
     * repaid, and a stored "next payment" would go stale the moment a customer
     * paid something off.
     */
    @Transactional(readOnly = true)
    public Optional<UserDtos.NextRepayment> nextRepayment(UUID userId) {
        Instant now = Instant.now();

        record Pending(Loan loan, Installment installment) {
        }

        return openLoans(userId).stream()
                .flatMap(loan -> loan.schedule(now).stream()
                        .filter(row -> !row.status().isSettled())
                        .findFirst()
                        .map(row -> new Pending(loan, row))
                        .stream())
                .min(Comparator.comparing(p -> p.installment().dueDate()))
                .map(p -> new UserDtos.NextRepayment(
                        p.loan().getId(),
                        p.loan().getPurpose(),
                        p.installment().number(),
                        p.installment().amount(),
                        p.installment().dueDate(),
                        Duration.between(now, p.installment().dueDate()).toDays()));
    }

    private List<Loan> openLoans(UUID userId) {
        return loans.findByUserIdAndStatusIn(userId, List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE));
    }

    private static String describeAccount(String bank, String accountNumber) {
        if (bank == null || accountNumber == null) {
            return "no account on file";
        }
        return bank + " " + com.quadrilateral.kudi9ja.common.util.Masks.accountTail(accountNumber);
    }

    private User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("That account"));
    }
}
