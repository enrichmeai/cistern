package com.enrichmeai.cistern.webflux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enforcement guard through a real boot (T7.7, #94), and the two loud-but-allowed shapes
 * around it. Each case starts the module's Boot application on an ephemeral port with a fresh
 * storage root, exactly as {@code java -jar} would, so what is asserted is what an operator sees.
 *
 * <ul>
 *   <li><strong>Refused:</strong> a service principal configured with no
 *       {@code cistern.owner.web-id}. The context never comes up; the failure names the fix.</li>
 *   <li><strong>Allowed, loud:</strong> nothing configured — T5.3's unprotected pod, warned
 *       about on every boot ({@code NO_OWNER_CONFIGURED}).</li>
 *   <li><strong>Allowed, loud:</strong> an owner named with no token and no other credential
 *       source — enforcement on, nobody able to authenticate
 *       ({@code ENFORCEMENT_WITHOUT_CREDENTIAL}).</li>
 * </ul>
 * The happy path — an owner named without a token, authenticating through another source — is
 * {@link OwnerWithoutTokenHttpTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class EnforcementGuardBootTest {

    private static final String OWNER = "https://valuedocs.co.in/profile#admin";
    private static final String LEGAL = "https://valuedocs.co.in/apps/legal#id";
    private static final String LEGAL_HASH =
            "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481";

    /** Boot the module's application with these properties, on an ephemeral port and a fresh root. */
    private static ConfigurableApplicationContext boot(String... properties) {
        return new SpringApplicationBuilder(TestCisternApplication.class)
                .properties("server.port=0", "cistern.storage.root=" + freshRoot())
                .properties(properties)
                .run();
    }

    private static Path freshRoot() {
        try {
            return Files.createTempDirectory("cistern-t77-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether {@code message} appears anywhere in the failure's cause chain. */
    private static boolean causeChainMentions(Throwable failure, String message) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(message)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a service principal without cistern.owner.web-id: the server refuses to start, naming the fix")
    void credentialWithoutOwnerRefusesToStart() {
        String expected = WebfluxMessage.ENFORCEMENT_REQUIRES_OWNER.format(
                CisternProperties.Auth.CredentialSource.SERVICE_PRINCIPALS.property());

        Throwable failure = assertThrows(Throwable.class, () -> {
            try (ConfigurableApplicationContext context = boot(
                    "cistern.auth.service-principals[0].web-id=" + LEGAL,
                    "cistern.auth.service-principals[0].credential-hash=" + LEGAL_HASH)) {
                // Never reached: the point is that no context exists to close.
            }
        });

        assertTrue(causeChainMentions(failure, expected),
                "the start-up failure must carry the catalogue message; got: " + failure);
    }

    @Test
    @DisplayName("nothing configured: starts, and says on every boot that Web Access Control is off")
    void nothingConfiguredIsLoud(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot()) {
            assertFalse(context.containsBean("cisternAuthorizationFilter"), "no owner, no enforcement");
        }
        assertTrue(output.getOut().contains(WebfluxMessage.NO_OWNER_CONFIGURED.format()),
                "T5.3: an unprotected pod is loud, not silent");
    }

    @Test
    @DisplayName("an owner named with no token and no other credential: starts enforced, and says nobody can authenticate")
    void ownerWithoutAnyCredentialIsLoud(CapturedOutput output) {
        try (ConfigurableApplicationContext context = boot("cistern.owner.web-id=" + OWNER)) {
            assertTrue(context.containsBean("cisternAuthorizationFilter"), "the WebID alone turns enforcement on");
        }
        assertTrue(output.getOut().contains(WebfluxMessage.ENFORCEMENT_WITHOUT_CREDENTIAL.format()),
                "enforced but inert must be said out loud");
        assertFalse(output.getOut().contains(WebfluxMessage.NO_OWNER_CONFIGURED.format()),
                "an owner IS configured; the no-owner warning would be wrong here");
    }
}
