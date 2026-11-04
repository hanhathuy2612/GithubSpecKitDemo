package com.example.resetpassword.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One sign-in on one device: the refresh-token family (002 research R4). */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32)
    private RevokeReason revokeReason;

    protected AuthSession() {
    }

    public AuthSession(String id, Long accountId, Instant createdAt, Instant absoluteExpiresAt) {
        this.id = id;
        this.accountId = accountId;
        this.createdAt = createdAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public String getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public RevokeReason getRevokeReason() {
        return revokeReason;
    }
}
