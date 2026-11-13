package com.wooyongjeong.mockpg.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ScenarioJpaRepository extends JpaRepository<ScenarioEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScenarioEntity s where s.pgKey = :key")
    Optional<ScenarioEntity> lock(@Param("key") UUID key);
}
