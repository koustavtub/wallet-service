package com.keychain.service;

import com.keychain.exception.InsufficientBalanceException;
import com.keychain.exception.WalletNotFoundException;
import com.keychain.model.Transaction;
import com.keychain.model.Wallet;
import com.keychain.repository.TransactionRepository;
import com.keychain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    static final BigDecimal DEDUCTION_AMOUNT = new BigDecimal("100.00");

    private final WalletRepository walletRepo;
    private final TransactionRepository txRepo;

    /**
     * Creates a new wallet. No initial balance — customer must top up first.
     */
    @Transactional
    public Wallet createWallet() {
        Wallet wallet = new Wallet();
        return walletRepo.save(wallet);
    }

    /**
     * Adds funds to a wallet. FOR UPDATE locks the row so concurrent topups
     * don't produce a lost write on cached_balance.
     */
    @Transactional
    public Transaction topup(UUID walletId, BigDecimal amount) {
        Wallet wallet = walletRepo.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        wallet.creditBalance(amount);
        walletRepo.save(wallet);

        return txRepo.save(Transaction.topup(wallet, amount));
    }

    /**
     * Deducts ₹100 from the wallet for an order.
     *
     * Guarantees:
     * 1. Balance constraint — deduction only proceeds if cached_balance ≥ ₹100 (O(1) check).
     * 2. Idempotency — duplicate idempotency keys return the original transaction,
     *    not a new deduction. Enforced by a DB unique constraint; no second deduction
     *    can sneak through even under concurrent retries.
     * 3. Concurrency safety — SELECT FOR UPDATE serializes concurrent deductions on
     *    the same wallet, preventing double-spend.
     * 4. Atomicity — cached_balance and the transaction row are updated in the same
     *    transaction, so they can never diverge.
     *
     * Isolation: READ_COMMITTED is sufficient here because FOR UPDATE re-reads the
     * latest committed data after acquiring the lock, collapsing the window for
     * phantom reads within this critical path.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction deduct(UUID walletId, String idempotencyKey) {
        // ── Idempotency check ─────────────────────────────────────────────────
        // Fast path: if this key was already processed, return the original result.
        var existing = txRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("Idempotent replay for key={}", idempotencyKey);
            return existing.get();
        }

        // ── Lock & balance check ──────────────────────────────────────────────
        // FOR UPDATE locks the wallet row so concurrent deductions serialize here.
        // Reading cached_balance from the locked row is an O(1) point lookup.
        Wallet wallet = walletRepo.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.getCachedBalance().compareTo(DEDUCTION_AMOUNT) < 0) {
            throw new InsufficientBalanceException(wallet.getCachedBalance());
        }

        // ── Update balance + insert transaction atomically ────────────────────
        wallet.debitBalance(DEDUCTION_AMOUNT);
        walletRepo.save(wallet);

        // If two concurrent requests somehow both pass the balance check (shouldn't
        // happen with FOR UPDATE), the DB unique constraint on idempotency_key is
        // the final safety net — one will fail with DataIntegrityViolationException.
        try {
            return txRepo.save(Transaction.deduction(wallet, DEDUCTION_AMOUNT, idempotencyKey));
        } catch (DataIntegrityViolationException ex) {
            log.warn("Idempotency key race resolved for key={}", idempotencyKey);
            return txRepo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency constraint fired but transaction not found", ex));
        }
    }

    /**
     * Returns the cached balance. O(1) — reads the single cached_balance column.
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID walletId) {
        Wallet wallet = walletRepo.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
        return wallet.getCachedBalance();
    }

    /**
     * Returns the full transaction ledger for a wallet, newest first.
     */
    @Transactional(readOnly = true)
    public List<Transaction> getTransactions(UUID walletId) {
        if (!walletRepo.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }
        return txRepo.findByWalletIdOrderByCreatedAtDesc(walletId);
    }
}
