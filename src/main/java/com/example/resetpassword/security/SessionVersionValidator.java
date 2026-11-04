package com.example.resetpassword.security;

import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.repository.UserAccountRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 002 research R5: rejects access tokens of non-ACTIVE accounts and tokens whose {@code sv} is older than the
 * account's session version (password changed or reset). One primary-key read per protected request.
 */
@Component
public class SessionVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2TokenValidatorResult INVALID =
            OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));

    private final UserAccountRepository accounts;

    public SessionVersionValidator(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Long accountId;
        Object sv = jwt.getClaim("sv");
        try {
            accountId = Long.valueOf(jwt.getSubject());
        } catch (RuntimeException ex) {
            return INVALID;
        }
        if (!(sv instanceof Number version)) {
            return INVALID;
        }
        return accounts.findById(accountId)
                .filter(UserAccount::isActive)
                .filter(account -> account.getSessionVersion() == version.intValue())
                .map(account -> OAuth2TokenValidatorResult.success())
                .orElse(INVALID);
    }
}
