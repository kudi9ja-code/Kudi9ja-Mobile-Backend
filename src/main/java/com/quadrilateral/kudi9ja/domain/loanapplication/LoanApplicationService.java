package com.quadrilateral.kudi9ja.domain.loanapplication;

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
import com.quadrilateral.kudi9ja.domain.user.AuthService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
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

    /** Exactly two people vouch for a borrower. */
    public static final int GUARANTORS_REQUIRED = 2;

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

    public LoanApplicationService(
            LoanApplicationRepository applications,
            UserRepository users,
            LoanService loans,
            AuthService auth,
            NotificationService notifications,
            AuditService audit,
            ReceiptStorage documents,
            Kudi9jaProperties properties) {
        this.applications = applications;
        this.users = users;
        this.loans = loans;
        this.auth = auth;
        this.notifications = notifications;
        this.audit = audit;
        this.documents = documents;
        this.properties = properties;
    }

    // -- Applying -----------------------------------------------------------

    /**
     * Everything one application arrives with.
     *
     * @param statement the bank statement, already read off the request
     * @param photos    pictures of the business premises
     */
    public record Submission(
            BigDecimal amount,
            int months,
            String purpose,
            String businessName,
            String businessAddress,
            BigDecimal monthlyIncome,
            List<Guarantor> guarantors,
            Upload statement,
            List<Upload> photos,
            String pin) {
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

        if (applications.existsByUserIdAndStatus(userId, LoanApplicationStatus.PENDING)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "You already have an application with us. We will come back to you on that one "
                            + "before you send another.");
        }

        validateShape(submission);

        // The hard limits — verified identity, a priced tenure, the amount
        // bounds. These refuse now rather than after the documents are in.
        LoanService.Assessed assessed = loans.assess(userId, submission.amount(), submission.months());

        LoanApplication application = new LoanApplication();
        application.setId(UUID.randomUUID());
        application.setUserId(userId);
        application.setCustomerName(user.getFullName());
        application.setCustomerRef(user.getCustomerRef());
        application.setAmount(Money.of(submission.amount()));
        application.setTenureMonths(submission.months());
        application.setPurpose(submission.purpose().trim());
        application.setBusinessName(submission.businessName().trim());
        application.setBusinessAddress(submission.businessAddress().trim());
        application.setMonthlyIncome(
                submission.monthlyIncome() == null ? null : Money.of(submission.monthlyIncome()));
        application.setGuarantors(new ArrayList<>(submission.guarantors()));
        application.setScoreAtSubmission(assessed.assessment().score());
        application.setSubmittedAt(Instant.now());
        application.setStatus(LoanApplicationStatus.PENDING);

        // Now the files, once there is something for them to belong to.
        application.setBankStatement(store(user, submission.statement()));
        List<StoredDocument> photos = new ArrayList<>();
        for (Upload photo : submission.photos()) {
            photos.add(store(user, photo));
        }
        application.setBusinessPhotos(photos);

        LoanApplication saved = applications.save(application);

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Application received",
                "Your application to borrow " + Money.naira(saved.getAmount()) + " is with our team. "
                        + "We read the statement, the pictures and both guarantors before deciding, "
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
     * <p>The assessment is taken again here rather than read off the row. An
     * application may have sat in the queue for days, during which the customer
     * could have taken another loan, been frozen, or dropped a tier — and
     * lending on a week-old view of an account is how two loans get written
     * against headroom for one.
     */
    @Transactional
    public LoanApplication approve(UUID applicationId, AuditService.Actor actor, String note) {
        LoanApplication application = requirePending(applicationId);

        LoanService.Assessed assessed = loans.assess(
                application.getUserId(), application.getAmount(), application.getTenureMonths());

        Loan loan = loans.disburse(
                application.getUserId(),
                assessed,
                application.getPurpose(),
                actor.name());

        application.setStatus(LoanApplicationStatus.APPROVED);
        application.setReviewedAt(Instant.now());
        application.setReviewedBy(actor.describe());
        application.setLoanId(loan.getId());
        LoanApplication saved = applications.save(application);

        // No notification here: disburse() already tells the customer the money
        // has landed, with the figures on it. Two in the same second about the
        // same event reads as a fault.

        audit.record(
                actor,
                AuditCategory.LOAN,
                "Loan application approved",
                actor.name() + " approved " + Money.naira(application.getAmount()) + " for "
                        + application.getCustomerName() + " over " + application.getTenureMonths()
                        + " months"
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
        LoanApplication application = requirePending(applicationId);

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

    // -- Plumbing -----------------------------------------------------------

    private LoanApplication requirePending(UUID applicationId) {
        LoanApplication application = get(applicationId);
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
                    "Two guarantors are needed, with full details for both.");
        }
        if (submission.statement() == null || submission.statement().content().length == 0) {
            throw ApiException.validation(
                    "Attach a recent bank statement. We cannot assess an application without one.");
        }
        if (submission.photos() == null
                || submission.photos().size() != BUSINESS_PHOTOS_REQUIRED) {
            throw ApiException.validation(
                    "Attach " + BUSINESS_PHOTOS_REQUIRED + " photographs of your business premises.");
        }
        validateUpload(submission.statement(), "bank statement");
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

    private StoredDocument store(User user, Upload upload) {
        String key = documents.store(
                user.getCustomerRef(), upload.filename(), upload.contentType(), upload.content());
        return new StoredDocument(key, upload.contentType(), (long) upload.content().length);
    }
}
