package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaOrderStoreTest {
    private final PurchaseOrderJpaRepository orders = mock(PurchaseOrderJpaRepository.class);
    private final OrderRequestResultJpaRepository results = mock(OrderRequestResultJpaRepository.class);
    private final JpaOrderStore store = new JpaOrderStore(orders, results);

    @Test
    void onlyTheTwoRequestKeyConstraintsBecomeRequestKeyCollisions() {
        UUID key = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        when(orders.saveAndFlush(any())).thenThrow(duplicate("sales.uk_orders_request"));
        assertThatThrownBy(() -> store.create(1, key, 2, 3, "product", 100, 1, now, now.plusSeconds(300)))
            .isInstanceOf(OrderRequestKeyCollision.class);
        when(results.saveAndFlush(any())).thenThrow(duplicate("uk_order_request_results_member_key"));
        assertThatThrownBy(() -> store.record(1, key, 2, 1, null, OrderRejection.SALE_NOT_FOUND, now))
            .isInstanceOf(OrderRequestKeyCollision.class);
        var other = duplicate("uk_payments_pg_key");
        doThrow(other).when(results).saveAndFlush(any());
        assertThatThrownBy(() -> store.record(1, key, 2, 1, null, OrderRejection.SALE_NOT_FOUND, now)).isSameAs(other);
    }

    private DataIntegrityViolationException duplicate(String constraint) {
        var sql = new SQLException("duplicate", "23000", 1062);
        return new DataIntegrityViolationException("duplicate", new ConstraintViolationException("duplicate", sql,
            constraint));
    }
}
