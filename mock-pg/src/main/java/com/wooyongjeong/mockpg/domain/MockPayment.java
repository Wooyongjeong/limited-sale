package com.wooyongjeong.mockpg.domain;

import java.time.Instant;
import java.util.UUID;

public record MockPayment(UUID pgKey, long amount, PaymentStatus status, Scenario scenario, Instant completionDueAt,
        boolean responseLossConsumed, Instant finalizedAt, Instant createdAt, Instant updatedAt) {
    public static MockPayment start(UUID key, long amount, ScenarioConfiguration config, Instant now, boolean close) {
        PaymentStatus status;
        Instant due = null;
        Instant finalized = now;
        boolean consumed = false;
        if (close) {
            status = PaymentStatus.CLOSED;
        } else if (config.scenario() == Scenario.DELAY) {
            status = PaymentStatus.PROCESSING;
            due = now.plusSeconds(config.delaySeconds());
            finalized = null;
        } else {
            status = config.scenario() == Scenario.DECLINE ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
            consumed = config.scenario() == Scenario.RESPONSE_LOSS;
        }
        return new MockPayment(key, amount, status, config.scenario(), due, consumed, finalized, now, now);
    }

    public MockPayment resolve(Instant now, boolean close) {
        if (status != PaymentStatus.PROCESSING) {
            return this;
        }
        PaymentStatus resolved = DelayedPayment.resolve(completionDueAt, now, close);
        if (resolved == PaymentStatus.PROCESSING) {
            return this;
        }
        return new MockPayment(pgKey, amount, resolved, scenario, completionDueAt, responseLossConsumed,
                now, createdAt, now);
    }
}
