package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public record SaleCreation(long productId, String name, String description, Long unitPrice,
        Instant startsAt, Instant endsAt, int totalQuantity, Integer perMemberLimit) {
}
