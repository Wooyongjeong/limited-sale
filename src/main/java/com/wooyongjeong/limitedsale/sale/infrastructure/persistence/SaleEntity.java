package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import com.wooyongjeong.limitedsale.sale.domain.Sale;
import com.wooyongjeong.limitedsale.sale.domain.SaleTerms;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "sales")
public class SaleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    protected SaleEntity() {
    }

    SaleEntity(SaleTerms terms, String productName, Instant now) {
        setTerms(terms);
        productNameSnapshot = productName;
        createdAt = now;
        updatedAt = now;
    }

    void revise(Sale sale) {
        setTerms(sale.terms());
        productNameSnapshot = sale.productNameSnapshot();
        isStopped = sale.stopped();
        updatedAt = sale.updatedAt();
    }

    private void setTerms(SaleTerms terms) {
        productId = terms.productId();
        name = terms.name();
        description = terms.description();
        unitPrice = terms.unitPrice();
        startsAt = terms.startsAt();
        endsAt = terms.endsAt();
        totalQuantity = terms.totalQuantity();
        perMemberLimit = terms.perMemberLimit();
    }

    Sale toSale() {
        return new Sale(id, new SaleTerms(productId, name, description, unitPrice, startsAt, endsAt,
            totalQuantity, perMemberLimit), productNameSnapshot, reservedQuantity, soldQuantity,
            isStopped, version, createdAt, updatedAt);
    }
}
