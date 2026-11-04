package com.example.resetpassword.service.auth;

import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.repository.UserAccountRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for "who am I" so the controller makes no repository call (Constitution I). */
@Service
public class AccountQueryService {

    public record AccountView(long id, String email) {
    }

    private final UserAccountRepository accounts;

    public AccountQueryService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Optional<AccountView> findActive(long accountId) {
        return accounts.findById(accountId).filter(UserAccount::isActive)
                .map(a -> new AccountView(a.getId(), a.getEmail()));
    }
}
