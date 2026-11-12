package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    private final OrderTransaction transaction;

    public OrderService(OrderTransaction transaction) {
        this.transaction = transaction;
    }

    public OrderResult create(long memberId, UUID key, long saleId, int quantity) {
        try {
            return transaction.create(memberId, key, saleId, quantity);
        } catch (OrderRequestKeyCollision failure) {
            return transaction.lookupRequest(memberId, key, saleId, quantity).orElseThrow(() -> failure);
        }
    }

    public OrderResult find(long memberId, UUID publicId) {
        return transaction.find(memberId, publicId);
    }
}
