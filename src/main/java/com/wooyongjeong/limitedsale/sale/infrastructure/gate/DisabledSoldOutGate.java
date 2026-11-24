package com.wooyongjeong.limitedsale.sale.infrastructure.gate;

import com.wooyongjeong.limitedsale.sale.api.SoldOutGate;
import com.wooyongjeong.limitedsale.sale.domain.SoldOutMarker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stands in for {@link RedisSoldOutGate} when `limited-sale.sale.sold-out-gate.enabled` is false so every order is
 * decided by MySQL. It never touches Redis and registers no metrics, which lets a comparison run measure "no gate".
 */
@Component
@ConditionalOnProperty(prefix = "limited-sale.sale.sold-out-gate", name = "enabled", havingValue = "false")
public class DisabledSoldOutGate implements SoldOutGate, SoldOutMarker {
    @Override
    public boolean isMarked(long saleId) {
        return false;
    }

    @Override
    public void markAfterCommit(long saleId) {
    }

    @Override
    public void clearAfterCommit(long saleId) {
    }
}
