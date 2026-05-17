package com.keychain.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(BigDecimal currentBalance) {
        super("Insufficient balance. Current balance: ₹" + currentBalance + ", required: ₹100.00");
    }
}
