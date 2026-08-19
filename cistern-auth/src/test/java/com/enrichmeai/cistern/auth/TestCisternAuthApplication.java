package com.enrichmeai.cistern.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot application for this module's HTTP tests: scans {@code com.enrichmeai.cistern} so the
 * WebFlux wiring (cistern-webflux, on the classpath as a dependency) and this module's
 * {@link CisternAuthConfiguration} are both present — the same shape cistern-app has.
 */
@SpringBootApplication(scanBasePackages = "com.enrichmeai.cistern")
public class TestCisternAuthApplication {
}
