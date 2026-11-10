package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrderEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    private long productId;
    @Column(nullable = false, length = 100)
    private String productNameSnapshot;
    @Column(nullable = false)
    private long unitPrice;
    @Column(nullable = false)
    private long totalAmount;
    @Column(nullable = false)
    private int quantity;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private OrderStatus status;
    @Column(nullable = false)
    private Instant expiresAt;
    @Column(nullable = true)
    private Instant nextRecoveryAt;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = true, columnDefinition = "binary(16)")
    private UUID leaseToken;
    @Column(nullable = true)
    private Instant leaseUntil;
    @Column(nullable = false)
    private boolean requiresReview;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    protected PurchaseOrderEntity() {}
}
