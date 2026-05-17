package com.keychain.controller;

import com.keychain.dto.WalletDtos.*;
import com.keychain.model.Transaction;
import com.keychain.model.Wallet;
import com.keychain.service.WalletService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class  WalletController {

    private final WalletService walletService;

    /**
     * POST /wallets
     * Creates a new wallet. Called during customer onboarding / test setup.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> createWallet() {
        Wallet wallet = walletService.createWallet();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WalletResponse(wallet.getId(), wallet.getCreatedAt()));
    }

    /**
     * POST /wallets/:id/topup
     * Adds funds. Amount comes from the request body.
     */
    @PostMapping("/{id}/topup")
    public ResponseEntity<TransactionResponse> topup(
            @PathVariable UUID id,
            @Valid @RequestBody TopupRequest request
    ) {
        Transaction tx = walletService.topup(id, request.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponse.from(tx));
    }

    /**
     * POST /wallets/:id/deduct
     * Deducts ₹100. Called by the Order Service.
     * Requires the Idempotency-Key header — rejects requests without one.
     * Amount is NOT taken from the request body; it is fixed at ₹100 by business rule.
     */
    @PostMapping("/{id}/deduct")
    public ResponseEntity<TransactionResponse> deduct(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey
    ) {
        Transaction tx = walletService.deduct(id, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponse.from(tx));
    }

    /**
     * GET /wallets/:id/balance
     * Returns current balance. Balance is always derived from the ledger.
     */
    @GetMapping("/{id}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable UUID id) {
        return ResponseEntity.ok(
                new BalanceResponse(id, walletService.getBalance(id))
        );
    }

    /**
     * GET /wallets/:id/transactions
     * Returns full ledger history, newest first.
     */
    @GetMapping("/{id}/transactions")
    public ResponseEntity<List<TransactionResponse>> getTransactions(@PathVariable UUID id) {
        List<TransactionResponse> txs = walletService.getTransactions(id)
                .stream()
                .map(TransactionResponse::from)
                .toList();
        return ResponseEntity.ok(txs);
    }
}
