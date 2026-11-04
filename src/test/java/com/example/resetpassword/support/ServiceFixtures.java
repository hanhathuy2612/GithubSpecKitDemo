package com.example.resetpassword.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.resetpassword.domain.AccountStatus;
import com.example.resetpassword.domain.UserAccount;
import com.example.resetpassword.mail.ResetMailDispatcher;
import com.example.resetpassword.ratelimit.RateLimiter;
import com.example.resetpassword.repository.PasswordResetTokenRepository;
import com.example.resetpassword.repository.UserAccountRepository;
import com.example.resetpassword.security.TokenGenerator;
import com.example.resetpassword.service.PasswordResetService;
import java.lang.reflect.Field;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/** Wires {@link PasswordResetService} with mocks/fakes for unit tests (no Spring context). */
public class ServiceFixtures {

    public final UserAccountRepository accounts = mock(UserAccountRepository.class);
    public final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);
    public final ResetMailDispatcher mail = mock(ResetMailDispatcher.class);
    public final RateLimiter rateLimiter = mock(RateLimiter.class);
    public final TokenGenerator tokenGenerator = new TokenGenerator();
    /** Low strength keeps unit tests fast; production uses 12. */
    public final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    public final MutableClock clock = new MutableClock(TestFixtures.START);

    public ServiceFixtures() {
        when(rateLimiter.tryAcquire(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    public PasswordResetService service() {
        return new PasswordResetService(accounts, tokens, tokenGenerator, mail, clock, new NoOpTransactionManager());
    }

    public UserAccount account(long id, String email, boolean active) {
        return account(id, email, active, passwordEncoder.encode(TestFixtures.SEED_PASSWORD));
    }

    public UserAccount account(long id, String email, boolean active, String passwordHash) {
        UserAccount account = newInstance();
        set(account, "id", id);
        set(account, "email", email);
        set(account, "passwordHash", passwordHash);
        set(account, "status", active ? AccountStatus.ACTIVE : AccountStatus.DISABLED);
        set(account, "createdAt", TestFixtures.START);
        set(account, "updatedAt", TestFixtures.START);
        when(accounts.findByEmail(email)).thenReturn(Optional.of(account));
        when(accounts.findById(id)).thenReturn(Optional.of(account));
        return account;
    }

    private static UserAccount newInstance() {
        try {
            var ctor = UserAccount.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Runs callbacks inline; there is no real transaction in unit tests. */
    static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
