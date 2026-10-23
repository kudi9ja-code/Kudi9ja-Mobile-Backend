package com.quadrilateral.kudi9ja.domain.loan;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordedRepaymentRepository extends JpaRepository<RecordedRepayment, UUID> {

    List<RecordedRepayment> findByImportedLoanIdOrderByPaidAtAsc(UUID importedLoanId);

    List<RecordedRepayment> findByLoanIdOrderByPaidAtAsc(UUID loanId);

    List<RecordedRepayment> findByImportedLoanIdInOrLoanIdInOrderByPaidAtAsc(
            Collection<UUID> importedLoanIds, Collection<UUID> loanIds);
}
