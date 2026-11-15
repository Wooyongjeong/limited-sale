package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PurchaseOrderJpaRepository extends JpaRepository<PurchaseOrderEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrderEntity o where o.memberId = :member and o.publicId = :publicId")
    Optional<PurchaseOrderEntity> lockOwned(@Param("member") long member, @Param("publicId") UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from PurchaseOrderEntity o where o.id = :id")
    Optional<PurchaseOrderEntity> lockById(@Param("id") long id);

    @Query(value = "select DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", nativeQuery = true)
    String databaseTime();

    Optional<PurchaseOrderEntity> findByPublicId(UUID publicId);

    Optional<PurchaseOrderEntity> findByMemberIdAndPublicId(long memberId, UUID publicId);
}
