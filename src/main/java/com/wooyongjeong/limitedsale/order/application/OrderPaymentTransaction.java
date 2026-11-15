package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrder;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrderStore;
import com.wooyongjeong.limitedsale.payment.api.GatewayResult;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentOperations;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderPaymentTransaction {
    private final PaymentOrderStore orders;
    private final OrderStore views;
    private final PaymentOperations payments;
    private final SaleReservation sales;
    private final Duration retryInterval;

    public OrderPaymentTransaction(PaymentOrderStore orders, OrderStore views, PaymentOperations payments,
            SaleReservation sales, @Value("${limited-sale.payment.retry-interval:PT10S}") Duration retryInterval) {
        if (retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("Payment retry interval must be positive");
        }
        this.orders = orders;
        this.views = views;
        this.payments = payments;
        this.sales = sales;
        this.retryInterval = retryInterval;
    }

    @Transactional
    public PaymentAttempt begin(long memberId, UUID publicId) {
        var found = orders.lock(memberId, publicId);
        if (found.isEmpty()) {
            return new PaymentAttempt(0, null, PaymentAttempt.Action.NOT_FOUND);
        }
        PaymentOrder order = found.get();
        PaymentDetails payment = payments.lock(order.id());
        Instant now = orders.databaseTime();
        if (terminalOrder(order.status()) || terminalPayment(payment.status())) {
            return new PaymentAttempt(order.id(), payment, PaymentAttempt.Action.DONE);
        }
        boolean expired = !now.isBefore(order.expiresAt());
        if (!expired && !"READY".equals(payment.status()) && order.nextRecoveryAt() != null
                && now.isBefore(order.nextRecoveryAt())) {
            return new PaymentAttempt(order.id(), payment, PaymentAttempt.Action.WAIT);
        }
        PaymentAttempt.Action action = expired ? PaymentAttempt.Action.CLOSE
                : "READY".equals(payment.status()) ? PaymentAttempt.Action.CHARGE : PaymentAttempt.Action.QUERY;
        orders.pending(order.id(), now, now.plus(retryInterval));
        payment = payments.markStarted(order.id(), now);
        return new PaymentAttempt(order.id(), payment, action);
    }

    @Transactional
    public boolean expired(long orderId) {
        PaymentOrder order = orders.lock(orderId);
        return !orders.databaseTime().isBefore(order.expiresAt());
    }

    @Transactional
    public void record(long orderId, GatewayResult result) {
        orders.lock(orderId);
        payments.record(orderId, result == GatewayResult.MISSING ? "UNKNOWN" : result.name(), orders.databaseTime());
    }

    @Transactional
    public OrderResult finish(long orderId) {
        PaymentOrder order = orders.lock(orderId);
        PaymentDetails payment = payments.lock(orderId);
        if (!terminalOrder(order.status()) && terminalPayment(payment.status())) {
            Instant now = orders.databaseTime();
            boolean success = "SUCCEEDED".equals(payment.status());
            sales.finish(order.id(), order.saleId(), order.memberId(), order.quantity(), success, now);
            String status = success ? "CONFIRMED" : "FAILED".equals(payment.status()) ? "PAYMENT_FAILED" : "EXPIRED";
            orders.finish(order.id(), status, now);
            order = orders.lock(orderId);
        }
        OrderResult.Kind kind = switch (order.status()) {
            case "CONFIRMED" -> OrderResult.Kind.PAYMENT_SUCCEEDED;
            case "PAYMENT_FAILED" -> OrderResult.Kind.PAYMENT_DECLINED;
            case "EXPIRED" -> OrderResult.Kind.ORDER_EXPIRED;
            default -> OrderResult.Kind.PAYMENT_PENDING;
        };
        return new OrderResult(kind, views.find(orderId));
    }

    private boolean terminalOrder(String status) {
        return "CONFIRMED".equals(status) || "PAYMENT_FAILED".equals(status) || "EXPIRED".equals(status);
    }

    private boolean terminalPayment(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CLOSED".equals(status);
    }
}
