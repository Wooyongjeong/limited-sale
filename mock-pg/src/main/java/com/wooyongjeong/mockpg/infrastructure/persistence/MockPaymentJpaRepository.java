package com.wooyongjeong.mockpg.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MockPaymentJpaRepository extends JpaRepository<MockPaymentEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MockPaymentEntity p where p.pgKey = :key")
    Optional<MockPaymentEntity> lock(@Param("key") UUID key);
}
