package com.example.resetpassword.repository;

import com.example.resetpassword.domain.UserAccount;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String normalizedEmail);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserAccount a set a.passwordHash = :hash, a.updatedAt = :now where a.id = :id")
    int updatePasswordHash(@Param("id") Long id, @Param("hash") String hash, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserAccount a set a.sessionVersion = a.sessionVersion + 1 where a.id = :id")
    int bumpSessionVersion(@Param("id") Long id);
}
