package com.enrichmeai.cistern.webflux;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Boot application so this module's HTTP tests can stand up the real WebFlux stack
 * (the runnable server lives in cistern-app, which depends on this module and therefore
 * cannot be depended on from here). Component scanning is restricted to this module.
 */
@SpringBootApplication(scanBasePackages = "com.enrichmeai.cistern.webflux")
public class TestCisternApplication {
}
