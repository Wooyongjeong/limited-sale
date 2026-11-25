package com.wooyongjeong.limitedsale.order.domain;

import java.util.concurrent.CompletableFuture;

/**
 * Sends one outbox message to the broker. The future completes with the message's position once the broker has
 * acknowledged it, or fails.
 */
public interface OutboxSender {
    CompletableFuture<OutboxPosition> send(OutboxMessage message);
}
