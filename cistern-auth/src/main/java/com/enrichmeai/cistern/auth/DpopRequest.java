package com.enrichmeai.cistern.auth;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * What a DPoP proof is checked against: the request as the <em>client</em> addressed it
 * (T4.2, RFC 9449 §4.3 steps 8, 9 and 12).
 *
 * <p><strong>{@code target} is the seam where reverse proxies bite, so it is a parameter
 * rather than something read off the exchange in here.</strong> The client signs {@code htu}
 * with the URL it dialled — {@code https://pod.example/alice/note.ttl}. A server behind a
 * proxy, which is how Cistern is meant to be run, sees {@code http://127.0.0.1:3000/...}.
 * Compare the proof against what the socket says and every proof in production fails step 9,
 * with a 401 that looks like a broken client and is actually a deployment detail.
 *
 * <p>So the decision of how to derive the externally visible URL — whether {@code Forwarded}
 * and {@code X-Forwarded-*} are honoured, and from which hops they are trusted — belongs to
 * the layer that knows the deployment, and is made once when this is constructed. Trusting
 * those headers from an untrusted peer lets a caller choose the URL their proof is checked
 * against, which defeats step 9 entirely; that is why this type will not guess.
 *
 * @param method      the HTTP method, compared against {@code htm} case-sensitively per §4.2
 * @param target      the externally visible request URI, query and fragment already removed
 * @param accessToken the token presented alongside, if any — its presence makes {@code ath}
 *                    and the {@code cnf.jkt} binding required rather than optional
 */
public record DpopRequest(String method, URI target, Optional<String> accessToken) {

    public DpopRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(accessToken, "accessToken");
        if (method.isBlank()) {
            throw new IllegalArgumentException(AuthMessage.DPOP_METHOD_BLANK.format());
        }
        if (!target.isAbsolute()) {
            throw new IllegalArgumentException(AuthMessage.DPOP_TARGET_NOT_ABSOLUTE.format(target));
        }
        target = withoutQueryOrFragment(target);
    }

    /**
     * RFC 9449 §4.3 step 9: {@code htu} is compared "ignoring any query and fragment parts".
     *
     * <p>Shared with the validator, which applies it to the claim so that both sides of the
     * comparison are reduced the same way — a client is entitled to sign the full URL it
     * dialled, query string and all.
     */
    static URI withoutQueryOrFragment(URI uri) {
        if (uri.getRawQuery() == null && uri.getRawFragment() == null) {
            return uri;
        }
        String text = uri.toString();
        int cut = text.length();
        int query = text.indexOf('?');
        int fragment = text.indexOf('#');
        if (query >= 0) {
            cut = Math.min(cut, query);
        }
        if (fragment >= 0) {
            cut = Math.min(cut, fragment);
        }
        return URI.create(text.substring(0, cut));
    }
}
