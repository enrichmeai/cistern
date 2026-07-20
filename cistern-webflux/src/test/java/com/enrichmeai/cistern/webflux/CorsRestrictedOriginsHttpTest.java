package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wide-open CORS is T2.8's <em>default</em>, not a hard-coding — pinned here by narrowing it.
 *
 * <p>A public pod serving the open Solid app ecosystem needs any origin (Solid Protocol §8.1),
 * but a deployer running a pod for one known application should be able to say so, and an
 * institution may be required to. Without this class "configurable" would be a claim in a
 * javadoc; with it, a change that quietly wired the permissive set as a constant fails.
 *
 * <p>A separate class rather than a nested one because the properties have to be set before the
 * application context is built, and {@link CorsHttpTest}'s context is the default-configured one
 * it exists to test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "cistern.base-url=http://localhost:3000",
                "cistern.cors.allowed-origins=https://permitted-app.example",
                "cistern.cors.max-age=15m"})
class CorsRestrictedOriginsHttpTest {

    private static final String PERMITTED_ORIGIN = "https://permitted-app.example";

    private static final String REFUSED_ORIGIN = "https://some-other-app.example";

    /** {@code cistern.cors.max-age=15m} in seconds, as Fetch renders it. */
    private static final String EXPECTED_MAX_AGE = "900";

    private static final Path STORAGE_ROOT = createTempRoot();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t28-cors-restricted-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("the configured origin is granted, and still echoed rather than wildcarded")
    void theConfiguredOriginIsGranted() {
        RawHttp.Response response = preflight(PERMITTED_ORIGIN);

        assertTrue(HttpStatus.valueOf(response.status()).is2xxSuccessful(),
                () -> "the configured origin must be granted; got " + response.status());
        assertEquals(PERMITTED_ORIGIN,
                response.first(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("an origin outside the configured list is refused")
    void anUnconfiguredOriginIsRefused() {
        // A rejected preflight is a 403 with no Access-Control-Allow-Origin, which is what makes
        // the browser block the request rather than merely omit a header.
        RawHttp.Response response = preflight(REFUSED_ORIGIN);

        assertEquals(HttpStatus.FORBIDDEN.value(), response.status());
        assertFalse(response.has(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("cistern.cors.max-age is honoured too, so the whole record is wired, not just origins")
    void theConfiguredMaxAgeIsHonoured() {
        RawHttp.Response response = preflight(PERMITTED_ORIGIN);

        assertEquals(EXPECTED_MAX_AGE, response.first(HttpHeaders.ACCESS_CONTROL_MAX_AGE));
    }

    private RawHttp.Response preflight(String origin) {
        return RawHttp.request(port, HttpMethod.OPTIONS, "/")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name())
                .send();
    }
}
