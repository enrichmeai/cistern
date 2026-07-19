package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.util.iterator.ExtendedIterator;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, validated N3 Patch (Solid Protocol, "Modifying Resources Using N3 Patches",
 * <a href="https://solidproject.org/TR/protocol#n3-patch">§n3-patch</a>).
 *
 * <p>A patch consists of the three formulae of the single {@code solid:InsertDeletePatch}
 * patch resource the spec allows: {@code solid:where}, {@code solid:deletes} and
 * {@code solid:inserts}. Each formula is a list of triples whose terms may include
 * variables ({@code ?name}, represented as Jena variable nodes); a formula that was absent
 * from the document is the empty list ("When not present, they are presumed to be the empty
 * formula {}" — §n3-patch).
 *
 * <p><strong>Error split (load-bearing):</strong> {@link #parse} signals only
 * {@link CisternException.BadInput} — a malformed document, a violation of the spec's patch
 * constraints, or an N3 construct outside the patch subset. {@link #applyTo} signals only
 * {@link CisternException.Conflict} — a well-formed patch that cannot be applied to this
 * graph (409 semantics per spec). Parsing never signals Conflict; applying never signals
 * BadInput. No Jena or parser-internal exception escapes either method.
 *
 * <p>Note for the HTTP layer (T2.7): the spec requires <em>422</em> for documents violating
 * the patch constraints (§n3-patch, "Servers MUST respond with a 422 status code
 * [RFC4918]...") and <em>409</em> for patches that cannot be applied. {@code BadInput}
 * currently maps to 400; the 400-vs-422 distinction is an open architect decision recorded
 * on the T1.5 PR.
 *
 * <p>Deliberately synchronous, like {@link RdfIo}: parsing and application are CPU-bound,
 * so callers lift invocations into their reactive chains ({@code Mono.fromCallable}).
 *
 * @param where   triple patterns of the {@code solid:where} formula (may contain variables)
 * @param deletes triple patterns of the {@code solid:deletes} formula (variables all occur in {@code where})
 * @param inserts triple patterns of the {@code solid:inserts} formula (variables all occur in {@code where})
 */
public record N3Patch(List<Triple> where, List<Triple> deletes, List<Triple> inserts) {

    /** The media type identifying an N3 Patch document (§n3-patch: "identified by the media type text/n3"). */
    public static final String MEDIA_TYPE = "text/n3";

    public N3Patch {
        where = List.copyOf(where);
        deletes = List.copyOf(deletes);
        inserts = List.copyOf(inserts);
    }

    /**
     * Parses and validates a {@code text/n3} patch document against the Solid Protocol's
     * N3 Patch constraints (§n3-patch). Relative IRIs are resolved against the URI of the
     * resource being patched.
     *
     * <p>The accepted grammar is exactly the patch subset the spec defines: prefix/base
     * declarations, one patch resource typed {@code solid:InsertDeletePatch} (optionally
     * also {@code solid:Patch}), at most one {@code solid:deletes} / {@code solid:inserts}
     * / {@code solid:where} triple each with a non-nested formula object, and formulae
     * consisting only of triples / triple patterns. Anything else — including N3 constructs
     * such as implications, paths, collections, quantifiers, or nested formulae — is
     * rejected.
     *
     * @param representation the patch document bytes; content type must be {@code text/n3}
     *                       (parameters such as {@code ;charset=utf-8} are tolerated)
     * @param resource       the resource the patch targets; its URI is the base for
     *                       relative IRI resolution
     * @return the parsed, validated patch
     * @throws CisternException.BadInput on null arguments, a wrong content type, malformed
     *                                   N3, a violation of the spec's patch constraints, or
     *                                   an unsupported N3 construct
     */
    public static N3Patch parse(Representation representation, ResourceIdentifier resource) {
        if (representation == null) {
            throw new CisternException.BadInput("Cannot parse N3 Patch: no representation given");
        }
        if (representation.data() == null) {
            throw new CisternException.BadInput("Cannot parse N3 Patch: representation has no data");
        }
        if (resource == null) {
            throw new CisternException.BadInput("Cannot parse N3 Patch: no resource identifier given as base");
        }
        requireN3ContentType(representation.contentType());
        String document = decodeUtf8(representation.data());
        try {
            return N3PatchParser.parse(document, resource.uri());
        } catch (CisternException e) {
            throw e;
        } catch (RuntimeException e) {
            // Belt and braces: no parser-internal exception type escapes.
            throw new CisternException.BadInput(
                    "Malformed text/n3 patch document for <" + resource.uri() + ">: " + safeMessage(e));
        }
    }

    /**
     * Applies this patch to a target graph, returning the new graph state. The target model
     * is not modified; prefix mappings are carried over to the result.
     *
     * <p>Semantics exactly per §n3-patch ("Servers MUST process a patch resource against
     * the target document as follows"):
     * <ol>
     *   <li>Start from the target graph (callers pass an empty model when the target
     *       resource does not exist yet).</li>
     *   <li>If {@code solid:where} is non-empty, find all (possibly empty) variable
     *       mappings such that all of the resulting triples occur in the graph. "If no such
     *       mapping exists, or if multiple mappings exist, the server MUST respond with a
     *       409 status code" — signalled as {@link CisternException.Conflict}.</li>
     *   <li>The resulting mapping is propagated to the {@code solid:deletes} and
     *       {@code solid:inserts} formulae to obtain two sets of resulting triples.</li>
     *   <li>"If the set of triples resulting from ?deletions is non-empty and the dataset
     *       does not contain all of these triples, the server MUST respond with a 409
     *       status code" — signalled as {@link CisternException.Conflict}.</li>
     *   <li>The deletion triples are removed, then the insertion triples are added.</li>
     * </ol>
     *
     * @param target the current graph of the patched resource; never modified
     * @return a new model holding the patched graph
     * @throws CisternException.Conflict if the patch cannot be applied to this graph
     * @throws NullPointerException      if {@code target} is null (a programming error,
     *                                   deliberately not {@code BadInput}: applying never
     *                                   signals BadInput)
     */
    public Model applyTo(Model target) {
        Objects.requireNonNull(target, "target model must not be null");
        Graph graph = target.getGraph();

        Map<Node, Node> mapping = Map.of();
        if (!where.isEmpty()) {
            List<Map<Node, Node>> solutions = new ArrayList<>(2);
            findMappings(graph, 0, new HashMap<>(), solutions);
            if (solutions.isEmpty()) {
                throw new CisternException.Conflict(
                        "Cannot apply N3 Patch: no variable mapping exists for which all "
                                + "solid:where triples occur in the target graph");
            }
            if (solutions.size() > 1) {
                throw new CisternException.Conflict(
                        "Cannot apply N3 Patch: multiple variable mappings satisfy the "
                                + "solid:where formula; the Solid Protocol requires exactly one");
            }
            mapping = solutions.get(0);
        }

        Set<Triple> deletions = substituteAll(deletes, mapping);
        for (Triple deletion : deletions) {
            if (!graph.contains(deletion)) {
                throw new CisternException.Conflict(
                        "Cannot apply N3 Patch: solid:deletes triple is not present in the "
                                + "target graph: " + deletion);
            }
        }
        Set<Triple> insertions = substituteAll(inserts, mapping);

        Model result = ModelFactory.createDefaultModel();
        result.add(target);
        result.setNsPrefixes(target.getNsPrefixMap());
        Graph resultGraph = result.getGraph();
        deletions.forEach(resultGraph::delete);
        insertions.forEach(resultGraph::add);
        return result;
    }

    /**
     * Depth-first join of the {@code where} patterns against the graph. Collects distinct
     * complete variable mappings, stopping as soon as two are found (enough to establish
     * the "multiple mappings" conflict).
     */
    private void findMappings(Graph graph, int index, Map<Node, Node> binding, List<Map<Node, Node>> solutions) {
        if (solutions.size() >= 2) {
            return;
        }
        if (index == where.size()) {
            Map<Node, Node> solution = Map.copyOf(binding);
            if (!solutions.contains(solution)) {
                solutions.add(solution);
            }
            return;
        }
        Triple pattern = where.get(index);
        Node s = substitute(pattern.getSubject(), binding);
        Node p = substitute(pattern.getPredicate(), binding);
        Node o = substitute(pattern.getObject(), binding);
        ExtendedIterator<Triple> matches = graph.find(asFindPattern(s), asFindPattern(p), asFindPattern(o));
        try {
            while (matches.hasNext() && solutions.size() < 2) {
                Triple match = matches.next();
                Map<Node, Node> extended = new HashMap<>(binding);
                if (bind(s, match.getSubject(), extended)
                        && bind(p, match.getPredicate(), extended)
                        && bind(o, match.getObject(), extended)) {
                    findMappings(graph, index + 1, extended, solutions);
                }
            }
        } finally {
            matches.close();
        }
    }

    private static Node asFindPattern(Node term) {
        return term.isVariable() ? Node.ANY : term;
    }

    /**
     * Binds {@code term} (if it is a still-unbound variable) to {@code value}, or checks
     * consistency if the variable was bound by an earlier position of the same pattern.
     */
    private static boolean bind(Node term, Node value, Map<Node, Node> binding) {
        if (!term.isVariable()) {
            return true;
        }
        Node existing = binding.get(term);
        if (existing == null) {
            binding.put(term, value);
            return true;
        }
        return existing.equals(value);
    }

    /** Propagates the variable mapping into a formula, yielding the set of resulting (ground) triples. */
    private static Set<Triple> substituteAll(List<Triple> patterns, Map<Node, Node> mapping) {
        Set<Triple> result = new LinkedHashSet<>();
        for (Triple pattern : patterns) {
            Node s = substitute(pattern.getSubject(), mapping);
            Node p = substitute(pattern.getPredicate(), mapping);
            Node o = substitute(pattern.getObject(), mapping);
            if (s.isVariable() || p.isVariable() || o.isVariable()) {
                // Unreachable: parsing guarantees deletes/inserts variables all occur in
                // where, and the mapping is total over the where variables.
                throw new IllegalStateException("Unbound variable after mapping propagation: " + pattern);
            }
            result.add(Triple.create(s, p, o));
        }
        return result;
    }

    private static Node substitute(Node term, Map<Node, Node> mapping) {
        return term.isVariable() ? mapping.getOrDefault(term, term) : term;
    }

    private static void requireN3ContentType(String contentType) {
        if (contentType == null) {
            throw new CisternException.BadInput(
                    "No content type given; an N3 Patch document must be " + MEDIA_TYPE);
        }
        int semicolon = contentType.indexOf(';');
        String bare = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType)
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPE.equals(bare)) {
            throw new CisternException.BadInput(
                    "Unsupported content type \"" + contentType + "\" for an N3 Patch document; must be "
                            + MEDIA_TYPE);
        }
    }

    private static String decodeUtf8(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new CisternException.BadInput("N3 Patch document is not valid UTF-8");
        }
    }

    private static String safeMessage(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
