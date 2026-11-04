package com.example.resetpassword;

import com.example.resetpassword.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class ResetPasswordApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResetPasswordApplication.class, args);
    }
}
