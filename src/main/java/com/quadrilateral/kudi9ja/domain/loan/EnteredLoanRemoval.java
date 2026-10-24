package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.group.LoanGroupMemberRepository;
import com.quadrilateral.kudi9ja.domain.notification.NotificationPreferenceRepository;
import com.quadrilateral.kudi9ja.domain.notification.NotificationRepository;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerAssignmentRepository;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.WalletRepository;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Undoes a loan customer entered by mistake.
 *
 * <p>Entering a loan customer opens an account for them at once. While that
 * account is still not activated and nothing has been repaid since the entry,
 * the whole entry can be taken back: the loan, its file, and — if this was the
 * only thing on it — the account itself. After that the loan is real history,
 * closed by repayment or write-off like any other.
 */
@Service
public class EnteredLoanRemoval {

    private final ImportedLoanRepository imports;
    private final LoanRepository loans;
    private final RecordedRepaymentRepository recorded;
    private final LoanDocumentRepository documents;
    private final LoanOfficerAssignmentRepository officers;
    private final LoanGroupMemberRepository groupMembers;
    private final UserRepository users;
    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;

    public EnteredLoanRemoval(
            ImportedLoanRepository imports,
            LoanRepository loans,
            RecordedRepaymentRepository recorded,
            LoanDocumentRepository documents,
            LoanOfficerAssignmentRepository officers,
            LoanGroupMemberRepository groupMembers,
            UserRepository users,
            WalletRepository wallets,
            WalletTransactionRepository transactions,
            NotificationRepository notifications,
            NotificationPreferenceRepository preferences) {
        this.imports = imports;
        this.loans = loans;
        this.recorded = recorded;
        this.documents = documents;
        this.officers = officers;
        this.groupMembers = groupMembers;
        this.users = users;
        this.wallets = wallets;
        this.transactions = transactions;
        this.notifications = notifications;
        this.preferences = preferences;
    }

    /** @return whether the account itself went too, because nothing else was on it */
    @Transactional
    public boolean remove(ImportedLoan row) {
        User owner = row.getUserId() == null ? null : users.findById(row.getUserId()).orElse(null);
        if (owner == null || owner.getAccountStatus() != AccountStatus.PENDING_ACTIVATION) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "This customer has signed up, so the loan is part of their account's history. "
                            + "Close it by repayment or a write-off.");
        }
        Loan loan = row.getLoanId() == null ? null : loans.findById(row.getLoanId()).orElse(null);
        boolean repaidSince = loan != null && !Money.eq(loan.getAmountRepaid(), row.getAmountRepaid());
        // Repayments typed in with the loan are part of the entry and go with it.
        List<RecordedRepayment> onIt = new ArrayList<>(recorded.findByImportedLoanIdOrderByPaidAtAsc(row.getId()));
        if (loan != null) {
            recorded.findByLoanIdOrderByPaidAtAsc(loan.getId()).stream()
                    .filter(r -> onIt.stream().noneMatch(o -> o.getId().equals(r.getId())))
                    .forEach(onIt::add);
        }
        if (repaidSince || onIt.stream().anyMatch(r -> !r.isEnteredWithLoan())) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "Repayments have been recorded on this loan since it was entered, so it cannot be "
                            + "taken back. Write it off instead if it should not be collected.");
        }

        recorded.deleteAll(onIt);
        recorded.flush();
        documents.deleteAll(documents.findByImportedLoanIdOrderByUploadedAtAsc(row.getId()));
        officers.deleteAll(officers.findByImportedLoanIdOrderByLeadDescAssignedAtAsc(row.getId()));
        groupMembers.deleteAll(groupMembers.findByImportedLoanId(row.getId()));
        // The file points at the loan, so the file goes first.
        imports.delete(row);
        imports.flush();
        if (loan != null) {
            documents.deleteAll(documents.findByLoanIdOrderByUploadedAtAsc(loan.getId()));
            loans.delete(loan);
            loans.flush();
        }

        // The account goes too if this entry was all it held.
        boolean nothingElse = loans.findByUserIdOrderByRequestedAtDesc(owner.getId()).isEmpty()
                && imports.findByUserId(owner.getId()).isEmpty();
        if (!nothingElse) {
            return false;
        }
        officers.deleteAll(officers.findByUserIdOrderByLeadDescAssignedAtAsc(owner.getId()));
        groupMembers.deleteAll(groupMembers.findByUserId(owner.getId()));
        notifications.deleteAll(notifications.findByUserIdOrderByCreatedAtDesc(owner.getId()));
        preferences.deleteAll(preferences.findByUserId(owner.getId()));
        wallets.findByUserId(owner.getId()).ifPresent(wallet -> {
            if (transactions.countByUserId(owner.getId()) == 0) {
                wallets.delete(wallet);
            }
        });
        if (wallets.findByUserId(owner.getId()).isPresent()) {
            // Money has moved on it somehow; keep the account rather than lose a record.
            return false;
        }
        users.delete(owner);
        return true;
    }
}
