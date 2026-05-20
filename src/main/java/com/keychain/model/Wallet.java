package com.keychain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cached_balance", nullable = false, precision = 12, scale = 2)
    private BigDecimal cachedBalance = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = Instant.now();
    }

    public void creditBalance(BigDecimal amount) {
        this.cachedBalance = this.cachedBalance.add(amount);
    }

    public void debitBalance(BigDecimal amount) {
        this.cachedBalance = this.cachedBalance.subtract(amount);
    }
}
