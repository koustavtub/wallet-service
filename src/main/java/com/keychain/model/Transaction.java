package com.keychain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /**
     * Only set for DEDUCTION entries. Enforced unique at the DB level.
     * This is the idempotency anchor — a duplicate key causes a constraint
     * violation, which the service layer catches and treats as a replay.
     */
    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }

    public static Transaction topup(Wallet wallet, BigDecimal amount) {
        Transaction t = new Transaction();
        t.wallet = wallet;
        t.type = TransactionType.TOPUP;
        t.amount = amount;
        return t;
    }

    public static Transaction deduction(Wallet wallet, BigDecimal amount, String idempotencyKey) {
        Transaction t = new Transaction();
        t.wallet = wallet;
        t.type = TransactionType.DEDUCTION;
        t.amount = amount;
        t.idempotencyKey = idempotencyKey;
        return t;
    }
}
