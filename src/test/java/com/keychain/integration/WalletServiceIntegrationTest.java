package com.keychain.integration;

import com.keychain.dto.WalletDtos.*;
import com.keychain.exception.InsufficientBalanceException;
import com.keychain.model.Transaction;
import com.keychain.model.TransactionType;
import com.keychain.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WalletServiceIntegrationTest extends IntegrationTestBase {

    @Autowired
    WalletService walletService;

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("full lifecycle: create → topup → deduct → balance")
    void fullLifecycle() {
        var wallet = walletService.createWallet();
        walletService.topup(wallet.getId(), new BigDecimal("300.00"), UUID.randomUUID().toString());

        var deduction = walletService.deduct(wallet.getId(), UUID.randomUUID().toString());
        assertThat(deduction.getType()).isEqualTo(TransactionType.DEDUCTION);
        assertThat(deduction.getAmount()).isEqualByComparingTo("100.00");

        var balance = walletService.getBalance(wallet.getId());
        assertThat(balance).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("balance reflects multiple topups and deductions correctly")
    void balance_multipleMovements() {
        var wallet = walletService.createWallet();
        walletService.topup(wallet.getId(), new BigDecimal("500.00"), UUID.randomUUID().toString());
        walletService.topup(wallet.getId(), new BigDecimal("200.00"), UUID.randomUUID().toString());
        walletService.deduct(wallet.getId(), UUID.randomUUID().toString());
        walletService.deduct(wallet.getId(), UUID.randomUUID().toString());

        // 500 + 200 - 100 - 100 = 500
        assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("new wallet has zero balance")
    void newWallet_zeroBalance() {
        var wallet = walletService.createWallet();
        assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("0.00");
    }

    // ── Balance constraint ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Balance constraint")
    class BalanceConstraint {

        @Test
        @DisplayName("deduction fails when balance is exactly ₹0")
        void deduct_zeroBalance_fails() {
            var wallet = walletService.createWallet();

            assertThatThrownBy(() -> walletService.deduct(wallet.getId(), UUID.randomUUID().toString()))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("wallet balance never goes negative")
        void balance_neverNegative() {
            var wallet = walletService.createWallet();
            walletService.topup(wallet.getId(), new BigDecimal("100.00"), UUID.randomUUID().toString());
            walletService.deduct(wallet.getId(), UUID.randomUUID().toString());

            // Attempt a second deduction — must fail
            assertThatThrownBy(() -> walletService.deduct(wallet.getId(), UUID.randomUUID().toString()))
                    .isInstanceOf(InsufficientBalanceException.class);

            // Balance must still be 0, never negative
            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("deduction succeeds at exactly ₹100 and leaves zero balance")
        void deduct_exactlyOneHundred_leavesZero() {
            var wallet = walletService.createWallet();
            walletService.topup(wallet.getId(), new BigDecimal("100.00"), UUID.randomUUID().toString());
            walletService.deduct(wallet.getId(), UUID.randomUUID().toString());

            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("0.00");
        }
    }

    // ── Idempotency ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("replaying the same deduct idempotency key does not deduct twice")
        void deduct_replay_sameKey_noDoubleDeduction() {
            var wallet = walletService.createWallet();
            walletService.topup(wallet.getId(), new BigDecimal("200.00"), UUID.randomUUID().toString());

            String key = UUID.randomUUID().toString();
            Transaction first = walletService.deduct(wallet.getId(), key);
            Transaction second = walletService.deduct(wallet.getId(), key);

            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("deduct idempotent replay works even when balance would be insufficient for a new deduction")
        void deduct_replay_insufficientBalance_stillReturnsOriginal() {
            var wallet = walletService.createWallet();
            walletService.topup(wallet.getId(), new BigDecimal("100.00"), UUID.randomUUID().toString());

            String key = UUID.randomUUID().toString();
            Transaction first = walletService.deduct(wallet.getId(), key);

            Transaction replayed = walletService.deduct(wallet.getId(), key);
            assertThat(replayed.getId()).isEqualTo(first.getId());
        }

        @Test
        @DisplayName("replaying the same topup idempotency key does not credit twice")
        void topup_replay_sameKey_noDoubleCreditl() {
            var wallet = walletService.createWallet();
            String key = UUID.randomUUID().toString();

            Transaction first = walletService.topup(wallet.getId(), new BigDecimal("500.00"), key);
            Transaction second = walletService.topup(wallet.getId(), new BigDecimal("500.00"), key);

            assertThat(second.getId()).isEqualTo(first.getId());
            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("topup idempotent replay returns original even if amount in retry differs")
        void topup_replay_differentAmount_returnsOriginal() {
            var wallet = walletService.createWallet();
            String key = UUID.randomUUID().toString();

            Transaction first = walletService.topup(wallet.getId(), new BigDecimal("500.00"), key);
            // Retry with a different amount — original transaction must win
            Transaction replayed = walletService.topup(wallet.getId(), new BigDecimal("999.00"), key);

            assertThat(replayed.getId()).isEqualTo(first.getId());
            assertThat(replayed.getAmount()).isEqualByComparingTo("500.00");
            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("500.00");
        }
    }

    // ── Concurrency ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        /**
         * THE critical test. 10 threads all attempt to deduct from a wallet with ₹100.
         * Exactly 1 must succeed. The rest must get InsufficientBalanceException.
         * Balance must be exactly ₹0 at the end — never negative.
         */
        @Test
        @DisplayName("10 concurrent deductions on ₹100 wallet — exactly 1 succeeds")
        void concurrentDeductions_onlyOneSucceeds() throws InterruptedException {
            var wallet = walletService.createWallet();
            walletService.topup(wallet.getId(), new BigDecimal("100.00"), UUID.randomUUID().toString());

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1); // all threads start simultaneously
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                String key = UUID.randomUUID().toString(); // unique key per thread
                executor.submit(() -> {
                    try {
                        startGate.await();
                        walletService.deduct(wallet.getId(), key);
                        successCount.incrementAndGet();
                    } catch (InsufficientBalanceException e) {
                        failCount.incrementAndGet();
                    } catch (Exception e) {
                        // Unexpected — will surface in assertions
                        failCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown(); // release all threads at once
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(failCount.get()).isEqualTo(9);
            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("0.00");
        }

        /**
         * Same idempotency key sent concurrently by 5 threads.
         * Wallet must be deducted exactly once regardless of how many threads
         * "win" the insert race.
         */
        @Test
        @DisplayName("concurrent replays of the same idempotency key — wallet deducted exactly once")
        void concurrentIdempotentReplays_deductedOnce() throws InterruptedException {
            var wallet = walletService.createWallet();
            walletService.topup(wallet.getId(), new BigDecimal("500.00"), UUID.randomUUID().toString());

            String sharedKey = UUID.randomUUID().toString();
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            List<UUID> returnedIds = new CopyOnWriteArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        Transaction tx = walletService.deduct(wallet.getId(), sharedKey);
                        returnedIds.add(tx.getId());
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startGate.countDown();
            doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // All threads that succeeded must have gotten the same transaction ID
            assertThat(returnedIds).isNotEmpty();
            assertThat(returnedIds).allMatch(id -> id.equals(returnedIds.get(0)));

            // Balance deducted exactly once: 500 - 100 = 400
            assertThat(walletService.getBalance(wallet.getId())).isEqualByComparingTo("400.00");
        }
    }

    // ── Ledger ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("transaction history contains all entries in reverse chronological order")
    void transactions_orderedNewestFirst() {
        var wallet = walletService.createWallet();
        walletService.topup(wallet.getId(), new BigDecimal("500.00"), UUID.randomUUID().toString());
        walletService.deduct(wallet.getId(), UUID.randomUUID().toString());
        walletService.topup(wallet.getId(), new BigDecimal("200.00"), UUID.randomUUID().toString());

        var txs = walletService.getTransactions(wallet.getId());

        assertThat(txs).hasSize(3);
        // Newest first — last topup is first entry
        assertThat(txs.get(0).getType()).isEqualTo(TransactionType.TOPUP);
        assertThat(txs.get(0).getAmount()).isEqualByComparingTo("200.00");
        assertThat(txs.get(1).getType()).isEqualTo(TransactionType.DEDUCTION);
        assertThat(txs.get(2).getType()).isEqualTo(TransactionType.TOPUP);
    }
}
