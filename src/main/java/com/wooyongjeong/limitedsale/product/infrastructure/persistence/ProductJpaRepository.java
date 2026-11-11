package com.wooyongjeong.limitedsale.product.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpaRepository extends JpaRepository<ProductEntity, Long> {
}
