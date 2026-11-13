package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payments")
public class PaymentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private long orderId;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID pgKey;
    @Column(nullable = false)
    private long amount;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;
    @Column(nullable = true)
    private Instant requestedAt;
    @Column(nullable = true)
    private Instant lastCheckedAt;
    @Column(nullable = true)
    private Instant finalizedAt;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    protected PaymentEntity() {
    }

    PaymentEntity(long orderId, long amount, Instant time) {
        this.orderId = orderId;
        this.amount = amount;
        pgKey = UUID.randomUUID();
        status = PaymentStatus.READY;
        createdAt = time;
        updatedAt = time;
    }

    UUID pgKey() {
        return pgKey;
    }
}
