package com.wooyongjeong.limitedsale.order.domain;

/**
 * The right to publish, held by at most one instance at a time. An instance that holds it must confirm it still does
 * before each batch; losing it, or failing to confirm, gives it up so another instance can take over.
 */
public interface OutboxLeadership {
    /** Tries to become the publisher without waiting. Returns whether this instance now holds the right. */
    boolean tryAcquire();

    /** Confirms the right is still held. A false result or a failure means it was given up already. */
    boolean stillHeld();

    /** Gives the right up, if held, so another instance can take over at once. Never throws. */
    void release();
}
