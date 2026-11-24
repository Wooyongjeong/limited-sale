package com.wooyongjeong.limitedsale.order.domain;

import java.util.concurrent.CompletableFuture;

/** Sends one outbox message to the broker. The future completes when the broker has acknowledged it, or fails. */
public interface OutboxSender {
    CompletableFuture<Void> send(OutboxMessage message);
}
