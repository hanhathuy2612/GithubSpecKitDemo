package com.example.resetpassword.service;

import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.repository.UserAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The spec's "credential check" (US2). No HTTP endpoint: sign-in is out of scope. */
@Service
public class CredentialService {

    private final UserAccountRepository accounts;
    private final PasswordEncoder passwordEncoder;

    public CredentialService(UserAccountRepository accounts, PasswordEncoder passwordEncoder) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public boolean verifyCredentials(String rawEmail, String password) {
        if (password == null || !PasswordPolicy.fitsMaxLength(password)) {
            return false;
        }
        return accounts.findByEmail(EmailNormalizer.normalize(rawEmail))
                .filter(UserAccount::isActive)
                .map(account -> passwordEncoder.matches(password, account.getPasswordHash()))
                .orElse(false);
    }
}
