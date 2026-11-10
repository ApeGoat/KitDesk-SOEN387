package com.kitdesk.service;

import com.kitdesk.config.JwtTokens;
import com.kitdesk.domain.DomainRuleViolation;
import com.kitdesk.domain.User;
import com.kitdesk.repository.UserRepository;
import com.kitdesk.service.Views.LoginResult;
import java.util.Optional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Login: checks the email and password and issues a token. */
@Service
public class AuthService {

    /**
     * Any valid bcrypt hash (cost 10, like the real ones). When the email is unknown the password
     * is still checked against this, so a wrong email takes as long as a wrong password and
     * response times don't reveal which emails exist. The login fails either way.
     */
    private static final String DUMMY_HASH = "$2b$10$R.m9r6cIa02wkiKcnJSa.OHr6LHRQOdgEIfMFLzelhc8v7ZKMcC9G";

    private final UserRepository users;
    private final JwtTokens tokens;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    public AuthService(UserRepository users, JwtTokens tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /**
     * @throws DomainRuleViolation INVALID_CREDENTIALS for an unknown email or a wrong password
     *                             (the same error for both, so the response doesn't reveal which)
     */
    @Transactional(readOnly = true)
    public LoginResult login(String email, String password) {
        Optional<User> user = users.findByEmailIgnoreCase(email.trim());
        String hash = user.map(User::getPasswordHash).orElse(DUMMY_HASH);
        boolean matches = passwords.matches(password, hash);
        if (user.isEmpty() || !matches) {
            throw new DomainRuleViolation(ErrorCatalog.INVALID_CREDENTIALS, "The email or password is incorrect.");
        }
        User u = user.get();
        return new LoginResult(tokens.issue(u.getId(), u.getRole()), "Bearer", tokens.lifetimeSeconds(), u.getRole());
    }
}
