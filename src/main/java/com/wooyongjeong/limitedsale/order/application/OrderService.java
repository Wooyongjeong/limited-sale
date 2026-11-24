package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Operation;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final OrderTransaction transaction;
    private final OrderMetrics metrics;
    private final OrderRateLimiter rateLimiter;
    private final SoldOutGate soldOutGate;

    public OrderService(OrderTransaction transaction, OrderMetrics metrics, OrderRateLimiter rateLimiter,
            SoldOutGate soldOutGate) {
        this.transaction = transaction;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.soldOutGate = soldOutGate;
    }

    public OrderResult create(long memberId, UUID key, long saleId, int quantity) {
        Optional<OrderResult> rejected = rateLimiter.reject(memberId, Operation.CREATE);
        if (rejected.isPresent()) {
            return rejected.get();
        }
        OrderResult result;
        try {
            // Checked here, outside any transaction, so Redis is never called while the sale row lock is held.
            result = soldOutGate.isMarked(saleId) ? transaction.rejectSoldOut(memberId, key, saleId, quantity)
                : transaction.create(memberId, key, saleId, quantity);
        } catch (OrderRequestKeyCollision failure) {
            result = transaction.lookupRequest(memberId, key, saleId, quantity).orElseThrow(() -> failure);
        }
        metrics.creation(result.kind());
        return result;
    }

    public OrderResult find(long memberId, UUID publicId) {
        return transaction.find(memberId, publicId);
    }

    public OrderPage list(long memberId, int page, int size) {
        return transaction.list(memberId, page, size);
    }
}
