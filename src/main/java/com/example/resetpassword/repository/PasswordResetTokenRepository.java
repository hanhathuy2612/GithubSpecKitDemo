package com.example.resetpassword.repository;

import com.example.resetpassword.domain.PasswordResetToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** FR-008: invalidate every unused, not-yet-invalidated token of the account. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordResetToken t set t.invalidatedAt = :now
            where t.accountId = :accountId and t.usedAt is null and t.invalidatedAt is null
            """)
    int invalidateUnusedForAccount(@Param("accountId") Long accountId, @Param("now") Instant now);

    /** Research R8: atomic single-use guard. Returns 1 only for the one caller that consumes the token. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PasswordResetToken t set t.usedAt = :now
            where t.id = :id and t.usedAt is null and t.invalidatedAt is null and t.expiresAt > :now
            """)
    int markUsedIfUsable(@Param("id") Long id, @Param("now") Instant now);
}
