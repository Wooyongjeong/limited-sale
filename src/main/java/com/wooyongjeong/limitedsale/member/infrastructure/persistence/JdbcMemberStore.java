package com.wooyongjeong.limitedsale.member.infrastructure.persistence;

import com.wooyongjeong.limitedsale.member.domain.AuthenticationRecord;
import com.wooyongjeong.limitedsale.member.domain.AuthenticationStore;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberConflict;
import com.wooyongjeong.limitedsale.member.domain.MemberInput;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import com.wooyongjeong.limitedsale.member.domain.MemberStore;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcMemberStore implements MemberStore, AuthenticationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcMemberStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<Member> findByEmail(String email) {
        return jdbc.query("SELECT email, nickname, role FROM members WHERE email = ?",
            (row, index) -> new Member(row.getString(1), row.getString(2), MemberRole.valueOf(row.getString(3))),
            email).stream().findFirst();
    }

    @Override
    public Optional<AuthenticationRecord> findForAuthentication(String email) {
        return jdbc.query("SELECT id, email, nickname, role, password_hash FROM members WHERE email = ?",
            (row, index) -> new AuthenticationRecord(row.getLong(1),
                new Member(row.getString(2), row.getString(3), MemberRole.valueOf(row.getString(4))),
                row.getString(5)), email).stream().findFirst();
    }

    @Override
    public long firstAvailableNicknameSuffix(String baseKey) {
        // The key range covers the base and its ASCII-digit suffixes without LIKE escaping.
        Long suffix = jdbc.queryForObject("""
            SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM members WHERE nickname_key = ?) THEN 0 ELSE
                COALESCE((SELECT MIN(next_number) FROM (
                    SELECT number + 1 AS next_number, LEAD(number) OVER (ORDER BY number) AS following
                    FROM (
                        SELECT 0 AS number
                        UNION ALL
                        SELECT CAST(SUBSTRING(nickname_key, CHAR_LENGTH(?) + 1) AS UNSIGNED) AS number
                        FROM members
                        WHERE nickname_key >= ? AND nickname_key < CONCAT(?, ':')
                          AND SUBSTRING(nickname_key, CHAR_LENGTH(?) + 1) REGEXP '^[1-9][0-9]*$'
                    ) occupied
                ) gaps WHERE following IS NULL OR following > next_number), 1) END
            """, Long.class, baseKey, baseKey, baseKey, baseKey, baseKey);
        return suffix;
    }

    @Override
    public void insert(String email, String passwordHash, String nickname, MemberRole role) {
        try {
            transactions.executeWithoutResult(status -> jdbc.update("INSERT INTO members "
                    + "(email, password_hash, nickname, nickname_key, role, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                email, passwordHash, nickname, MemberInput.nicknameKey(nickname), role.name()));
        } catch (DuplicateKeyException ex) {
            Throwable cause = ex.getMostSpecificCause();
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062) {
                if (sql.getMessage().contains("uk_members_email")) {
                    throw new MemberConflict(MemberConflict.Kind.EMAIL, ex);
                }
                if (sql.getMessage().contains("uk_members_nickname")) {
                    throw new MemberConflict(MemberConflict.Kind.NICKNAME, ex);
                }
            }
            throw ex;
        }
    }
}
