package com.example.resetpassword.web.page;

import com.example.resetpassword.service.TokenStatus;
import com.example.resetpassword.service.auth.RegistrationOutcome;
import com.example.resetpassword.service.auth.RegistrationService;
import com.example.resetpassword.web.ResetMessages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 002 FR-011a: the only page of the feature; same thin-controller pattern as the 001 reset page. */
@Controller
public class CompleteRegistrationPageController {

    private final RegistrationService registration;

    public CompleteRegistrationPageController(RegistrationService registration) {
        this.registration = registration;
    }

    @GetMapping("/complete-registration")
    public String open(@RequestParam(name = "token", required = false) String token, Model model,
            HttpServletResponse response) {
        noLeakHeaders(response);
        if (registration.checkLink(token) != TokenStatus.USABLE) {
            return invalid(model);
        }
        model.addAttribute("form", new CompleteRegistrationForm(token));
        return "complete-registration";
    }

    @PostMapping("/complete-registration")
    public String submit(@ModelAttribute("form") CompleteRegistrationForm form, Model model,
            HttpServletResponse response) {
        noLeakHeaders(response);
        return switch (registration.completeRegistration(form.getToken(), form.getPassword(),
                form.getConfirmPassword())) {
            case RegistrationOutcome.Completed completed -> {
                model.addAttribute("message", ResetMessages.REGISTRATION_DONE);
                yield "registration-complete";
            }
            case RegistrationOutcome.Rejected rejected -> {
                model.addAttribute("form", new CompleteRegistrationForm(form.getToken()));
                model.addAttribute("errors", rejected.violations().stream().map(ResetMessages::message).toList());
                yield "complete-registration";
            }
            case RegistrationOutcome.Invalid invalid -> invalid(model);
        };
    }

    private static String invalid(Model model) {
        model.addAttribute("message", ResetMessages.INVALID_LINK);
        return "registration-invalid";
    }

    private static void noLeakHeaders(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
    }
}
