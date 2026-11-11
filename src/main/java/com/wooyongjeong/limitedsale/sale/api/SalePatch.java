package com.wooyongjeong.limitedsale.sale.api;

import java.time.Instant;

public record SalePatch(long version, Long productId, String name, String description, Long unitPrice,
        Instant startsAt, Instant endsAt, Integer totalQuantity, Integer perMemberLimit) {
}
