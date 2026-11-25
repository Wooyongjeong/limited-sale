package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.common.observation.RequestIdFilter;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceInProcess;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceStore;
import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.payment.api.PaymentPreparation;
import com.wooyongjeong.limitedsale.sale.api.SaleAllocation;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one accepted request into an order, or a rejection, in one transaction. The acceptance row is locked first, so
 * a second delivery of the same message waits and then finds it finished: the effect happens once however often the
 * message arrives. Stock is taken exactly as for a synchronous order, except that the sale period is judged at the
 * acceptance time. The v2 order's request key is the acceptance ID, so it can never meet a v1 request key.
 */
@Service
public class OrderAcceptanceProcessor {
    public enum Outcome {
        ORDER_CREATED,
        REJECTED,
        /** Already finished by an earlier delivery. */
        DUPLICATE,
        /** No acceptance with this ID, or one for another sale than the message says: nothing to do. */
        SKIPPED
    }

    /** What one delivery did; {@code sinceAcceptance} is set for ORDER_CREATED and REJECTED (database times). */
    public record Result(Outcome outcome, Duration sinceAcceptance) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private final OrderAcceptanceStore acceptances;
    private final OrderStore orders;
    private final SaleReservation sales;
    private final PaymentPreparation payments;
    private final Duration expiration;

    public OrderAcceptanceProcessor(OrderAcceptanceStore acceptances, OrderStore orders, SaleReservation sales,
            PaymentPreparation payments, @Value("${limited-sale.order.expiration:PT5M}") Duration expiration) {
        this.acceptances = acceptances;
        this.orders = orders;
        this.sales = sales;
        this.payments = payments;
        this.expiration = expiration;
    }

    /**
     * Also puts the acceptance's request id into the log context, so what the caller and the error handler log next for
     * this message carries the id of the HTTP request that created it. The caller clears it.
     */
    @Transactional
    public Result process(UUID acceptanceId, long saleId) {
        AcceptanceInProcess acceptance = acceptances.lockForProcessing(acceptanceId).orElse(null);
        if (acceptance == null || acceptance.saleId() != saleId) {
            return Result.of(Outcome.SKIPPED);
        }
        if (acceptance.requestId() != null) {
            MDC.put(RequestIdFilter.MDC_KEY, acceptance.requestId());
        }
        if (acceptance.finished()) {
            return Result.of(Outcome.DUPLICATE);
        }
        SaleAllocation allocation = sales.allocateAccepted(saleId, acceptance.memberId(), acceptance.quantity(),
            acceptance.acceptedAt());
        if (allocation.reason() != SaleAllocation.Reason.ALLOCATED) {
            acceptances.finishRejected(acceptanceId, rejection(allocation.reason()), allocation.time());
            return new Result(Outcome.REJECTED, Duration.between(acceptance.acceptedAt(), allocation.time()));
        }
        // The unpaid order expires five minutes after it is created here, however long the request waited before.
        long orderId = orders.create(acceptance.memberId(), acceptanceId, saleId, allocation.productId(),
            allocation.productName(), allocation.unitPrice(), acceptance.quantity(), allocation.time(),
            allocation.time().plus(expiration));
        sales.attach(orderId, saleId, acceptance.memberId(), acceptance.quantity(), allocation.time());
        payments.prepare(orderId, Math.multiplyExact(allocation.unitPrice(), acceptance.quantity()),
            allocation.time());
        acceptances.finishWithOrder(acceptanceId, orderId, allocation.time());
        return new Result(Outcome.ORDER_CREATED, Duration.between(acceptance.acceptedAt(), allocation.time()));
    }

    private static OrderRejection rejection(SaleAllocation.Reason reason) {
        return switch (reason) {
            case SALE_NOT_FOUND -> OrderRejection.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderRejection.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderRejection.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> OrderRejection.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> OrderRejection.SALE_SOLD_OUT;
            case ALLOCATED -> throw new IllegalStateException("Allocated stock is not a rejection");
        };
    }
}
