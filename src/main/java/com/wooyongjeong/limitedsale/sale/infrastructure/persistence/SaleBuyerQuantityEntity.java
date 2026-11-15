package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import com.wooyongjeong.limitedsale.sale.domain.AllocationQuantities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "sale_buyer_quantities")
public class SaleBuyerQuantityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private long saleId;
    @Column(nullable = false)
    private long memberId;
    @Column(nullable = false)
    private int reservedQuantity;
    @Column(nullable = false)
    private int purchasedQuantity;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    protected SaleBuyerQuantityEntity() {
    }

    SaleBuyerQuantityEntity(long saleId, long memberId, Instant time) {
        this.saleId = saleId;
        this.memberId = memberId;
        createdAt = time;
        updatedAt = time;
    }

    int total() {
        return reservedQuantity + purchasedQuantity;
    }

    void finish(int quantity, boolean confirmed, Instant time) {
        var result = new AllocationQuantities(reservedQuantity, purchasedQuantity).finish(quantity, confirmed);
        reservedQuantity = result.reserved();
        purchasedQuantity = result.finalized();
        updatedAt = time;
    }

    void reserve(int quantity, Instant time) {
        reservedQuantity += quantity;
        updatedAt = time;
    }
}
