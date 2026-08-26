package com.enrichmeai.cistern.auth;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/**
 * What Cistern is willing to dereference when checking a WebID (T4.3).
 *
 * <p>A pod must serve users from any identity provider their WebID names — that is what makes
 * Solid interoperable — so the URL fetched here is <strong>chosen by the caller</strong>, in a
 * token they presented. That is a server-side request forgery primitive by construction: an
 * unauthenticated party naming a URL our process will connect to. The guards below are what
 * make it safe enough to do at all, and none of them is optional.
 *
 * <ul>
 *   <li><strong>HTTPS only.</strong> {@code http} would let a network attacker forge the very
 *       document that decides which issuer to trust; {@code file:}, {@code gopher:} and the
 *       rest have no business here.
 *   <li><strong>No private, loopback, link-local or wildcard addresses.</strong> Otherwise a
 *       WebID of {@code https://169.254.169.254/…} turns this into a cloud metadata reader,
 *       and {@code https://127.0.0.1:3000/…} into a way to make Cistern call itself.
 *   <li><strong>A redirect cap, re-checked at every hop.</strong> Checking only the first URL
 *       is the classic bypass: the attacker's public host answers 302 to a private one.
 *   <li><strong>A body cap and a timeout.</strong> A WebID pointing at an endless stream is a
 *       denial of service against the pod, not against its owner.
 * </ul>
 *
 * <p><strong>Residual risk, stated:</strong> resolving a host and then connecting is two
 * operations, so a DNS entry that changes between them (rebinding) can still land on an
 * address this rejected. Closing that needs a connection-time check inside the HTTP client's
 * socket factory. It is not closed here, and it is not pretended to be.
 *
 * @param connectTimeout how long to wait for the WebID document before giving up — the ruling
 *                       is fail-closed, so a slow host becomes a 401, never a hang
 * @param maxRedirects   hops followed, each re-checked against this policy
 * @param maxBodyBytes   the largest WebID document accepted
 * @param hosts          how a hostname becomes addresses. A dependency rather than a direct
 *                       call to {@link InetAddress}, for the same reason {@link java.time.Clock}
 *                       is one: a rule about DNS answers cannot be tested against a DNS this
 *                       does not control. Not a bypass — the rule itself is unchanged and
 *                       applies to whatever the resolver returns.
 */
public record WebIdFetchPolicy(Duration connectTimeout, int maxRedirects, int maxBodyBytes,
                               HostResolver hosts) {

    /** How a hostname becomes the addresses a connection would reach. */
    @FunctionalInterface
    public interface HostResolver {

        /** Every address {@code host} resolves to. */
        InetAddress[] resolve(String host) throws UnknownHostException;

        /** The real one. */
        static HostResolver system() {
            return InetAddress::getAllByName;
        }
    }

    /** The only scheme a WebID may be dereferenced over. */
    public static final String REQUIRED_SCHEME = "https";

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    public static final int DEFAULT_MAX_REDIRECTS = 3;
    public static final int DEFAULT_MAX_BODY_BYTES = 256 * 1024;

    public WebIdFetchPolicy {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(hosts, "hosts");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException(AuthMessage.WEBID_TIMEOUT_INVALID.format(connectTimeout));
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException(AuthMessage.WEBID_REDIRECTS_INVALID.format(maxRedirects));
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException(AuthMessage.WEBID_BODY_CAP_INVALID.format(maxBodyBytes));
        }
    }

    /** The defaults: 5s, 3 hops, 256 KiB, real DNS. */
    public static WebIdFetchPolicy defaults() {
        return new WebIdFetchPolicy(DEFAULT_TIMEOUT, DEFAULT_MAX_REDIRECTS, DEFAULT_MAX_BODY_BYTES,
                HostResolver.system());
    }

    /**
     * Whether {@code uri} may be dereferenced, and if not, why.
     *
     * <p>Called for the WebID itself and again for every redirect target.
     *
     * @return empty when the URI is permitted
     */
    public java.util.Optional<JwtRejectionReason> refuse(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            return java.util.Optional.of(JwtRejectionReason.WEBID_INVALID);
        }
        // Scheme before host: file:///etc/passwd has no host, and reporting that as "malformed"
        // would hide the fact that a scheme this must never follow was offered.
        if (!REQUIRED_SCHEME.equalsIgnoreCase(uri.getScheme())) {
            return java.util.Optional.of(JwtRejectionReason.WEBID_SCHEME_REFUSED);
        }
        if (uri.getHost() == null) {
            return java.util.Optional.of(JwtRejectionReason.WEBID_INVALID);
        }
        return isPubliclyRoutable(uri.getHost(), hosts)
                ? java.util.Optional.empty()
                : java.util.Optional.of(JwtRejectionReason.WEBID_ADDRESS_REFUSED);
    }

    /**
     * Whether every address {@code host} resolves to is on the public internet.
     *
     * <p>Every address, not the first: a host with both a public and a loopback record would
     * otherwise pass here and connect to whichever the client picked.
     */
    private static boolean isPubliclyRoutable(String host, HostResolver hosts) {
        try {
            InetAddress[] addresses = hosts.resolve(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()      // 127/8, ::1
                        || address.isAnyLocalAddress()   // 0.0.0.0, ::
                        || address.isLinkLocalAddress()  // 169.254/16 — cloud metadata
                        || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                        || address.isMulticastAddress()
                        || isUniqueLocalV6(address)) {   // fc00::/7
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            // Unresolvable is refused rather than allowed: the ruling is fail-closed.
            return false;
        }
    }

    /** {@code fc00::/7}, which {@link InetAddress} has no predicate for. */
    private static boolean isUniqueLocalV6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
