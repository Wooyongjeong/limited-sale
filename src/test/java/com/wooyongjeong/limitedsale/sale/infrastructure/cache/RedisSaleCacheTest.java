package com.wooyongjeong.limitedsale.sale.infrastructure.cache;

import com.wooyongjeong.limitedsale.sale.domain.CachedSale;
import com.wooyongjeong.limitedsale.sale.domain.SaleCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSaleCacheTest {
    private static final String KEY = "limited-sale:cache:sale:v1:7";
    private static final Instant STARTS_AT = Instant.parse("2030-01-01T00:00:00.123456Z");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RedisSaleCache cache;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        cache = new RedisSaleCache(redis, registry, Duration.ofSeconds(60));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static CachedSale sale() {
        return new CachedSale(7, 3, "name", "description", "product", 1000, STARTS_AT,
            STARTS_AT.plusSeconds(3600), 10, 2, 4, true, 5, STARTS_AT.minusSeconds(60), STARTS_AT.minusSeconds(30));
    }

    private double count(String name, String result) {
        return registry.get(name).tag("result", result).counter().count();
    }

    private String stored() {
        var json = ArgumentCaptor.forClass(String.class);
        cache.put(sale());
        verify(values).set(eq(KEY), json.capture(), eq(Duration.ofSeconds(60)));
        return json.getValue();
    }

    @Test
    void ttlMustBePositive() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new RedisSaleCache(redis, registry, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void storedJsonReadsBackWithMicrosecondTimesAndWithoutAStatus() {
        String json = stored();
        assertThat(json).doesNotContain("status");
        when(values.get(KEY)).thenReturn(json);

        var lookup = cache.find(7);

        assertThat(lookup).isEqualTo(new SaleCache.Lookup.Hit(sale()));
        assertThat(count("sale.cache.requests", "hit")).isEqualTo(1);
    }

    @Test
    void unreadableOrInconsistentValuesAreMissesAndTheKeyIsDeleted() {
        String valid = stored();
        List<String> corrupt = List.of("{not json", "", "{}", "null", valid.replace("\"saleId\":7", "\"saleId\":8"),
            valid.replace("\"totalQuantity\":10", "\"totalQuantity\":0"),
            valid.replace("\"soldQuantity\":4", "\"soldQuantity\":11"),
            valid.replace("\"stopped\":true,", ""), valid.replace("}", ",\"extra\":1}"),
            valid.replace("\"endsAt\":\"2030-01-01T01:00:00.123456Z\"", "\"endsAt\":\"2029-01-01T00:00:00Z\""));
        for (String value : corrupt) {
            when(values.get(KEY)).thenReturn(value);
            assertThat(cache.find(7)).as(value).isEqualTo(new SaleCache.Lookup.Miss());
        }

        verify(redis, org.mockito.Mockito.times(corrupt.size())).delete(KEY);
        assertThat(count("sale.cache.requests", "miss")).isEqualTo(corrupt.size());
        assertThat(count("sale.cache.requests", "error")).isZero();
    }

    @Test
    void redisFailuresOnReadAndWriteAreCountedAndNeverThrown() {
        when(values.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.find(7)).isEqualTo(new SaleCache.Lookup.Failure());
        assertThat(count("sale.cache.requests", "error")).isEqualTo(1);

        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down")).when(values)
            .set(any(), any(), any(Duration.class));
        cache.put(sale());
        assertThat(count("sale.cache.requests", "error")).isEqualTo(2);
    }

    @Test
    void removalWaitsForCommitAndIsSkippedOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit(7);
        verify(redis, never()).delete(KEY);
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redis, never()).delete(KEY);
        assertThat(count("sale.cache.evictions", "success")).isZero();

        synchronizations.get(0).afterCommit();
        verify(redis).delete(KEY);
        assertThat(count("sale.cache.evictions", "success")).isEqualTo(1);
    }

    @Test
    void failedRemovalAfterCommitIsCountedAndDoesNotPropagate() {
        when(redis.delete(KEY)).thenThrow(new RedisConnectionFailureException("down"));
        TransactionSynchronizationManager.initSynchronization();
        cache.evictAfterCommit(7);

        TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

        assertThat(count("sale.cache.evictions", "error")).isEqualTo(1);
        assertThat(count("sale.cache.evictions", "success")).isZero();
    }
}
