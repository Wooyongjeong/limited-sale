package com.wooyongjeong.limitedsale.order.infrastructure.messaging;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The errors worth retrying (3-6 #3): the database could not be reached, a lock wait timed out, a deadlock was broken
 * or a query timed out. Everything else would fail the same way again. The whole cause chain is looked at, because
 * the listener container wraps what the processing threw.
 */
final class TransientFailures {
    /** MySQL: lock wait timeout, deadlock, statement timeout (max_execution_time). */
    private static final Set<Integer> MYSQL_CODES = Set.of(1205, 1213, 3024);

    private TransientFailures() {
    }

    static boolean isTransient(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof RecoverableDataAccessException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotCreateTransactionException || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException || cause instanceof ConnectException
                    || cause instanceof SocketTimeoutException) {
                return true;
            }
            if (cause instanceof SQLException sql && (MYSQL_CODES.contains(sql.getErrorCode())
                    || (sql.getSQLState() != null && (sql.getSQLState().startsWith("08")
                    || sql.getSQLState().equals("40001"))))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
