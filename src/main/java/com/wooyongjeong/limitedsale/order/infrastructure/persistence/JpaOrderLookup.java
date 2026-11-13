package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.api.OrderLookup;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaOrderLookup implements OrderLookup {
    private final PurchaseOrderJpaRepository orders;

    public JpaOrderLookup(PurchaseOrderJpaRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong findId(UUID publicId) {
        return orders.findByPublicId(publicId).map(order -> OptionalLong.of(order.id())).orElseGet(OptionalLong::empty);
    }
}
