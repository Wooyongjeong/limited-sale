package com.wooyongjeong.mockpg.infrastructure.persistence;

import com.wooyongjeong.mockpg.domain.Scenario;
import com.wooyongjeong.mockpg.domain.ScenarioConfiguration;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "mock_scenario_settings")
class ScenarioEntity implements Persistable<UUID> {
    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "pg_key", columnDefinition = "binary(16)")
    private UUID pgKey;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private Scenario scenario;
    private Integer delaySeconds;
    // Assigned UUIDs must use INSERT so a stale default cannot merge over another request's configuration.
    @Transient
    private boolean newEntity = true;

    protected ScenarioEntity() {
    }

    ScenarioEntity(UUID pgKey) {
        this.pgKey = pgKey;
        set(new ScenarioConfiguration(Scenario.SUCCESS, null));
    }

    @Override
    public UUID getId() {
        return pgKey;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        newEntity = false;
    }

    ScenarioConfiguration configuration() {
        return new ScenarioConfiguration(scenario, delaySeconds);
    }

    void set(ScenarioConfiguration configuration) {
        scenario = configuration.scenario();
        delaySeconds = configuration.delaySeconds();
    }
}
