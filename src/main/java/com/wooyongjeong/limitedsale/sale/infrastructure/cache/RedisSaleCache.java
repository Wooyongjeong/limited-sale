package com.wooyongjeong.limitedsale.sale.infrastructure.cache;

import com.wooyongjeong.limitedsale.sale.domain.CachedSale;
import com.wooyongjeong.limitedsale.sale.domain.SaleCache;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores {@link CachedSale} as JSON under `limited-sale:cache:sale:v1:{id}`. Raise the version in the key whenever
 * the JSON fields change so entries written by an older build are never read as the new shape.
 *
 * <p>The metrics carry only the outcome, never a sale or member identifier.
 *
 * <p>Active unless `limited-sale.sale.cache.enabled` is false, in which case {@link DisabledSaleCache} replaces it.
 */
@Component
@ConditionalOnProperty(prefix = "limited-sale.sale.cache", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class RedisSaleCache implements SaleCache {
    private static final Logger log = LoggerFactory.getLogger(RedisSaleCache.class);
    private static final String KEY_PREFIX = "limited-sale:cache:sale:v1:";
    private static final String REQUESTS = "sale.cache.requests";
    private static final String EVICTIONS = "sale.cache.evictions";
    // A private mapper keeps the stored format independent of application-wide Jackson settings.
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectReader READER = MAPPER.readerFor(CachedSale.class).with(
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Counter hit;
    private final Counter miss;
    private final Counter requestError;
    private final Counter evictionSuccess;
    private final Counter evictionError;

    public RedisSaleCache(StringRedisTemplate redis, MeterRegistry registry,
            @Value("${limited-sale.sale.cache.ttl:PT60S}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("limited-sale.sale.cache.ttl must be positive");
        }
        this.redis = redis;
        this.ttl = ttl;
        // Register every outcome up front so rate() and increase() see a zero baseline.
        hit = requests(registry, "hit");
        miss = requests(registry, "miss");
        requestError = requests(registry, "error");
        evictionSuccess = evictions(registry, "success");
        evictionError = evictions(registry, "error");
    }

    private static Counter requests(MeterRegistry registry, String result) {
        return Counter.builder(REQUESTS).description("Sale detail cache lookups and stores by outcome")
            .tag("result", result).register(registry);
    }

    private static Counter evictions(MeterRegistry registry, String result) {
        return Counter.builder(EVICTIONS).description("Sale detail cache removals after commit by outcome")
            .tag("result", result).register(registry);
    }

    private static String key(long saleId) {
        return KEY_PREFIX + saleId;
    }

    @Override
    public Lookup find(long saleId) {
        String json;
        try {
            json = redis.opsForValue().get(key(saleId));
        } catch (RuntimeException exception) {
            requestError.increment();
            log.warn("판매 이벤트 캐시 조회 실패, DB로 응답합니다: saleId={}, cause={}", saleId, exception.toString());
            return new Lookup.Failure();
        }
        if (json == null) {
            miss.increment();
            return new Lookup.Miss();
        }
        CachedSale sale;
        try {
            sale = READER.readValue(json);
            if (sale.saleId() != saleId) {
                throw new IllegalStateException("Cached sale id " + sale.saleId() + " differs from key");
            }
        } catch (RuntimeException exception) {
            // An unreadable entry is a miss; removing it lets the caller replace it with a fresh value.
            miss.increment();
            log.warn("판매 이벤트 캐시 값을 읽을 수 없어 미적중으로 처리하고 키를 지웁니다: saleId={}, cause={}",
                saleId, exception.toString());
            discard(saleId);
            return new Lookup.Miss();
        }
        hit.increment();
        return new Lookup.Hit(sale);
    }

    @Override
    public void put(CachedSale sale) {
        try {
            redis.opsForValue().set(key(sale.saleId()), MAPPER.writeValueAsString(sale), ttl);
        } catch (RuntimeException exception) {
            requestError.increment();
            log.warn("판매 이벤트 캐시 저장 실패, DB 결과로 응답합니다: saleId={}, cause={}", sale.saleId(),
                exception.toString());
        }
    }

    @Override
    public void evictAfterCommit(long saleId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evict(saleId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(saleId);
            }
        });
    }

    /** The transaction is already committed, so a failure is recorded instead of thrown. */
    private void evict(long saleId) {
        try {
            redis.delete(key(saleId));
            evictionSuccess.increment();
        } catch (RuntimeException exception) {
            evictionError.increment();
            log.error("커밋 후 판매 이벤트 캐시 삭제 실패, TTL({}) 안에 옛 값이 보일 수 있습니다: saleId={}, cause={}",
                ttl, saleId, exception.toString());
        }
    }

    private void discard(long saleId) {
        try {
            redis.delete(key(saleId));
        } catch (RuntimeException exception) {
            log.warn("읽을 수 없는 판매 이벤트 캐시 키 삭제 실패: saleId={}, cause={}", saleId, exception.toString());
        }
    }
}
