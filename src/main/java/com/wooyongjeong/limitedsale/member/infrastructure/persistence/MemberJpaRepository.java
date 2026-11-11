package com.wooyongjeong.limitedsale.member.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MemberJpaRepository extends JpaRepository<MemberEntity, Long> {
    Optional<MemberEntity> findByEmail(String email);

    // The key range covers the base and its ASCII-digit suffixes without LIKE escaping.
    @Query(value = """
        SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM members WHERE nickname_key = :baseKey) THEN 0 ELSE
            COALESCE((SELECT MIN(next_number) FROM (
                SELECT number + 1 AS next_number, LEAD(number) OVER (ORDER BY number) AS following
                FROM (
                    SELECT 0 AS number
                    UNION ALL
                    SELECT CAST(SUBSTRING(nickname_key, CHAR_LENGTH(:baseKey) + 1) AS UNSIGNED) AS number
                    FROM members
                    WHERE nickname_key >= :baseKey AND nickname_key < CONCAT(:baseKey, ':')
                      AND SUBSTRING(nickname_key, CHAR_LENGTH(:baseKey) + 1) REGEXP '^[1-9][0-9]*$'
                ) occupied
            ) gaps WHERE following IS NULL OR following > next_number), 1) END
        """, nativeQuery = true)
    long firstAvailableNicknameSuffix(@Param("baseKey") String baseKey);
}
