package com.keychain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.keychain.model.Transaction;
import com.keychain.model.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class WalletDtos {

    // ── Requests ─────────────────────────────────────────────────────────────

    public record TopupRequest(
            @NotNull(message = "amount is required")
            @DecimalMin(value = "0.01", message = "amount must be greater than 0")
            BigDecimal amount
    ) {}

    // DeductRequest has no body — amount is fixed at ₹100 by business rule.
    // Idempotency-Key is passed as a header, not in the body.

    // ── Responses ────────────────────────────────────────────────────────────

    public record WalletResponse(UUID id, Instant createdAt) {}

    public record BalanceResponse(UUID walletId, BigDecimal balance) {}

    public record TransactionResponse(
            UUID id,
            UUID walletId,
            TransactionType type,
            BigDecimal amount,
            @JsonProperty("idempotency_key") String idempotencyKey,
            Instant createdAt
    ) {
        public static TransactionResponse from(Transaction t) {
            return new TransactionResponse(
                    t.getId(),
                    t.getWallet().getId(),
                    t.getType(),
                    t.getAmount(),
                    t.getIdempotencyKey(),
                    t.getCreatedAt()
            );
        }
    }

    public record ErrorResponse(String error, String code) {}
}
