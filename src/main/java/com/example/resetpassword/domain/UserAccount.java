package com.example.resetpassword.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Seeded account (data-model.md). Email is stored normalized: trimmed and lower-cased. */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Embedded in access tokens as {@code sv}; bumped on password change/reset (002 FR-015a). */
    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    protected UserAccount() {
    }

    /** A completed registration (002 FR-011a): accounts are created ACTIVE, never unverified. */
    public static UserAccount active(String normalizedEmail, String passwordHash, Instant now) {
        UserAccount account = new UserAccount();
        account.email = normalizedEmail;
        account.passwordHash = passwordHash;
        account.status = AccountStatus.ACTIVE;
        account.createdAt = now;
        account.updatedAt = now;
        account.sessionVersion = 0;
        return account;
    }

    public int getSessionVersion() {
        return sessionVersion;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
