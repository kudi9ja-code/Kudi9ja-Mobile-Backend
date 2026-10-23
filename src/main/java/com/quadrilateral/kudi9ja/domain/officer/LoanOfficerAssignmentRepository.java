package com.quadrilateral.kudi9ja.domain.officer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanOfficerAssignmentRepository extends JpaRepository<LoanOfficerAssignment, UUID> {

    List<LoanOfficerAssignment> findByUserIdOrderByLeadDescAssignedAtAsc(UUID userId);

    List<LoanOfficerAssignment> findByImportedLoanIdOrderByLeadDescAssignedAtAsc(UUID importedLoanId);

    List<LoanOfficerAssignment> findByUserIdIn(Collection<UUID> userIds);

    List<LoanOfficerAssignment> findByImportedLoanIdIn(Collection<UUID> importedLoanIds);

    List<LoanOfficerAssignment> findByOfficerIdOrderByAssignedAtDesc(UUID officerId);

    long countByUserId(UUID userId);

    long countByImportedLoanId(UUID importedLoanId);

    long countByOfficerId(UUID officerId);
}
