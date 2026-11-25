package com.wooyongjeong.limitedsale.order.domain;

/** Why an acceptance ended as PROCESSING_FAILED; stored as its failure reason and shown to its owner. */
public enum ProcessingFailure {
    /** A transient error (database connection, lock wait, deadlock, query timeout) lasted past the retry budget. */
    TRANSIENT_RETRIES_EXHAUSTED,
    /** An error that a retry would not change. */
    PROCESSING_ERROR,
    /** Republished as often as allowed and still not processed. */
    STALLED
}
