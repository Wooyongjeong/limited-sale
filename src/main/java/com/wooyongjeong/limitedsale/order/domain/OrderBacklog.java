package com.wooyongjeong.limitedsale.order.domain;

/** Cheap counts of orders whose payment outcome is still open, for monitoring. */
public interface OrderBacklog {
    long unfinished();

    /** Unfinished orders flagged for operator review after the payment result stayed unknown. */
    long requiringReview();
}
