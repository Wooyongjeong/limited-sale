package com.wooyongjeong.limitedsale.member.infrastructure.persistence;

import com.wooyongjeong.limitedsale.member.domain.AuthenticationRecord;
import com.wooyongjeong.limitedsale.member.domain.AuthenticationStore;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberConflict;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import com.wooyongjeong.limitedsale.member.domain.MemberStore;
import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JpaMemberStore implements MemberStore, AuthenticationStore {
    private static final Pattern DUPLICATE_KEY = Pattern.compile(
        "for key ['`\"](?:[^'`\".]+\\.)?(uk_members_email|uk_members_nickname)['`\"]");
    private final MemberJpaRepository repository;
    private final TransactionTemplate transactions;

    public JpaMemberStore(MemberJpaRepository repository, PlatformTransactionManager manager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<Member> findByEmail(String email) {
        return repository.findByEmail(email).map(MemberEntity::toMember);
    }

    @Override
    public Optional<AuthenticationRecord> findForAuthentication(String email) {
        return repository.findByEmail(email).map(MemberEntity::toAuthenticationRecord);
    }

    @Override
    public long firstAvailableNicknameSuffix(String baseKey) {
        return repository.firstAvailableNicknameSuffix(baseKey);
    }

    @Override
    public void insert(String email, String passwordHash, String nickname, MemberRole role) {
        try {
            transactions.executeWithoutResult(status ->
                repository.saveAndFlush(new MemberEntity(email, passwordHash, nickname, role)));
        } catch (DataIntegrityViolationException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1062 && sql.getMessage() != null) {
                    var match = DUPLICATE_KEY.matcher(sql.getMessage());
                    if (match.find()) {
                        var kind = match.group(1).equals("uk_members_email")
                            ? MemberConflict.Kind.EMAIL : MemberConflict.Kind.NICKNAME;
                        throw new MemberConflict(kind, ex);
                    }
                }
            }
            throw ex;
        }
    }
}
