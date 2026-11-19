package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentOperations;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentOperations implements PaymentOperations {
    private final PaymentJpaRepository payments;

    public JpaPaymentOperations(PaymentJpaRepository payments) {
        this.payments = payments;
    }

    @Override
    public PaymentDetails find(long orderId) {
        return payments.findByOrderId(orderId).orElseThrow().details();
    }

    @Override
    public Map<Long, String> statuses(Collection<Long> orderIds) {
        Map<Long, String> statuses = payments.findByOrderIdIn(orderIds).stream()
            .collect(Collectors.toUnmodifiableMap(PaymentEntity::orderId, payment -> payment.details().status()));
        if (statuses.size() != Set.copyOf(orderIds).size()) {
            throw new NoSuchElementException("Payment is missing for a requested order");
        }
        return statuses;
    }

    @Override
    public PaymentDetails lock(long orderId) {
        return payments.lockByOrderId(orderId).orElseThrow().details();
    }

    @Override
    public PaymentDetails markStarted(long orderId, Instant now) {
        PaymentEntity payment = payments.lockByOrderId(orderId).orElseThrow();
        payment.start(now);
        return payment.details();
    }

    @Override
    public PaymentDetails record(long orderId, String status, Instant now) {
        PaymentEntity payment = payments.lockByOrderId(orderId).orElseThrow();
        payment.record(status, now);
        return payment.details();
    }
}
