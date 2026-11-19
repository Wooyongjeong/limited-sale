package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

    @Query(value = "select id from purchase_orders where status in ('AWAITING_PAYMENT', 'PAYMENT_PENDING') "
            + "and (next_recovery_at <= UTC_TIMESTAMP(6) "
            + "or (expires_at <= UTC_TIMESTAMP(6) and requires_review = false)) "
            + "and (lease_until is null or lease_until <= UTC_TIMESTAMP(6)) "
            + "order by next_recovery_at, id limit :limit for update skip locked", nativeQuery = true)
    List<Long> candidates(@Param("limit") int limit);

    @Query(value = "select * from purchase_orders where id = :id for update skip locked", nativeQuery = true)
    Optional<PurchaseOrderEntity> tryLock(@Param("id") long id);

    @Query(value = "select DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", nativeQuery = true)
    String databaseTime();

    Optional<PurchaseOrderEntity> findByPublicId(UUID publicId);

    Optional<PurchaseOrderEntity> findByMemberIdAndPublicId(long memberId, UUID publicId);

    long countByMemberId(long memberId);

    long countByStatusIn(Collection<OrderStatus> statuses);

    long countByStatusInAndRequiresReviewTrue(Collection<OrderStatus> statuses);

    List<PurchaseOrderEntity> findByMemberId(long memberId, Pageable pageable);
}
