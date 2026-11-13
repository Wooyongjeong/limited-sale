package com.wooyongjeong.mockpg.domain;

import java.time.Instant;
import java.util.UUID;

public record PaymentResult(UUID pgKey, long amount, String status, Instant createdAt, Instant updatedAt,
        Instant finalizedAt, int responseDelaySeconds) {
}
