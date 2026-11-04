package com.example.resetpassword.repository;

import com.example.resetpassword.domain.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Research R4: only one caller can rotate a given token. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.rotatedAt = :now where t.id = :id and t.rotatedAt is null")
    int markRotatedIfCurrent(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.rotatedAt = :now where t.sessionId = :sessionId and t.rotatedAt is null")
    int markSessionTokensRotated(@Param("sessionId") String sessionId, @Param("now") Instant now);
}
