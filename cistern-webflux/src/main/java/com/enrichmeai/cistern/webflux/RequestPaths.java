package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Request path → {@link ResourceIdentifier}, resolved against the configured
 * {@code cistern.base-url}.
 *
 * <h2>Raw, not decoded</h2>
 * The identifier is built from the request's <b>raw</b> (still percent-encoded) path. That
 * matches the storage backend, which keys resources by raw path segments so that distinct
 * URIs stay distinct, and it matches RFC 3986 §2.2: a percent-encoded reserved character is
 * <em>not</em> equivalent to the character it encodes. Decoding first would merge URIs that
 * the protocol says are different resources.
 *
 * <h2>What is rejected, and why here</h2>
 * Some request-targets are syntactically fine as URIs but name a resource this server has no
 * way to represent. They are rejected at the edge with
 * {@link CisternException.BadInput} (→ 400) rather than being passed down:
 *
 * <ul>
 *   <li><b>{@code %2F} inside a segment.</b> {@code FileResourceStore} signals
 *       {@code IllegalArgumentException} for these, and — worse —
 *       {@link ResourceIdentifier#isContainer()} tests the <em>decoded</em> path, so
 *       {@code /a%2F} would report itself a container while its raw form has no trailing
 *       separator. Raw and decoded slash structure would disagree, and Solid's
 *       trailing-slash semantics (§3.1) are decided on that structure. Letting it through
 *       would turn a client mistake into a 500; the encoded-slash name is simply not
 *       addressable here, which is a 400.</li>
 *   <li><b>Empty segments</b> ({@code //}, {@code /a//b}) — the store cannot name them and
 *       signals {@code IllegalArgumentException}; same 500-instead-of-400 argument.</li>
 *   <li><b>Dot segments</b> ({@code .}, {@code ..}) — {@link ResourceIdentifier} requires a
 *       normalized URI. Rejecting is safer than silently collapsing them: {@code /a/../b}
 *       and {@code /b} would otherwise be two spellings of one resource, and path traversal
 *       deserves an explicit refusal rather than a quiet rewrite.</li>
 * </ul>
 *
 * <p>The query string is deliberately dropped: it is not part of a Solid resource's
 * identity, and carrying it would make {@code /a.ttl?x=1} a different stored resource.
 */
@Component
public class RequestPaths {

    /** The URI path separator; also the container marker (Solid Protocol §3.1). */
    private static final String SEPARATOR = "/";

    /** Two separators with nothing between them: a segment the store cannot name. */
    private static final String EMPTY_SEGMENT = SEPARATOR + SEPARATOR;

    /** Percent-encoded {@code /}, upper-cased for a case-insensitive comparison. */
    private static final String ENCODED_SLASH = "%2F";

    private static final String CURRENT_SEGMENT = ".";
    private static final String PARENT_SEGMENT = "..";

    private static final String[] NO_SEGMENTS = new String[0];

    /** Negative limit for {@link String#split}: trailing empty segments are kept, not hidden. */
    private static final int KEEP_TRAILING_EMPTY = -1;

    private final String baseUrl;

    public RequestPaths(CisternProperties properties) {
        this.baseUrl = properties.baseUrl();
    }

    /**
     * @param request the incoming request
     * @return the identifier the request targets
     * @throws CisternException.BadInput if the request-target cannot name a resource here
     */
    public ResourceIdentifier identifierFor(ServerRequest request) {
        return identifierFor(request.uri().getRawPath());
    }

    /** Package-visible seam so the rules can be unit-tested without an HTTP stack. */
    ResourceIdentifier identifierFor(String rawPath) {
        if (rawPath == null || !rawPath.startsWith(SEPARATOR)) {
            throw new CisternException.BadInput(
                    WebfluxMessage.TARGET_NOT_ABSOLUTE.format(rawPath));
        }
        if (rawPath.contains(EMPTY_SEGMENT)) {
            throw new CisternException.BadInput(
                    WebfluxMessage.TARGET_EMPTY_SEGMENT.format(rawPath));
        }
        for (String segment : segmentsOf(rawPath)) {
            if (segment.equals(CURRENT_SEGMENT) || segment.equals(PARENT_SEGMENT)) {
                throw new CisternException.BadInput(
                        WebfluxMessage.TARGET_DOT_SEGMENT.format(rawPath));
            }
            if (segment.toUpperCase(Locale.ROOT).contains(ENCODED_SLASH)) {
                throw new CisternException.BadInput(
                        WebfluxMessage.TARGET_ENCODED_SLASH.format(rawPath));
            }
        }
        return new ResourceIdentifier(parse(baseUrl + rawPath, rawPath));
    }

    /**
     * Segments of the raw path, ignoring the leading separator and a trailing one (the
     * container marker). {@code "/"} yields no segments.
     */
    private static String[] segmentsOf(String rawPath) {
        String body = rawPath.substring(SEPARATOR.length());
        if (body.endsWith(SEPARATOR)) {
            body = body.substring(0, body.length() - SEPARATOR.length());
        }
        return body.isEmpty() ? NO_SEGMENTS : body.split(SEPARATOR, KEEP_TRAILING_EMPTY);
    }

    /**
     * {@code new URI(String)} rather than {@code URI.create}: a raw path may still contain
     * octets that are illegal in a URI (a literal space, a stray {@code %} that is not a
     * valid escape). That is a malformed request-target — the client's 400, never a 500.
     */
    private static URI parse(String candidate, String rawPath) {
        try {
            return new URI(candidate);
        } catch (URISyntaxException e) {
            throw new CisternException.BadInput(
                    WebfluxMessage.TARGET_MALFORMED.format(rawPath, e.getReason()));
        }
    }
}
