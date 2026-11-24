package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import com.wooyongjeong.limitedsale.order.domain.AcceptanceMessage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "order_outbox")
class OrderOutboxEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String topic;
    @Column(nullable = false, length = 64)
    private String messageKey;
    @Column(nullable = false, length = 2000)
    private String payload;
    @Column(nullable = false)
    private Instant createdAt;
    private Instant publishedAt;
    @Column(nullable = false)
    private int attempts;

    protected OrderOutboxEntity() {
    }

    OrderOutboxEntity(AcceptanceMessage message, Instant now) {
        topic = message.topic();
        messageKey = message.key();
        payload = message.payload();
        createdAt = now;
    }
}
