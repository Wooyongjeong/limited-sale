package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryPolicyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void recoveryDoesNotChargeAnUnrequestedOrderBeforeExpiry() {
        assertThat(RecoveryPolicy.action("READY", NOW.plusSeconds(1), NOW, false))
                .isEqualTo(RecoveryPolicy.Action.WAIT);
    }

    @Test
    void expiryClosesEvenAnUnrequestedOrder() {
        assertThat(RecoveryPolicy.action("READY", NOW, NOW, false)).isEqualTo(RecoveryPolicy.Action.CLOSE);
    }

    @Test
    void requestedPaymentIsQueriedBeforeExpiry() {
        assertThat(RecoveryPolicy.action("UNKNOWN", NOW.plusSeconds(1), NOW, false))
                .isEqualTo(RecoveryPolicy.Action.QUERY);
    }

    @Test
    void expiredTokenCannotOwnAnOrderEvenBeforeReplacement() {
        UUID token = UUID.randomUUID();
        assertThat(RecoveryPolicy.owns(token, token, NOW, NOW)).isFalse();
        assertThat(RecoveryPolicy.owns(token, token, NOW.plusNanos(1000), NOW)).isTrue();
        assertThat(RecoveryPolicy.owns(token, UUID.randomUUID(), NOW.plusSeconds(1), NOW)).isFalse();
    }
}
