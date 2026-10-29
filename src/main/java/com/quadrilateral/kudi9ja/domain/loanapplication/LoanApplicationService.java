package com.quadrilateral.kudi9ja.domain.loanapplication;

import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.settings.LendingTerms;
import com.quadrilateral.kudi9ja.domain.user.AuthService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applying to borrow, and the decision a person makes on it.
 *
 * <p>Three rules hold everything else up.
 *
 * <p><b>Submitting moves no money.</b> The wallet is untouched until an admin
 * approves, exactly as a pay-in claim credits nothing until one is confirmed.
 * A customer who has applied has applied, and the balance says so.
 *
 * <p><b>Approving disburses through the existing path.</b> Nothing about
 * pricing, fees, the schedule or the ledger is reimplemented here — the loan is
 * created by {@link LoanService#disburse}, the same code that has always
 * written one, so an approved application produces a loan indistinguishable
 * from the ones already on the books.
 *
 * <p><b>Rejecting says why, in words for the customer.</b> The reason is
 * required, it is sent to them, and it is on the application they can read
 * afterwards. Somebody whose statement was unreadable can send a better one;
 * somebody who was refused with a shrug can only wonder.
 */
@Service
public class LoanApplicationService {

    private static final Logger log = LoggerFactory.getLogger(LoanApplicationService.class);

    /** One person vouches for a borrower. */
    public static final int GUARANTORS_REQUIRED = 1;

    /** And three pictures of where the money is going to work. */
    public static final int BUSINESS_PHOTOS_REQUIRED = 3;

    private final LoanApplicationRepository applications;
    private final UserRepository users;
    private final LoanService loans;
    private final AuthService auth;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ReceiptStorage documents;
    private final Kudi9jaProperties properties;
    private final LoanOfficerService officers;

    public LoanApplicationService(
            LoanApplicationRepository applications,
            UserRepository users,
            LoanService loans,
            AuthService auth,
            NotificationService notifications,
            AuditService audit,
            ReceiptStorage documents,
            Kudi9jaProperties properties,
            LoanOfficerService officers) {
        this.applications = applications;
        this.users = users;
        this.loans = loans;
        this.auth = auth;
        this.notifications = notifications;
        this.audit = audit;
        this.documents = documents;
        this.properties = properties;
        this.officers = officers;
    }

    // -- Applying -----------------------------------------------------------

    /**
     * Everything one application arrives with.
     *
     * @param statement         the bank statement, already read off the request
     * @param statementPassword what opens it, if the bank locked it; else null
     * @param selfie            the applicant's face, taken now
     * @param photos            pictures of the business premises
     */
    public record Submission(
            BigDecimal amount,
            int months,
            RepaymentFrequency frequency,
            String purpose,
            String businessName,
            String businessAddress,
            BigDecimal monthlyIncome,
            List<Guarantor> guarantors,
            Upload statement,
            String statementPassword,
            Upload selfie,
            List<Upload> photos,
            String pin) {
    }

    /** Whether this customer borrows on the loan-group terms. */
    public boolean groupPricing(UUID userId) {
        return loans.termsFor(userId).group();
    }

    /** One file as it arrived, before it has anywhere to live. */
    public record Upload(String filename, String contentType, byte[] content) {
    }

    /**
     * Takes an application.
     *
     * <p>Ordering matters here and is deliberate. The PIN, the hard eligibility
     * limits and the shape of the form are all checked <b>before</b> a single
     * byte is uploaded: pushing four files to object storage and then refusing
     * the application leaves four orphans nobody will ever look at, and costs
     * the customer their upload allowance to be told something we knew at the
     * first line.
     */
    @Transactional
    public LoanApplication submit(UUID userId, Submission submission) {
        auth.verifyPin(userId, submission.pin());

        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));

        if (applications.existsByUserIdAndStatusIn(
                userId, List.of(LoanApplicationStatus.PENDING, LoanApplicationStatus.OFFERED))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "You already have an application with us. We will come back to you on that one "
                            + "before you send another.");
        }

        validateShape(submission);

        // The hard limits — verified identity, a priced tenure, the amount
        // bounds. These refuse now rather than after the documents are in.
        // Nothing else does: within them, the amount is the customer's to ask
        // and the admin's to judge.
        loans.assess(userId, submission.amount(), submission.months(), submission.frequency());

        LoanApplication application = new LoanApplication();
        application.setId(UUID.randomUUID());
        application.setUserId(userId);
        application.setCustomerName(user.getFullName());
        application.setCustomerRef(user.getCustomerRef());
        application.setAmount(Money.of(submission.amount()));
        application.setTenureMonths(submission.months());
        application.setRepaymentFrequency(RepaymentFrequency.orMonthly(submission.frequency()));
        application.setPurpose(submission.purpose().trim());
        application.setBusinessName(submission.businessName().trim());
        application.setBusinessAddress(submission.businessAddress().trim());
        application.setMonthlyIncome(
                submission.monthlyIncome() == null ? null : Money.of(submission.monthlyIncome()));
        application.setGuarantors(new ArrayList<>(submission.guarantors()));
        application.setSubmittedAt(Instant.now());
        application.setStatus(LoanApplicationStatus.PENDING);

        // Now the files, once there is something for them to belong to.
        application.setBankStatement(store(user, submission.statement(), "bank statement"));
        application.setStatementPassword(
                submission.statementPassword() == null || submission.statementPassword().isBlank()
                        ? null
                        : submission.statementPassword().trim());
        application.setSelfie(store(user, submission.selfie(), "photograph of yourself"));
        List<StoredDocument> photos = new ArrayList<>();
        int n = 0;
        for (Upload photo : submission.photos()) {
            photos.add(store(user, photo, "business photograph " + (++n)));
        }
        application.setBusinessPhotos(photos);

        LoanApplication saved = applications.save(application);

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Application received",
                "Your application to borrow " + Money.naira(saved.getAmount()) + " is with our team. "
                        + "We read the statement, the pictures and your guarantor before deciding, "
                        + "so this is not instant. Nothing has been added to your wallet yet.");

        audit.record(
                new AuditService.Actor(userId, user.getFullName(), user.getEmail()),
                AuditCategory.CUSTOMER,
                "Loan application submitted",
                user.getFullName() + " applied to borrow " + Money.naira(saved.getAmount())
                        + " over " + saved.getTenureMonths() + " months for " + saved.getPurpose()
                        + ".",
                userId,
                user.getCustomerRef());

        log.info("Loan application {} submitted by {} for {}",
                saved.getId(), userId, saved.getAmount());
        return saved;
    }

    /**
     * Withdraws an application nobody has decided yet.
     *
     * <p>Only while it is pending. Once a person has spent time on it the
     * decision is a record, and a customer cannot delete a refusal by taking
     * the application away after the fact.
     */
    @Transactional
    public LoanApplication cancel(UUID userId, UUID applicationId) {
        LoanApplication application = applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> ApiException.notFound("That application"));

        if (!application.isPending()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That application has already been decided and cannot be withdrawn.");
        }

        application.setStatus(LoanApplicationStatus.CANCELLED);
        application.setReviewedAt(Instant.now());
        return applications.save(application);
    }

    // -- Reading ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<LoanApplication> mine(UUID userId) {
        return applications.findByUserIdOrderBySubmittedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public LoanApplication mine(UUID userId, UUID applicationId) {
        return applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> ApiException.notFound("That application"));
    }

    @Transactional(readOnly = true)
    public Page<LoanApplication> queue(LoanApplicationStatus status, Pageable pageable) {
        return status == null
                ? applications.findAllByOrderBySubmittedAtDesc(pageable)
                : applications.findByStatusOrderBySubmittedAtDesc(status, pageable);
    }

    @Transactional(readOnly = true)
    public LoanApplication get(UUID applicationId) {
        return applications.findById(applicationId)
                .orElseThrow(() -> ApiException.notFound("That application"));
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return applications.countByStatus(LoanApplicationStatus.PENDING);
    }

    /**
     * A signed, expiring link to one of the uploaded documents.
     *
     * <p>Signed by us and served through our own endpoint rather than by the
     * storage provider, for the reason set out on the receipt signer: the
     * caller's panel access is re-checked when the link is opened, and the view
     * is written to the audit log. A bank statement is a more sensitive
     * document than a receipt, not a less sensitive one.
     */
    public String documentUrl(String key) {
        return documents.signedUrl(key, properties.storage().signedUrlTtl());
    }

    // -- Deciding -----------------------------------------------------------

    /**
     * Approves an application and disburses the loan.
     *
     * <p>The hard limits are checked again here rather than trusted from
     * submission. An application may have sat in the queue for days, during
     * which the customer could have been frozen or lending switched off — and
     * lending on a week-old view of an account is how money leaves for an
     * account that may no longer receive it.
     */
    @Transactional
    public LoanApplication approve(UUID applicationId, AuditService.Actor actor, String note) {
        LoanApplication application = requirePending(applicationId);
        boolean agreed = application.hasAgreedTerms();

        LoanService.Assessed assessed = loans.assess(
                application.getUserId(), application.getAmount(), application.lendingMonths(),
                application.getRepaymentFrequency(), agreed);

        Loan loan = loans.disburse(
                application.getUserId(),
                assessed,
                application.getRepaymentFrequency(),
                application.getPurpose(),
                actor.name(),
                agreed ? agreedPricing(application) : null);

        application.setStatus(LoanApplicationStatus.APPROVED);
        application.setReviewedAt(Instant.now());
        application.setReviewedBy(actor.describe());
        application.setLoanId(loan.getId());
        LoanApplication saved = applications.save(application);
        // A borrower without a loan officer gets the one who approved them.
        if (actor.id() != null) {
            officers.ensureForCustomer(application.getUserId(), actor.id());
        }

        // No notification here: disburse() already tells the customer the money
        // has landed, with the figures on it. Two in the same second about the
        // same event reads as a fault.

        audit.record(
                actor,
                AuditCategory.LOAN,
                "Loan application approved",
                actor.name() + " approved " + Money.naira(application.getAmount()) + " for "
                        + application.getCustomerName() + " over " + application.lendingMonths()
                        + " months"
                        + (agreed ? " on the changed terms the customer agreed to (" + termsSummary(application) + ")" : "")
                        + (note == null || note.isBlank() ? "." : ". Note: " + note.trim()),
                application.getUserId(),
                application.getCustomerRef());

        log.info("Loan application {} approved by {}; disbursed as loan {}",
                saved.getId(), actor.name(), loan.getId());
        return saved;
    }

    /**
     * Turns an application down, with a reason the customer is shown.
     *
     * <p>The reason is not optional and is not a code. It is read by the person
     * it is about, and the whole value of it is that they can act on it: a
     * statement that did not open can be sent again, a guarantor who cannot be
     * reached can be replaced. Applying again is expected.
     */
    @Transactional
    public LoanApplication reject(UUID applicationId, AuditService.Actor actor, String reason) {
        // Open either way: under review, or with the customer on new terms.
        LoanApplication application = get(applicationId);
        if (!application.getStatus().isOpen()) {
            requirePending(applicationId);
        }

        String written = reason == null ? "" : reason.trim();
        if (written.length() < 10) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Say why this was declined, in a sentence the customer can act on. "
                            + "They are shown exactly what you write here.");
        }

        application.setStatus(LoanApplicationStatus.REJECTED);
        application.setReviewedAt(Instant.now());
        application.setReviewedBy(actor.describe());
        application.setRejectionReason(written);
        LoanApplication saved = applications.save(application);

        notifications.push(
                application.getUserId(),
                NotifyKind.GENERAL,
                "Loan application declined",
                "We could not approve your application to borrow "
                        + Money.naira(application.getAmount()) + ". " + written
                        + " You are welcome to apply again once that is sorted.");

        audit.record(
                actor,
                AuditCategory.LOAN,
                "Loan application declined",
                actor.name() + " declined " + Money.naira(application.getAmount()) + " for "
                        + application.getCustomerName() + ". Reason given: " + written,
                application.getUserId(),
                application.getCustomerRef());

        log.info("Loan application {} declined by {}", saved.getId(), actor.name());
        return saved;
    }

    // -- Changing the terms -------------------------------------------------

    /**
     * The terms an admin offers instead of the ones the application is priced
     * on. Each is optional; one left out keeps what the application has now.
     *
     * @param flatRate         flat interest for the whole loan, as a fraction
     * @param managementFee    in naira, taken from the payout
     * @param repaymentFeeRate on each repayment, as a fraction of the instalment
     */
    public record Offer(
            Integer months, BigDecimal flatRate, BigDecimal managementFee, BigDecimal repaymentFeeRate, String note) {
    }

    /**
     * Sends changed terms back to the customer.
     *
     * <p>Nothing is lent on them until the customer agrees: the application
     * waits on them, not on the team. Offering terms identical to what it is
     * priced on now is refused, because that is an approval, not an offer. An
     * offer on an application whose customer already agreed to earlier terms
     * replaces them and asks again.
     */
    @Transactional
    public LoanApplication offerTerms(UUID applicationId, AuditService.Actor actor, Offer offer) {
        LoanApplication application = get(applicationId);
        if (!application.getStatus().isOpen()) {
            requirePending(applicationId);
        }

        LendingTerms t = loans.termsFor(application.getUserId());
        LoanService.Pricing current = currentPricing(application, t);
        int currentMonths = application.lendingMonths();

        int months = offer.months() == null ? currentMonths : offer.months();
        if (months < 1 || months > t.maxTenureMonths()) {
            throw ApiException.validation("Loans run from 1 to " + t.maxTenureMonths() + " months.");
        }
        // A new length with no rate given is priced on the rate card for it.
        BigDecimal rate = offer.flatRate() != null
                ? offer.flatRate()
                : months == currentMonths ? current.flatRate() : t.rateFor(months);
        BigDecimal fee = offer.managementFee() == null ? current.managementFee() : Money.of(offer.managementFee());
        BigDecimal feeRate = offer.repaymentFeeRate() == null ? current.repaymentFeeRate() : offer.repaymentFeeRate();

        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw ApiException.validation("The interest rate has to be between 0% and 100%.");
        }
        if (fee.signum() < 0 || !Money.lt(fee, application.getAmount())) {
            throw ApiException.validation("The management fee has to be less than the amount lent.");
        }
        if (feeRate.signum() < 0 || feeRate.compareTo(new BigDecimal("0.2")) > 0) {
            throw ApiException.validation("The transaction fee has to be between 0% and 20% of each repayment.");
        }
        boolean changed = months != currentMonths
                || rate.compareTo(current.flatRate()) != 0
                || fee.compareTo(current.managementFee()) != 0
                || feeRate.compareTo(current.repaymentFeeRate()) != 0;
        if (!changed) {
            throw ApiException.validation(
                    "Nothing has changed from the terms it is priced on now. Approve it as it is instead.");
        }

        String note = offer.note() == null || offer.note().isBlank() ? null : offer.note().trim();
        application.setProposedMonths(months);
        application.setProposedFlatRate(rate);
        application.setProposedManagementFee(fee);
        application.setProposedRepaymentFeeRate(feeRate);
        application.setProposalNote(note);
        application.setProposedAt(Instant.now());
        application.setProposedBy(actor.describe());
        application.setTermsAgreedAt(null);
        application.setStatus(LoanApplicationStatus.OFFERED);
        LoanApplication saved = applications.save(application);

        notifications.push(
                application.getUserId(),
                NotifyKind.GENERAL,
                "New terms for your loan application",
                "We have reviewed your application to borrow " + Money.naira(application.getAmount())
                        + " and can offer it on changed terms: " + termsSummary(saved) + "."
                        + (note == null ? "" : " " + note)
                        + " Open Loans to agree to them or turn them down. Nothing is lent until you agree.");

        audit.record(
                actor,
                AuditCategory.LOAN,
                "Loan application terms changed",
                actor.name() + " offered " + application.getCustomerName() + " "
                        + Money.naira(application.getAmount()) + " on changed terms: " + termsSummary(saved)
                        + (note == null ? "." : ". Note: " + note),
                application.getUserId(),
                application.getCustomerRef());

        log.info("New terms offered on loan application {} by {}", saved.getId(), actor.name());
        return saved;
    }

    /**
     * The customer agrees to the changed terms. The application goes back to
     * the team for a decision, now priced on them.
     */
    @Transactional
    public LoanApplication agreeToTerms(UUID userId, UUID applicationId, String pin) {
        auth.verifyPin(userId, pin);
        LoanApplication application = requireOffered(userId, applicationId);

        application.setTermsAgreedAt(Instant.now());
        application.setStatus(LoanApplicationStatus.PENDING);
        LoanApplication saved = applications.save(application);

        audit.record(
                customerActor(userId),
                AuditCategory.LOAN,
                "Changed loan terms agreed",
                application.getCustomerName() + " agreed to borrow " + Money.naira(application.getAmount())
                        + " on the changed terms: " + termsSummary(saved) + ". Back with the team for a decision.",
                userId,
                application.getCustomerRef());

        log.info("Customer {} agreed to the new terms on loan application {}", userId, applicationId);
        return saved;
    }

    /** The customer turns the changed terms down. That ends the application. */
    @Transactional
    public LoanApplication declineTerms(UUID userId, UUID applicationId) {
        LoanApplication application = requireOffered(userId, applicationId);

        application.setStatus(LoanApplicationStatus.TERMS_DECLINED);
        application.setReviewedAt(Instant.now());
        LoanApplication saved = applications.save(application);

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Loan application closed",
                "You turned down the new terms for " + Money.naira(application.getAmount())
                        + ", so nothing was lent. You are welcome to apply again.");

        audit.record(
                customerActor(userId),
                AuditCategory.LOAN,
                "Changed loan terms turned down",
                application.getCustomerName() + " turned down the changed terms on "
                        + Money.naira(application.getAmount()) + " (" + termsSummary(saved)
                        + "). The application is closed.",
                userId,
                application.getCustomerRef());

        log.info("Customer {} turned down the new terms on loan application {}", userId, applicationId);
        return saved;
    }

    private LoanApplication requireOffered(UUID userId, UUID applicationId) {
        LoanApplication application = applications.findByIdAndUserId(applicationId, userId)
                .orElseThrow(() -> ApiException.notFound("That application"));
        if (application.getStatus() != LoanApplicationStatus.OFFERED) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "There are no new terms waiting for your answer on that application.");
        }
        return application;
    }

    /** What an application is priced on now: agreed changed terms, or the settings. */
    private static LoanService.Pricing currentPricing(LoanApplication application, LendingTerms t) {
        return application.hasAgreedTerms()
                ? agreedPricing(application)
                : LoanService.standardPricing(t, application.getAmount(), application.getTenureMonths());
    }

    private static LoanService.Pricing agreedPricing(LoanApplication application) {
        return new LoanService.Pricing(
                application.getProposedFlatRate(),
                application.getProposedManagementFee(),
                application.getProposedRepaymentFeeRate());
    }

    /** "6 months, 30% interest, ₦5,000.00 management fee, 2% transaction fee on each repayment". */
    private static String termsSummary(LoanApplication application) {
        return application.getProposedMonths() + (application.getProposedMonths() == 1 ? " month, " : " months, ")
                + pct(application.getProposedFlatRate()) + " interest, "
                + Money.naira(application.getProposedManagementFee()) + " management fee, "
                + pct(application.getProposedRepaymentFeeRate()) + " transaction fee on each repayment";
    }

    private static String pct(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private AuditService.Actor customerActor(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("That account"));
        return new AuditService.Actor(userId, user.getFullName(), user.getEmail());
    }

    // -- Plumbing -----------------------------------------------------------

    private LoanApplication requirePending(UUID applicationId) {
        LoanApplication application = get(applicationId);
        if (application.getStatus() == LoanApplicationStatus.OFFERED) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The customer has not answered the new terms yet. It comes back here once they agree.");
        }
        if (!application.isPending()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That application was already " + application.getStatus().label().toLowerCase(
                            Locale.ROOT) + ".");
        }
        return application;
    }

    /**
     * The form, before anything is uploaded.
     *
     * <p>Bean validation covers each field on its own. What it cannot see is
     * how many of a repeated thing arrived, which is what this is for.
     */
    private void validateShape(Submission submission) {
        if (submission.guarantors() == null
                || submission.guarantors().size() != GUARANTORS_REQUIRED) {
            throw ApiException.validation(
                    "A guarantor is needed, with their full details.");
        }
        if (submission.statement() == null || submission.statement().content().length == 0) {
            throw ApiException.validation(
                    "Attach a recent bank statement. We cannot assess an application without one.");
        }
        if (submission.selfie() == null || submission.selfie().content().length == 0) {
            throw ApiException.validation(
                    "Add a clear photograph of yourself. We need to see who we are lending to.");
        }
        if (submission.photos() == null
                || submission.photos().size() != BUSINESS_PHOTOS_REQUIRED) {
            throw ApiException.validation(
                    "Attach " + BUSINESS_PHOTOS_REQUIRED + " photographs of your business premises.");
        }
        validateUpload(submission.statement(), "bank statement");
        validateUpload(submission.selfie(), "photograph of yourself");
        // A face has to be a picture. The statement may be a PDF; this may not.
        String selfieType = submission.selfie().contentType() == null
                ? "" : submission.selfie().contentType().toLowerCase(Locale.ROOT);
        if (!selfieType.startsWith("image/")) {
            throw ApiException.validation(
                    "The photograph of yourself has to be a picture, not a document.");
        }
        for (Upload photo : submission.photos()) {
            validateUpload(photo, "photograph");
        }
    }

    /**
     * Size and type, on the same limits a receipt is held to.
     *
     * <p>Deliberately the same: these are photographs from the same phone
     * camera and PDFs from the same banking app, and a second set of numbers to
     * keep in step would drift from the first.
     */
    private void validateUpload(Upload upload, String what) {
        Kudi9jaProperties.Storage storage = properties.storage();
        if (upload.content().length > storage.maxReceiptBytes()) {
            throw ApiException.validation(
                    "That " + what + " is too large. Attach one under "
                            + (storage.maxReceiptBytes() / (1024 * 1024)) + "MB.");
        }
        String type = upload.contentType() == null
                ? "" : upload.contentType().toLowerCase(Locale.ROOT);
        if (storage.allowedReceiptTypes().stream().noneMatch(type::startsWith)) {
            throw ApiException.validation(
                    "Attach a photo, screenshot or PDF for the " + what + ".");
        }
    }

    /**
     * Puts one document in storage, naming it if that fails.
     *
     * <p>The store was written for pay-in receipts and says "receipt" when it
     * cannot save something. A customer sending a loan application attached
     * no receipt, and "that receipt could not be saved" told them nothing
     * about which of their five files was refused. The log line underneath
     * carries the provider's reason; this carries the document's name.
     */
    private StoredDocument store(User user, Upload upload, String what) {
        String key;
        try {
            key = documents.store(
                    user.getCustomerRef(), upload.filename(), upload.contentType(), upload.content());
        } catch (ApiException e) {
            if (e.code() != ErrorCode.INTERNAL) {
                throw e;
            }
            log.error("Could not store the {} for {}: {} bytes of {} named {}",
                    what, user.getCustomerRef(), upload.content().length,
                    upload.contentType(), upload.filename());
            throw new ApiException(ErrorCode.INTERNAL,
                    "Your " + what + " could not be saved. Try again, or attach a different file.");
        }
        return new StoredDocument(key, upload.contentType(), (long) upload.content().length);
    }
}
