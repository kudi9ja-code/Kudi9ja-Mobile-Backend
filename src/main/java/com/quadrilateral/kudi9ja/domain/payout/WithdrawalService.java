package com.quadrilateral.kudi9ja.domain.payout;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import com.quadrilateral.kudi9ja.domain.settings.SettingsService;
import com.quadrilateral.kudi9ja.domain.user.AuthService;
import com.quadrilateral.kudi9ja.domain.user.KycTier;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.domain.wallet.TxStatus;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransaction;
import com.quadrilateral.kudi9ja.integration.bank.BankDirectory;
import com.quadrilateral.kudi9ja.integration.paystack.PaystackGateway;
import com.quadrilateral.kudi9ja.web.dto.WalletDtos;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money out.
 *
 * <p>The wallet is debited <b>at request, not at approval</b>. That is the
 * whole design: a customer with ₦10,000 who asks to withdraw it should not be
 * able to also lock it into savings while the request sits in a queue. So the
 * money leaves immediately against a pending ledger row, and the review decides
 * only what becomes of that row.
 *
 * <pre>
 *   request → wallet debited, PENDING row written
 *        ├── approve → row settles, money is sent, customer told
 *        └── decline → row reversed, refunded in full, reason given
 * </pre>
 *
 * <p>Money only ever goes to the payout account already on the customer's
 * record, which was resolved and name-matched when it was set. The client does
 * not get to name a destination.
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    private final WithdrawalRequestRepository withdrawals;
    private final UserRepository users;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final AuditService audit;
    private final AuthService auth;
    private final ObjectProvider<PaystackGateway> paystack;

    public WithdrawalService(
            WithdrawalRequestRepository withdrawals,
            UserRepository users,
            LedgerService ledger,
            SettingsService settings,
            NotificationService notifications,
            AuditService audit,
            AuthService auth,
            ObjectProvider<PaystackGateway> paystack) {
        this.withdrawals = withdrawals;
        this.users = users;
        this.ledger = ledger;
        this.settings = settings;
        this.notifications = notifications;
        this.audit = audit;
        this.auth = auth;
        this.paystack = paystack;
    }

    // ── Request ────────────────────────────────────────────────────────────

    @Transactional
    public WalletDtos.WithdrawalResponse request(UUID userId, BigDecimal amount, String pin) {
        return request(userId, amount, pin, null, null);
    }

    /**
     * Requests a withdrawal, optionally naming where the client believes it is
     * going.
     *
     * <p>The destination is <b>never taken from the request</b> — it is always
     * the payout account on the customer's own record, resolved and name-matched
     * when it was set, because the Terms promise payouts only to an account in
     * the customer's own name.
     *
     * <p>What {@code expectedBank} and {@code expectedAccountNumber} are for is
     * catching the case where the two have drifted apart. The withdrawal screen
     * prefills those fields from the profile and lets them be edited; without
     * this check an edited value would be silently ignored, the app would show
     * a confirmation naming the bank the customer typed, and the money would go
     * somewhere else. Being refused with the reason is far better than being
     * quietly redirected — even when the redirect is to the right place.
     */
    @Transactional
    public WalletDtos.WithdrawalResponse request(
            UUID userId,
            BigDecimal amount,
            String pin,
            String expectedBank,
            String expectedAccountNumber) {

        settings.requireNotInMaintenance();
        auth.verifyPin(userId, pin);

        PlatformSettings s = settings.currentReadOnly();
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));

        if (!user.getAccountStatus().canTransact()) {
            throw new ApiException(
                    ErrorCode.ACCOUNT_FROZEN,
                    "Money cannot leave your account while it is "
                            + user.getAccountStatus().label().toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getKycTier() != KycTier.TIER2) {
            throw new ApiException(
                    ErrorCode.KYC_TIER_TOO_LOW,
                    "Finish verifying your identity before withdrawing.");
        }
        if (!user.hasPayoutAccount()) {
            throw new ApiException(
                    ErrorCode.NO_PAYOUT_ACCOUNT,
                    "Add the bank account you want to be paid into first. It has to be in your own name.");
        }
        requireDestinationMatches(user, expectedBank, expectedAccountNumber);

        BigDecimal requested = Money.of(amount);
        if (Money.lt(requested, s.getMinWithdrawalAmount())) {
            throw new ApiException(
                    ErrorCode.AMOUNT_TOO_SMALL,
                    "The smallest withdrawal is " + Money.naira(s.getMinWithdrawalAmount()) + ".",
                    Map.of("minimum", s.getMinWithdrawalAmount()));
        }

        // The debit runs first and refuses if the wallet is short. The request
        // row is written against the transaction it produced, sharing its id.
        WalletTransaction tx = ledger.debit(
                userId,
                requested,
                TxKind.WITHDRAWAL,
                LedgerService.Entry.of(
                                "Withdrawal to " + user.getPayoutBank() + " — awaiting approval",
                                Masks.accountTail(user.getPayoutAccountNumber()))
                        .related(LedgerService.Related.WITHDRAWAL, null),
                TxStatus.PENDING);

        WithdrawalRequest request = new WithdrawalRequest();
        request.setId(tx.getId());
        request.setUserId(userId);
        request.setCustomerName(user.getFullName());
        request.setCustomerRef(user.getCustomerRef());
        request.setAmount(requested);
        request.setBank(user.getPayoutBank());
        request.setDestinationAccount(user.getPayoutAccountNumber());
        request.setDestinationName(user.getPayoutAccountName());
        request.setRequestedAt(Instant.now());
        request.setReference(tx.getReference());
        request.setStatus(WithdrawalStatus.PENDING);
        WithdrawalRequest saved = withdrawals.save(request);

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Withdrawal submitted",
                Money.naira(requested) + " to your " + user.getPayoutBank()
                        + " account is with our team for approval. It has already left your wallet so it "
                        + "cannot be spent twice — if we decline it, every naira comes straight back. "
                        + "We aim to review within one working day.",
                requested);

        log.info("Withdrawal {} requested for {}", saved.getId(), requested);
        return WalletDtos.WithdrawalResponse.from(
                saved,
                ledger.balanceOf(userId),
                "Submitted for approval. We usually review within one working day.");
    }

    // ── Review ─────────────────────────────────────────────────────────────

    /**
     * Releases the money.
     *
     * <p>The wallet was debited when the customer asked, so approval only
     * settles the record and marks the transfer as sent.
     */
    @Transactional
    public WithdrawalRequest approve(
            UUID requestId, AuditService.Actor actor, String payoutReference, String note) {

        WithdrawalRequest request = withdrawals.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("That withdrawal"));
        if (!request.isPending()) {
            throw new ApiException(
                    ErrorCode.ALREADY_REVIEWED,
                    "That withdrawal was already "
                            + request.getStatus().label().toLowerCase(Locale.ROOT) + ".");
        }

        PaystackGateway gateway = paystack.getIfAvailable();
        boolean paid;
        if (gateway != null) {
            paid = sendThroughPaystack(gateway, request);
        } else {
            // No Paystack: the admin paid it by hand from the company account.
            request.setPayoutProvider("MANUAL");
            request.setPayoutStatus("PAID");
            request.setPaidOutAt(Instant.now());
            paid = true;
        }

        request.setStatus(WithdrawalStatus.APPROVED);
        request.setReviewedAt(Instant.now());
        request.setReviewedBy(actor == null ? "System" : actor.describe());
        if (payoutReference != null && !payoutReference.isBlank()) {
            request.setPayoutReference(payoutReference);
        } else if (request.getTransferCode() != null) {
            request.setPayoutReference(request.getTransferCode());
        }
        request.setNote(note);
        withdrawals.save(request);

        // Settles the pending row that debited the wallet at request time —
        // now if the money has gone, or when Paystack says it has.
        if (paid) {
            ledger.settle(request.getId(), "Withdrawal to " + request.getBank());
        }

        notifications.push(
                request.getUserId(),
                NotifyKind.GENERAL,
                "Withdrawal approved",
                Money.naira(request.getAmount()) + " is on its way to your "
                        + request.getBank() + " account.",
                request.getAmount());

        audit.record(
                actor,
                AuditCategory.CUSTOMER,
                "Withdrawal approved",
                Money.naira(request.getAmount()) + " to " + request.getBank() + " "
                        + Masks.accountTail(request.getDestinationAccount())
                        + " for " + request.getCustomerName() + " (" + request.getReference() + ").",
                request.getUserId(),
                request.getCustomerRef());

        log.info("Approved withdrawal {} for {}", request.getId(), request.getAmount());
        return request;
    }

    /**
     * Sends an approved withdrawal through Paystack.
     *
     * <p>Called inside the approval, so a refusal from Paystack — the balance
     * is short, the account is wrong — leaves the request pending and the
     * admin sees why. Returns whether the money is already gone; usually it is
     * still processing, and {@link #payoutOutcome} settles or refunds it.
     */
    private boolean sendThroughPaystack(PaystackGateway gateway, WithdrawalRequest request) {
        String bankCode = BankDirectory.byName(request.getBank())
                .map(BankDirectory.Bank::code)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CONFLICT,
                        "We do not have a bank code for " + request.getBank() + ", so Paystack cannot send to it. "
                                + "Pay this one by hand and approve it with the bank's reference."));
        // Paystack wants 16 to 50 lowercase letters, digits, dashes or underscores.
        String transferReference = "k9wd-" + request.getId().toString().toLowerCase(Locale.ROOT);
        long kobo = request.getAmount().movePointRight(2).longValueExact();
        String name = request.getDestinationName() == null || request.getDestinationName().isBlank()
                ? request.getCustomerName()
                : request.getDestinationName();

        PaystackGateway.Transfer transfer;
        try {
            String recipient = gateway.createRecipient(name, request.getDestinationAccount(), bankCode);
            transfer = gateway.transfer(kobo, recipient, transferReference,
                    "Kudi9ja withdrawal " + request.getReference());
        } catch (PaystackGateway.PaystackException e) {
            String said = e.getMessage().strip();
            if (!said.endsWith(".")) said += ".";
            throw new ApiException(ErrorCode.CONFLICT, said + " Nothing was sent; the request is still waiting.");
        }

        request.setPayoutProvider("PAYSTACK");
        request.setTransferReference(transferReference);
        request.setTransferCode(transfer.transferCode());
        if ("success".equalsIgnoreCase(transfer.status())) {
            request.setPayoutStatus("PAID");
            request.setPaidOutAt(Instant.now());
            return true;
        }
        if ("failed".equalsIgnoreCase(transfer.status()) || "reversed".equalsIgnoreCase(transfer.status())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Paystack could not send this transfer. Nothing was sent; the request is still waiting.");
        }
        request.setPayoutStatus("PROCESSING");
        if ("otp".equalsIgnoreCase(transfer.status())) {
            request.setPayoutFailure("Waiting for the transfer to be approved with an OTP on the Paystack dashboard.");
        }
        return false;
    }

    /**
     * What Paystack reported about a transfer: it arrived, or it failed or was
     * returned. Arrives by webhook, possibly more than once; a repeat changes
     * nothing.
     *
     * <p>A failure puts every naira back in the wallet. The request stays
     * approved — it was — and its payout is marked failed, with the reason.
     */
    @Transactional
    public void payoutOutcome(String transferReference, boolean succeeded, String reason) {
        if (transferReference == null || !transferReference.startsWith("k9wd-")) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(transferReference.substring("k9wd-".length()));
        } catch (IllegalArgumentException e) {
            log.warn("Transfer outcome for a reference we did not issue: {}", transferReference);
            return;
        }
        WithdrawalRequest request = withdrawals.findById(id).orElse(null);
        if (request == null) {
            log.warn("Transfer outcome for an unknown withdrawal: {}", transferReference);
            return;
        }
        if ("FAILED".equals(request.getPayoutStatus()) || ("PAID".equals(request.getPayoutStatus()) && succeeded)) {
            return;
        }
        WalletTransaction tx = ledger.find(request.getId()).orElse(null);
        boolean pending = tx != null && tx.getStatus() == TxStatus.PENDING;

        if (succeeded) {
            if (pending) {
                ledger.settle(request.getId(), "Withdrawal to " + request.getBank());
            }
            request.setPayoutStatus("PAID");
            request.setPayoutFailure(null);
            request.setPaidOutAt(Instant.now());
            withdrawals.save(request);
            notifications.push(
                    request.getUserId(),
                    NotifyKind.GENERAL,
                    "Withdrawal sent",
                    Money.naira(request.getAmount()) + " has been sent to your " + request.getBank() + " account.",
                    request.getAmount());
            return;
        }

        String why = reason == null || reason.isBlank() ? "the bank did not accept the transfer" : reason.trim();
        if (pending) {
            ledger.reverse(
                    request.getId(),
                    "Withdrawal to " + request.getBank() + " — transfer failed",
                    "Refund: bank transfer failed");
        } else {
            // Paid, then returned by the bank: the money is back with us.
            ledger.credit(request.getUserId(), request.getAmount(), TxKind.DEPOSIT,
                    LedgerService.Entry.of("Refund: bank transfer returned", "Kudi9ja")
                            .related(LedgerService.Related.WITHDRAWAL, request.getId()));
        }
        request.setPayoutStatus("FAILED");
        request.setPayoutFailure(why.length() > 500 ? why.substring(0, 500) : why);
        withdrawals.save(request);

        notifications.push(
                request.getUserId(),
                NotifyKind.GENERAL,
                "Withdrawal returned to your wallet",
                "We could not send " + Money.naira(request.getAmount()) + " to your " + request.getBank()
                        + " account: " + why + ". The full amount is back in your wallet.",
                request.getAmount());
        audit.recordSystem(
                AuditCategory.CUSTOMER,
                "Withdrawal transfer failed",
                Money.naira(request.getAmount()) + " to " + request.getBank() + " "
                        + Masks.accountTail(request.getDestinationAccount()) + " for " + request.getCustomerName()
                        + " (" + request.getReference() + ") failed and was refunded. Paystack said: " + why);
    }

    /**
     * Refuses the request and puts every naira back.
     *
     * <p>The pending row is marked reversed and a compensating credit is
     * written, so the ledger still replays to the balance and the customer can
     * see the money return rather than merely finding it there.
     */
    @Transactional
    public WithdrawalRequest decline(UUID requestId, AuditService.Actor actor, String reason) {
        WithdrawalRequest request = withdrawals.findById(requestId)
                .orElseThrow(() -> ApiException.notFound("That withdrawal"));
        if (!request.isPending()) {
            throw new ApiException(
                    ErrorCode.ALREADY_REVIEWED,
                    "That withdrawal was already "
                            + request.getStatus().label().toLowerCase(Locale.ROOT) + ".");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation(
                    "Give a reason. The customer is told it, and they are owed an explanation "
                            + "for money that did not go where they asked.");
        }

        request.setStatus(WithdrawalStatus.DECLINED);
        request.setReviewedAt(Instant.now());
        request.setReviewedBy(actor == null ? "System" : actor.describe());
        request.setNote(reason.trim());
        withdrawals.save(request);

        ledger.reverse(
                request.getId(),
                "Withdrawal to " + request.getBank() + " — declined",
                "Refund: withdrawal declined");

        notifications.push(
                request.getUserId(),
                NotifyKind.GENERAL,
                "Withdrawal declined",
                Money.naira(request.getAmount()) + " has been returned to your wallet in full. Reason: "
                        + reason.trim(),
                request.getAmount());

        audit.record(
                actor,
                AuditCategory.CUSTOMER,
                "Withdrawal declined",
                Money.naira(request.getAmount()) + " to " + request.getBank() + " for "
                        + request.getCustomerName() + " refunded in full. Reason: " + reason.trim(),
                request.getUserId(),
                request.getCustomerRef());

        log.info("Declined withdrawal {} and refunded {}", request.getId(), request.getAmount());
        return request;
    }

    /**
     * Refuses a request that names a destination other than the one on file.
     *
     * <p>Both arguments are optional: a client that sends nothing is asking for
     * "wherever my payout account is", which is the only thing this endpoint
     * ever does anyway. A client that <i>does</i> name one is making a claim to
     * its customer about where the money is going, and that claim has to be
     * true.
     *
     * <p>The refusal points at the endpoint that can actually change it, which
     * is deliberately a different one behind a code, the PIN and a fresh name
     * enquiry — the payout account is where money leaves, so it does not move
     * as a side effect of a withdrawal.
     */
    private void requireDestinationMatches(User user, String bank, String accountNumber) {
        boolean bankDiffers = bank != null && !bank.isBlank()
                && !bank.trim().equalsIgnoreCase(user.getPayoutBank());
        boolean accountDiffers = accountNumber != null && !accountNumber.isBlank()
                && !accountNumber.trim().equals(user.getPayoutAccountNumber());

        if (!bankDiffers && !accountDiffers) {
            return;
        }

        throw new ApiException(
                ErrorCode.VALIDATION_FAILED,
                "Withdrawals only go to the bank account on your profile — "
                        + user.getPayoutBank() + " "
                        + Masks.accountTail(user.getPayoutAccountNumber())
                        + ". To be paid somewhere else, change your payout account first.",
                Map.of(
                        "payoutBank", user.getPayoutBank(),
                        "payoutAccountNumber", Masks.accountTail(user.getPayoutAccountNumber()),
                        "changeAt", "PATCH /api/v1/me/payout"));
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<WithdrawalRequest> listForCustomer(UUID userId, Pageable pageable) {
        return withdrawals.findByUserIdOrderByRequestedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public WithdrawalRequest getForCustomer(UUID userId, UUID id) {
        return withdrawals.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("That withdrawal"));
    }

    @Transactional(readOnly = true)
    public Page<WithdrawalRequest> queue(WithdrawalStatus status, String query, Pageable pageable) {
        return withdrawals.queue(status, query, pageable);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return withdrawals.countByStatus(WithdrawalStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public BigDecimal pendingValue() {
        return Money.of(withdrawals.pendingValue());
    }

    @Transactional(readOnly = true)
    public BigDecimal pendingValueFor(UUID userId) {
        return withdrawals.findByUserIdOrderByRequestedAtDesc(userId).stream()
                .filter(WithdrawalRequest::isPending)
                .map(WithdrawalRequest::getAmount)
                .reduce(Money.zero(), Money::add);
    }

    @Transactional(readOnly = true)
    public List<WithdrawalRequest> pendingSince(Instant before) {
        return withdrawals.pendingSince(before);
    }

    public WalletDtos.AdminWithdrawalResponse toAdminResponse(WithdrawalRequest request) {
        return new WalletDtos.AdminWithdrawalResponse(
                request.getId(),
                request.getUserId(),
                request.getCustomerName(),
                request.getCustomerRef(),
                request.getAmount(),
                request.getBank(),
                request.getDestinationAccount(),
                request.getDestinationName(),
                request.getReference(),
                request.getStatus(),
                request.getStatus().label(),
                request.getRequestedAt(),
                Duration.between(request.getRequestedAt(), Instant.now()).toHours(),
                request.getReviewedAt(),
                request.getReviewedBy(),
                request.getNote(),
                request.getPayoutReference(),
                request.getPayoutProvider(),
                request.getPayoutStatus(),
                request.getTransferCode(),
                request.getPayoutFailure(),
                request.getPaidOutAt());
    }
}
