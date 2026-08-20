package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Where the pod is, in the two senses a front door needs (T6.2's static binding):
 *
 * <ul>
 *   <li><strong>{@code podBase}</strong> — the origin resources are minted under
 *       ({@code cistern.base-url}). Every {@link ResourceIdentifier} the tools name — in
 *       refusals, in receipts, in {@code ldp:contains} — lives under it.</li>
 *   <li><strong>{@code connectBase}</strong> — where this process sends HTTP requests. For the
 *       standalone bridge the two are the same value; embedded in cistern-app the connection
 *       is the server's own loopback port while identifiers stay under the configured base
 *       (which is safe because {@code RequestPaths} resolves identifiers from configuration,
 *       never from the {@code Host} header).</li>
 * </ul>
 *
 * <p>{@link #resolve(String)} is the one place a model-supplied {@code url} argument becomes a
 * target: a path ({@code /notes/week}) or an absolute URL under either base is accepted;
 * anything else is refused as {@link PodProblem.BadArgument} — this door reaches one pod, and
 * a URL outside it is a mistake, not a request to proxy.
 */
record PodAddress(URI connectBase, URI podBase) {

    private static final String PATH_SEPARATOR = "/";

    PodAddress {
        connectBase = trimmed(Objects.requireNonNull(connectBase, "connectBase"));
        podBase = trimmed(Objects.requireNonNull(podBase, "podBase"));
    }

    /** The same address for both roles: the standalone bridge's shape. */
    static PodAddress of(URI base) {
        return new PodAddress(base, base);
    }

    /** A model-supplied {@code url} argument as a target, or {@link PodProblem.BadArgument}. */
    PodTarget resolve(String urlArgument) {
        Objects.requireNonNull(urlArgument, ToolArgument.URL.jsonName());
        String path = pathOf(urlArgument.trim());
        try {
            ResourceIdentifier identifier = new ResourceIdentifier(new URI(podBase + path));
            return new PodTarget(identifier, new URI(connectBase + path));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new PodProblem.BadArgument(McpMessage.ARGUMENT_INVALID.format(
                    ToolArgument.URL.jsonName(), urlArgument, e.getMessage()));
        }
    }

    /** Where to send a request for {@code identifier} (already known to live under the pod base). */
    URI requestUriFor(ResourceIdentifier identifier) {
        String uri = identifier.uri().toString();
        if (!uri.startsWith(podBase.toString() + PATH_SEPARATOR)) {
            throw new PodProblem.BadArgument(McpMessage.TARGET_OUTSIDE_POD.format(uri));
        }
        return URI.create(connectBase + uri.substring(podBase.toString().length()));
    }

    private String pathOf(String urlArgument) {
        if (urlArgument.startsWith(PATH_SEPARATOR)) {
            return urlArgument;
        }
        for (URI base : new URI[] {podBase, connectBase}) {
            String prefix = base.toString() + PATH_SEPARATOR;
            if (urlArgument.startsWith(prefix)) {
                return urlArgument.substring(base.toString().length());
            }
        }
        throw new PodProblem.BadArgument(McpMessage.TARGET_OUTSIDE_POD.format(urlArgument));
    }

    /** Base URLs are origins (plus an optional prefix); a trailing slash is insignificant. */
    private static URI trimmed(URI base) {
        String text = base.toString();
        while (text.endsWith(PATH_SEPARATOR)) {
            text = text.substring(0, text.length() - PATH_SEPARATOR.length());
        }
        return URI.create(text);
    }

    /**
     * One resolved target: the identifier the pod knows the resource by (under the pod base)
     * and the URI this process requests it at (under the connect base).
     */
    record PodTarget(ResourceIdentifier identifier, URI requestUri) {
    }
}
