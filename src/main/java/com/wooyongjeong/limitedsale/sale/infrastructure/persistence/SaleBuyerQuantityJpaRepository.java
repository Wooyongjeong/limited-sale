package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SaleBuyerQuantityJpaRepository extends JpaRepository<SaleBuyerQuantityEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from SaleBuyerQuantityEntity b where b.saleId = :saleId and b.memberId = :memberId")
    Optional<SaleBuyerQuantityEntity> findForUpdate(@Param("saleId") long saleId, @Param("memberId") long memberId);
}
