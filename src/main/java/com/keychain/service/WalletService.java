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
     * Adds funds to a wallet.
     *
     * Guarantees:
     * 1. Amount constraint — amount > 0 (enforced by the validation layer).
     * 2. Idempotency — duplicate idempotency keys return the original transaction,
     *    not a new topup. The same DB unique constraint and catch pattern used by
     *    deduct() applies here: if the customer's frontend retries after a network
     *    timeout, the wallet is credited exactly once.
     */
    @Transactional
    public Transaction topup(UUID walletId, BigDecimal amount, String idempotencyKey) {
        var existing = txRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("Idempotent topup replay for key={}", idempotencyKey);
            return existing.get();
        }

        Wallet wallet = walletRepo.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        try {
            Transaction tx = Transaction.topup(wallet, amount, idempotencyKey);
            return txRepo.save(tx);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Idempotency key race resolved for topup key={}", idempotencyKey);
            return txRepo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency constraint fired but transaction not found", ex));
        }
    }

    /**
     * Deducts ₹100 from the wallet for an order.
     *
     * Guarantees:
     * 1. Balance constraint — deduction only proceeds if balance ≥ ₹100.
     * 2. Idempotency — duplicate idempotency keys return the original transaction,
     *    not a new deduction. Enforced by a DB unique constraint; no second deduction
     *    can sneak through even under concurrent retries.
     * 3. Concurrency safety — SELECT FOR UPDATE serializes concurrent deductions on
     *    the same wallet, preventing double-spend.
     *
     * Isolation: READ_COMMITTED is sufficient here because FOR UPDATE re-reads the
     * latest committed data after acquiring the lock, collapsing the window for
     * phantom reads within this critical path.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Transaction deduct(UUID walletId, String idempotencyKey) {
        // ── Idempotency check ─────────────────────────────────────────────────
        // Fast path: if this key was already processed, return the original result.
        // This handles retries before we even touch the wallet.
        var existing = txRepo.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.debug("Idempotent replay for key={}", idempotencyKey);
            return existing.get();
        }

        // ── Lock & read balance ───────────────────────────────────────────────
        // FOR UPDATE locks the wallet row so concurrent deductions serialize here.
        // Any other deduct() call for this wallet blocks until this txn commits.
        Wallet wallet = walletRepo.findByIdForUpdate(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));

        BigDecimal balance = txRepo.computeBalance(walletId);

        if (balance.compareTo(DEDUCTION_AMOUNT) < 0) {
            throw new InsufficientBalanceException(balance);
        }

        // ── Insert deduction ──────────────────────────────────────────────────
        // If two concurrent requests somehow both pass the balance check (shouldn't
        // happen with FOR UPDATE), the DB unique constraint on idempotency_key is
        // the final safety net — one will fail with DataIntegrityViolationException.
        try {
            Transaction tx = Transaction.deduction(wallet, DEDUCTION_AMOUNT, idempotencyKey);
            return txRepo.save(tx);
        } catch (DataIntegrityViolationException ex) {
            // Race condition on idempotency key: another thread committed first.
            // Fetch and return that transaction — this is still a success.
            log.warn("Idempotency key race resolved for key={}", idempotencyKey);
            return txRepo.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Idempotency constraint fired but transaction not found", ex));
        }
    }

    /**
     * Returns the current balance. Balance is always derived — never stored.
     */
    @Transactional(readOnly = true)
    public BigDecimal getBalance(UUID walletId) {
        if (!walletRepo.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }
        return txRepo.computeBalance(walletId);
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
