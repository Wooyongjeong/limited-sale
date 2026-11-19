package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final OrderTransaction transaction;
    private final OrderMetrics metrics;

    public OrderService(OrderTransaction transaction, OrderMetrics metrics) {
        this.transaction = transaction;
        this.metrics = metrics;
    }

    public OrderResult create(long memberId, UUID key, long saleId, int quantity) {
        OrderResult result;
        try {
            result = transaction.create(memberId, key, saleId, quantity);
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
