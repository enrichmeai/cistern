package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal HTTP/1.1 client over a plain socket, for the tests that must see exactly what the
 * server put on the wire.
 *
 * <h2>Why not {@code WebTestClient}</h2>
 * Both of its binding modes are unusable for CORS assertions here, for different reasons, and
 * both fail in the same misleading way — a 403 that looks like the server refusing an origin:
 *
 * <ul>
 *   <li><b>Bound to the application context</b> (the mock), the request never has a scheme,
 *       host or port. CORS is decided by comparing the request's {@code Origin} against the
 *       origin it was sent <em>to</em>, so Spring's {@code CorsUtils.isSameOrigin} asserts all
 *       three are present ("Actual request scheme must not be null");
 *       {@code DefaultCorsProcessor} catches the assertion, logs "Reject: origin is malformed"
 *       and answers 403. Setting a {@code baseUrl} does not help — it does not change the URI
 *       the server side sees.</li>
 *   <li><b>Bound to a real port</b>, the request goes out through an HTTP client library that
 *       this build environment intercepts: the response comes back 403 carrying headers no part
 *       of Cistern emits ({@code X-Correlation-ID}, {@code X-Frame-Options},
 *       {@code Referrer-Policy}). {@code HttpClient.noProxy()} does not avoid it. A raw socket
 *       to the same port, in the same JVM, gets the correct 200 — which is how the
 *       interception was identified rather than mistaken for a bug in the server.</li>
 * </ul>
 *
 * <p>So these tests speak HTTP themselves. That is a virtue as much as a workaround: what they
 * assert is the actual byte stream a browser would receive, with no client library's
 * interpretation in between — ground rule 6 applied to the transport.
 *
 * <p>Blocking I/O is deliberate and fine: ground rule 3 constrains production code, and a test
 * that spoke the wire reactively would be exercising a client library rather than the server.
 */
final class RawHttp {

    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private static final String CRLF = "\r\n";

    private static final String HOST = "localhost";

    /** Separates the status code from the reason phrase and the version in the status line. */
    private static final String STATUS_LINE_SEPARATOR = " ";

    private static final int STATUS_CODE_FIELD = 1;

    private static final char HEADER_SEPARATOR = ':';

    /**
     * A response's status line and header section. No body: every assertion these tests make is
     * about headers, and not reading the body keeps the helper to one obvious behaviour.
     */
    record Response(int status, HttpHeaders headers) {

        String first(String fieldName) {
            return headers.getFirst(fieldName);
        }

        /** Every field line of this name, empty when absent — never null, so callers can stream. */
        List<String> all(String fieldName) {
            List<String> values = headers.get(fieldName);
            return values == null ? List.of() : values;
        }

        boolean has(String fieldName) {
            return headers.containsHeader(fieldName);
        }
    }

    /** A request builder: {@code request(port, GET, "/a").header("Origin", o).send()}. */
    static Request request(int port, HttpMethod method, String target) {
        return new Request(port, method.name(), target);
    }

    static final class Request {

        private final int port;
        private final String method;
        private final String target;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Request(int port, String method, String target) {
            this.port = port;
            this.method = method;
            this.target = target;
        }

        Request header(String fieldName, String value) {
            headers.put(fieldName, value);
            return this;
        }

        Response send() {
            return RawHttp.send(port, requestText());
        }

        private String requestText() {
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(target).append(" HTTP/1.1").append(CRLF)
                    .append(HttpHeaders.HOST).append(": ").append(HOST).append(':').append(port)
                    .append(CRLF);
            headers.forEach((fieldName, value) ->
                    request.append(fieldName).append(": ").append(value).append(CRLF));
            // So the server closes once the response is complete and the read below terminates
            // without needing to interpret Content-Length or chunked framing.
            return request.append(HttpHeaders.CONNECTION).append(": close").append(CRLF)
                    .append(CRLF)
                    .toString();
        }
    }

    private static Response send(int port, String requestText) {
        try (Socket socket = new Socket(HOST, port)) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII);
            out.write(requestText);
            out.flush();
            return readResponse(socket);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Response readResponse(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.US_ASCII));

        String statusLine = in.readLine();
        if (statusLine == null) {
            throw new IOException("empty response");
        }
        int status = Integer.parseInt(
                statusLine.split(STATUS_LINE_SEPARATOR)[STATUS_CODE_FIELD]);

        HttpHeaders headers = new HttpHeaders();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(HEADER_SEPARATOR);
            if (separator > 0) {
                headers.add(line.substring(0, separator).trim(),
                        line.substring(separator + 1).trim());
            }
        }
        return new Response(status, headers);
    }

    private RawHttp() {
        // static factory only
    }
}
