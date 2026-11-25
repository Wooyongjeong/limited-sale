package com.wooyongjeong.limitedsale.order.domain;

import java.time.Duration;

/**
 * One stored outbox row to send as it is: topic, key and payload exactly as 3-3 saved them. {@code age} is how long the
 * row had existed when it was read (database time), the start of its publish delay.
 */
public record OutboxMessage(long id, String topic, String key, String payload, Duration age) {
}
