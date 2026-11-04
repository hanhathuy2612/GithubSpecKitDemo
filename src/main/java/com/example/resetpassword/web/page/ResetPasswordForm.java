package com.example.resetpassword.web.page;

public class ResetPasswordForm {

    private String token;
    private String newPassword;
    private String confirmPassword;

    public ResetPasswordForm() {
    }

    public ResetPasswordForm(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    @Override
    public String toString() {
        return "ResetPasswordForm[redacted]";
    }
}
