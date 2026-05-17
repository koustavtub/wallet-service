package com.keychain.repository;

import com.keychain.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Computes balance as SUM of topups minus SUM of deductions.
     * Returns 0 if no transactions exist (COALESCE handles NULL from empty set).
     * This is the canonical balance — never stored, always derived.
     */
    @Query("""
            SELECT COALESCE(SUM(
                CASE t.type
                    WHEN 'TOPUP'     THEN t.amount
                    WHEN 'DEDUCTION' THEN -t.amount
                END
            ), 0)
            FROM Transaction t
            WHERE t.wallet.id = :walletId
            """)
    BigDecimal computeBalance(@Param("walletId") UUID walletId);

    /**
     * Ledger entries for a wallet, newest first.
     */
    List<Transaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId);

    /**
     * Idempotency lookup — find a prior deduction by its key.
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
