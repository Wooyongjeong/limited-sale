package com.wooyongjeong.limitedsale.sale.infrastructure.gate;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisSoldOutGateTest {
    private static final String KEY = "limited-sale:sale:sold-out:v1:7";
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private RedisSoldOutGate gate;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        gate = new RedisSoldOutGate(redis, registry, Duration.ofSeconds(5));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private double requests(String result) {
        return registry.get("sale.sold.out.gate.requests").tag("result", result).counter().count();
    }

    private double updates(String operation, String result) {
        return registry.get("sale.sold.out.gate.updates").tag("operation", operation).tag("result", result)
            .counter().count();
    }

    @Test
    void ttlMustBePositive() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1))) {
            assertThatThrownBy(() -> new RedisSoldOutGate(redis, registry, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void everyOutcomeStartsAtZeroAndCarriesNoIdentifierLabel() {
        for (String result : new String[] {"hit", "miss", "error"}) {
            assertThat(requests(result)).isZero();
        }
        for (String operation : new String[] {"mark", "clear"}) {
            for (String result : new String[] {"success", "error"}) {
                assertThat(updates(operation, result)).isZero();
            }
        }
        for (Meter meter : registry.getMeters()) {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey())
                .isSubsetOf("result", "operation");
        }
        assertThat(registry.getMeters()).hasSize(7);
    }

    @Test
    void lookupReportsHitAndMissFromKeyExistence() {
        when(redis.hasKey(KEY)).thenReturn(true);
        when(redis.hasKey("limited-sale:sale:sold-out:v1:8")).thenReturn(false);

        assertThat(gate.isMarked(7)).isTrue();
        assertThat(gate.isMarked(8)).isFalse();

        assertThat(requests("hit")).isEqualTo(1);
        assertThat(requests("miss")).isEqualTo(1);
        assertThat(requests("error")).isZero();
    }

    @Test
    void aFailedLookupReadsAsNotMarkedAndIsCounted() {
        when(redis.hasKey(KEY)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(gate.isMarked(7)).isFalse();

        assertThat(requests("error")).isEqualTo(1);
        assertThat(requests("hit")).isZero();
        assertThat(requests("miss")).isZero();
    }

    @Test
    void aNullAnswerFromRedisIsNotMarked() {
        when(redis.hasKey(KEY)).thenReturn(null);

        assertThat(gate.isMarked(7)).isFalse();
    }

    @Test
    void markAndClearWaitForCommitAndAreSkippedOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        gate.markAfterCommit(7);
        gate.clearAfterCommit(7);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());
        List<TransactionSynchronization> pending = TransactionSynchronizationManager.getSynchronizations();
        assertThat(pending).hasSize(2);

        pending.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
        verify(redis, never()).delete(anyString());

        pending.forEach(TransactionSynchronization::afterCommit);
        var ttl = ArgumentCaptor.forClass(Duration.class);
        verify(values).set(org.mockito.ArgumentMatchers.eq(KEY), anyString(), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(5));
        verify(redis).delete(KEY);
        assertThat(updates("mark", "success")).isEqualTo(1);
        assertThat(updates("clear", "success")).isEqualTo(1);
    }

    @Test
    void failedMarkAndClearAreCountedAndNeverThrown() {
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(),
            any(Duration.class));
        doThrow(new RedisConnectionFailureException("down")).when(redis).delete(anyString());
        TransactionSynchronizationManager.initSynchronization();
        gate.markAfterCommit(7);
        gate.clearAfterCommit(7);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertThat(updates("mark", "error")).isEqualTo(1);
        assertThat(updates("clear", "error")).isEqualTo(1);
        assertThat(updates("mark", "success")).isZero();
        assertThat(updates("clear", "success")).isZero();
    }

    @Test
    void outsideATransactionTheWriteHappensAtOnce() {
        gate.markAfterCommit(7);
        gate.clearAfterCommit(7);

        verify(values).set(org.mockito.ArgumentMatchers.eq(KEY), anyString(), org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(5)));
        verify(redis).delete(KEY);
    }
}
