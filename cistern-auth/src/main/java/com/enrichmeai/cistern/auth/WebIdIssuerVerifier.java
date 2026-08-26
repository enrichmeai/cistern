package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Solid;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.NodeIterator;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Confirms that a WebID authorises the issuer that minted a token for it (T4.3).
 *
 * <p>This is the check that makes Solid-OIDC trustworthy. T4.1 proves an issuer signed the
 * token; T4.2 proves the caller holds the key it is bound to. Neither says the WebID ever
 * agreed to that issuer — and without this, anyone who can run an identity provider can mint a
 * token for anybody's WebID. So: fetch the WebID document, and look for a
 * {@code solid:oidcIssuer} triple naming the token's {@code iss}.
 *
 * <h2>Two rulings this encodes</h2>
 *
 * <p><strong>Fail closed.</strong> A WebID that cannot be fetched authenticates nobody — 401,
 * never 500, and never "assume it was fine". The cost is real and accepted: a third party's
 * outage locks their users out of this pod. Anonymous requests never reach here, so public
 * resources stay readable throughout.
 *
 * <p><strong>Any HTTPS WebID.</strong> A pod that only accepts some identities is not
 * interoperable Solid, so the URL is caller-chosen and {@link WebIdFetchPolicy} carries the
 * guards that makes that survivable. Redirects are followed by hand rather than by the client,
 * because each hop has to be re-checked against that policy — following them automatically and
 * checking only the first URL is the standard SSRF bypass.
 *
 * <p>Verified answers are cached for {@code cacheTtl}. Refusals are not: a WebID that has just
 * added its issuer should work on the next request, not after a timeout.
 */
public final class WebIdIssuerVerifier implements WebIdIssuers {

    private final WebClient http;
    private final WebIdFetchPolicy policy;
    private final Duration cacheTtl;
    private final Clock clock;
    private final ConcurrentHashMap<Authorisation, Instant> verified = new ConcurrentHashMap<>();

    /** A WebID's authorisation of one issuer — the unit that is cached. */
    record Authorisation(URI webId, URI issuer) {
    }

    public WebIdIssuerVerifier(WebClient http, WebIdFetchPolicy policy, Duration cacheTtl, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether {@code webId} names {@code issuer}. Never empty, never an error. */
    @Override
    public Mono<WebIdVerdict> verify(URI webId, URI issuer) {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(issuer, "issuer");

        Authorisation authorisation = new Authorisation(webId, issuer);
        Instant cached = verified.get(authorisation);
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached)) {
            return Mono.just(WebIdVerdict.Verified.instance());
        }

        return fetch(documentOf(webId), policy.maxRedirects())
                .map(body -> check(webId, issuer, body, authorisation))
                // A policy refusal keeps its own reason: a test that cannot tell "scheme
                // refused" from "host unreachable" cannot prove the SSRF guard ran at all.
                .onErrorResume(RefusedException.class,
                        e -> Mono.just(new WebIdVerdict.Refused(e.reason, e.getMessage())))
                .onErrorResume(e -> Mono.just(
                        WebIdVerdict.Refused.of(JwtRejectionReason.WEBID_UNREACHABLE, describe(e))));
    }

    /**
     * The document a WebID lives in: the WebID minus its fragment.
     *
     * <p>A WebID is {@code https://alice.example/profile/card#me}; the fragment identifies
     * Alice <em>within</em> the document, and asking a server for it would be asking for the
     * wrong resource.
     */
    static URI documentOf(URI webId) {
        String text = webId.toString();
        int hash = text.indexOf('#');
        return hash < 0 ? webId : URI.create(text.substring(0, hash));
    }

    private Mono<String> fetch(URI uri, int redirectsLeft) {
        Optional<JwtRejectionReason> refusal = policy.refuse(uri);
        if (refusal.isPresent()) {
            return Mono.error(new RefusedException(refusal.get(), uri));
        }
        return http.get()
                .uri(uri)
                .accept(MediaType.valueOf(Representation.TURTLE))
                .exchangeToMono(response -> {
                    if (response.statusCode().is3xxRedirection()) {
                        if (redirectsLeft <= 0) {
                            return response.releaseBody().then(Mono.error(
                                    new RefusedException(JwtRejectionReason.WEBID_UNREACHABLE, uri)));
                        }
                        String location = response.headers().header(HttpHeaders.LOCATION)
                                .stream().findFirst().orElse(null);
                        if (location == null) {
                            return response.releaseBody().then(Mono.error(
                                    new RefusedException(JwtRejectionReason.WEBID_UNREACHABLE, uri)));
                        }
                        // Resolved against the current hop, then re-checked by the recursive
                        // call: a public host answering 302 to 127.0.0.1 gets no further.
                        return response.releaseBody()
                                .then(fetch(uri.resolve(location), redirectsLeft - 1));
                    }
                    if (!response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody().then(Mono.error(
                                new RefusedException(JwtRejectionReason.WEBID_UNREACHABLE, uri)));
                    }
                    return response.bodyToMono(String.class);
                })
                .timeout(policy.connectTimeout());
    }

    /**
     * The half of verification that needs no network: parse the document, look for the triple.
     *
     * <p>Package-private so it is tested directly. The fetch half cannot be exercised against
     * a loopback fixture server without weakening {@link WebIdFetchPolicy}, and a guard with a
     * test-only bypass is a guard that stops guarding — so the policy is tested on its own
     * (every refused scheme and address range) and its wiring here is tested by one case that
     * proves a refused URL never reaches the network at all.
     */
    WebIdVerdict check(URI webId, URI issuer, String body) {
        return check(webId, issuer, body, new Authorisation(webId, issuer));
    }

    private WebIdVerdict check(URI webId, URI issuer, String body, Authorisation authorisation) {
        if (body.length() > policy.maxBodyBytes()) {
            return WebIdVerdict.Refused.of(JwtRejectionReason.WEBID_UNREACHABLE, "document exceeds cap");
        }
        Model model;
        try {
            model = RdfIo.parse(
                    new Representation(Representation.TURTLE, body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new ResourceIdentifier(documentOf(webId)));
        } catch (RuntimeException e) {
            return WebIdVerdict.Refused.of(JwtRejectionReason.WEBID_UNPARSEABLE, describe(e));
        }

        Resource subject = ResourceFactory.createResource(webId.toString());
        Set<String> named = new LinkedHashSet<>();
        NodeIterator issuers = model.listObjectsOfProperty(subject, Solid.OIDC_ISSUER);
        while (issuers.hasNext()) {
            RDFNode node = issuers.next();
            if (node.isURIResource()) {
                named.add(node.asResource().getURI());
            }
        }
        if (!namesIssuer(named, issuer)) {
            return WebIdVerdict.Refused.of(JwtRejectionReason.WEBID_ISSUER_NOT_NAMED, issuer, named);
        }
        verified.put(authorisation, clock.instant().plus(cacheTtl));
        return WebIdVerdict.Verified.instance();
    }

    /**
     * Whether {@code named} contains {@code issuer}.
     *
     * <p>Compared with a trailing slash tolerated on either side, because that is the one
     * difference a real deployment actually produces: CSS reports {@code iss} as
     * {@code https://host/} while a hand-written profile commonly writes {@code https://host}.
     * Nothing else is normalised — an issuer is otherwise compared verbatim.
     */
    private static boolean namesIssuer(Set<String> named, URI issuer) {
        String wanted = withoutTrailingSlash(issuer.toString());
        for (String candidate : named) {
            if (withoutTrailingSlash(candidate).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String withoutTrailingSlash(String uri) {
        return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
    }

    private static String describe(Throwable e) {
        if (e instanceof RefusedException refused) {
            return refused.reason.describe(refused.uri);
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    /** Carries a policy refusal out of the reactive chain without becoming a 500. */
    private static final class RefusedException extends RuntimeException {

        private final transient JwtRejectionReason reason;
        private final transient URI uri;


        RefusedException(JwtRejectionReason reason, URI uri) {
            super(reason.describe(uri), null, false, false);
            this.reason = reason;
            this.uri = uri;
        }
    }
}
