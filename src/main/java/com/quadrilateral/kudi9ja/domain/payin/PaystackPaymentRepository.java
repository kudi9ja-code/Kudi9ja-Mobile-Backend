package com.quadrilateral.kudi9ja.domain.payin;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaystackPaymentRepository extends JpaRepository<PaystackPayment, UUID> {

    Optional<PaystackPayment> findByReference(String reference);

    Optional<PaystackPayment> findByReferenceAndUserId(String reference, UUID userId);

    /** Taken under a write lock, so the return from checkout and the webhook cannot both credit. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaystackPayment p where p.reference = :reference")
    Optional<PaystackPayment> lockByReference(@Param("reference") String reference);

    Page<PaystackPayment> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<PaystackPayment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PaystackPayment> findByStatusOrderByCreatedAtDesc(PaystackPayment.Status status, Pageable pageable);
}
