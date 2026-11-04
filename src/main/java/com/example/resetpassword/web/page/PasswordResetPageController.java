package com.example.resetpassword.web.page;

import com.example.resetpassword.service.PasswordResetService;
import com.example.resetpassword.web.ResetMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

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
}
