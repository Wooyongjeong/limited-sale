package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStore {
    Optional<OrderRequestOutcome> request(long memberId, UUID key);
    long create(long memberId, UUID key, long saleId, long productId, String productName, long unitPrice,
        int quantity, Instant now, Instant expiresAt);
    void record(long memberId, UUID key, long saleId, int quantity, Long orderId,
        OrderRejection rejection, Instant now);
    Optional<OrderView> find(long memberId, UUID publicId);
    OrderView find(long orderId);
    Results list(long memberId, int page, int size);

    record Results(List<OrderView> items, long total) {
        public Results {
            items = List.copyOf(items);
        }
    }
}
