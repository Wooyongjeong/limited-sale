package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentKeyLookup {
    private final PaymentJpaRepository payments;

    public PaymentKeyLookup(PaymentJpaRepository payments) {
        this.payments = payments;
    }

    @Transactional(readOnly = true)
    public UUID find(long orderId) {
        return payments.findByOrderId(orderId).orElseThrow().pgKey();
    }
}
