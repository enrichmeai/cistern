package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Proactive content negotiation (RFC 9110 §12.1) for reads.
 *
 * <h2>Algorithm</h2>
 * For each representation the server could produce, the quality assigned to it is the
 * q-value of the <b>most specific</b> matching {@code Accept} entry — {@code text/turtle}
 * beats {@code text/*} beats *&#47;* — as RFC 9110 §12.5.1 specifies ("media ranges can be
 * overridden by more specific media ranges"). The producible representation with the highest
 * quality wins; ties go to server preference ({@link RdfSerialization#preferred()}). A
 * quality of 0 means "not acceptable" (§12.4.2), so a candidate scoring 0 is never selected.
 *
 * <p>An absent (or empty) {@code Accept} header means the client expresses no preference and
 * anything is acceptable (§12.5.1: "A request without any Accept header field implies that
 * the user agent will accept any media type in response"). For an RDF source that resolves
 * to Turtle, per LDP 1.0 §4.3.2.2.
 *
 * <p>Outcomes are typed — an {@link RdfSerialization} constant or the resource's stored
 * {@link MediaType} — never a media-type string. When nothing the server can produce is
 * acceptable this signals {@link CisternException.NotAcceptable}, the domain signal the
 * single error mapper turns into 406 (RFC 9110 §15.5.7). Nothing here writes a status code
 * or a body.
 */
@Component
public class ContentNegotiator {

    /** RFC 9110 §12.4.2: "q=0 means 'not acceptable'". */
    private static final double NOT_ACCEPTABLE = 0.0;

    /** Specificity ranks used to resolve which {@code Accept} range governs a candidate. */
    private static final int SPECIFICITY_WILDCARD_TYPE = 0;
    private static final int SPECIFICITY_WILDCARD_SUBTYPE = 1;
    private static final int SPECIFICITY_EXACT = 2;
    private static final int SPECIFICITY_NONE = -1;

    /**
     * Chooses the serialization for an RDF source. The candidate set is fixed by Solid
     * Protocol §5.5 and never depends on how the resource happens to be stored: a container
     * has no stored serialization at all, and a stored Turtle document must still be
     * serviceable as JSON-LD.
     *
     * @param accept the request's parsed {@code Accept} entries; empty if the header is absent
     * @return the serialization to produce
     * @throws CisternException.NotAcceptable if neither RDF serialization is acceptable
     */
    public RdfSerialization negotiateRdf(List<MediaType> accept) {
        if (accept.isEmpty()) {
            return RdfSerialization.preferred();
        }
        RdfSerialization best = null;
        double bestQuality = NOT_ACCEPTABLE;
        for (RdfSerialization candidate : RdfSerialization.values()) {
            double quality = qualityOf(candidate.mediaType(), accept);
            if (quality > bestQuality) {                 // strictly greater: ties keep the
                best = candidate;                        // earlier, more preferred candidate
                bestQuality = quality;
            }
        }
        if (best == null) {
            throw new CisternException.NotAcceptable(WebfluxMessage.RDF_SOURCE_NOT_ACCEPTABLE
                    .format(RdfSerialization.TURTLE.contentType(),
                            RdfSerialization.JSON_LD.contentType(), accept));
        }
        return best;
    }

    /**
     * Checks the single representation of a non-RDF source against {@code Accept}. A binary
     * resource has exactly one representation — Cistern never transcodes it — so negotiation
     * can only accept or refuse it.
     *
     * @param stored the resource's stored media type
     * @return {@code stored}, once established as acceptable
     * @throws CisternException.NotAcceptable if the stored media type is not acceptable
     */
    public MediaType requireAcceptable(List<MediaType> accept, MediaType stored) {
        if (!accept.isEmpty() && qualityOf(stored, accept) <= NOT_ACCEPTABLE) {
            throw new CisternException.NotAcceptable(
                    WebfluxMessage.NON_RDF_SOURCE_NOT_ACCEPTABLE.format(stored, accept));
        }
        return stored;
    }

    /**
     * The q-value the client assigned to {@code candidate}: the quality of the most specific
     * compatible {@code Accept} entry, or 0 if none is compatible.
     */
    private static double qualityOf(MediaType candidate, List<MediaType> accept) {
        int bestSpecificity = SPECIFICITY_NONE;
        double quality = NOT_ACCEPTABLE;
        for (MediaType range : accept) {
            if (!range.isCompatibleWith(candidate)) {
                continue;
            }
            int specificity = specificityOf(range);
            if (specificity > bestSpecificity) {
                bestSpecificity = specificity;
                quality = range.getQualityValue();
            }
        }
        return quality;
    }

    /** {@code type/subtype} is more specific than {@code type/*}, which beats *&#47;*. */
    private static int specificityOf(MediaType range) {
        if (range.isWildcardType()) {
            return SPECIFICITY_WILDCARD_TYPE;
        }
        return range.isWildcardSubtype() ? SPECIFICITY_WILDCARD_SUBTYPE : SPECIFICITY_EXACT;
    }
}
