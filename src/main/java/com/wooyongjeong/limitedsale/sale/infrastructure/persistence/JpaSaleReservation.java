package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import com.wooyongjeong.limitedsale.sale.api.SaleAllocation;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import com.wooyongjeong.limitedsale.sale.domain.SaleCache;
import java.time.Instant;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JpaSaleReservation implements SaleReservation {
    private final SaleJpaRepository sales;
    private final SaleBuyerQuantityJpaRepository buyers;
    private final SaleReservationJpaRepository reservations;
    private final SaleCache cache;

    public JpaSaleReservation(SaleJpaRepository sales, SaleBuyerQuantityJpaRepository buyers,
            SaleReservationJpaRepository reservations, SaleCache cache) {
        this.sales = sales;
        this.buyers = buyers;
        this.reservations = reservations;
        this.cache = cache;
    }

    @Override
    public SaleAllocation allocate(long saleId, long memberId, int quantity) {
        var found = sales.findForUpdate(saleId);
        if (found.isEmpty()) {
            return new SaleAllocation(SaleAllocation.Reason.SALE_NOT_FOUND, 0, null, 0,
                Instant.parse(sales.databaseTime()));
        }
        SaleEntity entity = found.get();
        SaleBuyerQuantityEntity buyer = buyers.findForUpdate(saleId, memberId).orElse(null);
        Instant now = Instant.parse(sales.databaseTime());
        var sale = entity.toSale();
        var terms = sale.terms();
        var decision = sale.allocationDecision(now, buyer == null ? 0 : buyer.total(), quantity);
        SaleAllocation.Reason reason = switch (decision) {
            case ALLOCATED -> SaleAllocation.Reason.ALLOCATED;
            case SALE_OUTSIDE_PERIOD -> SaleAllocation.Reason.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> SaleAllocation.Reason.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> SaleAllocation.Reason.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> SaleAllocation.Reason.SALE_SOLD_OUT;
        };
        if (reason == SaleAllocation.Reason.ALLOCATED) {
            if (buyer == null) {
                buyer = new SaleBuyerQuantityEntity(saleId, memberId, now);
                buyers.save(buyer);
            }
            buyer.reserve(quantity, now);
            entity.reserve(quantity, now);
        }
        return new SaleAllocation(reason, terms.productId(), sale.productNameSnapshot(), terms.unitPrice(), now);
    }

    @Override
    public void finish(long orderId, long saleId, long memberId, int quantity, boolean confirmed, Instant time) {
        SaleEntity sale = sales.findForUpdate(saleId).orElseThrow();
        SaleBuyerQuantityEntity buyer = buyers.findForUpdate(saleId, memberId).orElseThrow();
        SaleReservationEntity reservation = reservations.findForUpdate(orderId).orElseThrow();
        reservation.finish(saleId, memberId, quantity, confirmed, time);
        boolean soldOut = sale.finish(quantity, confirmed, time);
        buyer.finish(quantity, confirmed, time);
        if (soldOut) {
            // Only this transition changes the cached status (SOLD_OUT); other confirmations leave the entry alone.
            cache.evictAfterCommit(saleId);
        }
    }

    @Override
    public void attach(long orderId, long saleId, long memberId, int quantity, Instant time) {
        reservations.save(new SaleReservationEntity(orderId, saleId, memberId, quantity, time));
    }
}
