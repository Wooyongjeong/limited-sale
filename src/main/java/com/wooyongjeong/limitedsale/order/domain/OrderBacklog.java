package com.wooyongjeong.limitedsale.order.domain;

import java.time.Duration;

/** Cheap counts of orders whose payment outcome is still open, and of v2 requests still on their way, for monitoring. */
public interface OrderBacklog {
    long unfinished();

    /** Unfinished orders flagged for operator review after the payment result stayed unknown. */
    long requiringReview();

    /** Outbox rows not yet published, and the age of the oldest (zero when there is none). */
    Queue unpublishedOutbox();

    /** Acceptances still ACCEPTED, and the age of the oldest (zero when there is none). */
    Queue acceptedRequests();

    record Queue(long size, Duration oldest) {
    }
}
