package com.enrichmeai.cistern.mcp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The real HTTP stack — cistern-webflux's handlers, {@code AuthorizationFilter},
 * {@code OwnerPodSeeder}, the receipts route, the file store — booted in-process for the
 * end-to-end tests, exactly as cistern-cli's {@code TestServer} does (ground rule 6: the MCP
 * tools are exercised against the server they will really call, not a mock of it). Scanning
 * is restricted to the webflux module; cistern-app cannot be depended on from here (its jar
 * is repackaged for running, not for the classpath).
 */
@SpringBootApplication(scanBasePackages = "com.enrichmeai.cistern.webflux")
class TestServer {
}
