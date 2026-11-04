package com.example.resetpassword.repository;

import com.example.resetpassword.domain.AuthSession;
import com.example.resetpassword.domain.RevokeReason;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason
            where s.id = :id and s.revokedAt is null
            """)
    int revoke(@Param("id") String id, @Param("now") Instant now, @Param("reason") RevokeReason reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason
            where s.accountId = :accountId and s.revokedAt is null
            """)
    int revokeAllForAccount(@Param("accountId") Long accountId, @Param("now") Instant now,
            @Param("reason") RevokeReason reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AuthSession s set s.revokedAt = :now, s.revokeReason = :reason
            where s.accountId = :accountId and s.id <> :keep and s.revokedAt is null
            """)
    int revokeAllForAccountExcept(@Param("accountId") Long accountId, @Param("keep") String keepSessionId,
            @Param("now") Instant now, @Param("reason") RevokeReason reason);
}
