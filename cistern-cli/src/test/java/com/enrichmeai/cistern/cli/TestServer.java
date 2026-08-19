package com.enrichmeai.cistern.cli;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The real HTTP stack — cistern-webflux's handlers, {@code AuthorizationFilter},
 * {@code OwnerPodSeeder}, the file store — booted in-process for the end-to-end test. Mirrors
 * {@code CisternApplication}, which cannot be depended on from here (its jar is repackaged for
 * running, not for the classpath); scanning is restricted to the webflux module exactly as
 * that module's own HTTP tests do.
 */
@SpringBootApplication(scanBasePackages = "com.enrichmeai.cistern.webflux")
class TestServer {
}
