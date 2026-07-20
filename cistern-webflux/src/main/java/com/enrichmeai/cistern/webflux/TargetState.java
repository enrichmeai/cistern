package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The current validator state of a request's target, which is the only thing a precondition is
 * ever compared against (RFC 9110 §13.1: "a comparison between a set of validators obtained
 * from prior representations of the target resource to the current state of validators for the
 * selected representation").
 *
 * <p>Sealed on the one distinction the RFC's evaluation steps actually turn on — whether the
 * origin server "has a current representation for the target resource" (§13.1.1 step 1,
 * §13.1.2 step 1) — so {@code If-None-Match: *} and {@code If-Match: *} are answered by a
 * pattern match rather than by a nullable tag set that every caller would have to remember to
 * check.
 *
 * <h2>Why the tag set is a set, and how it is populated</h2>
 * A resource can have several current representations at once, each with its own strong
 * validator: an RDF source is serviceable as both {@code text/turtle} and
 * {@code application/ld+json} (Solid Protocol §5.5) and, by RFC 9110 §8.8.1, those two must not
 * share a validator because their bytes differ. Which of them counts as "the selected
 * representation" depends on the method:
 *
 * <ul>
 *   <li><b>{@code GET}/{@code HEAD}</b> — proactive negotiation genuinely selects one
 *       ({@link #ofSelected}), and only that one may satisfy an {@code If-None-Match}: a 304
 *       asserts that the copy the client holds is current, which is a claim about specific
 *       bytes. A client holding the Turtle copy but asking for JSON-LD must get the JSON-LD.</li>
 *   <li><b>{@code PUT}/{@code DELETE}</b> — nothing selects a representation, so the state is
 *       every current one ({@link #acrossAllRepresentations}). See
 *       {@link EntityTagCondition#satisfiedBy} for why §13.1.1's own wording supports that and
 *       why any narrower reading would break every {@code If-Match} formed from a {@code GET}.</li>
 * </ul>
 *
 * <p>Every tag is computed by {@link EntityTag}, the same call the read path uses to emit
 * {@code ETag}, so the validator compared against is by construction the validator served.
 */
sealed interface TargetState {

    /**
     * The target has at least one current representation.
     *
     * @param currentTags  the validator of each current representation; never empty
     * @param lastModified the modification date §13.1.3/§13.1.4 compare against
     */
    record Present(Set<EntityTag> currentTags, Instant lastModified) implements TargetState {
        public Present {
            currentTags = Set.copyOf(currentTags);
            if (currentTags.isEmpty()) {
                throw new IllegalArgumentException("A present resource has a validator");
            }
        }
    }

    /**
     * The target has no current representation. Reachable only on the write path: a
     * {@code PUT} to an absent target is a create (201), so RFC 9110 §13.2.1 still has
     * preconditions evaluated for it — which is exactly what makes {@code If-None-Match: *}
     * a create-only guard and {@code If-Match} on a resource that is not there a 412.
     */
    record Absent() implements TargetState {
    }

    /**
     * The state for a {@code GET} or {@code HEAD}: the one representation negotiation chose.
     *
     * @param selected the media type the response would carry — the negotiated serialization
     *                 for an RDF source, the stored type for a non-RDF source
     */
    static TargetState ofSelected(ResourceView view, MediaType selected) {
        return new Present(Set.of(EntityTag.forRepresentation(view, selected)),
                view.lastModified());
    }

    /**
     * The state for a method that selects no representation ({@code PUT}, {@code DELETE}): the
     * validator of every representation the resource currently has.
     *
     * <p>The candidate set comes from the same two places the read path draws it from — the
     * fixed {@link RdfSerialization} pair for an RDF source (Solid Protocol §5.5) and the single
     * stored type for a non-RDF source — so a tag a client could have obtained from a
     * {@code GET} is always in here, and a tag it could not have obtained never is.
     */
    static TargetState acrossAllRepresentations(ResourceView view) {
        Set<EntityTag> tags = switch (view) {
            case ResourceView.Rdf rdf -> Stream.of(RdfSerialization.values())
                    .map(serialization -> EntityTag.forRepresentation(rdf, serialization.mediaType()))
                    .collect(Collectors.toUnmodifiableSet());
            case ResourceView.NonRdf binary -> Set.of(EntityTag.forRepresentation(
                    binary, ContentNegotiator.storedMediaTypeOf(binary)));
        };
        return new Present(tags, view.lastModified());
    }
}
