package com.quadrilateral.kudi9ja.domain.payin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.common.util.Reference;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import com.quadrilateral.kudi9ja.domain.settings.SettingsService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.integration.paystack.PaystackGateway;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import com.quadrilateral.kudi9ja.web.dto.PayInDtos;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only route money enters a wallet.
 *
 * <p>There is no card, no USSD and no instant credit. A customer transfers to
 * the collection account quoting a reference minted for that one payment, then
 * claims it in-app with the amount, the sender's name and a receipt. Nothing is
 * credited until an admin has matched the reference against the bank statement
 * and seen the receipt.
 *
 * <pre>
 *   mint reference → customer transfers → claim with receipt → admin reviews
 *        ├── confirm → credit the wallet (or apply to the loan) + notify + audit
 *        └── reject  → nothing moves, reason recorded + notify + audit
 * </pre>
 */
@Service
public class PayInService {

    private static final Logger log = LoggerFactory.getLogger(PayInService.class);

    private final PayInClaimRepository claims;
    private final PaymentReferenceRepository references;
    private final UnmatchedPayInRepository unmatched;
    private final UserRepository users;
    private final LoanRepository loans;
    private final LoanService loanService;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ReceiptStorage receipts;
    private final Kudi9jaProperties properties;
    private final ObjectProvider<PaystackGateway> paystack;

    public PayInService(
            PayInClaimRepository claims,
            PaymentReferenceRepository references,
            UnmatchedPayInRepository unmatched,
            UserRepository users,
            LoanRepository loans,
            LoanService loanService,
            LedgerService ledger,
            SettingsService settings,
            NotificationService notifications,
            AuditService audit,
            ReceiptStorage receipts,
            Kudi9jaProperties properties,
            ObjectProvider<PaystackGateway> paystack) {
        this.claims = claims;
        this.references = references;
        this.unmatched = unmatched;
        this.users = users;
        this.loans = loans;
        this.loanService = loanService;
        this.ledger = ledger;
        this.settings = settings;
        this.notifications = notifications;
        this.audit = audit;
        this.receipts = receipts;
        this.properties = properties;
        this.paystack = paystack;
    }

    // ── Mint a reference ───────────────────────────────────────────────────

    /**
     * The reference currently on the customer's screen.
     *
     * <p><b>Idempotent.</b> Opening the pay-in screen three times returns the
     * same reference three times — a customer who looked at the screen has not
     * made three payments, and three references on their record would be three
     * things for an admin to rule out.
     *
     * <p>A new one is minted only when the last was copied, which is the moment
     * a customer actually intends to pay.
     */
    @Transactional
    public PayInDtos.PaymentInstructionResponse activeReference(UUID userId) {
        settings.requireNotInMaintenance();
        PlatformSettings s = settings.currentReadOnly();
        User user = requireUser(userId);

        PaymentReference active = references.findFirstByUserIdAndCopiedAtIsNull(userId)
                .orElseGet(() -> references.save(
                        PaymentReference.issue(userId, mintUnique(user))));

        return instruction(s, active.getReference(), false);
    }

    /**
     * Records that the customer took the reference away, and mints the next.
     *
     * <p>The copy is the event worth writing down. Until then the reference is
     * just text on a screen; afterwards it is on its way into a bank narration,
     * and an admin holding a statement needs to be able to find it.
     *
     * <p>Returns the <b>new</b> reference, so the screen updates the moment the
     * old one is on the clipboard. Two transfers of the same amount on the same
     * day are otherwise impossible to tell apart.
     */
    @Transactional
    public PayInDtos.PaymentInstructionResponse markCopiedAndMintNext(UUID userId) {
        settings.requireNotInMaintenance();
        PlatformSettings s = settings.currentReadOnly();
        User user = requireUser(userId);

        references.findFirstByUserIdAndCopiedAtIsNull(userId).ifPresent(active -> {
            active.setCopiedAt(Instant.now());
            references.save(active);
        });

        PaymentReference next = references.save(
                PaymentReference.issue(userId, mintUnique(user)));

        return instruction(s, next.getReference(), true);
    }

    /** Every reference this customer has taken away, newest first. */
    @Transactional(readOnly = true)
    public List<PaymentReference> copiedReferences(UUID userId) {
        return references.findByUserIdAndCopiedAtIsNotNullOrderByCopiedAtDesc(userId);
    }

    /**
     * Retried until unique.
     *
     * <p>The suffix is four characters from a 32-character alphabet, so a
     * collision is rare — but "rare" is not "impossible", and two claims
     * sharing a reference is exactly the confusion the reference exists to
     * prevent.
     */
    private String mintUnique(User user) {
        for (int attempt = 0; attempt < 12; attempt++) {
            String candidate = Reference.paymentReference(user.getCustomerRef());
            if (!claims.existsByReference(candidate) && !references.existsByReference(candidate)) {
                return candidate;
            }
        }
        throw new ApiException(
                ErrorCode.INTERNAL, "We could not create a payment reference. Try again.");
    }

    private PayInDtos.PaymentInstructionResponse instruction(
            PlatformSettings s, String reference, boolean freshlyMinted) {

        return new PayInDtos.PaymentInstructionResponse(
                reference,
                s.getCompanyBank(),
                s.getCompanyAccountNumber(),
                s.getCompanyAccountName(),
                s.getMinDepositAmount(),
                freshlyMinted
                        ? "Copied. Paste it as the narration on your transfer. This is a new "
                                + "reference for your next payment — the one you just copied is "
                                + "saved against your account."
                        : "Quote " + reference + " as the narration on your transfer, then come "
                                + "back and tell us you have paid, with a screenshot of the "
                                + "receipt. This reference is for this payment only.");
    }

    // ── Claim ──────────────────────────────────────────────────────────────

    /**
     * A customer says they have paid.
     *
     * <p>The receipt is mandatory and is stored as a real object. Nothing is
     * credited here, and the balance is unchanged when this returns.
     */
    @Transactional
    public PayInClaim submitClaim(
            UUID userId,
            PayInDtos.ClaimRequest request,
            String receiptFilename,
            String receiptContentType,
            byte[] receiptBytes) {

        settings.requireNotInMaintenance();
        if (paystack.getIfAvailable() != null) {
            // Paystack confirms a payment itself and credits it at once, so there
            // is nothing for a receipt to prove and nothing for an admin to approve.
            // Claims already waiting are still reviewed; no new ones are taken.
            throw new ApiException(
                    ErrorCode.FEATURE_DISABLED,
                    "Pay in through Paystack instead — by card, bank transfer or USSD. It is credited the "
                            + "moment it is confirmed, with no receipt to upload.");
        }
        PlatformSettings s = settings.currentReadOnly();
        User user = requireUser(userId);

        if (!user.getAccountStatus().canTransact()) {
            throw new ApiException(
                    ErrorCode.ACCOUNT_FROZEN,
                    "Your account cannot take payments in at the moment. Contact support.");
        }

        BigDecimal amount = Money.of(request.amount());
        if (Money.lt(amount, s.getMinDepositAmount())) {
            throw new ApiException(
                    ErrorCode.AMOUNT_TOO_SMALL,
                    "The smallest pay-in we can match is " + Money.naira(s.getMinDepositAmount()) + ".",
                    Map.of("minimum", s.getMinDepositAmount()));
        }

        if (receiptBytes == null || receiptBytes.length == 0) {
            throw new ApiException(
                    ErrorCode.RECEIPT_REQUIRED,
                    "Attach the receipt from your bank. We cannot confirm a payment without one.");
        }
        validateReceipt(receiptContentType, receiptBytes.length);

        String reference = request.reference() == null ? "" : request.reference().trim().toUpperCase(Locale.ROOT);
        if (reference.isBlank()) {
            throw ApiException.validation("Quote the reference you were given.");
        }
        if (!reference.startsWith(user.getCustomerRef())) {
            throw ApiException.validation(
                    "That reference was not issued to you. Take a fresh one and quote it on your transfer.");
        }
        if (claims.existsByReference(reference)) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "That reference has already been claimed. Every payment needs its own reference.");
        }

        DepositPurpose purpose = request.purpose() == null ? DepositPurpose.WALLET : request.purpose();
        Loan loan = null;
        if (purpose == DepositPurpose.LOAN_REPAYMENT) {
            if (request.loanId() == null) {
                throw ApiException.validation("Say which loan this payment is for.");
            }
            loan = loans.findByIdAndUserId(request.loanId(), userId)
                    .orElseThrow(() -> ApiException.notFound("That loan"));
            if (!loan.isOpen()) {
                throw new ApiException(ErrorCode.LOAN_CLOSED, "That loan is already closed.");
            }
        }

        String receiptKey = receipts.store(
                user.getCustomerRef(), receiptFilename, receiptContentType, receiptBytes);

        PayInClaim claim = new PayInClaim();
        claim.setId(UUID.randomUUID());
        claim.setUserId(userId);
        claim.setCustomerName(user.getFullName());
        claim.setCustomerRef(user.getCustomerRef());
        claim.setAmount(amount);
        claim.setClaimedAt(Instant.now());
        claim.setReference(reference);
        claim.setPurpose(purpose);
        claim.setLoanId(loan == null ? null : loan.getId());
        claim.setLoanPurpose(loan == null ? null : loan.getPurpose());
        claim.setReceiptKey(receiptKey);
        claim.setReceiptContentType(receiptContentType);
        claim.setReceiptSizeBytes((long) receiptBytes.length);
        claim.setSenderName(request.senderName() == null ? null : request.senderName().trim());
        claim.setSenderBank(request.senderBank() == null ? null : request.senderBank().trim());
        claim.setStatus(DepositStatus.PENDING);

        PayInClaim saved = claims.save(claim);

        // Tie the claim back to the reference the customer was issued, so an
        // admin comparing a bank narration can see it was one we handed out
        // and not something typed from memory. A reference we never issued is
        // itself worth noticing.
        references.findByReference(reference).ifPresent(issued -> {
            issued.setClaimId(saved.getId());
            if (issued.getCopiedAt() == null) {
                issued.setCopiedAt(Instant.now());
            }
            references.save(issued);
        });

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Payment submitted",
                "Your " + Money.naira(amount) + " "
                        + (purpose == DepositPurpose.LOAN_REPAYMENT ? "loan repayment" : "transfer")
                        + " is with our team. We check it against the bank statement, usually within "
                        + "one working day. Nothing has been added to your wallet yet.",
                amount);

        log.info("Pay-in claim {} for {} against reference {}", saved.getId(), amount, reference);
        return saved;
    }

    // ── Admin review ───────────────────────────────────────────────────────

    /**
     * The admin has matched the receipt to the statement.
     *
     * <p>For a wallet claim this credits the wallet. For a loan repayment it
     * credits the wallet <b>and</b> immediately applies the amount to the named
     * loan, so both legs appear in the ledger rather than a single figure that
     * explains neither.
     */
    @Transactional
    public PayInClaim confirm(UUID claimId, AuditService.Actor actor, String note) {
        PayInClaim claim = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("That claim"));
        if (!claim.isPending()) {
            throw new ApiException(
                    ErrorCode.ALREADY_REVIEWED,
                    "That payment was already " + claim.getStatus().label().toLowerCase(Locale.ROOT) + ".");
        }

        claim.setStatus(DepositStatus.CONFIRMED);
        claim.setReviewedAt(Instant.now());
        claim.setReviewedBy(actor == null ? "System" : actor.describe());
        claim.setNote(note);
        claims.save(claim);

        PlatformSettings s = settings.currentReadOnly();

        ledger.credit(claim.getUserId(), claim.getAmount(), TxKind.DEPOSIT,
                LedgerService.Entry.of(
                                claim.isLoanRepayment()
                                        ? "Transfer confirmed for loan repayment"
                                        : "Bank transfer confirmed",
                                s.getCompanyBank())
                        .withReference(claim.getReference())
                        .related(LedgerService.Related.PAY_IN, claim.getId()));

        if (claim.isLoanRepayment() && claim.getLoanId() != null) {
            Loan loan = loans.findById(claim.getLoanId())
                    .orElseThrow(() -> ApiException.notFound("That loan"));
            if (loan.isOpen()) {
                loanService.applyRepayment(
                        loan, claim.getAmount(), "Loan repayment from confirmed transfer", true);
            } else {
                // The loan closed between the claim and the review. The money
                // stays in the wallet rather than being pushed at a settled
                // debt, and the customer is told where it went.
                notifications.push(
                        claim.getUserId(),
                        NotifyKind.GENERAL,
                        "Payment added to your wallet",
                        "That loan was already cleared, so your " + Money.naira(claim.getAmount())
                                + " went into your wallet instead.",
                        claim.getAmount());
            }
        } else {
            notifications.push(
                    claim.getUserId(),
                    NotifyKind.GENERAL,
                    "Payment confirmed",
                    Money.naira(claim.getAmount()) + " has been added to your wallet.",
                    claim.getAmount());
        }

        audit.record(
                actor,
                AuditCategory.CUSTOMER,
                "Pay-in confirmed",
                Money.naira(claim.getAmount()) + " from " + claim.getCustomerName()
                        + " (" + claim.getReference() + ") confirmed as "
                        + claim.getPurpose().label().toLowerCase(Locale.ROOT) + ".",
                claim.getUserId(),
                claim.getCustomerRef());

        log.info("Confirmed pay-in {} for {}", claim.getId(), claim.getAmount());
        return claim;
    }

    /**
     * The admin could not find the payment, or the receipt does not match.
     *
     * <p>Nothing was ever credited, so there is nothing to reverse. A reason is
     * required: "rejected" without one leaves a customer who really did pay
     * with no way forward.
     */
    @Transactional
    public PayInClaim reject(UUID claimId, AuditService.Actor actor, String reason) {
        PayInClaim claim = claims.findById(claimId)
                .orElseThrow(() -> ApiException.notFound("That claim"));
        if (!claim.isPending()) {
            throw new ApiException(
                    ErrorCode.ALREADY_REVIEWED,
                    "That payment was already " + claim.getStatus().label().toLowerCase(Locale.ROOT) + ".");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.validation(
                    "Give a reason. The customer is told it, and they need it to put the payment right.");
        }

        claim.setStatus(DepositStatus.REJECTED);
        claim.setReviewedAt(Instant.now());
        claim.setReviewedBy(actor == null ? "System" : actor.describe());
        claim.setNote(reason.trim());
        claims.save(claim);

        notifications.push(
                claim.getUserId(),
                NotifyKind.GENERAL,
                "Payment not confirmed",
                "We could not confirm your " + Money.naira(claim.getAmount()) + " payment. Reason: "
                        + reason.trim() + " Nothing was taken from you — contact "
                        + properties.company().supportEmail() + " if you think this is wrong.",
                claim.getAmount());

        audit.record(
                actor,
                AuditCategory.CUSTOMER,
                "Pay-in rejected",
                Money.naira(claim.getAmount()) + " from " + claim.getCustomerName()
                        + " (" + claim.getReference() + ") rejected. Reason: " + reason.trim(),
                claim.getUserId(),
                claim.getCustomerRef());

        return claim;
    }

    // ── Unmatched money ────────────────────────────────────────────────────

    /**
     * Records a credit that cannot be tied to a customer.
     *
     * <p>The Terms commit us to holding it, tracing it, and returning it to
     * source after thirty days. The clock starts here.
     */
    @Transactional
    public UnmatchedPayIn recordUnmatched(
            PayInDtos.RecordUnmatchedRequest request, AuditService.Actor actor) {

        if (request.bankReference() != null && unmatched.existsByBankReference(request.bankReference())) {
            throw new ApiException(ErrorCode.CONFLICT, "That bank credit is already on the register.");
        }

        Instant received = request.receivedAt() == null ? Instant.now() : request.receivedAt();
        int holdDays = properties.jobs().unmatchedPayInReturnDays();

        UnmatchedPayIn record = new UnmatchedPayIn();
        record.setId(UUID.randomUUID());
        record.setAmount(Money.of(request.amount()));
        record.setNarration(request.narration());
        record.setSenderName(request.senderName());
        record.setSenderBank(request.senderBank());
        record.setSenderAccount(request.senderAccount());
        record.setBankReference(request.bankReference());
        record.setReceivedAt(received);
        record.setRecordedBy(actor == null ? "System" : actor.describe());
        record.setStatus(UnmatchedStatus.HELD);
        record.setReturnDueAt(received.plus(holdDays, ChronoUnit.DAYS));
        UnmatchedPayIn saved = unmatched.save(record);

        audit.record(
                actor,
                AuditCategory.COMPLIANCE,
                "Unmatched pay-in recorded",
                Money.naira(saved.getAmount()) + " received "
                        + com.quadrilateral.kudi9ja.common.util.Dates.lagosDate(received)
                        + " could not be tied to a customer. Narration: \"" + request.narration()
                        + "\". Due back to source by "
                        + com.quadrilateral.kudi9ja.common.util.Dates.lagosDate(saved.getReturnDueAt()) + ".",
                saved.getId(),
                saved.getBankReference());

        return saved;
    }

    /**
     * Ties an unmatched credit to a customer and credits it.
     *
     * <p>Goes through the ledger like any other pay-in, so the customer sees a
     * confirmed transfer rather than a balance that changed for no visible
     * reason.
     */
    @Transactional
    public UnmatchedPayIn matchToCustomer(
            UUID unmatchedId, UUID userId, AuditService.Actor actor, String note) {

        UnmatchedPayIn record = unmatched.findById(unmatchedId)
                .orElseThrow(() -> ApiException.notFound("That credit"));
        if (!record.isHeld()) {
            throw new ApiException(ErrorCode.ALREADY_REVIEWED, "That credit has already been dealt with.");
        }
        User user = requireUser(userId);
        PlatformSettings s = settings.currentReadOnly();

        ledger.credit(userId, record.getAmount(), TxKind.DEPOSIT,
                LedgerService.Entry.of("Bank transfer traced and confirmed", s.getCompanyBank())
                        .withReference(record.getBankReference() == null
                                ? Reference.ledger("K9")
                                : record.getBankReference())
                        .related(LedgerService.Related.PAY_IN, record.getId()));

        record.setStatus(UnmatchedStatus.MATCHED);
        record.setMatchedUserId(userId);
        record.setResolvedAt(Instant.now());
        record.setResolvedBy(actor == null ? "System" : actor.describe());
        record.setTraceNotes(append(record.getTraceNotes(), note));
        unmatched.save(record);

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Payment traced",
                "We found your " + Money.naira(record.getAmount())
                        + " transfer and added it to your wallet.",
                record.getAmount());

        audit.record(
                actor,
                AuditCategory.CUSTOMER,
                "Unmatched pay-in matched",
                Money.naira(record.getAmount()) + " traced to " + user.getFullName()
                        + " (" + user.getCustomerRef() + ") and credited.",
                userId,
                user.getCustomerRef());

        return record;
    }

    /** Marks a held credit as sent back to the account it came from. */
    @Transactional
    public UnmatchedPayIn markReturned(UUID unmatchedId, AuditService.Actor actor, String note) {
        UnmatchedPayIn record = unmatched.findById(unmatchedId)
                .orElseThrow(() -> ApiException.notFound("That credit"));
        if (!record.isHeld()) {
            throw new ApiException(ErrorCode.ALREADY_REVIEWED, "That credit has already been dealt with.");
        }

        record.setStatus(UnmatchedStatus.RETURNED);
        record.setResolvedAt(Instant.now());
        record.setResolvedBy(actor == null ? "System" : actor.describe());
        record.setTraceNotes(append(record.getTraceNotes(), note));
        unmatched.save(record);

        audit.record(
                actor,
                AuditCategory.COMPLIANCE,
                "Unmatched pay-in returned",
                Money.naira(record.getAmount()) + " returned to source at "
                        + record.getSenderBank() + ". " + (note == null ? "" : note),
                record.getId(),
                record.getBankReference());
        return record;
    }

    @Transactional
    public UnmatchedPayIn addTraceNote(UUID unmatchedId, AuditService.Actor actor, String note) {
        UnmatchedPayIn record = unmatched.findById(unmatchedId)
                .orElseThrow(() -> ApiException.notFound("That credit"));
        record.setTraceNotes(append(record.getTraceNotes(), note));
        return unmatched.save(record);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<PayInClaim> listForCustomer(UUID userId, Pageable pageable) {
        return claims.findByUserIdOrderByClaimedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public PayInClaim getForCustomer(UUID userId, UUID claimId) {
        return claims.findByIdAndUserId(claimId, userId)
                .orElseThrow(() -> ApiException.notFound("That claim"));
    }

    @Transactional(readOnly = true)
    public Page<PayInClaim> queue(DepositStatus status, String query, Pageable pageable) {
        return claims.queue(status, query, pageable);
    }

    @Transactional(readOnly = true)
    public PayInClaim get(UUID claimId) {
        return claims.findById(claimId).orElseThrow(() -> ApiException.notFound("That claim"));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return claims.countByStatus(DepositStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public BigDecimal pendingValue() {
        return Money.of(claims.pendingValue());
    }

    @Transactional(readOnly = true)
    public Page<UnmatchedPayIn> unmatchedList(UnmatchedStatus status, Pageable pageable) {
        return status == null
                ? unmatched.findAllByOrderByReceivedAtDesc(pageable)
                : unmatched.findByStatusOrderByReceivedAtAsc(status, pageable);
    }

    /** Credits still waiting to be traced to a customer, and what they add up to. */
    @Transactional(readOnly = true)
    public long unmatchedHeldCount() {
        return unmatched.countByStatus(UnmatchedStatus.HELD);
    }

    @Transactional(readOnly = true)
    public BigDecimal unmatchedHeldValue() {
        return Money.of(unmatched.heldValue());
    }

    @Transactional(readOnly = true)
    public List<UnmatchedPayIn> dueForReturn() {
        return unmatched.dueForReturn(Instant.now());
    }

    @Transactional(readOnly = true)
    public List<PayInClaim> pendingSince(Instant before) {
        return claims.pendingSince(before);
    }

    /**
     * A URL an admin can open to see the receipt.
     *
     * <p>Signed, expiring, and audited: every view of a customer's receipt is
     * written to the log, because a receipt carries a bank account and a name
     * and looking at one is an act worth recording.
     */
    @Transactional
    public String receiptUrl(UUID claimId, AuditService.Actor actor) {
        PayInClaim claim = get(claimId);
        audit.record(
                actor,
                AuditCategory.DATA_ACCESS,
                "Receipt viewed",
                "Receipt for " + Money.naira(claim.getAmount()) + " from " + claim.getCustomerName()
                        + " (" + claim.getReference() + ") was opened.",
                claim.getUserId(),
                claim.getCustomerRef());
        return receipts.signedUrl(claim.getReceiptKey(), properties.storage().signedUrlTtl());
    }

    /** The same URL, without an audit entry, for building a list view. */
    public String receiptUrlUnaudited(PayInClaim claim) {
        return receipts.signedUrl(claim.getReceiptKey(), properties.storage().signedUrlTtl());
    }

    public PayInDtos.AdminClaimResponse toAdminResponse(PayInClaim claim) {
        return new PayInDtos.AdminClaimResponse(
                claim.getId(),
                claim.getUserId(),
                claim.getCustomerName(),
                claim.getCustomerRef(),
                claim.getAmount(),
                claim.getReference(),
                claim.getPurpose(),
                claim.getPurpose().label(),
                claim.getLoanId(),
                claim.getLoanPurpose(),
                claim.getSenderName(),
                claim.getSenderBank(),
                claim.getStatus(),
                claim.getStatus().label(),
                claim.getClaimedAt(),
                Duration.between(claim.getClaimedAt(), Instant.now()).toHours(),
                claim.getReviewedAt(),
                claim.getReviewedBy(),
                claim.getNote(),
                receiptUrlUnaudited(claim));
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private User requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("That account"));
    }

    private void validateReceipt(String contentType, int size) {
        Kudi9jaProperties.Storage storage = properties.storage();
        if (size > storage.maxReceiptBytes()) {
            throw ApiException.validation(
                    "That receipt is too large. Attach one under "
                            + (storage.maxReceiptBytes() / (1024 * 1024)) + "MB.");
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (storage.allowedReceiptTypes().stream().noneMatch(type::startsWith)) {
            throw ApiException.validation(
                    "Attach a photo, screenshot or PDF of the receipt.");
        }
    }

    private static String append(String existing, String note) {
        if (note == null || note.isBlank()) {
            return existing;
        }
        String stamped = com.quadrilateral.kudi9ja.common.util.Dates.lagosDate(Instant.now())
                + ": " + note.trim();
        return existing == null || existing.isBlank() ? stamped : existing + "\n" + stamped;
    }
}
