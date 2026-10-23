package com.quadrilateral.kudi9ja.domain.wallet;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.common.util.Reference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way money moves.
 *
 * <p>Nothing else in the system touches a balance. Every credit and debit goes
 * through here, takes the wallet under a write lock, checks the balance inside
 * that lock, writes one append-only ledger row carrying the resulting balance,
 * and leaves. That is what makes the invariant hold: <b>the balance is the
 * running total of the ledger and is reconstructible from it.</b>
 *
 * <p>Callers pass a {@code relatedType} and {@code relatedId} so a plan, loan
 * or claim can show its own legs of the ledger without a text search on the
 * description.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;

    public LedgerService(WalletRepository wallets, WalletTransactionRepository transactions) {
        this.wallets = wallets;
        this.transactions = transactions;
    }

    /** Opens the wallet a new account starts with: zero, and an empty ledger. */
    @Transactional
    public Wallet openWallet(UUID userId) {
        return wallets.findByUserId(userId).orElseGet(() -> wallets.save(Wallet.forUser(userId)));
    }

    @Transactional(readOnly = true)
    public Wallet require(UUID userId) {
        return wallets.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("That wallet"));
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(UUID userId) {
        return require(userId).getBalance();
    }

    // ── Movements ──────────────────────────────────────────────────────────

    /** Increases the balance and writes the row. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction credit(UUID userId, BigDecimal amount, TxKind kind, Entry entry) {
        requirePositive(amount);
        if (!kind.isCredit()) {
            throw new IllegalArgumentException(kind + " is not a credit kind");
        }
        Wallet wallet = lock(userId);
        wallet.setBalance(Money.add(wallet.getBalance(), amount));
        return write(wallet, userId, amount, kind, entry, TxStatus.SUCCESSFUL);
    }

    /**
     * Reduces the balance and writes the row, refusing when the wallet is short.
     *
     * <p>The check and the write happen inside the same lock, which is the only
     * arrangement that stops the same naira being spent twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction debit(UUID userId, BigDecimal amount, TxKind kind, Entry entry) {
        return debit(userId, amount, kind, entry, TxStatus.SUCCESSFUL);
    }

    /**
     * Reduces the balance and writes the row at a given status.
     *
     * <p>A withdrawal is written {@link TxStatus#PENDING}: the money leaves at
     * request, not at approval, so it cannot be spent twice while under review.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction debit(
            UUID userId, BigDecimal amount, TxKind kind, Entry entry, TxStatus status) {
        requirePositive(amount);
        if (kind.isCredit()) {
            throw new IllegalArgumentException(kind + " is not a debit kind");
        }
        Wallet wallet = lock(userId);
        if (!wallet.canAfford(amount)) {
            throw ApiException.insufficientFunds();
        }
        wallet.setBalance(Money.subtract(wallet.getBalance(), amount));
        return write(wallet, userId, amount, kind, entry, status);
    }

    /** One row of the ledger, by id. */
    @Transactional(readOnly = true)
    public java.util.Optional<WalletTransaction> find(UUID transactionId) {
        return transactions.findById(transactionId);
    }

    /**
     * Settles a pending row. The balance already moved when it was written, so
     * approval only changes what the statement says about it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction settle(UUID transactionId, String description) {
        WalletTransaction tx = transactions.findById(transactionId)
                .orElseThrow(() -> ApiException.notFound("That transaction"));
        if (tx.getStatus() != TxStatus.PENDING) {
            throw new ApiException(ErrorCode.ALREADY_REVIEWED, "That transaction has already been settled.");
        }
        tx.setStatus(TxStatus.SUCCESSFUL);
        tx.setDescription(description);
        return transactions.save(tx);
    }

    /**
     * Reverses a pending row and puts the money back.
     *
     * <p>Two things happen and both are permanent: the original row is marked
     * reversed so the record shows what became of the request, and a
     * compensating credit is written so the ledger still replays to the balance.
     * Nothing is deleted or rewritten — the ledger is append-only.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction reverse(UUID transactionId, String description, String refundDescription) {
        WalletTransaction tx = transactions.findById(transactionId)
                .orElseThrow(() -> ApiException.notFound("That transaction"));
        if (tx.getStatus() != TxStatus.PENDING) {
            throw new ApiException(ErrorCode.ALREADY_REVIEWED, "That transaction has already been settled.");
        }
        tx.setStatus(TxStatus.REVERSED);
        tx.setDescription(description);
        transactions.save(tx);

        return credit(
                tx.getUserId(),
                tx.getAmount(),
                TxKind.DEPOSIT,
                Entry.of(refundDescription, "Kudi9ja")
                        .related(tx.getRelatedType(), tx.getRelatedId()));
    }

    // ── Reconciliation ─────────────────────────────────────────────────────

    /**
     * Whether the stored balance still matches the ledger.
     *
     * <p>Run by the nightly job and by the admin panel. A false here is a
     * defect: every row is written at kobo scale inside the same lock as the
     * balance it produced, so there is no rounding path to a mismatch.
     */
    @Transactional(readOnly = true)
    public Reconciliation reconcile(UUID userId) {
        Wallet wallet = require(userId);
        BigDecimal replayed = Money.of(transactions.replayBalance(wallet.getId()));
        BigDecimal stored = Money.of(wallet.getBalance());
        boolean ok = Money.eq(replayed, stored);
        if (!ok) {
            log.error("Wallet {} diverged from its ledger: stored {} replayed {}",
                    wallet.getId(), stored, replayed);
        }
        return new Reconciliation(userId, wallet.getId(), stored, replayed, ok);
    }

    /** The outcome of replaying a ledger against the balance it should produce. */
    public record Reconciliation(
            UUID userId, UUID walletId, BigDecimal storedBalance, BigDecimal ledgerBalance, boolean balanced) {

        public BigDecimal difference() {
            return Money.subtract(storedBalance, ledgerBalance);
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Wallet lock(UUID userId) {
        return wallets.findByUserIdForUpdate(userId)
                .orElseThrow(() -> ApiException.notFound("That wallet"));
    }

    private WalletTransaction write(
            Wallet wallet, UUID userId, BigDecimal amount, TxKind kind, Entry entry, TxStatus status) {

        WalletTransaction tx = new WalletTransaction();
        tx.setId(UUID.randomUUID());
        tx.setWalletId(wallet.getId());
        tx.setUserId(userId);
        tx.setSequence(wallet.takeSequence());
        tx.setKind(kind);
        tx.setAmount(Money.of(amount));
        tx.setDescription(entry.description());
        tx.setOccurredAt(Instant.now());
        tx.setBalanceAfter(wallet.getBalance());
        tx.setReference(entry.reference() == null ? Reference.ledger("K9") : entry.reference());
        tx.setCounterparty(entry.counterparty());
        tx.setStatus(status);
        tx.setRelatedType(entry.relatedType());
        tx.setRelatedId(entry.relatedId());

        wallet.setUpdatedAt(tx.getOccurredAt());
        wallets.save(wallet);
        return transactions.save(tx);
    }

    private static void requirePositive(BigDecimal amount) {
        if (Money.isZeroOrLess(amount)) {
            throw ApiException.validation("An amount must be greater than zero.");
        }
    }

    /**
     * What a ledger row says about itself, beyond the amount and the kind.
     *
     * <p>Built with {@link #of} and refined fluently, so a caller writes the
     * description and the counterparty at the point it knows them and leaves
     * the rest to sensible defaults.
     */
    public record Entry(
            String description,
            String counterparty,
            String reference,
            String relatedType,
            UUID relatedId) {

        public static Entry of(String description) {
            return new Entry(description, "", null, null, null);
        }

        public static Entry of(String description, String counterparty) {
            return new Entry(description, counterparty, null, null, null);
        }

        public Entry withReference(String reference) {
            return new Entry(description, counterparty, reference, relatedType, relatedId);
        }

        public Entry related(String type, UUID id) {
            return new Entry(description, counterparty, reference, type, id);
        }

        public Entry describedAs(String next) {
            return new Entry(next, counterparty, reference, relatedType, relatedId);
        }
    }

    /** The names {@code relatedType} takes, so they cannot drift apart. */
    public static final class Related {
        public static final String SAVINGS_PLAN = "savings_plan";
        public static final String LOAN = "loan";
        public static final String PAY_IN = "pay_in";
        public static final String WITHDRAWAL = "withdrawal";
        public static final String CIRCLE = "thrift_circle";

        private Related() {
        }
    }
}
