package com.keychain.service;

import com.keychain.exception.InsufficientBalanceException;
import com.keychain.exception.WalletNotFoundException;
import com.keychain.model.Transaction;
import com.keychain.model.TransactionType;
import com.keychain.model.Wallet;
import com.keychain.repository.TransactionRepository;
import com.keychain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock WalletRepository walletRepo;
    @Mock TransactionRepository txRepo;
    @InjectMocks WalletService walletService;

    private Wallet wallet;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        wallet = mock(Wallet.class);
        walletId = UUID.randomUUID();
        lenient().when(wallet.getId()).thenReturn(walletId);
    }

    @Nested
    @DisplayName("deduct()")
    class Deduct {

        @Test
        @DisplayName("succeeds when balance is exactly ₹100")
        void deduct_exactBalance_succeeds() {
            String key = UUID.randomUUID().toString();
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(walletRepo.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
            when(txRepo.computeBalance(walletId)).thenReturn(new BigDecimal("100.00"));
            when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = walletService.deduct(walletId, key);

            assertThat(result.getType()).isEqualTo(TransactionType.DEDUCTION);
            assertThat(result.getAmount()).isEqualByComparingTo("100.00");
            verify(txRepo).save(any(Transaction.class));
        }

        @Test
        @DisplayName("succeeds when balance is greater than ₹100")
        void deduct_surplusBalance_succeeds() {
            String key = UUID.randomUUID().toString();
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(walletRepo.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
            when(txRepo.computeBalance(walletId)).thenReturn(new BigDecimal("500.00"));
            when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatNoException().isThrownBy(() -> walletService.deduct(walletId, key));
        }

        @Test
        @DisplayName("fails with InsufficientBalanceException when balance < ₹100")
        void deduct_insufficientBalance_throws() {
            String key = UUID.randomUUID().toString();
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(walletRepo.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
            when(txRepo.computeBalance(walletId)).thenReturn(new BigDecimal("99.99"));

            assertThatThrownBy(() -> walletService.deduct(walletId, key))
                    .isInstanceOf(InsufficientBalanceException.class)
                    .hasMessageContaining("99.99");

            verify(txRepo, never()).save(any());
        }

        @Test
        @DisplayName("fails with InsufficientBalanceException when balance is zero")
        void deduct_zeroBalance_throws() {
            String key = UUID.randomUUID().toString();
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(walletRepo.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
            when(txRepo.computeBalance(walletId)).thenReturn(BigDecimal.ZERO);

            assertThatThrownBy(() -> walletService.deduct(walletId, key))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("idempotent replay — returns existing transaction without a new deduction")
        void deduct_duplicateKey_returnsOriginal() {
            String key = UUID.randomUUID().toString();
            Transaction existing = mock(Transaction.class);
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

            Transaction result = walletService.deduct(walletId, key);

            assertThat(result).isSameAs(existing);
            // Wallet should never be touched on a replay
            verifyNoInteractions(walletRepo);
            verify(txRepo, never()).save(any());
        }

        @Test
        @DisplayName("idempotency race: DB constraint fires → fetches existing and returns it")
        void deduct_constraintRaceCondition_returnsExisting() {
            String key = UUID.randomUUID().toString();
            Transaction existing = mock(Transaction.class);

            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(walletRepo.findByIdForUpdate(walletId)).thenReturn(Optional.of(wallet));
            when(txRepo.computeBalance(walletId)).thenReturn(new BigDecimal("200.00"));
            when(txRepo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
            // Second lookup after constraint fires
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty(), Optional.of(existing));

            Transaction result = walletService.deduct(walletId, key);

            assertThat(result).isSameAs(existing);
        }

        @Test
        @DisplayName("throws WalletNotFoundException for unknown wallet")
        void deduct_unknownWallet_throws() {
            UUID unknownId = UUID.randomUUID();
            String key = UUID.randomUUID().toString();
            when(txRepo.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(walletRepo.findByIdForUpdate(unknownId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> walletService.deduct(unknownId, key))
                    .isInstanceOf(WalletNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("topup()")
    class Topup {

        @Test
        @DisplayName("records a TOPUP transaction")
        void topup_valid_recordsTransaction() {
            when(walletRepo.findById(walletId)).thenReturn(Optional.of(wallet));
            when(txRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Transaction result = walletService.topup(walletId, new BigDecimal("500.00"));

            assertThat(result.getType()).isEqualTo(TransactionType.TOPUP);
            assertThat(result.getAmount()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("throws WalletNotFoundException for unknown wallet")
        void topup_unknownWallet_throws() {
            when(walletRepo.findById(walletId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> walletService.topup(walletId, new BigDecimal("100")))
                    .isInstanceOf(WalletNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("getBalance()")
    class GetBalance {

        @Test
        @DisplayName("returns aggregated balance")
        void getBalance_returnsAggregated() {
            when(walletRepo.existsById(walletId)).thenReturn(true);
            when(txRepo.computeBalance(walletId)).thenReturn(new BigDecimal("350.00"));

            BigDecimal balance = walletService.getBalance(walletId);

            assertThat(balance).isEqualByComparingTo("350.00");
        }

        @Test
        @DisplayName("throws WalletNotFoundException for unknown wallet")
        void getBalance_unknownWallet_throws() {
            when(walletRepo.existsById(walletId)).thenReturn(false);

            assertThatThrownBy(() -> walletService.getBalance(walletId))
                    .isInstanceOf(WalletNotFoundException.class);
        }
    }
}
