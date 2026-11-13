package com.wooyongjeong.mockpg.presentation.response;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID pgKey, long amount, String status, Instant createdAt, Instant updatedAt,
        Instant finalizedAt) {
}
