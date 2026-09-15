package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalService;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlan;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlanRepository;
import com.quadrilateral.kudi9ja.domain.savings.SavingsStatus;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxFilter;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransaction;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The customer side of the admin panel: who they are, what they hold, and
 * changing where they stand.
 *
 * <p>Everything here is read-mostly. The panel does not move a customer's money
 * — pay-ins, withdrawals and loans each have their own service with their own
 * permission — and it cannot edit a customer's verified identity, because that
 * was checked against the issuing institutions and letting an admin retype it
 * would undo the check.
 *
 * <p>What it can do is change a customer's standing, and each of those changes
 * is written to the audit log with a mandatory reason.
 */
@Service
public class AdminCustomerService {

    private final UserRepository users;
    private final AdminUserRepository admins;
    private final AdminAccessService access;
    private final LedgerService ledger;
    private final WalletTransactionRepository transactions;
    private final SavingsPlanRepository plans;
    private final LoanRepository loans;
    private final WithdrawalService withdrawals;
    private final NotificationService notifications;
    private final AuditService audit;

    public AdminCustomerService(
            UserRepository users,
            AdminUserRepository admins,
            AdminAccessService access,
            LedgerService ledger,
            WalletTransactionRepository transactions,
            SavingsPlanRepository plans,
            LoanRepository loans,
            WithdrawalService withdrawals,
            NotificationService notifications,
            AuditService audit) {
        this.users = users;
        this.admins = admins;
        this.access = access;
        this.ledger = ledger;
        this.transactions = transactions;
        this.plans = plans;
        this.loans = loans;
        this.withdrawals = withdrawals;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<AdminDtos.CustomerRow> search(String query, AccountStatus status, Pageable pageable) {
        access.requireCanView();
        return users.search(blankToNull(query), status, pageable).map(this::toRow);
    }

    /**
     * A customer's full record.
     *
     * <p>Opening one is itself written to the audit log. A customer record holds
     * an address, a date of birth and a payout account, and the Privacy Policy
     * commits to being able to say who looked at it.
     */
    @Transactional
    public AdminDtos.CustomerDetail detail(UUID customerId) {
        AdminUser actor = access.requireCanView();
        User user = require(customerId);

        audit.record(
                actorOf(actor),
                AuditCategory.DATA_ACCESS,
                "Customer record opened",
                actor.getName() + " opened the record for " + user.getFullName()
                        + " (" + user.getCustomerRef() + ").",
                user.getId(),
                user.getCustomerRef());

        return AdminDtos.CustomerDetail.from(
                user,
                admins.findByEmailIgnoreCase(user.getEmail()).orElse(null),
                financials(user));
    }

    @Transactional(readOnly = true)
    public Page<WalletTransaction> transactions(UUID customerId, TxFilter filter, Pageable pageable) {
        access.requireCanView();
        require(customerId);
        TxFilter effective = filter == null ? TxFilter.ALL : filter;
        return effective == TxFilter.ALL
                ? transactions.findByUserIdOrderByOccurredAtDescSequenceDesc(customerId, pageable)
                : transactions.findByUserIdAndKindInOrderByOccurredAtDescSequenceDesc(
                        customerId, effective.kinds(), pageable);
    }

    @Transactional(readOnly = true)
    public List<SavingsPlan> plans(UUID customerId) {
        access.requireCanView();
        require(customerId);
        return plans.findByUserIdOrderByStartDateDesc(customerId);
    }

    @Transactional(readOnly = true)
    public List<Loan> loans(UUID customerId) {
        access.requireCanView();
        require(customerId);
        return loans.findByUserIdOrderByRequestedAtDesc(customerId);
    }

    /**
     * Flags a customer for review.
     *
     * <p>A flag records a concern and nothing more: the customer keeps
     * transacting, and their app looks exactly as it did. It is the step before
     * a freeze, not a quiet version of one — a customer whose money has stopped
     * moving is always told.
     */
    @Transactional
    public User flag(UUID customerId, String reason) {
        AdminUser actor = access.requireCanManageCustomers();
        User user = require(customerId);
        AccountStatus was = user.getAccountStatus();

        if (was == AccountStatus.CLOSED) {
            throw new ApiException(
                    com.quadrilateral.kudi9ja.common.error.ErrorCode.ACCOUNT_CLOSED,
                    "That account is closed.");
        }

        user.setAccountStatus(AccountStatus.FLAGGED);
        user.setStatusNote(reason.trim());
        User saved = users.save(user);

        audit.record(
                actorOf(actor),
                AuditCategory.CUSTOMER,
                "Customer flagged",
                saved.getFullName() + " (" + saved.getCustomerRef() + ") moved from "
                        + was.label() + " → " + AccountStatus.FLAGGED.label()
                        + ". Reason: " + reason.trim(),
                saved.getId(),
                saved.getCustomerRef());

        return saved;
    }

    /**
     * Changes a customer's standing outright.
     *
     * <p>A freeze stops money moving, so the customer is notified: finding a
     * frozen wallet with no explanation is the worst version of this, and the
     * Terms promise a reason.
     */
    @Transactional
    public User setStatus(UUID customerId, AccountStatus status, String reason) {
        AdminUser actor = access.requireCanManageCustomers();
        User user = require(customerId);
        AccountStatus was = user.getAccountStatus();

        if (was == status) {
            return user;
        }
        if (status == AccountStatus.CLOSED) {
            throw new ApiException(
                    com.quadrilateral.kudi9ja.common.error.ErrorCode.FORBIDDEN,
                    "Closing an account is not a panel action. It goes through the customer's own "
                            + "deletion request, so the five-year retention rules are applied.");
        }

        user.setAccountStatus(status);
        user.setStatusNote(reason.trim());
        User saved = users.save(user);

        audit.record(
                actorOf(actor),
                AuditCategory.CUSTOMER,
                status.canTransact() ? "Customer released" : "Customer frozen",
                saved.getFullName() + " (" + saved.getCustomerRef() + ") moved from "
                        + was.label() + " → " + status.label() + ". Reason: " + reason.trim(),
                saved.getId(),
                saved.getCustomerRef());

        if (!status.canTransact()) {
            notifications.push(
                    saved.getId(),
                    NotifyKind.SECURITY,
                    "Your account is on hold",
                    "Money cannot move in or out of your wallet at the moment. "
                            + "Reason given: " + reason.trim()
                            + ". Your savings and any loan are unaffected. "
                            + "Contact support@kudi9ja.com and we will explain.");
        } else if (!was.canTransact()) {
            notifications.push(
                    saved.getId(),
                    NotifyKind.SECURITY,
                    "Your account is active again",
                    "The hold on your wallet has been lifted. You can transact as normal.");
        }

        return saved;
    }

    /**
     * Whether a customer's stored balance still matches their ledger.
     *
     * <p>Support's first question when somebody says their balance is wrong.
     * The balance is the running total of an append-only ledger and must be
     * reconstructible from it, so a divergence is a defect to be chased — which
     * is why this reports one and there is no endpoint anywhere that lets an
     * admin correct a balance by hand.
     */
    @Transactional(readOnly = true)
    public LedgerService.Reconciliation reconcile(UUID customerId) {
        access.requireCanView();
        require(customerId);
        return ledger.reconcile(customerId);
    }

    /** Every derived figure on a customer record, computed here and nowhere else. */
    @Transactional(readOnly = true)
    public AdminDtos.CustomerFinancials financials(User user) {
        UUID id = user.getId();

        return new AdminDtos.CustomerFinancials(
                ledger.balanceOf(id),
                Money.of(plans.totalSaved(id)),
                Money.of(transactions.totalInterestEarned(id)),
                Money.of(transactions.totalDeposited(id)),
                totalOwed(id),
                withdrawals.pendingValueFor(id),
                plans.findByUserIdAndStatusInOrderByMaturityDateAsc(
                        id, List.of(SavingsStatus.ACTIVE)).size(),
                plans.countByUserId(id),
                loans.countByUserIdAndStatus(id, LoanStatus.ACTIVE),
                loans.findByUserIdOrderByRequestedAtDesc(id).size(),
                loans.countByUserIdAndStatus(id, LoanStatus.OVERDUE),
                transactions.countByUserId(id));
    }

    private BigDecimal totalOwed(UUID userId) {
        return Money.of(loans.findByUserIdAndStatusIn(userId, List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE))
                .stream()
                .map(Loan::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private AdminDtos.CustomerRow toRow(User user) {
        return new AdminDtos.CustomerRow(
                user.getId(),
                user.getCustomerRef(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getKycTier(),
                user.getKycTier().label(),
                user.getAccountStatus(),
                user.getAccountStatus().label(),
                ledger.balanceOf(user.getId()),
                user.getCreatedAt(),
                user.getLastActiveAt());
    }

    private User require(UUID customerId) {
        return users.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("That customer"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
