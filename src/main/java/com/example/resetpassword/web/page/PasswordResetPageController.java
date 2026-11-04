package com.example.resetpassword.web.page;

import com.example.resetpassword.service.PasswordResetService;
import com.example.resetpassword.service.ResetOutcome;
import com.example.resetpassword.service.TokenStatus;
import com.example.resetpassword.web.ResetMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Thin controller for the server-rendered pages; same service calls as the API (Constitution I–II). */
@Controller
public class PasswordResetPageController {

    private final PasswordResetService service;

    public PasswordResetPageController(PasswordResetService service) {
        this.service = service;
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordForm(Model model) {
        model.addAttribute("form", new ForgotPasswordForm());
        return "forgot-password";
    }

    @PostMapping("/forgot-password")
    public String submitForgotPassword(@Valid @ModelAttribute("form") ForgotPasswordForm form,
            BindingResult binding, Model model, HttpServletRequest request) {
        if (binding.hasErrors()) {
            model.addAttribute("emailError", ResetMessages.INVALID_EMAIL);
            return "forgot-password";
        }
        service.requestReset(form.getEmail(), request.getRemoteAddr());
        // Do not echo the submitted email: the page must be identical for every account (FR-005).
        model.addAttribute("form", new ForgotPasswordForm());
        model.addAttribute("confirmation", ResetMessages.REQUEST_ACCEPTED);
        return "forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam(name = "token", required = false) String token, Model model,
            HttpServletResponse response) {
        noLeakHeaders(response);
        if (service.checkToken(token) != TokenStatus.USABLE) {
            return invalid(model);
        }
        model.addAttribute("form", new ResetPasswordForm(token));
        return "reset-password";
    }

    @PostMapping("/reset-password")
    public String submitResetPassword(@ModelAttribute("form") ResetPasswordForm form, Model model,
            HttpServletResponse response) {
        noLeakHeaders(response);
        ResetOutcome outcome = service.resetPassword(form.getToken(), form.getNewPassword(), form.getConfirmPassword());
        return switch (outcome) {
            case ResetOutcome.Success success -> {
                model.addAttribute("message", ResetMessages.RESET_DONE);
                yield "reset-success";
            }
            case ResetOutcome.Rejected rejected -> {
                model.addAttribute("form", new ResetPasswordForm(form.getToken()));
                model.addAttribute("errors", rejected.violations().stream().map(ResetMessages::message).toList());
                yield "reset-password";
            }
            case ResetOutcome.InvalidOrExpired invalid -> invalid(model);
        };
    }

    private static String invalid(Model model) {
        model.addAttribute("message", ResetMessages.INVALID_LINK);
        return "reset-invalid";
    }

    /** The token travels in the URL: never leak it via Referer or caches (research R11). */
    private static void noLeakHeaders(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
    }
}
