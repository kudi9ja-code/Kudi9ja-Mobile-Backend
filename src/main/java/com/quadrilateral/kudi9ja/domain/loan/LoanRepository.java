package com.quadrilateral.kudi9ja.domain.loan;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    List<Loan> findByUserIdOrderByRequestedAtDesc(UUID userId);

    Page<Loan> findByUserIdOrderByRequestedAtDesc(UUID userId, Pageable pageable);

    Optional<Loan> findByIdAndUserId(UUID id, UUID userId);

    List<Loan> findByUserIdAndStatusIn(UUID userId, List<LoanStatus> statuses);

    long countByUserIdAndStatus(UUID userId, LoanStatus status);

    boolean existsByUserIdAndStatus(UUID userId, LoanStatus status);

    /** The hourly overdue sweep: active loans past due with a balance left. */
    @Query("""
            select l from Loan l
             where l.status = com.quadrilateral.kudi9ja.domain.loan.LoanStatus.ACTIVE
               and l.dueDate is not null
               and l.dueDate <= :now
            """)
    List<Loan> findPastDue(@Param("now") Instant now);

    /** Open loans, for the daily repayment reminders. */
    @Query("""
            select l from Loan l
             where l.status in (
                   com.quadrilateral.kudi9ja.domain.loan.LoanStatus.ACTIVE,
                   com.quadrilateral.kudi9ja.domain.loan.LoanStatus.OVERDUE)
            """)
    List<Loan> findAllOpen();

    /** The whole book, newest first. What the panel's lending screen lists. */
    List<Loan> findAllByOrderByRequestedAtDesc();

    List<Loan> findByStatusOrderByRequestedAtDesc(LoanStatus status);

    long countByStatus(LoanStatus status);

    @Query("""
            select coalesce(sum(l.principal - l.amountRepaid - l.rebateGranted), 0)
              from Loan l
             where l.status = com.quadrilateral.kudi9ja.domain.loan.LoanStatus.OVERDUE
            """)
    BigDecimal totalOverdueAcrossBook();

    @Query("""
            select coalesce(sum(l.principal), 0)
              from Loan l
             where l.status in (
                   com.quadrilateral.kudi9ja.domain.loan.LoanStatus.ACTIVE,
                   com.quadrilateral.kudi9ja.domain.loan.LoanStatus.OVERDUE)
            """)
    BigDecimal totalLentAcrossBook();

    /**
     * Interest charged on the loans still running: what the open book earns if
     * it all comes back. Each loan is priced at the rate it was written at, so
     * this is summed per loan rather than taken off the current rate card.
     */
    @Query("""
            select coalesce(sum(l.principal * l.flatRate), 0)
              from Loan l
             where l.status in (
                   com.quadrilateral.kudi9ja.domain.loan.LoanStatus.ACTIVE,
                   com.quadrilateral.kudi9ja.domain.loan.LoanStatus.OVERDUE)
            """)
    BigDecimal totalInterestChargedAcrossBook();
}
