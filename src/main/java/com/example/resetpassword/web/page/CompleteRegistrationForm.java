package com.example.resetpassword.web.page;

public class CompleteRegistrationForm {

    private String token;
    private String password;
    private String confirmPassword;

    public CompleteRegistrationForm() {
    }

    public CompleteRegistrationForm(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    @Override
    public String toString() {
        return "CompleteRegistrationForm[redacted]";
    }
}
