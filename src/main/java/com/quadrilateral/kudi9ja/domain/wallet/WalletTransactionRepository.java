package com.quadrilateral.kudi9ja.domain.wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findByUserIdOrderByOccurredAtDescSequenceDesc(UUID userId, Pageable pageable);

    Page<WalletTransaction> findByUserIdAndKindInOrderByOccurredAtDescSequenceDesc(
            UUID userId, Collection<TxKind> kinds, Pageable pageable);

    List<WalletTransaction> findByUserIdOrderBySequenceAsc(UUID userId);

    List<WalletTransaction> findByRelatedTypeAndRelatedIdOrderBySequenceAsc(String relatedType, UUID relatedId);

    Optional<WalletTransaction> findByReference(String reference);

    /** Every row of one kind except those in the given status, newest first. */
    List<WalletTransaction> findByKindAndStatusNotOrderByOccurredAtDesc(TxKind kind, TxStatus status);

    long countByUserId(UUID userId);

    /**
     * What a customer has already transferred today, for the daily limit.
     * Reversed rows do not count against it — the money came back.
     */
    @Query("""
            select coalesce(sum(t.amount), 0)
              from WalletTransaction t
             where t.userId = :userId
               and t.kind = :kind
               and t.status <> com.quadrilateral.kudi9ja.domain.wallet.TxStatus.REVERSED
               and t.occurredAt >= :from
               and t.occurredAt < :to
            """)
    BigDecimal sumByKindBetween(
            @Param("userId") UUID userId,
            @Param("kind") TxKind kind,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * The balance the ledger says a wallet should hold. Reconciliation compares
     * this against the stored balance; a divergence is a defect, not a rounding
     * quirk, because every row is written at kobo scale.
     */
    @Query("""
            select coalesce(sum(case when t.kind in (
                        com.quadrilateral.kudi9ja.domain.wallet.TxKind.DEPOSIT,
                        com.quadrilateral.kudi9ja.domain.wallet.TxKind.INTEREST_PAYOUT,
                        com.quadrilateral.kudi9ja.domain.wallet.TxKind.SAVINGS_RELEASE,
                        com.quadrilateral.kudi9ja.domain.wallet.TxKind.LOAN_DISBURSEMENT)
                    then t.amount else -t.amount end), 0)
              from WalletTransaction t
             where t.walletId = :walletId
            """)
    BigDecimal replayBalance(@Param("walletId") UUID walletId);

    /** Every naira of interest and bonus this customer has ever been paid. */
    @Query("""
            select coalesce(sum(t.amount), 0)
              from WalletTransaction t
             where t.userId = :userId
               and t.kind = com.quadrilateral.kudi9ja.domain.wallet.TxKind.INTEREST_PAYOUT
               and t.status <> com.quadrilateral.kudi9ja.domain.wallet.TxStatus.REVERSED
            """)
    BigDecimal totalInterestEarned(@Param("userId") UUID userId);

    /** Everything confirmed into the wallet from a bank transfer. */
    @Query("""
            select coalesce(sum(t.amount), 0)
              from WalletTransaction t
             where t.userId = :userId
               and t.kind = com.quadrilateral.kudi9ja.domain.wallet.TxKind.DEPOSIT
               and t.status <> com.quadrilateral.kudi9ja.domain.wallet.TxStatus.REVERSED
            """)
    BigDecimal totalDeposited(@Param("userId") UUID userId);
}
