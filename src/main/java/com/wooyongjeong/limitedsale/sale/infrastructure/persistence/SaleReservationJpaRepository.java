package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface SaleReservationJpaRepository extends JpaRepository<SaleReservationEntity, Long> {
}
