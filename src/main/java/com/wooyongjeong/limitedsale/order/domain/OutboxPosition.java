package com.wooyongjeong.limitedsale.order.domain;

/** Where the broker stored a sent outbox message: its partition and offset. */
public record OutboxPosition(int partition, long offset) {
}
