package com.example.resetpassword.web.api;

import com.example.resetpassword.service.auth.AccountQueryService;
import com.example.resetpassword.service.auth.AuthSessionService;
import com.example.resetpassword.service.auth.SignInOutcome;
import com.example.resetpassword.service.auth.SignInService;
import com.example.resetpassword.web.api.dto.MeResponse;
import com.example.resetpassword.web.api.dto.RefreshRequest;
import com.example.resetpassword.web.api.dto.SignInRequest;
import com.example.resetpassword.web.api.dto.TokenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller for /api/auth (Constitution I): one service call per endpoint, outcome → response. */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    static final URI INVALID_CREDENTIALS = URI.create("/problems/invalid-credentials");
    static final URI INVALID_REFRESH = URI.create("/problems/invalid-refresh-token");

    private final SignInService signIn;
    private final AuthSessionService sessions;
    private final AccountQueryService accounts;

    public AuthApiController(SignInService signIn, AuthSessionService sessions, AccountQueryService accounts) {
        this.signIn = signIn;
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @PostMapping("/sign-in")
    public ResponseEntity<?> signIn(@Valid @RequestBody SignInRequest body, HttpServletRequest request) {
        return switch (signIn.signIn(body.email(), body.password(), request.getRemoteAddr())) {
            case SignInOutcome.Success success -> ResponseEntity.ok(TokenResponse.from(success.credentials()));
            case SignInOutcome.Failed failed ->
                    problem(HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "Email or password is incorrect.");
        };
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest body) {
        return sessions.refresh(body.refreshToken())
                .<ResponseEntity<?>>map(credentials -> ResponseEntity.ok(TokenResponse.from(credentials)))
                .orElseGet(() -> problem(HttpStatus.UNAUTHORIZED, INVALID_REFRESH, "Refresh token is invalid."));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.findActive(Long.parseLong(jwt.getSubject()))
                .<ResponseEntity<?>>map(view -> ResponseEntity.ok(new MeResponse(view.id(), view.email())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    static ResponseEntity<ProblemDetail> problem(HttpStatus status, URI type, String title) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type);
        problem.setTitle(title);
        return ResponseEntity.status(status).body(problem);
    }
}
