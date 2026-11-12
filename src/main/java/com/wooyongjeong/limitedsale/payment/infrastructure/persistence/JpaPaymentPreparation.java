package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import com.wooyongjeong.limitedsale.payment.api.PaymentPreparation;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentPreparation implements PaymentPreparation {
    private final PaymentJpaRepository payments;

    public JpaPaymentPreparation(PaymentJpaRepository payments) {
        this.payments = payments;
    }

    @Override
    public void prepare(long orderId, long amount, Instant time) {
        payments.save(new PaymentEntity(orderId, amount, time));
    }
}
