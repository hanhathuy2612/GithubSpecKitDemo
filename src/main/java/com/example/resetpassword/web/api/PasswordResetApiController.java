package com.example.resetpassword.web.api;

import com.example.resetpassword.service.PasswordResetService;
import com.example.resetpassword.service.PasswordViolation;
import com.example.resetpassword.service.ResetOutcome;
import com.example.resetpassword.web.ResetMessages;
import com.example.resetpassword.web.api.dto.ConfirmResetRequest;
import com.example.resetpassword.web.api.dto.MessageResponse;
import com.example.resetpassword.web.api.dto.RequestResetRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller (Constitution I): parse and validate the request, call the service, map the result. */
@RestController
@RequestMapping("/api/password-reset")
public class PasswordResetApiController {

    private final PasswordResetService service;

    public PasswordResetApiController(PasswordResetService service) {
        this.service = service;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public MessageResponse requestReset(@Valid @RequestBody RequestResetRequest body, HttpServletRequest request) {
        service.requestReset(body.email(), request.getRemoteAddr());
        return new MessageResponse(ResetMessages.REQUEST_ACCEPTED);
    }

    @PostMapping("/confirm")
    public ResponseEntity<?> confirmReset(@Valid @RequestBody ConfirmResetRequest body) {
        ResetOutcome outcome = service.resetPassword(body.token(), body.newPassword(), body.confirmPassword());
        return switch (outcome) {
            case ResetOutcome.Success success -> ResponseEntity.ok(new MessageResponse(ResetMessages.RESET_DONE));
            case ResetOutcome.Rejected rejected -> ResponseEntity.badRequest().body(
                    ApiExceptionHandler.validationProblem(rejected.violations().stream().map(this::toError).toList()));
            case ResetOutcome.InvalidOrExpired invalid -> ResponseEntity.badRequest().body(invalidTokenProblem());
        };
    }

    public static final URI INVALID_TOKEN_TYPE = URI.create("/problems/invalid-reset-token");

    private static ProblemDetail invalidTokenProblem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request a new link via POST /api/password-reset/requests.");
        problem.setType(INVALID_TOKEN_TYPE);
        problem.setTitle(ResetMessages.INVALID_LINK);
        return problem;
    }

    private Map<String, String> toError(PasswordViolation violation) {
        String field = violation == PasswordViolation.MISMATCH ? "confirmPassword" : "newPassword";
        return Map.of("field", field, "code", violation.name(), "message", ResetMessages.message(violation));
    }
}
