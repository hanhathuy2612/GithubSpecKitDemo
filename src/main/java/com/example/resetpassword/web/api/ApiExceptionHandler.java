package com.example.resetpassword.web.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps request-shape failures of the API to RFC 9457 problems. Never echoes request bodies or rejected values
 * (FR-015).
 */
@RestControllerAdvice(basePackages = "com.example.resetpassword.web.api")
public class ApiExceptionHandler {

    public static final URI VALIDATION_TYPE = URI.create("/problems/validation");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalid(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", messageFor(error.getField())))
                .distinct()
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return validationProblem(List.of(Map.of("field", "body", "message", "Request body is missing or malformed.")));
    }

    static ProblemDetail validationProblem(List<? extends Map<String, String>> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request is invalid.");
        problem.setType(VALIDATION_TYPE);
        problem.setTitle("Validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static String messageFor(String field) {
        return switch (field) {
            case "email" -> "Must be a valid email address.";
            case "token" -> "Token is required.";
            default -> "Value is required.";
        };
    }
}
