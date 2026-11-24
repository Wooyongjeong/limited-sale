package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.OrderRateLimit.Operation;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestKeyCollision;
import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Accepts an order request for later processing. The request limit is the one the synchronous order path uses, so a
 * member has a single budget for creating orders however they are requested.
 */
@Service
public class OrderAcceptanceService {
    private final OrderAcceptanceTransaction transaction;
    private final OrderRateLimiter rateLimiter;
    private final SoldOutGate soldOutGate;

    public OrderAcceptanceService(OrderAcceptanceTransaction transaction, OrderRateLimiter rateLimiter,
            SoldOutGate soldOutGate) {
        this.transaction = transaction;
        this.rateLimiter = rateLimiter;
        this.soldOutGate = soldOutGate;
    }

    public OrderAcceptanceResult accept(long memberId, UUID key, long saleId, int quantity) {
        var limited = rateLimiter.reject(memberId, Operation.CREATE);
        if (limited.isPresent()) {
            return OrderAcceptanceResult.rateLimited(limited.get().retryAfter());
        }
        try {
            // Read outside any transaction so Redis is never called while a database connection is held.
            return soldOutGate.isMarked(saleId) ? transaction.rejectSoldOut(memberId, key, saleId, quantity)
                : transaction.accept(memberId, key, saleId, quantity);
        } catch (OrderRequestKeyCollision failure) {
            // A concurrent request with the same key committed first; answer with its stored result.
            return transaction.lookupRequest(memberId, key, saleId, quantity).orElseThrow(() -> failure);
        }
    }

    public OrderAcceptanceResult find(long memberId, UUID acceptanceId) {
        return transaction.find(memberId, acceptanceId);
    }
}
