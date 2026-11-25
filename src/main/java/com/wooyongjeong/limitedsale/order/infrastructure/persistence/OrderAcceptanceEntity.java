package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.AcceptanceInProcess;
import com.wooyongjeong.limitedsale.order.domain.AcceptanceStatus;
import com.wooyongjeong.limitedsale.order.domain.OrderAcceptance;
import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.ProcessingFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_acceptances")
class OrderAcceptanceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID publicId;
    @Column(nullable = false)
    private long memberId;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID requestKey;
    @Column(nullable = false)
    private long saleId;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private AcceptanceStatus status;
    @Column(length = 32)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private OrderRejection rejectionReason;
    @Column(length = 64)
    private String failureReason;
    private Long orderId;
    @Column(nullable = false)
    private Instant acceptedAt;
    private Instant finishedAt;
    @Column(length = 64)
    private String requestId;

    protected OrderAcceptanceEntity() {
    }

    private OrderAcceptanceEntity(UUID publicId, long memberId, UUID requestKey, long saleId, int quantity,
            AcceptanceStatus status, OrderRejection rejectionReason, Instant now, Instant finishedAt, String requestId) {
        this.requestId = requestId;
        this.publicId = publicId;
        this.memberId = memberId;
        this.requestKey = requestKey;
        this.saleId = saleId;
        this.quantity = quantity;
        this.status = status;
        this.rejectionReason = rejectionReason;
        acceptedAt = now;
        this.finishedAt = finishedAt;
    }

    static OrderAcceptanceEntity accepted(UUID publicId, long memberId, UUID requestKey, long saleId, int quantity,
            Instant now, String requestId) {
        return new OrderAcceptanceEntity(publicId, memberId, requestKey, saleId, quantity, AcceptanceStatus.ACCEPTED,
            null, now, null, requestId);
    }

    static OrderAcceptanceEntity rejected(UUID publicId, long memberId, UUID requestKey, long saleId, int quantity,
            OrderRejection reason, Instant now, String requestId) {
        return new OrderAcceptanceEntity(publicId, memberId, requestKey, saleId, quantity, AcceptanceStatus.REJECTED,
            reason, now, now, requestId);
    }

    AcceptanceInProcess toInProcess() {
        return new AcceptanceInProcess(memberId, saleId, quantity, status, acceptedAt, requestId);
    }

    void orderCreated(long orderId, Instant now) {
        requireAccepted();
        status = AcceptanceStatus.ORDER_CREATED;
        this.orderId = orderId;
        finishedAt = now;
    }

    void rejected(OrderRejection reason, Instant now) {
        requireAccepted();
        status = AcceptanceStatus.REJECTED;
        rejectionReason = reason;
        finishedAt = now;
    }

    void failed(ProcessingFailure reason, Instant now) {
        requireAccepted();
        status = AcceptanceStatus.PROCESSING_FAILED;
        failureReason = reason.name();
        finishedAt = now;
    }

    private void requireAccepted() {
        if (status != AcceptanceStatus.ACCEPTED) {
            throw new IllegalStateException("Only an ACCEPTED acceptance can be finished, not " + status);
        }
    }

    /** {@code orderPublicId} is looked up by the caller because only the order table knows it. */
    OrderAcceptance toAcceptance(UUID orderPublicId) {
        return new OrderAcceptance(publicId, saleId, quantity, status, rejectionReason, failureReason, orderPublicId,
            acceptedAt, finishedAt);
    }

    Long orderId() {
        return orderId;
    }

    long id() {
        return id;
    }
}
