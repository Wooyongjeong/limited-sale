package com.wooyongjeong.mockpg.infrastructure.persistence;

import com.wooyongjeong.mockpg.domain.MockPayment;
import com.wooyongjeong.mockpg.domain.PaymentResult;
import com.wooyongjeong.mockpg.domain.PaymentStatus;
import com.wooyongjeong.mockpg.domain.Scenario;
import com.wooyongjeong.mockpg.domain.ScenarioConfiguration;
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
@Table(name = "mock_payments")
public class MockPaymentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(nullable = false, columnDefinition = "binary(16)")
    private UUID pgKey;
    @Column(nullable = false)
    private long amount;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private MockPaymentStatus status;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private MockScenario scenario;
    private Instant completionDueAt;
    @Column(nullable = false)
    private boolean responseLossConsumed;
    private Instant finalizedAt;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected MockPaymentEntity() {
    }

    MockPaymentEntity(UUID key, long amount, ScenarioConfiguration config, Instant now, boolean close) {
        apply(MockPayment.start(key, amount, config, now, close));
    }

    long amount() {
        return amount;
    }

    void resolve(Instant now, boolean close) {
        apply(toDomain().resolve(now, close));
    }

    private MockPayment toDomain() {
        return new MockPayment(pgKey, amount, PaymentStatus.valueOf(status.name()), Scenario.valueOf(scenario.name()),
                completionDueAt, responseLossConsumed, finalizedAt, createdAt, updatedAt);
    }

    private void apply(MockPayment payment) {
        pgKey = payment.pgKey();
        amount = payment.amount();
        status = MockPaymentStatus.valueOf(payment.status().name());
        scenario = MockScenario.valueOf(payment.scenario().name());
        completionDueAt = payment.completionDueAt();
        responseLossConsumed = payment.responseLossConsumed();
        finalizedAt = payment.finalizedAt();
        createdAt = payment.createdAt();
        updatedAt = payment.updatedAt();
    }

    PaymentResult result(int delay) {
        return new PaymentResult(pgKey, amount, status.name(), createdAt, updatedAt, finalizedAt, delay);
    }
}
