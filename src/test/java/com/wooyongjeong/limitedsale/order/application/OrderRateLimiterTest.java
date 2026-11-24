package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Decision;
import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Operation;
import com.wooyongjeong.limitedsale.payment.api.PaymentGateway;
import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderRateLimiterTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderMetrics metrics = new OrderMetrics(registry);
    private final OrderRateLimit limit = mock(OrderRateLimit.class);

    private double count(String operation, String result) {
        return registry.get("order.rate.limit").tag("operation", operation).tag("result", result).counter().count();
    }

    @Test
    void rejectionCarriesTheRemainingWindowAndIsCountedPerOperation() {
        when(limit.hit(7, Operation.PAYMENT)).thenReturn(new Decision.Rejected(Duration.ofMillis(420)));
        var rejected = new OrderRateLimiter(limit, metrics, true).reject(7, Operation.PAYMENT);
        assertThat(rejected).hasValueSatisfying(result -> {
            assertThat(result.kind()).isEqualTo(OrderResult.Kind.RATE_LIMITED);
            assertThat(result.retryAfter()).isEqualTo(Duration.ofMillis(420));
            assertThat(result.order()).isNull();
        });
        assertThat(count("payment", "rejected")).isEqualTo(1);
        assertThat(count("create", "rejected")).isZero();
    }

    @Test
    void allowedAndRedisFailureBothLetTheRequestThroughButAreCountedApart() {
        when(limit.hit(7, Operation.CREATE)).thenReturn(new Decision.Allowed(), new Decision.Unavailable());
        var limiter = new OrderRateLimiter(limit, metrics, true);
        assertThat(limiter.reject(7, Operation.CREATE)).isEmpty();
        assertThat(limiter.reject(7, Operation.CREATE)).isEmpty();
        assertThat(count("create", "allowed")).isEqualTo(1);
        assertThat(count("create", "bypassed")).isEqualTo(1);
        assertThat(count("create", "rejected")).isZero();
    }

    @Test
    void aDisabledLimitDoesNotCallRedisOrCount() {
        assertThat(new OrderRateLimiter(limit, metrics, false).reject(7, Operation.CREATE)).isEmpty();
        verifyNoInteractions(limit);
        assertThat(registry.find("order.rate.limit").counters())
            .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    void aRejectedRequestNeverReachesTheTransactionOrThePaymentGateway() {
        var transaction = mock(OrderTransaction.class);
        var gate = mock(SoldOutGate.class);
        var paymentTransaction = mock(OrderPaymentTransaction.class);
        var gateway = mock(PaymentGateway.class);
        when(limit.hit(7, Operation.CREATE)).thenReturn(new Decision.Rejected(Duration.ofSeconds(1)));
        when(limit.hit(7, Operation.PAYMENT)).thenReturn(new Decision.Rejected(Duration.ofSeconds(1)));
        var limiter = new OrderRateLimiter(limit, metrics, true);

        var created = new OrderService(transaction, metrics, limiter, gate).create(7, UUID.randomUUID(), 1, 1);
        var paid = new OrderPaymentService(paymentTransaction, gateway, metrics, limiter, Duration.ofSeconds(1))
            .pay(7, UUID.randomUUID());

        assertThat(created.kind()).isEqualTo(OrderResult.Kind.RATE_LIMITED);
        assertThat(paid.kind()).isEqualTo(OrderResult.Kind.RATE_LIMITED);
        verifyNoInteractions(transaction, paymentTransaction, gateway, gate);
        // The limit has its own counter; the business outcome counters only count requests that were processed.
        assertThat(registry.find("order.creation.results").counters())
            .allSatisfy(counter -> assertThat(counter.count()).isZero());
        assertThat(registry.find("order.payment.results").counters())
            .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }
}
