package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import java.net.ConnectException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientFailuresTest {
    private static Throwable wrapped(Throwable cause) {
        // What the listener container hands the error handler.
        return new ListenerExecutionFailedException("listener failed", new RuntimeException(cause));
    }

    @Test
    void databaseOutagesLockWaitsDeadlocksAndTimeoutsAreTransient() {
        assertThat(TransientFailures.isTransient(wrapped(new CannotAcquireLockException("lock wait")))).isTrue();
        assertThat(TransientFailures.isTransient(wrapped(new QueryTimeoutException("slow")))).isTrue();
        assertThat(TransientFailures.isTransient(wrapped(new CannotCreateTransactionException("no connection"))))
            .isTrue();
        assertThat(TransientFailures.isTransient(wrapped(new ConnectException("refused")))).isTrue();
        assertThat(TransientFailures.isTransient(wrapped(new SQLException("Lock wait timeout", "HY000", 1205))))
            .isTrue();
        assertThat(TransientFailures.isTransient(wrapped(new SQLException("Deadlock", "40001", 1213)))).isTrue();
        assertThat(TransientFailures.isTransient(wrapped(new SQLException("link failure", "08S01", 0)))).isTrue();
    }

    @Test
    void everythingElseIsNot() {
        assertThat(TransientFailures.isTransient(wrapped(new IllegalStateException("defect")))).isFalse();
        assertThat(TransientFailures.isTransient(wrapped(new DataIntegrityViolationException("constraint"))))
            .isFalse();
        assertThat(TransientFailures.isTransient(wrapped(new SQLException("check violated", "HY000", 3819))))
            .isFalse();
        assertThat(TransientFailures.isTransient(new ArithmeticException("overflow"))).isFalse();
    }
}
