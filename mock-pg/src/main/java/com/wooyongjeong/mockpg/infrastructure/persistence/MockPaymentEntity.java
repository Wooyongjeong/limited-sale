package com.wooyongjeong.mockpg.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mock_payments")
public class MockPaymentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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
    @Column(nullable = true)
    private Instant completionDueAt;
    @Column(nullable = false)
    private boolean responseLossConsumed;
    @Column(nullable = true)
    private Instant finalizedAt;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    protected MockPaymentEntity() {}
}
