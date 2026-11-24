package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.order.domain.AcceptanceMessage;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceStatus;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptanceStore;
import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.infrastructure.messaging.OrderAcceptanceTopicConfiguration;
import com.wooyongjeong.limitedsale.sale.api.SaleAdmission;
import com.wooyongjeong.limitedsale.sale.api.SaleAdmissionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional steps of the asynchronous accept path. Nothing here locks the sale row or calls Redis, Kafka or
 * the payment gateway: a request is only decided against a plain read and stored, and stock is left to the consumer.
 */
@Service
public class OrderAcceptanceTransaction {
    private final OrderAcceptanceStore acceptances;
    private final SaleAdmission sales;
    private final Clock clock;

    public OrderAcceptanceTransaction(OrderAcceptanceStore acceptances, SaleAdmission sales, Clock clock) {
        this.acceptances = acceptances;
        this.sales = sales;
        this.clock = clock;
    }

    /**
     * Replays an existing key first. A new request is either stored as a rejection, or as an acceptance together with
     * its outbox row so both commit or neither does. The acceptance time is the database time of the decision.
     */
    @Transactional
    public OrderAcceptanceResult accept(long memberId, UUID key, long saleId, int quantity) {
        var existing = acceptances.request(memberId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), saleId, quantity);
        }
        SaleAdmissionResult admission = sales.check(saleId);
        UUID acceptanceId = UUID.randomUUID();
        if (admission.reason() != SaleAdmissionResult.Reason.ADMITTED) {
            OrderRejection reason = rejection(admission.reason());
            acceptances.reject(acceptanceId, memberId, key, saleId, quantity, reason, admission.time());
            return rejected(reason);
        }
        var message = AcceptanceMessage.of(OrderAcceptanceTopicConfiguration.ORDER_ACCEPTANCES_TOPIC, acceptanceId,
            saleId);
        return new OrderAcceptanceResult(OrderAcceptanceResult.Kind.ACCEPTED,
            acceptances.accept(acceptanceId, memberId, key, saleId, quantity, admission.time(), message));
    }

    /** For a sale the sold-out pre-check marked: no sale read, no outbox row, the same replay-first rule. */
    @Transactional
    public OrderAcceptanceResult rejectSoldOut(long memberId, UUID key, long saleId, int quantity) {
        var existing = acceptances.request(memberId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), saleId, quantity);
        }
        acceptances.reject(UUID.randomUUID(), memberId, key, saleId, quantity, OrderRejection.SALE_SOLD_OUT,
            Instant.now(clock).truncatedTo(ChronoUnit.MICROS));
        return rejected(OrderRejection.SALE_SOLD_OUT);
    }

    @Transactional(readOnly = true)
    public Optional<OrderAcceptanceResult> lookupRequest(long memberId, UUID key, long saleId, int quantity) {
        return acceptances.request(memberId, key).map(existing -> replay(existing, saleId, quantity));
    }

    @Transactional(readOnly = true)
    public OrderAcceptanceResult find(long memberId, UUID acceptanceId) {
        return acceptances.find(memberId, acceptanceId)
            .map(found -> new OrderAcceptanceResult(OrderAcceptanceResult.Kind.REPLAYED, found))
            .orElseGet(() -> new OrderAcceptanceResult(OrderAcceptanceResult.Kind.ACCEPTANCE_NOT_FOUND, null));
    }

    private OrderAcceptanceResult replay(OrderAcceptance existing, long saleId, int quantity) {
        if (existing.saleId() != saleId || existing.quantity() != quantity) {
            return new OrderAcceptanceResult(OrderAcceptanceResult.Kind.IDEMPOTENCY_CONFLICT, null);
        }
        if (existing.status() == AcceptanceStatus.REJECTED) {
            return rejected(existing.rejection());
        }
        return new OrderAcceptanceResult(OrderAcceptanceResult.Kind.REPLAYED, existing);
    }

    private OrderRejection rejection(SaleAdmissionResult.Reason reason) {
        return switch (reason) {
            case SALE_NOT_FOUND -> OrderRejection.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderRejection.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderRejection.SALE_STOPPED;
            case ADMITTED -> throw new IllegalStateException("An admitted sale is not a rejection");
        };
    }

    private OrderAcceptanceResult rejected(OrderRejection reason) {
        var kind = switch (reason) {
            case SALE_NOT_FOUND -> OrderAcceptanceResult.Kind.SALE_NOT_FOUND;
            case SALE_OUTSIDE_PERIOD -> OrderAcceptanceResult.Kind.SALE_OUTSIDE_PERIOD;
            case SALE_STOPPED -> OrderAcceptanceResult.Kind.SALE_STOPPED;
            case PURCHASE_LIMIT_EXCEEDED -> OrderAcceptanceResult.Kind.PURCHASE_LIMIT_EXCEEDED;
            case SALE_SOLD_OUT -> OrderAcceptanceResult.Kind.SALE_SOLD_OUT;
        };
        return new OrderAcceptanceResult(kind, null);
    }
}
