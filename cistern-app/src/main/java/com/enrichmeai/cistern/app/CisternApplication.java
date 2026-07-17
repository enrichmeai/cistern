package com.enrichmeai.cistern.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.enrichmeai.cistern")
public class CisternApplication {

    public static void main(String[] args) {
        SpringApplication.run(CisternApplication.class, args);
    }
}
