package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payments")
public class PaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    protected PaymentEntity() {}
}
