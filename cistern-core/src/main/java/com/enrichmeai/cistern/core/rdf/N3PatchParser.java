package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.sparql.core.Var;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Strict recursive-descent parser for exactly the N3 Patch subset the Solid Protocol
 * defines (<a href="https://solidproject.org/TR/protocol#n3-patch">§n3-patch</a>).
 *
 * <p>Jena has no real Notation3 support ({@code Lang.N3} is an alias for Turtle and cannot
 * parse formulae or variables), so the patch grammar is parsed here. Accepted:
 * <ul>
 *   <li>{@code @prefix} / {@code @base} directives, and their SPARQL-style
 *       {@code PREFIX} / {@code BASE} forms; comments ({@code #});</li>
 *   <li>statements about a single patch resource (IRI, {@code _:label} or {@code []}
 *       subject) using {@code rdf:type} ({@code a}) with object {@code solid:Patch} or
 *       {@code solid:InsertDeletePatch}, and {@code solid:deletes} / {@code solid:inserts}
 *       / {@code solid:where} with a formula object; {@code ;} / {@code ,} sugar;</li>
 *   <li>non-nested formulae {@code { ... }} whose statements are triples / triple
 *       patterns: IRIs, prefixed names, literals (strings with language tags or datatypes,
 *       numbers, booleans) and {@code ?variables}.</li>
 * </ul>
 *
 * <p>Everything else — N3 implications ({@code =>}, {@code <=}, {@code =}), quantifiers
 * ({@code @forAll}, {@code @forSome}), collections, paths, blank node property lists with
 * content, nested formulae, variables outside formulae — is a lexical/grammar error and is
 * rejected with {@link CisternException.BadInput} (400).
 *
 * <p>Violations of the spec's enumerated patch constraints are a separate, coarser failure:
 * the document parses as N3 but is not a usable patch, so it is rejected with
 * {@link CisternException.UnprocessableEntity} (422), as §n3-patch mandates. The dividing
 * line is mechanical: anything the lexer/grammar rejects is {@code BadInput}; anything
 * {@link #validate} rejects is {@code UnprocessableEntity}.
 *
 * <p><strong>Deliberate limitation — blank nodes in {@code solid:where}.</strong> The spec
 * forbids blank nodes only in {@code ?insertions}/{@code ?deletions}, so a {@code ?conditions}
 * formula containing a ground blank-node triple is spec-well-formed. Cistern nonetheless
 * refuses it, classified as {@link CisternException.UnprocessableEntity} (422 — "well-formed
 * but I cannot process it", the honest answer; 400 would wrongly claim the document is
 * malformed). The reason is that the spec's processing rule is defined over <em>variable</em>
 * mappings only and says nothing about matching blank nodes, so any behaviour here would be
 * invention. To revisit with harness evidence during T2.7/T3.3 — see issue #57.
 */
final class N3PatchParser {

    static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    static final String SOLID_NS = "http://www.w3.org/ns/solid/terms#";
    static final String SOLID_PATCH = SOLID_NS + "Patch";
    static final String SOLID_INSERT_DELETE_PATCH = SOLID_NS + "InsertDeletePatch";
    static final String SOLID_INSERTS = SOLID_NS + "inserts";
    static final String SOLID_DELETES = SOLID_NS + "deletes";
    static final String SOLID_WHERE = SOLID_NS + "where";

    /** A formula term: the object of solid:deletes / solid:inserts / solid:where. */
    private record Formula(List<Triple> triples) {
    }

    /** An outer-document triple; the object is either a {@link Node} or a {@link Formula}. */
    private record OuterTriple(Node subject, Node predicate, Object object) {
    }

    private final String src;
    private int pos;
    private URI base;
    private final Map<String, String> prefixes = new HashMap<>();

    private N3PatchParser(String src, URI base) {
        this.src = src;
        this.base = base;
    }

    /**
     * Parses and validates an N3 Patch document.
     *
     * @param document the full text of the {@code text/n3} document
     * @param base     absolute base URI for relative IRI resolution (the patched resource)
     * @return the validated patch
     * @throws CisternException.BadInput on any syntax error, constraint violation or
     *                                   unsupported construct
     */
    static N3Patch parse(String document, URI base) {
        return new N3PatchParser(document, base).parseDocument();
    }

    // ------------------------------------------------------------------ document

    private N3Patch parseDocument() {
        List<OuterTriple> triples = new ArrayList<>();
        while (true) {
            skipWs();
            if (eof()) {
                break;
            }
            if (peek() == '@') {
                parseAtDirective();
            } else if (atSparqlDirective()) {
                parseSparqlDirective();
            } else {
                parseOuterStatement(triples);
            }
        }
        return validate(triples);
    }

    private void parseAtDirective() {
        expect('@');
        String word = readBareWord();
        switch (word) {
            case "prefix" -> {
                parsePrefixDeclaration();
                skipWs();
                expect('.');
            }
            case "base" -> {
                parseBaseDeclaration();
                skipWs();
                expect('.');
            }
            case "forAll", "forSome", "keywords" ->
                    throw error("unsupported N3 construct: @" + word + " is outside the N3 Patch subset");
            default -> throw error("unknown directive @" + word);
        }
    }

    private boolean atSparqlDirective() {
        int end = pos;
        while (end < src.length() && isAsciiLetter(src.charAt(end))) {
            end++;
        }
        if (end == pos || (end < src.length() && src.charAt(end) == ':')) {
            return false; // not a bare word, or a prefixed name such as prefix:local
        }
        String word = src.substring(pos, end).toLowerCase(Locale.ROOT);
        return word.equals("prefix") || word.equals("base");
    }

    private void parseSparqlDirective() {
        String word = readBareWord().toLowerCase(Locale.ROOT);
        if (word.equals("prefix")) {
            parsePrefixDeclaration(); // SPARQL form: no terminating '.'
        } else {
            parseBaseDeclaration();
        }
    }

    private void parsePrefixDeclaration() {
        skipWs();
        String label = readPrefixLabel();
        expect(':');
        skipWs();
        if (peek() != '<') {
            throw error("expected an IRI after the prefix label in a prefix declaration");
        }
        Node iri = readIri();
        prefixes.put(label, iri.getURI());
    }

    private void parseBaseDeclaration() {
        skipWs();
        if (peek() != '<') {
            throw error("expected an IRI in a base declaration");
        }
        Node iri = readIri();
        this.base = URI.create(iri.getURI());
    }

    // ------------------------------------------------------------------ statements

    private void parseOuterStatement(List<OuterTriple> sink) {
        Node subject = parseOuterSubject();
        parseOuterPredicateObjectList(subject, sink);
        skipWs();
        expect('.');
    }

    private void parseOuterPredicateObjectList(Node subject, List<OuterTriple> sink) {
        while (true) {
            Node verb = parseVerb(false);
            while (true) {
                Object object = parseObject(false);
                sink.add(new OuterTriple(subject, verb, object));
                skipWs();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                break;
            }
            skipWs();
            if (peek() == ';') {
                while (peek() == ';') {
                    pos++;
                    skipWs();
                }
                if (peek() == '.' || peek() == '}') {
                    break; // trailing ';' before the statement terminator
                }
                continue;
            }
            break;
        }
    }

    private Node parseOuterSubject() {
        skipWs();
        int c = peek();
        return switch (c) {
            case '<' -> {
                rejectReverseImplication();
                yield readIri();
            }
            case '[' -> readAnon();
            case '_' -> readBlankNodeLabel();
            case '?' -> throw error("variables are only allowed inside a formula");
            case '{' -> throw error("a formula may only appear as the object of solid:deletes, "
                    + "solid:inserts or solid:where");
            case '(' -> throw error("unsupported N3 construct: collections are outside the N3 Patch subset");
            case '"', '\'' -> throw error("a literal cannot be the subject of a statement");
            default -> {
                if (isDigit(c) || c == '+' || c == '-' || c == '.') {
                    throw error("unexpected character at the start of a statement");
                }
                yield parsePrefixedNameOnly("as a statement subject");
            }
        };
    }

    private Node parseVerb(boolean inFormula) {
        skipWs();
        int c = peek();
        switch (c) {
            case '<' -> {
                rejectReverseImplication();
                return readIri();
            }
            case '=' -> throw error("unsupported N3 construct: '=' / '=>' are outside the N3 Patch subset");
            case '?' -> {
                if (inFormula) {
                    return readVariable();
                }
                throw error("variables are only allowed inside a formula");
            }
            case '{' -> throw error(inFormula
                    ? "nested formulae are not allowed in an N3 Patch"
                    : "a formula cannot be used as a predicate");
            case '_', '[' -> throw error("a blank node cannot be used as a predicate");
            case '"', '\'' -> throw error("a literal cannot be used as a predicate");
            default -> {
                if (prefixedNameAhead()) {
                    return readPrefixedName();
                }
                if (readNameToken().equals("a")) {
                    return NodeFactory.createURI(RDF_TYPE);
                }
                throw error("expected a predicate (IRI, prefixed name or 'a')");
            }
        }
    }

    private Object parseObject(boolean inFormula) {
        skipWs();
        int c = peek();
        switch (c) {
            case '{' -> {
                if (inFormula) {
                    throw error("nested formulae are not allowed in an N3 Patch");
                }
                return parseFormula();
            }
            case '<' -> {
                rejectReverseImplication();
                return readIri();
            }
            case '[' -> {
                return readAnon();
            }
            case '_' -> {
                return readBlankNodeLabel();
            }
            case '?' -> {
                if (inFormula) {
                    return readVariable();
                }
                throw error("variables are only allowed inside a formula");
            }
            case '(' -> throw error("unsupported N3 construct: collections are outside the N3 Patch subset");
            case '"', '\'' -> {
                return readStringLiteral();
            }
            default -> {
                if (isDigit(c) || c == '+' || c == '-' || (c == '.' && isDigit(peekAt(1)))) {
                    return readNumericLiteral();
                }
                if (prefixedNameAhead()) {
                    return readPrefixedName();
                }
                String word = readNameToken();
                if (word.equals("true") || word.equals("false")) {
                    return NodeFactory.createLiteralDT(word, XSDDatatype.XSDboolean);
                }
                throw error("expected an object (IRI, prefixed name, literal"
                        + (inFormula ? ", variable" : ", formula") + ")");
            }
        }
    }

    // ------------------------------------------------------------------ formulae

    private Formula parseFormula() {
        expect('{');
        List<Triple> triples = new ArrayList<>();
        while (true) {
            skipWs();
            if (eof()) {
                throw error("unterminated formula: missing '}'");
            }
            if (peek() == '}') {
                pos++;
                break;
            }
            if (peek() == '@') {
                throw error("directives are not allowed inside a formula");
            }
            Node subject = parseFormulaSubject();
            parseFormulaPredicateObjectList(subject, triples);
            skipWs();
            if (peek() == '.') {
                pos++;
                continue;
            }
            if (peek() == '}') {
                pos++;
                break; // final '.' inside a formula is optional
            }
            throw error("expected '.' or '}' after a statement in a formula");
        }
        return new Formula(List.copyOf(triples));
    }

    private Node parseFormulaSubject() {
        skipWs();
        int c = peek();
        return switch (c) {
            case '<' -> {
                rejectReverseImplication();
                yield readIri();
            }
            case '?' -> readVariable();
            case '[' -> readAnon();
            case '_' -> readBlankNodeLabel();
            case '{' -> throw error("nested formulae are not allowed in an N3 Patch");
            case '(' -> throw error("unsupported N3 construct: collections are outside the N3 Patch subset");
            case '"', '\'' -> throw error("a literal cannot be the subject of a triple pattern");
            default -> {
                if (isDigit(c) || c == '+' || c == '-' || c == '.') {
                    throw error("a literal cannot be the subject of a triple pattern");
                }
                yield parsePrefixedNameOnly("as a triple pattern subject");
            }
        };
    }

    private void parseFormulaPredicateObjectList(Node subject, List<Triple> sink) {
        while (true) {
            Node verb = parseVerb(true);
            while (true) {
                Object object = parseObject(true);
                sink.add(Triple.create(subject, verb, (Node) object));
                skipWs();
                if (peek() == ',') {
                    pos++;
                    continue;
                }
                break;
            }
            skipWs();
            if (peek() == ';') {
                while (peek() == ';') {
                    pos++;
                    skipWs();
                }
                if (peek() == '.' || peek() == '}') {
                    break;
                }
                continue;
            }
            break;
        }
    }

    // ------------------------------------------------------------------ terms

    /** Reads a prefixed name when the upcoming token can be nothing else; {@code what} labels the error. */
    private Node parsePrefixedNameOnly(String what) {
        if (prefixedNameAhead()) {
            return readPrefixedName();
        }
        throw error("expected an IRI, prefixed name or blank node " + what);
    }

    /**
     * True if a prefixed name (including the default-prefix {@code :local} form) begins at
     * the cursor — i.e. a {@code ':'} follows a valid, possibly empty, prefix label. Unlike a
     * naive scan this stops a prefix label before a statement-terminating {@code '.'}, so
     * barewords such as {@code a}, {@code true} and {@code false} are not misread.
     */
    private boolean prefixedNameAhead() {
        int i = pos;
        if (i < src.length() && src.charAt(i) == ':') {
            return true; // default prefix
        }
        if (i >= src.length() || !isAsciiLetter(src.charAt(i))) {
            return false;
        }
        i++;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (isAsciiLetter(c) || isDigit(c) || c == '_' || c == '-') {
                i++;
            } else if (c == '.' && i + 1 < src.length() && isPrefixChar(src.charAt(i + 1))) {
                i++;
            } else {
                break;
            }
        }
        return i < src.length() && src.charAt(i) == ':';
    }

    /** Reads a bareword token ({@code [A-Za-z0-9_-]+}, no dots), for the keywords {@code a}, {@code true}, {@code false}. */
    private String readNameToken() {
        int start = pos;
        while (!eof()) {
            char c = src.charAt(pos);
            if (isAsciiLetter(c) || isDigit(c) || c == '_' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        return src.substring(start, pos);
    }

    private void rejectReverseImplication() {
        if (peekAt(1) == '=') {
            throw error("unsupported N3 construct: '<=' is outside the N3 Patch subset");
        }
    }

    private Node readIri() {
        expect('<');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) {
                throw error("unterminated IRI: missing '>'");
            }
            char c = src.charAt(pos);
            if (c == '>') {
                pos++;
                break;
            }
            if (c == '\\') {
                pos++;
                int e = peek();
                if (e == 'u') {
                    pos++;
                    sb.appendCodePoint(readHex(4));
                } else if (e == 'U') {
                    pos++;
                    sb.appendCodePoint(readHex(8));
                } else {
                    throw error("illegal escape sequence in IRI (only \\uXXXX and \\UXXXXXXXX are allowed)");
                }
                continue;
            }
            if (c <= ' ' || c == '<' || c == '"' || c == '{' || c == '}' || c == '|' || c == '^' || c == '`') {
                throw error("illegal character in IRI: '" + printable(c) + "'");
            }
            sb.append(c);
            pos++;
        }
        return NodeFactory.createURI(resolveIri(sb.toString()));
    }

    private String resolveIri(String iri) {
        try {
            URI reference = new URI(iri);
            URI resolved = reference.isAbsolute() ? reference : base.resolve(reference);
            if (!resolved.isAbsolute()) {
                throw error("cannot resolve relative IRI <" + iri + "> against base <" + base + ">");
            }
            return resolved.toString();
        } catch (URISyntaxException e) {
            throw error("invalid IRI <" + iri + ">");
        }
    }

    private Node readPrefixedName() {
        String prefix = readPrefixLabel();
        expect(':');
        String local = readLocalName();
        String namespace = prefixes.get(prefix);
        if (namespace == null) {
            throw error("undeclared prefix \"" + prefix + ":\"");
        }
        return NodeFactory.createURI(namespace + local);
    }

    private String readPrefixLabel() {
        int start = pos;
        if (!eof() && isAsciiLetter(src.charAt(pos))) {
            pos++;
            while (!eof()) {
                char c = src.charAt(pos);
                if (isAsciiLetter(c) || isDigit(c) || c == '_' || c == '-') {
                    pos++;
                } else if (c == '.' && pos + 1 < src.length() && isPrefixChar(src.charAt(pos + 1))) {
                    pos++;
                } else {
                    break;
                }
            }
        }
        return src.substring(start, pos); // possibly empty: the default prefix ":"
    }

    private String readLocalName() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (!eof()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == ':' || (!first && c == '-')) {
                sb.append(c);
                pos++;
            } else if (c == '.' && !first
                    && pos + 1 < src.length() && isLocalNameChar(src.charAt(pos + 1))) {
                sb.append(c);
                pos++;
            } else if (c == '%') {
                if (pos + 2 >= src.length() || !isHex(src.charAt(pos + 1)) || !isHex(src.charAt(pos + 2))) {
                    throw error("'%' in a local name must start a %XX percent-encoded triplet");
                }
                sb.append(src, pos, pos + 3);
                pos += 3;
            } else if (c == '\\') {
                pos++;
                int e = peek();
                if (e == -1 || "_~.-!$&'()*+,;=/?#@%".indexOf(e) < 0) {
                    throw error("illegal escape sequence in local name");
                }
                sb.append((char) e);
                pos++;
            } else {
                break;
            }
            first = false;
        }
        return sb.toString(); // possibly empty, e.g. "ex:"
    }

    private boolean isLocalNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '.'
                || c == '%' || c == '\\';
    }

    private Node readBlankNodeLabel() {
        expect('_');
        expect(':');
        int start = pos;
        while (!eof()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || (pos > start && c == '-')) {
                pos++;
            } else if (c == '.' && pos > start
                    && pos + 1 < src.length()
                    && (Character.isLetterOrDigit(src.charAt(pos + 1)) || src.charAt(pos + 1) == '_')) {
                pos++;
            } else {
                break;
            }
        }
        if (pos == start) {
            throw error("expected a blank node label after '_:'");
        }
        return NodeFactory.createBlankNode(src.substring(start, pos));
    }

    private Node readAnon() {
        expect('[');
        skipWs();
        if (peek() != ']') {
            throw error("blank node property lists are not supported in an N3 Patch document");
        }
        pos++;
        return NodeFactory.createBlankNode();
    }

    private Node readVariable() {
        expect('?');
        int start = pos;
        while (!eof()) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                pos++;
            } else {
                break;
            }
        }
        if (pos == start) {
            throw error("expected a variable name after '?'");
        }
        String name = src.substring(start, pos);
        char firstChar = name.charAt(0);
        if (isDigit(firstChar)) {
            throw error("a variable name must not start with a digit: ?" + name);
        }
        return Var.alloc(name);
    }

    private Node readStringLiteral() {
        char quote = src.charAt(pos);
        String lexicalForm;
        if (pos + 2 < src.length() && src.charAt(pos + 1) == quote && src.charAt(pos + 2) == quote) {
            lexicalForm = readLongString(quote);
        } else {
            lexicalForm = readShortString(quote);
        }
        int c = peek();
        if (c == '@') {
            pos++;
            String lang = readLanguageTag();
            return NodeFactory.createLiteralLang(lexicalForm, lang);
        }
        if (c == '^') {
            pos++;
            expect('^');
            skipWs();
            Node datatypeIri;
            if (peek() == '<') {
                datatypeIri = readIri();
            } else {
                datatypeIri = parsePrefixedNameOnly("as a datatype IRI");
            }
            RDFDatatype datatype = TypeMapper.getInstance().getSafeTypeByName(datatypeIri.getURI());
            return NodeFactory.createLiteralDT(lexicalForm, datatype);
        }
        return NodeFactory.createLiteralDT(lexicalForm, XSDDatatype.XSDstring);
    }

    private String readShortString(char quote) {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) {
                throw error("unterminated string literal");
            }
            char c = src.charAt(pos);
            if (c == quote) {
                pos++;
                return sb.toString();
            }
            if (c == '\n' || c == '\r') {
                throw error("unescaped line break in string literal");
            }
            if (c == '\\') {
                pos++;
                appendEscape(sb);
                continue;
            }
            sb.append(c);
            pos++;
        }
    }

    private String readLongString(char quote) {
        pos += 3; // opening quote run
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (eof()) {
                throw error("unterminated long string literal");
            }
            char c = src.charAt(pos);
            if (c == quote
                    && pos + 2 < src.length()
                    && src.charAt(pos + 1) == quote
                    && src.charAt(pos + 2) == quote) {
                pos += 3;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                appendEscape(sb);
                continue;
            }
            sb.append(c);
            pos++;
        }
    }

    private void appendEscape(StringBuilder sb) {
        int c = peek();
        switch (c) {
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 'f' -> sb.append('\f');
            case '"' -> sb.append('"');
            case '\'' -> sb.append('\'');
            case '\\' -> sb.append('\\');
            case 'u' -> {
                pos++;
                sb.appendCodePoint(readHex(4));
                return;
            }
            case 'U' -> {
                pos++;
                sb.appendCodePoint(readHex(8));
                return;
            }
            default -> throw error("illegal escape sequence in string literal");
        }
        pos++;
    }

    private String readLanguageTag() {
        int start = pos;
        while (!eof() && isAsciiLetter(src.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw error("expected a language tag after '@'");
        }
        while (peek() == '-') {
            pos++;
            int segment = pos;
            while (!eof() && (isAsciiLetter(src.charAt(pos)) || isDigit(src.charAt(pos)))) {
                pos++;
            }
            if (pos == segment) {
                throw error("malformed language tag");
            }
        }
        return src.substring(start, pos);
    }

    private Node readNumericLiteral() {
        int start = pos;
        if (peek() == '+' || peek() == '-') {
            pos++;
        }
        int integerDigits = readDigits();
        boolean hasDot = false;
        if (peek() == '.' && isDigit(peekAt(1))) {
            hasDot = true;
            pos++;
            readDigits();
        }
        if (integerDigits == 0 && !hasDot) {
            throw error("malformed numeric literal");
        }
        boolean hasExponent = false;
        if (peek() == 'e' || peek() == 'E') {
            hasExponent = true;
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            if (readDigits() == 0) {
                throw error("malformed exponent in numeric literal");
            }
        }
        String lexicalForm = src.substring(start, pos);
        XSDDatatype datatype = hasExponent ? XSDDatatype.XSDdouble
                : hasDot ? XSDDatatype.XSDdecimal
                : XSDDatatype.XSDinteger;
        return NodeFactory.createLiteralDT(lexicalForm, datatype);
    }

    private int readDigits() {
        int count = 0;
        while (!eof() && isDigit(src.charAt(pos))) {
            pos++;
            count++;
        }
        return count;
    }

    private int readHex(int digits) {
        if (pos + digits > src.length()) {
            throw error("truncated \\u escape sequence");
        }
        int value = 0;
        for (int i = 0; i < digits; i++) {
            char c = src.charAt(pos + i);
            if (!isHex(c)) {
                throw error("malformed \\u escape sequence");
            }
            value = value * 16 + Character.digit(c, 16);
        }
        pos += digits;
        if (!Character.isValidCodePoint(value)) {
            throw error("\\u escape sequence is not a valid code point");
        }
        return value;
    }

    // ------------------------------------------------------------------ validation

    /**
     * Enforces the document-level constraints of §n3-patch: exactly one patch resource,
     * identified only by the allowed triple forms; required {@code solid:InsertDeletePatch}
     * type; at most one {@code solid:deletes} / {@code solid:inserts} / {@code solid:where}
     * triple, each with a formula object; no blank nodes in any formula; every variable in
     * {@code ?insertions}/{@code ?deletions} occurs in {@code ?conditions}.
     */
    private N3Patch validate(List<OuterTriple> triples) {
        if (triples.isEmpty()) {
            // "A patch document MUST contain one or more patch resources" (§n3-patch): an
            // empty (or directives-only) document is well-formed N3, so this is 422, not 400.
            throw constraintError("it contains no patch resource "
                    + "(a triple '?patch a solid:InsertDeletePatch' is required)");
        }
        Set<Node> subjects = new LinkedHashSet<>();
        boolean hasRequiredType = false;
        Formula where = null;
        Formula deletes = null;
        Formula inserts = null;
        for (OuterTriple triple : triples) {
            subjects.add(triple.subject());
            String predicate = triple.predicate().getURI();
            switch (predicate) {
                case RDF_TYPE -> {
                    Object object = triple.object();
                    if (!(object instanceof Node node) || !node.isURI()
                            || !(SOLID_PATCH.equals(node.getURI())
                                    || SOLID_INSERT_DELETE_PATCH.equals(node.getURI()))) {
                        throw constraintError("the type of a patch resource must be solid:Patch or "
                                + "solid:InsertDeletePatch");
                    }
                    if (SOLID_INSERT_DELETE_PATCH.equals(node.getURI())) {
                        hasRequiredType = true;
                    }
                }
                case SOLID_WHERE -> where = formulaObject(triple, "solid:where", where);
                case SOLID_DELETES -> deletes = formulaObject(triple, "solid:deletes", deletes);
                case SOLID_INSERTS -> inserts = formulaObject(triple, "solid:inserts", inserts);
                default -> throw constraintError("unexpected predicate <" + predicate + ">: a patch "
                        + "resource may only use rdf:type, solid:deletes, solid:inserts and solid:where");
            }
        }
        if (subjects.size() > 1) {
            throw constraintError("the patch document must contain exactly one patch resource, "
                    + "but " + subjects.size() + " subjects were found");
        }
        if (!hasRequiredType) {
            throw constraintError("the patch resource must contain a triple "
                    + "'?patch a solid:InsertDeletePatch'");
        }
        List<Triple> whereTriples = where == null ? List.of() : where.triples();
        List<Triple> deleteTriples = deletes == null ? List.of() : deletes.triples();
        List<Triple> insertTriples = inserts == null ? List.of() : inserts.triples();

        rejectBlankNodes(whereTriples, "solid:where");
        rejectBlankNodes(deleteTriples, "solid:deletes");
        rejectBlankNodes(insertTriples, "solid:inserts");

        Set<Node> whereVariables = variablesOf(whereTriples);
        requireVariablesDeclared(deleteTriples, whereVariables, "solid:deletes");
        requireVariablesDeclared(insertTriples, whereVariables, "solid:inserts");

        return new N3Patch(whereTriples, deleteTriples, insertTriples);
    }

    private Formula formulaObject(OuterTriple triple, String predicate, Formula existing) {
        if (existing != null) {
            throw constraintError("a patch resource must contain at most one " + predicate + " triple");
        }
        if (!(triple.object() instanceof Formula formula)) {
            throw constraintError("the object of " + predicate + " must be a formula { ... }");
        }
        return formula;
    }

    private void rejectBlankNodes(List<Triple> triples, String predicate) {
        for (Triple triple : triples) {
            if (triple.getSubject().isBlank() || triple.getObject().isBlank()) {
                if (predicate.equals("solid:where")) {
                    // Spec-well-formed but deliberately unprocessable: the mapping algorithm
                    // is defined over variables only. See the class Javadoc and issue #57.
                    throw constraintError("blank nodes in the solid:where formula are not "
                            + "supported: the specification defines the mapping algorithm over "
                            + "variables only and leaves blank-node matching undefined");
                }
                throw constraintError("the " + predicate + " formula must not contain blank nodes");
            }
        }
    }

    private void requireVariablesDeclared(List<Triple> triples, Set<Node> whereVariables, String predicate) {
        for (Node variable : variablesOf(triples)) {
            if (!whereVariables.contains(variable)) {
                throw constraintError("variable ?" + variable.getName() + " in the " + predicate
                        + " formula does not occur in the solid:where formula");
            }
        }
    }

    private static Set<Node> variablesOf(List<Triple> triples) {
        Set<Node> variables = new LinkedHashSet<>();
        for (Triple triple : triples) {
            for (Node node : new Node[] {triple.getSubject(), triple.getPredicate(), triple.getObject()}) {
                if (node.isVariable()) {
                    variables.add(node);
                }
            }
        }
        return variables;
    }

    /**
     * A document that is well-formed N3 but breaches one of the spec's enumerated patch
     * constraints — the server can parse it and cannot process it, which is exactly 422
     * (RFC 4918), as §n3-patch mandates: "Servers MUST respond with a 422 status code if a
     * patch document does not satisfy all of the above constraints."
     */
    private CisternException.UnprocessableEntity constraintError(String message) {
        return new CisternException.UnprocessableEntity("Invalid N3 Patch document: " + message
                + " (Solid Protocol, Modifying Resources Using N3 Patches)");
    }

    // ------------------------------------------------------------------ lexing helpers

    private void skipWs() {
        while (!eof()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos++;
            } else if (c == '#') {
                while (!eof() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    private String readBareWord() {
        int start = pos;
        while (!eof() && isAsciiLetter(src.charAt(pos))) {
            pos++;
        }
        if (pos == start) {
            throw error("expected a word");
        }
        return src.substring(start, pos);
    }

    private void expect(char expected) {
        if (eof() || src.charAt(pos) != expected) {
            throw error("expected '" + expected + "'"
                    + (eof() ? " but reached the end of the document" : ""));
        }
        pos++;
    }

    private boolean eof() {
        return pos >= src.length();
    }

    private int peek() {
        return eof() ? -1 : src.charAt(pos);
    }

    private int peekAt(int offset) {
        int i = pos + offset;
        return i >= src.length() ? -1 : src.charAt(i);
    }

    private static boolean isAsciiLetter(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHex(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean isPrefixChar(char c) {
        return isAsciiLetter(c) || isDigit(c) || c == '_' || c == '-' || c == '.';
    }

    private static String printable(char c) {
        return c < ' ' ? String.format("\\u%04X", (int) c) : String.valueOf(c);
    }

    private CisternException.BadInput error(String message) {
        int line = 1;
        int column = 1;
        int limit = Math.min(pos, src.length());
        for (int i = 0; i < limit; i++) {
            if (src.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new CisternException.BadInput(
                "Invalid N3 Patch document at line " + line + ", column " + column + ": " + message);
    }
}
