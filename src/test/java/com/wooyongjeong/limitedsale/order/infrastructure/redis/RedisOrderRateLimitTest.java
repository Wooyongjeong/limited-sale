package com.wooyongjeong.limitedsale.order.infrastructure.redis;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RedisOrderRateLimitTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @Test
    void theAllowedCountMustBePositive() {
        assertThatThrownBy(() -> new RedisOrderRateLimit(redis, 0, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-requests");
        assertThatThrownBy(() -> new RedisOrderRateLimit(redis, -1, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RedisOrderRateLimit(redis, 1, Duration.ofSeconds(1))).doesNotThrowAnyException();
    }

    @Test
    void theWindowMustBeAtLeastOneMillisecond() {
        for (Duration window : new Duration[] {Duration.ZERO, Duration.ofMillis(-5), Duration.ofNanos(999_999)}) {
            assertThatThrownBy(() -> new RedisOrderRateLimit(redis, 5, window))
                .as(window.toString()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window");
        }
        assertThatCode(() -> new RedisOrderRateLimit(redis, 5, Duration.ofMillis(1))).doesNotThrowAnyException();
    }
}
