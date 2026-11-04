package com.example.resetpassword.repository;

import com.example.resetpassword.domain.RegistrationToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegistrationTokenRepository extends JpaRepository<RegistrationToken, Long> {

    Optional<RegistrationToken> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RegistrationToken t set t.invalidatedAt = :now
            where t.email = :email and t.usedAt is null and t.invalidatedAt is null
            """)
    int invalidateUnusedForEmail(@Param("email") String email, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RegistrationToken t set t.usedAt = :now
            where t.id = :id and t.usedAt is null and t.invalidatedAt is null and t.expiresAt > :now
            """)
    int markUsedIfUsable(@Param("id") Long id, @Param("now") Instant now);
}
