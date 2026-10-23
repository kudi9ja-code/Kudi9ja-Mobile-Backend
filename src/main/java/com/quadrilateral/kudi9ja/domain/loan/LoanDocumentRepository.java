package com.quadrilateral.kudi9ja.domain.loan;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanDocumentRepository extends JpaRepository<LoanDocument, UUID> {

    List<LoanDocument> findByImportedLoanIdOrderByUploadedAtAsc(UUID importedLoanId);

    List<LoanDocument> findByLoanIdOrderByUploadedAtAsc(UUID loanId);

    List<LoanDocument> findByImportedLoanIdInOrLoanIdInOrderByUploadedAtAsc(
            Collection<UUID> importedLoanIds, Collection<UUID> loanIds);

    long countByImportedLoanId(UUID importedLoanId);
}
