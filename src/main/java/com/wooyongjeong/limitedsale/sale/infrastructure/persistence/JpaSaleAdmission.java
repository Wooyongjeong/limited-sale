package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import com.wooyongjeong.limitedsale.sale.api.SaleAdmission;
import com.wooyongjeong.limitedsale.sale.api.SaleAdmissionResult;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaSaleAdmission implements SaleAdmission {
    private final SaleJpaRepository sales;

    public JpaSaleAdmission(SaleJpaRepository sales) {
        this.sales = sales;
    }

    @Override
    public SaleAdmissionResult check(long saleId) {
        // A plain read, never FOR UPDATE: an accepted request must not queue behind the sale row lock.
        var found = sales.findById(saleId);
        Instant now = Instant.parse(sales.databaseTime());
        if (found.isEmpty()) {
            return new SaleAdmissionResult(SaleAdmissionResult.Reason.SALE_NOT_FOUND, now);
        }
        var reason = switch (found.get().toSale().admissionDecision(now)) {
            case SALE_OUTSIDE_PERIOD -> SaleAdmissionResult.Reason.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> SaleAdmissionResult.Reason.SALE_STOPPED;
            case ALLOCATED -> SaleAdmissionResult.Reason.ADMITTED;
            case PURCHASE_LIMIT_EXCEEDED, SALE_SOLD_OUT ->
                throw new IllegalStateException("Stock and purchase limits are not part of admission");
        };
        return new SaleAdmissionResult(reason, now);
    }
}
