package com.wooyongjeong.limitedsale.order.domain;

/** One stored outbox row to send as it is: topic, key and payload exactly as 3-3 saved them. */
public record OutboxMessage(long id, String topic, String key, String payload) {
}
