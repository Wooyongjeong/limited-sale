package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.OrderRejection;
import com.wooyongjeong.limitedsale.order.domain.OrderRequestOutcome;
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
@Table(name = "order_request_results")
class OrderRequestResultEntity {
    private enum Outcome {
        CREATED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private long memberId;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID requestKey;
    @Column(nullable = false)
    private long saleId;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private Outcome outcome;
    private Long orderId;
    @Column(length = 32)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private OrderRejection rejectionReason;
    @Column(nullable = false)
    private Instant createdAt;

    protected OrderRequestResultEntity() {
    }

    OrderRequestResultEntity(long memberId, UUID requestKey, long saleId, int quantity, Long orderId,
            OrderRejection rejectionReason, Instant now) {
        this.memberId = memberId;
        this.requestKey = requestKey;
        this.saleId = saleId;
        this.quantity = quantity;
        this.orderId = orderId;
        this.rejectionReason = rejectionReason;
        outcome = orderId == null ? Outcome.REJECTED : Outcome.CREATED;
        createdAt = now;
    }

    OrderRequestOutcome toOutcome() {
        return new OrderRequestOutcome(saleId, quantity, orderId, rejectionReason);
    }
}
