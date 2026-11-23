package com.wooyongjeong.limitedsale.sale.infrastructure.cache;

import com.wooyongjeong.limitedsale.sale.domain.CachedSale;
import com.wooyongjeong.limitedsale.sale.domain.SaleCache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for {@link RedisSaleCache} when `limited-sale.sale.cache.enabled` is false so the sale detail query
 * always reads MySQL. It never touches Redis and registers no metrics, which lets a comparison run measure
 * "no cache" instead of "a cache with a very short TTL".
 */
@Component
@ConditionalOnProperty(prefix = "limited-sale.sale.cache", name = "enabled", havingValue = "false")
public class DisabledSaleCache implements SaleCache {
    @Override
    public Lookup find(long saleId) {
        return new Lookup.Miss();
    }

    @Override
    public void put(CachedSale sale) {
    }

    @Override
    public void evictAfterCommit(long saleId) {
    }
}
