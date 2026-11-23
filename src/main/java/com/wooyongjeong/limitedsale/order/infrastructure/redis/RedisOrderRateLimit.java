package com.wooyongjeong.limitedsale.order.infrastructure.redis;

import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed window that starts at the first request, stored under
 * `limited-sale:rate-limit:order:{create|payment}:{memberId}`. The script increments, arms the expiry on the first
 * hit and returns the count with the remaining time in one atomic step, so instances never need a shared clock.
 *
 * <p>The default allows one request per window. In the stage 2-6 b3 reference run, abusive members who sent about
 * 1.6 requests per second never reached the earlier default of five, while a limit of one rejected 81% of their
 * requests and left normal members, who send one order and one payment, unaffected.
 */
@Component
public class RedisOrderRateLimit implements OrderRateLimit {
    private static final Logger log = LoggerFactory.getLogger(RedisOrderRateLimit.class);
    private static final String KEY_PREFIX = "limited-sale:rate-limit:order:";
    // PTTL -1 means the key has no expiry (written by something else); arming it again prevents a permanent block.
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> HIT = new DefaultRedisScript<>("""
        local count = redis.call('INCR', KEYS[1])
        local ttl = redis.call('PTTL', KEYS[1])
        if count == 1 or ttl < 0 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            ttl = tonumber(ARGV[1])
        end
        return {count, ttl}
        """, List.class);
    private final StringRedisTemplate redis;
    private final int maxRequests;
    private final Duration window;

    public RedisOrderRateLimit(StringRedisTemplate redis,
            @Value("${limited-sale.order.rate-limit.max-requests:1}") int maxRequests,
            @Value("${limited-sale.order.rate-limit.window:1s}") Duration window) {
        if (maxRequests <= 0) {
            throw new IllegalArgumentException("limited-sale.order.rate-limit.max-requests must be positive");
        }
        if (window.isZero() || window.isNegative() || window.toMillis() == 0) {
            throw new IllegalArgumentException(
                "limited-sale.order.rate-limit.window must be at least one millisecond");
        }
        this.redis = redis;
        this.maxRequests = maxRequests;
        this.window = window;
    }

    @Override
    public Decision hit(long memberId, Operation operation) {
        try {
            List<?> result = redis.execute(HIT, List.of(key(memberId, operation)), Long.toString(window.toMillis()));
            if (result == null || result.size() != 2) {
                throw new IllegalStateException("Unexpected rate limit script result: " + result);
            }
            long count = ((Number) result.get(0)).longValue();
            long remainingMillis = ((Number) result.get(1)).longValue();
            if (count <= maxRequests) {
                return new Decision.Allowed();
            }
            return new Decision.Rejected(Duration.ofMillis(remainingMillis));
        } catch (RuntimeException exception) {
            // The wrapper alone ("Error in execution") hides what Redis answered, so the root cause is logged too.
            log.warn("주문 요청 제한 확인 실패, 제한 없이 통과시킵니다: operation={}, cause={}, root={}", operation,
                exception.toString(), NestedExceptionUtils.getMostSpecificCause(exception).toString());
            return new Decision.Unavailable();
        }
    }

    private static String key(long memberId, Operation operation) {
        return KEY_PREFIX + operation.name().toLowerCase(Locale.ROOT) + ":" + memberId;
    }
}
