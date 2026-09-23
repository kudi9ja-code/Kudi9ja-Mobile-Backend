package com.quadrilateral.kudi9ja.domain.loan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedLoanRepository extends JpaRepository<ImportedLoan, UUID> {

    /** What is waiting for this BVN, oldest disbursement first. */
    List<ImportedLoan> findByBvnAndClaimedAtIsNullOrderByDisbursedAtAsc(String bvn);

    /** Rows that name this email but were entered against a different BVN. */
    List<ImportedLoan> findByEmailIgnoreCaseAndClaimedAtIsNull(String email);

    List<ImportedLoan> findByClaimedAtIsNullOrderByImportedAtDesc();

    List<ImportedLoan> findByClaimedAtIsNotNullOrderByClaimedAtDesc();

    List<ImportedLoan> findAllByOrderByImportedAtDesc();

    List<ImportedLoan> findByUserId(UUID userId);
}
