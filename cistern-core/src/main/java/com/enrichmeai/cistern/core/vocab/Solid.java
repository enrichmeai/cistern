package com.enrichmeai.cistern.core.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * The Solid terms vocabulary
 * (<a href="http://www.w3.org/ns/solid/terms#">http://www.w3.org/ns/solid/terms#</a>).
 *
 * <p>Its members appear on the wire as <em>extension link relation types</em> rather than in a
 * graph: RFC 8288 §2.1.2 lets an application "use an extension relation type, which is a URI
 * that uniquely identifies the relation type", and Solid uses that for the relations it defines
 * itself. They are RDF vocabulary IRIs all the same, so they live in a per-namespace constant
 * class (ground rule 7) beside {@link Pim} rather than being spelled at the site that writes a
 * header.
 */
public final class Solid {

    /** The Solid terms namespace, {@value}. */
    public static final String NS = "http://www.w3.org/ns/solid/terms#";

    /**
     * {@code solid:storageDescription} — from any resource in a storage to the URI of that
     * storage's description resource.
     *
     * <p>Solid Protocol §4.1: "Servers MUST include the {@code Link} header field with
     * {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"} targeting the URI of the
     * storage description resource in the response of HTTP {@code GET}, {@code HEAD} and
     * {@code OPTIONS} requests targeting a resource in a storage."
     */
    public static final Property STORAGE_DESCRIPTION =
            ResourceFactory.createProperty(NS, "storageDescription");

    /**
     * {@code solid:oidcIssuer} — from a WebID to an identity provider it authorises to issue
     * tokens on its behalf.
     *
     * <p>Solid-OIDC §5: a resource server confirms "the WebID document contains a
     * {@code solid:oidcIssuer} triple naming the issuer of the access token" before trusting
     * the token's WebID. Without that check any issuer may mint a token for any WebID, so this
     * one triple carries the whole trust model: the token says who it is, and only the WebID
     * document says who is allowed to say so.
     */
    public static final Property OIDC_ISSUER = ResourceFactory.createProperty(NS, "oidcIssuer");

    private Solid() {
        // constants only
    }
}
