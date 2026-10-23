package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.storage.ReceiptStorage;
import com.quadrilateral.kudi9ja.web.dto.LoanImportDtos;
import com.quadrilateral.kudi9ja.web.dto.OfflineLoanDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The file behind a loan made outside the app, and the money that comes back
 * the same way.
 *
 * <p>Three things an admin does here that the paper notebook used to hold:
 *
 * <ul>
 *   <li>keep the borrower's details — address, business, guarantors — on the
 *       loan while it waits for its borrower to sign up;</li>
 *   <li>attach documents: the statement, the ID, the photographs, the signed
 *       form;</li>
 *   <li>record repayments that arrived by hand. On a waiting loan the amount
 *       repaid moves; on a loan already on an account the repayment is
 *       applied to it exactly as a wallet repayment would be, minus the
 *       wallet. Neither touches the ledger: the money never passed through
 *       one.</li>
 * </ul>
 *
 * <p>Every change is written to the audit log with the admin's name.
 */
@Service
public class OfflineLoanService {

    private static final Logger log = LoggerFactory.getLogger(OfflineLoanService.class);

    /** An empty IN list renders differently across databases; one id that matches nothing is portable. */
    private static final UUID NONE = new UUID(0L, 0L);

    private final ImportedLoanRepository imports;
    private final LoanRepository loans;
    private final LoanService loanService;
    private final LoanDocumentRepository documents;
    private final RecordedRepaymentRepository repayments;
    private final UserRepository users;
    private final AdminAccessService access;
    private final AuditService audit;
    private final ReceiptStorage storage;
    private final Kudi9jaProperties properties;

    public OfflineLoanService(
            ImportedLoanRepository imports,
            LoanRepository loans,
            LoanService loanService,
            LoanDocumentRepository documents,
            RecordedRepaymentRepository repayments,
            UserRepository users,
            AdminAccessService access,
            AuditService audit,
            ReceiptStorage storage,
            Kudi9jaProperties properties) {
        this.imports = imports;
        this.loans = loans;
        this.loanService = loanService;
        this.documents = documents;
        this.repayments = repayments;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.storage = storage;
        this.properties = properties;
    }

    public record Upload(String filename, String contentType, byte[] content) {
    }

    // ── The borrower's file ───────────────────────────────────────────────

    /** Copies the optional file fields of an import request onto a new row. */
    static void applyFile(ImportedLoan row, LoanImportDtos.ImportLoanRequest request) {
        row.setNin(blankToNull(request.nin()));
        row.setDateOfBirth(request.dateOfBirth());
        row.setGender(blankToNull(request.gender()));
        row.setAddress(blankToNull(request.address()));
        row.setState(blankToNull(request.state()));
        row.setOccupation(blankToNull(request.occupation()));
        row.setBusinessName(blankToNull(request.businessName()));
        row.setBusinessAddress(blankToNull(request.businessAddress()));
        row.setMonthlyIncome(request.monthlyIncome() == null ? null : Money.of(request.monthlyIncome()));
        row.setPayoutBank(blankToNull(request.payoutBank()));
        row.setPayoutAccountNumber(blankToNull(request.payoutAccountNumber()));
        row.setNotes(blankToNull(request.notes()));
        row.setGuarantors(new ArrayList<>(request.guarantors() == null
                ? List.of()
                : request.guarantors().stream().map(LoanImportDtos.OfflineGuarantorPayload::toEntity).toList()));
    }

    @Transactional(readOnly = true)
    public OfflineLoanDtos.OfflineLoanFile file(UUID importId) {
        access.requireCanView();
        ImportedLoan row = requireImport(importId);
        return toFile(row);
    }

    /**
     * Corrects the file. Identity fields — name, email, phone, NIN, date of
     * birth, payout account — can only change while the loan is waiting: once
     * claimed, the account is the record of who the borrower is. The rest is
     * the loan's file and stays editable.
     */
    @Transactional
    public OfflineLoanDtos.OfflineLoanFile update(UUID importId, OfflineLoanDtos.OfflineLoanUpdate u) {
        AdminUser actor = access.requireCanActOnLoans();
        ImportedLoan row = requireImport(importId);

        boolean touchesIdentity = u.bvn() != null || u.fullName() != null || u.email() != null || u.phone() != null
                || u.nin() != null || u.dateOfBirth() != null || u.payoutBank() != null
                || u.payoutAccountNumber() != null;
        // Who the customer is lives on their account. While that account is one
        // the team opened and the customer has not yet taken over, the team may
        // still correct it — a misspelt name, a wrong phone number. Once the
        // customer has signed up, those details are theirs.
        User waiting = row.isClaimed() && row.getUserId() != null
                ? users.findById(row.getUserId())
                        .filter(owner -> owner.getAccountStatus() == AccountStatus.PENDING_ACTIVATION)
                        .orElse(null)
                : null;
        if (row.isClaimed() && touchesIdentity && waiting == null) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "This customer has signed up, so who they are is theirs to keep up to date now. "
                            + "Only the loan's file — address, business, guarantors, notes — can still change here.");
        }
        if (waiting != null) {
            applyToAccount(waiting, u);
        }

        List<String> changed = new ArrayList<>();
        if (u.fullName() != null && !u.fullName().isBlank()) {
            row.setFullName(u.fullName().trim());
            changed.add("name");
        }
        if (u.email() != null) {
            row.setEmail(u.email().isBlank() ? null : u.email().trim().toLowerCase(Locale.ROOT));
            changed.add("email");
        }
        if (u.phone() != null) {
            row.setPhone(blankToNull(u.phone()));
            changed.add("phone");
        }
        if (u.nin() != null) {
            row.setNin(blankToNull(u.nin()));
            changed.add("NIN");
        }
        if (u.dateOfBirth() != null) {
            row.setDateOfBirth(u.dateOfBirth());
            changed.add("date of birth");
        }
        if (u.payoutBank() != null) {
            row.setPayoutBank(blankToNull(u.payoutBank()));
            changed.add("payout bank");
        }
        if (u.payoutAccountNumber() != null) {
            row.setPayoutAccountNumber(blankToNull(u.payoutAccountNumber()));
            changed.add("payout account");
        }
        if (u.gender() != null) {
            row.setGender(blankToNull(u.gender()));
            changed.add("gender");
        }
        if (u.address() != null) {
            row.setAddress(blankToNull(u.address()));
            changed.add("address");
        }
        if (u.state() != null) {
            row.setState(blankToNull(u.state()));
            changed.add("state");
        }
        if (u.occupation() != null) {
            row.setOccupation(blankToNull(u.occupation()));
            changed.add("occupation");
        }
        if (u.businessName() != null) {
            row.setBusinessName(blankToNull(u.businessName()));
            changed.add("business name");
        }
        if (u.businessAddress() != null) {
            row.setBusinessAddress(blankToNull(u.businessAddress()));
            changed.add("business address");
        }
        if (u.monthlyIncome() != null) {
            row.setMonthlyIncome(Money.of(u.monthlyIncome()));
            changed.add("monthly income");
        }
        if (u.purpose() != null && !u.purpose().isBlank()) {
            row.setPurpose(u.purpose().trim());
            changed.add("purpose");
        }
        if (u.notes() != null) {
            row.setNotes(blankToNull(u.notes()));
            changed.add("notes");
        }
        if (u.guarantors() != null) {
            row.getGuarantors().clear();
            row.getGuarantors().addAll(u.guarantors().stream().map(LoanImportDtos.OfflineGuarantorPayload::toEntity).toList());
            changed.add("guarantors");
        }

        if (changed.isEmpty()) {
            return toFile(row);
        }
        ImportedLoan saved = imports.save(row);
        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Offline loan file updated",
                actor.getName() + " updated the " + String.join(", ", changed) + " on the file for "
                        + saved.getFullName() + "'s " + Money.naira(saved.getPrincipal()) + " loan.",
                saved.getId(),
                "Imported loan " + saved.getId());
        return toFile(saved);
    }

    /** Copies the corrections onto an account the customer has not taken over yet. */
    private void applyToAccount(User user, OfflineLoanDtos.OfflineLoanUpdate u) {
        if (u.bvn() != null && !u.bvn().equals(user.getBvn())) {
            if (users.existsByBvn(u.bvn())) {
                throw new ApiException(ErrorCode.BVN_TAKEN, "That BVN already belongs to another customer.");
            }
            user.setBvn(u.bvn());
        }
        if (u.fullName() != null && !u.fullName().isBlank()) {
            user.setFullName(u.fullName().trim());
        }
        if (u.email() != null) {
            String email = u.email().isBlank() ? null : u.email().trim().toLowerCase(Locale.ROOT);
            if (email != null && users.findByEmailIgnoreCase(email).filter(o -> !o.getId().equals(user.getId())).isPresent()) {
                throw new ApiException(ErrorCode.EMAIL_TAKEN, "That email address already belongs to another customer.");
            }
            user.setEmail(email);
        }
        if (u.phone() != null) {
            String phone = blankToNull(u.phone());
            if (phone != null && users.findByPhone(phone).filter(o -> !o.getId().equals(user.getId())).isPresent()) {
                throw new ApiException(ErrorCode.PHONE_TAKEN, "That phone number already belongs to another customer.");
            }
            user.setPhone(phone);
        }
        if (u.nin() != null) {
            String nin = blankToNull(u.nin());
            if (nin != null && !nin.equals(user.getNin()) && users.existsByNin(nin)) {
                throw new ApiException(ErrorCode.NIN_TAKEN, "That NIN already belongs to another customer.");
            }
            user.setNin(nin);
        }
        if (u.dateOfBirth() != null) {
            user.setDateOfBirth(u.dateOfBirth());
        }
        if (u.gender() != null) {
            user.setGender(blankToNull(u.gender()));
        }
        if (u.address() != null) {
            user.setAddress(blankToNull(u.address()));
        }
        if (u.state() != null) {
            user.setState(blankToNull(u.state()));
        }
        if (u.payoutBank() != null) {
            user.setPayoutBank(blankToNull(u.payoutBank()));
        }
        if (u.payoutAccountNumber() != null) {
            user.setPayoutAccountNumber(blankToNull(u.payoutAccountNumber()));
        }
        users.save(user);
    }

    // ── Documents ─────────────────────────────────────────────────────────

    @Transactional
    public OfflineLoanDtos.OfflineDocument attachToImport(UUID importId, String label, Upload upload) {
        AdminUser actor = access.requireCanActOnLoans();
        ImportedLoan row = requireImport(importId);
        LoanDocument doc = store(actor, "IMPORT-" + row.getId().toString().substring(0, 8).toUpperCase(Locale.ROOT), label, upload);
        doc.setImportedLoanId(row.getId());
        LoanDocument saved = documents.save(doc);
        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Document attached to a loan file",
                actor.getName() + " attached \"" + saved.getLabel() + "\" to the file for "
                        + row.getFullName() + "'s " + Money.naira(row.getPrincipal()) + " loan.",
                row.getId(),
                "Imported loan " + row.getId());
        return OfflineLoanDtos.OfflineDocument.from(saved, this::documentUrl);
    }

    @Transactional
    public OfflineLoanDtos.OfflineDocument attachToLoan(UUID loanId, String label, Upload upload) {
        AdminUser actor = access.requireCanActOnLoans();
        Loan loan = requireLoan(loanId);
        String ref = users.findById(loan.getUserId()).map(u -> u.getCustomerRef()).orElse("LOAN");
        LoanDocument doc = store(actor, ref, label, upload);
        doc.setLoanId(loan.getId());
        LoanDocument saved = documents.save(doc);
        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Document attached to a loan file",
                actor.getName() + " attached \"" + saved.getLabel() + "\" to the "
                        + Money.naira(loan.getPrincipal()) + " " + loan.getPurpose() + " loan of " + ref + ".",
                loan.getId(),
                "Loan " + loan.getId());
        return OfflineLoanDtos.OfflineDocument.from(saved, this::documentUrl);
    }

    @Transactional
    public void removeDocument(UUID documentId) {
        AdminUser actor = access.requireCanActOnLoans();
        LoanDocument doc = documents.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("That document"));
        documents.delete(doc);
        try {
            storage.delete(doc.getStorageKey());
        } catch (RuntimeException e) {
            // The row is gone, which is what the panel reads. An orphaned object
            // in storage is a housekeeping matter, not a reason to refuse.
            log.warn("Could not delete stored document {}: {}", doc.getStorageKey(), e.getMessage());
        }
        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Document removed from a loan file",
                actor.getName() + " removed \"" + doc.getLabel() + "\" ("
                        + (doc.getOriginalFilename() == null ? "unnamed file" : doc.getOriginalFilename()) + ").",
                doc.getImportedLoanId() != null ? doc.getImportedLoanId() : doc.getLoanId(),
                doc.getImportedLoanId() != null ? "Imported loan " + doc.getImportedLoanId() : "Loan " + doc.getLoanId());
    }

    // ── Repayments that arrived by hand ───────────────────────────────────

    /**
     * Records a repayment against an imported loan. While the loan waits, its
     * amount repaid moves. Once it is on an account, the repayment goes to that
     * loan the way any repayment does — status, settlement and the customer's
     * notification included.
     */
    @Transactional
    public OfflineLoanDtos.OfflineLoanFile recordOnImport(UUID importId, OfflineLoanDtos.OfflineRepaymentRequest request) {
        AdminUser actor = access.requireCanActOnLoans();
        ImportedLoan row = requireImport(importId);
        BigDecimal amount = Money.of(request.amount());
        Instant paidAt = paidAt(request, row.getDisbursedAt());

        if (row.isClaimed()) {
            Loan loan = requireLoan(row.getLoanId());
            applyToLoan(loan, amount, request);
        } else {
            if (Money.gt(amount, row.outstanding())) {
                throw ApiException.validation("That is more than the " + Money.naira(row.outstanding())
                        + " still owed on this loan.");
            }
            row.setAmountRepaid(Money.add(row.getAmountRepaid(), amount));
            imports.save(row);
        }

        RecordedRepayment saved = saveRepayment(actor, row.getId(), row.getLoanId(), amount, paidAt, request);
        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Repayment recorded by hand",
                actor.getName() + " recorded " + Money.naira(saved.getAmount()) + " paid by "
                        + methodLabel(saved.getMethod()) + " on " + row.getFullName() + "'s "
                        + Money.naira(row.getPrincipal()) + " loan"
                        + (saved.getReference() == null ? "" : " (reference " + saved.getReference() + ")") + ".",
                row.getId(),
                "Imported loan " + row.getId());
        return toFile(requireImport(importId));
    }

    /** Records a repayment that arrived outside the app against any loan on an account. */
    @Transactional
    public OfflineLoanDtos.LoanRecords recordOnLoan(UUID loanId, OfflineLoanDtos.OfflineRepaymentRequest request) {
        AdminUser actor = access.requireCanActOnLoans();
        Loan loan = requireLoan(loanId);
        BigDecimal amount = Money.of(request.amount());
        Instant paidAt = paidAt(request, loan.getDisbursedAt());
        applyToLoan(loan, amount, request);

        UUID importId = imports.findByLoanId(loanId).map(ImportedLoan::getId).orElse(null);
        RecordedRepayment saved = saveRepayment(actor, importId, loanId, amount, paidAt, request);
        String ref = users.findById(loan.getUserId()).map(u -> u.getFullName() + " (" + u.getCustomerRef() + ")").orElse("the borrower");
        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Repayment recorded by hand",
                actor.getName() + " recorded " + Money.naira(saved.getAmount()) + " paid by "
                        + methodLabel(saved.getMethod()) + " on " + ref + "'s "
                        + Money.naira(loan.getPrincipal()) + " loan"
                        + (saved.getReference() == null ? "" : " (reference " + saved.getReference() + ")") + ".",
                loan.getId(),
                "Loan " + loan.getId());
        return records(loanId);
    }

    @Transactional(readOnly = true)
    public OfflineLoanDtos.LoanRecords records(UUID loanId) {
        access.requireCanView();
        Loan loan = requireLoan(loanId);
        UUID importId = imports.findByLoanId(loan.getId()).map(ImportedLoan::getId).orElse(null);
        List<UUID> importIds = importId == null ? List.of(NONE) : List.of(importId);
        return new OfflineLoanDtos.LoanRecords(
                loan.getId(),
                importId,
                documents.findByImportedLoanIdInOrLoanIdInOrderByUploadedAtAsc(importIds, List.of(loan.getId())).stream()
                        .map(d -> OfflineLoanDtos.OfflineDocument.from(d, this::documentUrl))
                        .toList(),
                repayments.findByImportedLoanIdInOrLoanIdInOrderByPaidAtAsc(importIds, List.of(loan.getId())).stream()
                        .map(OfflineLoanDtos.OfflineRepayment::from)
                        .toList());
    }

    /** Everything recorded by hand about one customer: their imported loans, documents and repayments. */
    @Transactional(readOnly = true)
    public OfflineLoanDtos.CustomerLoanRecords forCustomer(UUID userId) {
        access.requireCanView();
        List<ImportedLoan> theirs = imports.findByUserId(userId);
        List<UUID> importIds = new ArrayList<>(theirs.stream().map(ImportedLoan::getId).toList());
        List<UUID> loanIds = new ArrayList<>(loans.findByUserIdOrderByRequestedAtDesc(userId).stream().map(Loan::getId).toList());
        if (importIds.isEmpty()) {
            importIds.add(NONE);
        }
        if (loanIds.isEmpty()) {
            loanIds.add(NONE);
        }
        return new OfflineLoanDtos.CustomerLoanRecords(
                theirs.stream().map(row -> LoanImportDtos.ImportedLoanResponse.from(row, liveLoan(row), owner(row))).toList(),
                documents.findByImportedLoanIdInOrLoanIdInOrderByUploadedAtAsc(importIds, loanIds).stream()
                        .map(d -> OfflineLoanDtos.OfflineDocument.from(d, this::documentUrl))
                        .toList(),
                repayments.findByImportedLoanIdInOrLoanIdInOrderByPaidAtAsc(importIds, loanIds).stream()
                        .map(OfflineLoanDtos.OfflineRepayment::from)
                        .toList());
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void applyToLoan(Loan loan, BigDecimal amount, OfflineLoanDtos.OfflineRepaymentRequest request) {
        if (!loan.isOpen()) {
            throw new ApiException(ErrorCode.LOAN_CLOSED,
                    "That loan is " + loan.getStatus().label().toLowerCase(Locale.ROOT) + ". There is nothing left to repay.");
        }
        if (Money.gt(amount, loan.outstanding())) {
            throw ApiException.validation("That is more than the " + Money.naira(loan.outstanding())
                    + " still owed on this loan.");
        }
        loanService.applyRepayment(loan, amount, "Repayment received by " + methodLabel(request.method()), false);
    }

    private RecordedRepayment saveRepayment(
            AdminUser actor, UUID importId, UUID loanId, BigDecimal amount, Instant paidAt,
            OfflineLoanDtos.OfflineRepaymentRequest request) {
        RecordedRepayment r = new RecordedRepayment();
        r.setId(UUID.randomUUID());
        r.setImportedLoanId(importId);
        r.setLoanId(loanId);
        r.setAmount(amount);
        r.setPaidAt(paidAt);
        r.setMethod(request.method());
        r.setReference(blankToNull(request.reference()));
        r.setNote(blankToNull(request.note()));
        r.setRecordedBy(actor.getName());
        r.setRecordedAt(Instant.now());
        return repayments.save(r);
    }

    private Instant paidAt(OfflineLoanDtos.OfflineRepaymentRequest request, Instant disbursedAt) {
        Instant now = Instant.now();
        Instant paidAt = request.paidAt() == null ? now : request.paidAt();
        if (paidAt.isAfter(now.plusSeconds(60))) {
            throw ApiException.validation("The payment date is in the future.");
        }
        if (disbursedAt != null && paidAt.isBefore(disbursedAt)) {
            throw ApiException.validation("The payment date is before the loan was disbursed.");
        }
        return paidAt;
    }

    private LoanDocument store(AdminUser actor, String ownerRef, String label, Upload upload) {
        Kudi9jaProperties.Storage config = properties.storage();
        if (upload.content() == null || upload.content().length == 0) {
            throw ApiException.validation("Attach a file.");
        }
        if (upload.content().length > config.maxReceiptBytes()) {
            throw ApiException.validation("That file is too large. Attach one under "
                    + (config.maxReceiptBytes() / (1024 * 1024)) + "MB.");
        }
        String type = upload.contentType() == null ? "" : upload.contentType().toLowerCase(Locale.ROOT);
        if (config.allowedReceiptTypes().stream().noneMatch(type::startsWith)) {
            throw ApiException.validation("Attach a photo, a PDF, or a Word or Excel document.");
        }
        String cleanLabel = label == null || label.isBlank() ? "Document" : label.trim();
        if (cleanLabel.length() > 120) {
            cleanLabel = cleanLabel.substring(0, 120);
        }
        String key = storage.store(ownerRef, upload.filename(), upload.contentType(), upload.content());

        LoanDocument doc = new LoanDocument();
        doc.setId(UUID.randomUUID());
        doc.setLabel(cleanLabel);
        doc.setStorageKey(key);
        doc.setContentType(upload.contentType());
        doc.setSizeBytes((long) upload.content().length);
        String filename = upload.filename();
        doc.setOriginalFilename(filename == null ? null : filename.length() > 200 ? filename.substring(0, 200) : filename);
        doc.setUploadedBy(actor.getName());
        doc.setUploadedAt(Instant.now());
        return doc;
    }

    private OfflineLoanDtos.OfflineLoanFile toFile(ImportedLoan row) {
        List<UUID> loanIds = row.getLoanId() == null ? List.of(NONE) : List.of(row.getLoanId());
        return new OfflineLoanDtos.OfflineLoanFile(
                LoanImportDtos.ImportedLoanResponse.from(row, liveLoan(row), owner(row)),
                documents.findByImportedLoanIdInOrLoanIdInOrderByUploadedAtAsc(List.of(row.getId()), loanIds).stream()
                        .map(d -> OfflineLoanDtos.OfflineDocument.from(d, this::documentUrl))
                        .toList(),
                repayments.findByImportedLoanIdInOrLoanIdInOrderByPaidAtAsc(List.of(row.getId()), loanIds).stream()
                        .map(OfflineLoanDtos.OfflineRepayment::from)
                        .toList());
    }

    private User owner(ImportedLoan row) {
        return row.getUserId() == null ? null : users.findById(row.getUserId()).orElse(null);
    }

    private Loan liveLoan(ImportedLoan row) {
        return row.getLoanId() == null ? null : loans.findById(row.getLoanId()).orElse(null);
    }

    private String documentUrl(String key) {
        return storage.signedUrl(key, properties.storage().signedUrlTtl());
    }

    private ImportedLoan requireImport(UUID id) {
        return imports.findById(id).orElseThrow(() -> ApiException.notFound("That loan entry"));
    }

    private Loan requireLoan(UUID id) {
        return loans.findById(id).orElseThrow(() -> ApiException.notFound("That loan"));
    }

    private static String methodLabel(String method) {
        return switch (method) {
            case "CASH" -> "cash";
            case "BANK_TRANSFER" -> "bank transfer";
            case "POS" -> "POS";
            case "CHEQUE" -> "cheque";
            default -> "another method";
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
