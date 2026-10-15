package com.orderflow.engine.adapters.out.persistence;

import com.orderflow.engine.ordering.OrderRejectedException;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;

/**
 * Runs a statement of the order confirmation that may wait for a lock held by a concurrent confirmation. When the wait
 * passes the transaction's lock_timeout, PostgreSQL cancels the statement with SQLSTATE 55P03 (lock_not_available);
 * that becomes the rejection the API answers with 409, instead of an internal error (data model section 8, RISK-01).
 */
final class LockWaits {

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private LockWaits() {
    }

    /** {@code held} names the lock the statement waits for, in the message of the rejection. */
    static <T> T bounded(String held, Supplier<T> statement) {
        try {
            return statement.get();
        } catch (DataAccessException e) {
            if (timedOut(e)) {
                throw OrderRejectedException.lockWaitTimedOut(held + " is held by a confirmation still in flight", e);
            }
            throw e;
        }
    }

    private static boolean timedOut(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
