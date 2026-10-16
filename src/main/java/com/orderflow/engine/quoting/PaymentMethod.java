package com.orderflow.engine.quoting;

/** How the customer pays for a quoted cart (SPEC BR-06). Only a CREDIT quote is checked against a credit account. */
public enum PaymentMethod {
    CASH,
    CREDIT
}
