package com.wooyongjeong.limitedsale.sale.infrastructure.gate;

import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import com.wooyongjeong.limitedsale.sale.domain.SoldOutMarker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Keeps one marker key per sale under `limited-sale:sale:sold-out:v1:{id}`; the value carries no meaning, the key's
 * existence is the hint. Every mark carries the TTL, so a marker left behind by a failed clear or a reordered
 * commit disappears by itself and at worst turns away orders for that long.
 *
 * <p>The metrics carry only the outcome, never a sale or member identifier.
 *
 * <p>Active unless `limited-sale.sale.sold-out-gate.enabled` is false, in which case {@link DisabledSoldOutGate}
 * replaces it.
 */
@Component
@ConditionalOnProperty(prefix = "limited-sale.sale.sold-out-gate", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class RedisSoldOutGate implements SoldOutGate, SoldOutMarker {
    private static final Logger log = LoggerFactory.getLogger(RedisSoldOutGate.class);
    private static final String KEY_PREFIX = "limited-sale:sale:sold-out:v1:";
    private static final String REQUESTS = "sale.sold.out.gate.requests";
    private static final String UPDATES = "sale.sold.out.gate.updates";
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Counter hit;
    private final Counter miss;
    private final Counter requestError;
    private final Counter markSuccess;
    private final Counter markError;
    private final Counter clearSuccess;
    private final Counter clearError;

    public RedisSoldOutGate(StringRedisTemplate redis, MeterRegistry registry,
            @Value("${limited-sale.sale.sold-out-gate.ttl:PT5S}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("limited-sale.sale.sold-out-gate.ttl must be positive");
        }
        this.redis = redis;
        this.ttl = ttl;
        // Register every outcome up front so rate() and increase() see a zero baseline.
        hit = requests(registry, "hit");
        miss = requests(registry, "miss");
        requestError = requests(registry, "error");
        markSuccess = updates(registry, "mark", "success");
        markError = updates(registry, "mark", "error");
        clearSuccess = updates(registry, "clear", "success");
        clearError = updates(registry, "clear", "error");
    }

    private static Counter requests(MeterRegistry registry, String result) {
        return Counter.builder(REQUESTS).description("Sold-out pre-check lookups by outcome")
            .tag("result", result).register(registry);
    }

    private static Counter updates(MeterRegistry registry, String operation, String result) {
        return Counter.builder(UPDATES).description("Sold-out marker writes after commit by operation and outcome")
            .tag("operation", operation).tag("result", result).register(registry);
    }

    private static String key(long saleId) {
        return KEY_PREFIX + saleId;
    }

    @Override
    public boolean isMarked(long saleId) {
        try {
            boolean marked = Boolean.TRUE.equals(redis.hasKey(key(saleId)));
            (marked ? hit : miss).increment();
            return marked;
        } catch (RuntimeException exception) {
            requestError.increment();
            log.warn("품절 사전 판정 조회 실패, MySQL로 판정합니다: saleId={}, cause={}", saleId, exception.toString());
            return false;
        }
    }

    @Override
    public void markAfterCommit(long saleId) {
        afterCommit(() -> mark(saleId));
    }

    @Override
    public void clearAfterCommit(long saleId) {
        afterCommit(() -> clear(saleId));
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /** The transaction is already committed, so a failure is recorded instead of thrown. */
    private void mark(long saleId) {
        try {
            redis.opsForValue().set(key(saleId), "1", ttl);
            markSuccess.increment();
        } catch (RuntimeException exception) {
            markError.increment();
            log.warn("커밋 후 품절 표시 저장 실패, 이후 요청은 MySQL이 판정합니다: saleId={}, cause={}", saleId,
                exception.toString());
        }
    }

    private void clear(long saleId) {
        try {
            redis.delete(key(saleId));
            clearSuccess.increment();
        } catch (RuntimeException exception) {
            clearError.increment();
            log.error("커밋 후 품절 표시 삭제 실패, TTL({}) 동안 거짓 품절이 될 수 있습니다: saleId={}, cause={}", ttl,
                saleId, exception.toString());
        }
    }
}
