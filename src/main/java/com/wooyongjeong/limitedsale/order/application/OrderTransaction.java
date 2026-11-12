package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestOutcome;
import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.payment.api.PaymentPreparation;
import com.wooyongjeong.limitedsale.sale.api.SaleAllocation;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderTransaction {
    private final OrderStore orders;
    private final SaleReservation sales;
    private final PaymentPreparation payments;
    private final Duration expiration;

    public OrderTransaction(OrderStore orders, SaleReservation sales, PaymentPreparation payments,
            @Value("${limited-sale.order.expiration:PT5M}") Duration expiration) {
        if (expiration.isNegative() || expiration.isZero()) {
            throw new IllegalArgumentException("Order expiration must be positive");
        }
        this.orders = orders;
        this.sales = sales;
        this.payments = payments;
        this.expiration = expiration;
    }

    @Transactional
    public OrderResult create(long memberId, UUID key, long saleId, int quantity) {
        var existing = orders.request(memberId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), saleId, quantity);
        }
        SaleAllocation allocation = sales.allocate(saleId, memberId, quantity);
        if (allocation.reason() != SaleAllocation.Reason.ALLOCATED) {
            OrderRejection reason = rejection(allocation.reason());
            orders.record(memberId, key, saleId, quantity, null, reason, allocation.time());
            return rejected(reason);
        }
        long orderId = orders.create(memberId, key, saleId, allocation.productId(), allocation.productName(),
            allocation.unitPrice(), quantity, allocation.time(), allocation.time().plus(expiration));
        sales.attach(orderId, saleId, memberId, quantity, allocation.time());
        payments.prepare(orderId, Math.multiplyExact(allocation.unitPrice(), quantity), allocation.time());
        orders.record(memberId, key, saleId, quantity, orderId, null, allocation.time());
        return new OrderResult(OrderResult.Kind.CREATED, orders.find(orderId));
    }

    @Transactional(readOnly = true)
    public Optional<OrderResult> lookupRequest(long memberId, UUID key, long saleId, int quantity) {
        return orders.request(memberId, key).map(outcome -> replay(outcome, saleId, quantity));
    }

    @Transactional(readOnly = true)
    public OrderResult find(long memberId, UUID publicId) {
        return orders.find(memberId, publicId).map(order -> new OrderResult(OrderResult.Kind.REPLAYED, order))
            .orElseGet(() -> new OrderResult(OrderResult.Kind.ORDER_NOT_FOUND, null));
    }

    private OrderResult replay(OrderRequestOutcome existing, long saleId, int quantity) {
        if (existing.saleId() != saleId || existing.quantity() != quantity) {
            return new OrderResult(OrderResult.Kind.IDEMPOTENCY_CONFLICT, null);
        }
        if (existing.orderId() == null) {
            return rejected(existing.rejection());
        }
        return new OrderResult(OrderResult.Kind.REPLAYED, orders.find(existing.orderId()));
    }

    private OrderRejection rejection(SaleAllocation.Reason reason) {
        return switch (reason) {
            case SALE_NOT_FOUND -> OrderRejection.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderRejection.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderRejection.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> OrderRejection.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> OrderRejection.SALE_SOLD_OUT;
            case ALLOCATED -> throw new IllegalStateException("Allocated stock is not a rejection");
        };
    }

    private OrderResult rejected(OrderRejection reason) {
        OrderResult.Kind kind = switch (reason) {
            case SALE_NOT_FOUND -> OrderResult.Kind.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderResult.Kind.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderResult.Kind.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> OrderResult.Kind.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> OrderResult.Kind.SALE_SOLD_OUT;
        };
        return new OrderResult(kind, null);
    }

}
