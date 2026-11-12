package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sale_reservations")
public class SaleReservationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private long orderId;
    @Column(nullable = false)
    private long saleId;
    @Column(nullable = false)
    private long memberId;
    @Column(nullable = false)
    private int quantity;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private ReservationStatus status;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    protected SaleReservationEntity() {
    }

    SaleReservationEntity(long orderId, long saleId, long memberId, int quantity, Instant time) {
        this.orderId = orderId;
        this.saleId = saleId;
        this.memberId = memberId;
        this.quantity = quantity;
        status = ReservationStatus.RESERVED;
        createdAt = time;
        updatedAt = time;
    }
}
