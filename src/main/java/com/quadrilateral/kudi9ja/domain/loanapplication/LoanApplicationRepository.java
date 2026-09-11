package com.quadrilateral.kudi9ja.domain.loanapplication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanApplicationRepository extends JpaRepository<LoanApplication, UUID> {

    Page<LoanApplication> findByStatusOrderBySubmittedAtDesc(
            LoanApplicationStatus status, Pageable pageable);

    Page<LoanApplication> findAllByOrderBySubmittedAtDesc(Pageable pageable);

    List<LoanApplication> findByUserIdOrderBySubmittedAtDesc(UUID userId);

    Optional<LoanApplication> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Whether this customer already has one waiting.
     *
     * <p>One at a time. A second application while the first is unread is not a
     * second request for money — it is the same request, sent again because
     * nothing visible happened, and it doubles the queue an admin has to read
     * without adding anything to it.
     */
    boolean existsByUserIdAndStatus(UUID userId, LoanApplicationStatus status);

    long countByStatus(LoanApplicationStatus status);
}
