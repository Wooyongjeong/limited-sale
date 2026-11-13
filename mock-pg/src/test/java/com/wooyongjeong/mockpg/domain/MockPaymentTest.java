package com.wooyongjeong.mockpg.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPaymentTest {
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void declinedPaymentRemainsFailedAfterClose() {
        var payment = MockPayment.start(UUID.randomUUID(), 10,
                new ScenarioConfiguration(Scenario.DECLINE, null), now, false);
        assertThat(payment.resolve(now.plusSeconds(10), true).status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void responseLossIsConsumedWhenSuccessIsCreated() {
        var payment = MockPayment.start(UUID.randomUUID(), 10,
                new ScenarioConfiguration(Scenario.RESPONSE_LOSS, null), now, false);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.responseLossConsumed()).isTrue();
    }
}
