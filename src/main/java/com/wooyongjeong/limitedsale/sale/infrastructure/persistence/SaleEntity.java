package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sales")
public class SaleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private long productId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 2000)
    private String description;
    @Column(nullable = false, length = 100)
    private String productNameSnapshot;
    @Column(nullable = false)
    private long unitPrice;
    @Column(nullable = false)
    private Instant startsAt;
    @Column(nullable = false)
    private Instant endsAt;
    @Column(nullable = false)
    private int totalQuantity;
    @Column(nullable = false)
    private int reservedQuantity;
    @Column(nullable = false)
    private int soldQuantity;
    @Column(nullable = false)
    private int perMemberLimit;
    @Column(nullable = false, name = "is_stopped")
    private boolean isStopped;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    protected SaleEntity() {}
}
