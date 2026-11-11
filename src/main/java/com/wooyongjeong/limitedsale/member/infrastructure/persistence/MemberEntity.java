package com.wooyongjeong.limitedsale.member.infrastructure.persistence;

import com.wooyongjeong.limitedsale.member.domain.AuthenticationRecord;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberInput;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "members")
public class MemberEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(nullable = false, length = 255)
    private String passwordHash;
    @Column(nullable = false, length = 12)
    private String nickname;
    @Column(nullable = false, length = 12)
    private String nicknameKey;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private MemberRole role;
    @Generated(sql = "UTC_TIMESTAMP(6)")
    @Column(nullable = false)
    private Instant createdAt;
    @Generated(sql = "UTC_TIMESTAMP(6)")
    @Column(nullable = false)
    private Instant updatedAt;

    protected MemberEntity() {
    }

    MemberEntity(String email, String passwordHash, String nickname, MemberRole role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        this.nicknameKey = MemberInput.nicknameKey(nickname);
        this.role = role;
    }

    Member toMember() {
        return new Member(email, nickname, role);
    }

    AuthenticationRecord toAuthenticationRecord() {
        return new AuthenticationRecord(id, toMember(), passwordHash);
    }
}
