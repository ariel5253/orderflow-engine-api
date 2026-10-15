package com.orderflow.engine.outbox;

/** The external system an outbox event goes to. Each order has exactly one event per destination. */
public enum Destination {
    PUSH,
    ERP
}
