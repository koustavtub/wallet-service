package com.keychain.controller;

import tools.jackson.databind.json.JsonMapper;
import com.keychain.dto.WalletDtos.*;
import com.keychain.exception.InsufficientBalanceException;
import com.keychain.exception.WalletNotFoundException;
import com.keychain.model.Transaction;
import com.keychain.model.TransactionType;
import com.keychain.model.Wallet;
import com.keychain.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WalletController.class)
class WalletControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper objectMapper;
    @MockitoBean WalletService walletService;

    // ── POST /wallets ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /wallets → 201 with wallet id")
    void createWallet_returns201() throws Exception {
        Wallet wallet = mockWallet(UUID.randomUUID());
        when(walletService.createWallet()).thenReturn(wallet);

        mockMvc.perform(post("/wallets"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(wallet.getId().toString()));
    }

    // ── POST /wallets/:id/topup ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /topup with valid amount and key → 201")
    void topup_validAmount_returns201() throws Exception {
        UUID walletId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Transaction tx = mockTransaction(walletId, TransactionType.TOPUP, new BigDecimal("500.00"), key);
        when(walletService.topup(eq(walletId), any(), eq(key))).thenReturn(tx);

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 500.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TOPUP"))
                .andExpect(jsonPath("$.amount").value(500.00));
    }

    @Test
    @DisplayName("POST /topup without Idempotency-Key header → 400")
    void topup_missingIdempotencyKey_returns400() throws Exception {
        UUID walletId = UUID.randomUUID();

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("POST /topup idempotent replay → 201 with original transaction")
    void topup_idempotentReplay_returns201() throws Exception {
        UUID walletId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Transaction tx = mockTransaction(walletId, TransactionType.TOPUP, new BigDecimal("500.00"), key);
        when(walletService.topup(eq(walletId), any(), eq(key))).thenReturn(tx);

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 500.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotency_key").value(key));
    }

    @Test
    @DisplayName("POST /topup with zero amount → 400")
    void topup_zeroAmount_returns400() throws Exception {
        UUID walletId = UUID.randomUUID();

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /topup with missing amount → 400")
    void topup_missingAmount_returns400() throws Exception {
        UUID walletId = UUID.randomUUID();

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /topup for unknown wallet → 404")
    void topup_unknownWallet_returns404() throws Exception {
        UUID walletId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        when(walletService.topup(eq(walletId), any(), eq(key)))
                .thenThrow(new WalletNotFoundException(walletId));

        mockMvc.perform(post("/wallets/{id}/topup", walletId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    // ── POST /wallets/:id/deduct ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /deduct with valid key → 201")
    void deduct_valid_returns201() throws Exception {
        UUID walletId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        Transaction tx = mockTransaction(walletId, TransactionType.DEDUCTION, new BigDecimal("100.00"), key);
        when(walletService.deduct(walletId, key)).thenReturn(tx);

        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEDUCTION"))
                .andExpect(jsonPath("$.amount").value(100.00));
    }

    @Test
    @DisplayName("POST /deduct without Idempotency-Key header → 400")
    void deduct_missingIdempotencyKey_returns400() throws Exception {
        UUID walletId = UUID.randomUUID();

        mockMvc.perform(post("/wallets/{id}/deduct", walletId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    @DisplayName("POST /deduct with insufficient balance → 402")
    void deduct_insufficientBalance_returns402() throws Exception {
        UUID walletId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        when(walletService.deduct(walletId, key))
                .thenThrow(new InsufficientBalanceException(BigDecimal.ZERO));

        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                        .header("Idempotency-Key", key))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @DisplayName("POST /deduct for unknown wallet → 404")
    void deduct_unknownWallet_returns404() throws Exception {
        UUID walletId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        when(walletService.deduct(walletId, key))
                .thenThrow(new WalletNotFoundException(walletId));

        mockMvc.perform(post("/wallets/{id}/deduct", walletId)
                        .header("Idempotency-Key", key))
                .andExpect(status().isNotFound());
    }

    // ── GET /wallets/:id/balance ──────────────────────────────────────────────

    @Test
    @DisplayName("GET /balance returns current balance")
    void getBalance_returns200() throws Exception {
        UUID walletId = UUID.randomUUID();
        when(walletService.getBalance(walletId)).thenReturn(new BigDecimal("350.00"));

        mockMvc.perform(get("/wallets/{id}/balance", walletId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(350.00))
                .andExpect(jsonPath("$.walletId").value(walletId.toString()));
    }

    @Test
    @DisplayName("GET /balance for unknown wallet → 404")
    void getBalance_unknownWallet_returns404() throws Exception {
        UUID walletId = UUID.randomUUID();
        when(walletService.getBalance(walletId))
                .thenThrow(new WalletNotFoundException(walletId));

        mockMvc.perform(get("/wallets/{id}/balance", walletId))
                .andExpect(status().isNotFound());
    }

    // ── GET /wallets/:id/transactions ─────────────────────────────────────────

    @Test
    @DisplayName("GET /transactions returns ledger entries")
    void getTransactions_returns200() throws Exception {
        UUID walletId = UUID.randomUUID();
        Transaction tx1 = mockTransaction(walletId, TransactionType.DEDUCTION, new BigDecimal("100.00"), "key1");
        Transaction tx2 = mockTransaction(walletId, TransactionType.TOPUP, new BigDecimal("500.00"), null);
        when(walletService.getTransactions(walletId)).thenReturn(List.of(tx1, tx2));

        mockMvc.perform(get("/wallets/{id}/transactions", walletId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Wallet mockWallet(UUID id) {
        Wallet w = mock(Wallet.class);
        when(w.getId()).thenReturn(id);
        when(w.getCreatedAt()).thenReturn(Instant.now());
        return w;
    }

    private Transaction mockTransaction(UUID walletId, TransactionType type, BigDecimal amount, String key) {
        Wallet wallet = mockWallet(walletId);
        Transaction tx = mock(Transaction.class);
        when(tx.getId()).thenReturn(UUID.randomUUID());
        when(tx.getWallet()).thenReturn(wallet);
        when(tx.getType()).thenReturn(type);
        when(tx.getAmount()).thenReturn(amount);
        when(tx.getIdempotencyKey()).thenReturn(key);
        when(tx.getCreatedAt()).thenReturn(Instant.now());
        return tx;
    }
}
