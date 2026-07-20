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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code OPTIONS *} (RFC 9110 §9.3.7) over a real socket.
 *
 * <h2>Why the raw client</h2>
 * The asterisk-form is a request-<em>target</em>, not a URI: {@code OPTIONS * HTTP/1.1} is the
 * whole of it. Every HTTP client in the test stack builds its request line from a URI and so
 * cannot express it — which is exactly why this form is easy to get wrong and worth a real
 * request. {@link RawHttp} writes the request line itself.
 *
 * <p>It was in fact wrong. The first implementation compared the request path against
 * {@code "*"} and never matched, because Reactor Netty resolves the asterisk-form to an
 * <em>empty</em> path; {@code OPTIONS *} answered 400. Ground rule 6 in the flesh: the mistake
 * was invisible to any test built on the same assumption as the code, and only a real request
 * exposed it. This class exists so it cannot come back.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cistern.base-url=http://localhost:3000")
class ResourceOptionsAsteriskFormTest {

    /** RFC 9110 §9.3.7's asterisk-form request-target. */
    private static final String ASTERISK_FORM = "*";

    private static final Path STORAGE_ROOT = createTempRoot();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t28-asterisk-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("OPTIONS * answers 204 with the server-wide Allow, not a 400 or a 404")
    void asteriskFormIsAnsweredForTheServer() {
        RawHttp.Response response = asteriskOptions();

        assertEquals(HttpStatus.NO_CONTENT.value(), response.status(),
                () -> "OPTIONS * must be answered for the server in general; got "
                        + response.status() + " " + response.headers());
        // The union of every ResourceKind row, read from the table rather than spelled out —
        // the architect requirement on #19 applies to this response too.
        assertEquals(ResourceKind.supportedMethodsAllow(), response.first(HttpHeaders.ALLOW));
    }

    @Test
    @DisplayName("OPTIONS * claims nothing about any resource")
    void asteriskFormAdvertisesNoResourceMetadata() {
        // §9.3.7: it "applies to the server in general rather than to a specific resource", so
        // Accept-Put and the typing Links — which describe one resource — must be absent.
        RawHttp.Response response = asteriskOptions();

        for (String absent : List.of(HttpConstants.ACCEPT_PUT, HttpConstants.ACCEPT_POST,
                HttpHeaders.ACCEPT_PATCH, HttpHeaders.LINK)) {
            assertFalse(response.has(absent),
                    () -> absent + " describes a resource, and this response is about none");
        }
    }

    private RawHttp.Response asteriskOptions() {
        return RawHttp.request(port, HttpMethod.OPTIONS, ASTERISK_FORM).send();
    }
}
