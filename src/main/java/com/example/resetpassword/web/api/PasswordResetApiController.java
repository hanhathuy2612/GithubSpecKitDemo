package com.example.resetpassword.web.api;

import com.example.resetpassword.service.PasswordResetService;
import com.example.resetpassword.web.ResetMessages;
import com.example.resetpassword.web.api.dto.MessageResponse;
import com.example.resetpassword.web.api.dto.RequestResetRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
}
