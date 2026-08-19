package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reads the JSON Lines decision log back (T5.9): the {@link DecisionQuery} over the files
 * {@link JsonLinesDecisionSink} writes.
 *
 * <p>A scan, and deliberately so for v1: list the day files, keep the days that overlap the
 * interval, read each in date order, parse every line, keep the records the predicate accepts.
 * The day partition is the only index, and it is the one that matters — a receipts question is
 * almost always "this week" or "since Tuesday", so the interval prunes the scan to a handful of
 * files before a byte is read. Anything smarter (an index by target, by agent) is a later
 * ticket with a workload to justify it, and would build on the same files.
 *
 * <p>Order is chronological by construction: files ascend by day, and lines within a file are
 * in append order, which is the order decisions were taken. No sort is needed and none is done.
 *
 * <p>A line that does not parse is <strong>logged and skipped</strong>. Skipping silently would
 * hide a damaged log; failing the whole query would make one bad byte hide a whole day. The
 * warning names the file and the line number, so an operator can look.
 */
public final class JsonLinesDecisionQuery implements DecisionQuery {

    private static final Logger log = LoggerFactory.getLogger(JsonLinesDecisionQuery.class);

    /** Line numbers in the warning are one-based, as an editor counts them. */
    private static final int FIRST_LINE = 1;

    private final DecisionLog decisionLog;

    public JsonLinesDecisionQuery(DecisionLog decisionLog) {
        this.decisionLog = Objects.requireNonNull(decisionLog, "decisionLog");
    }

    @Override
    public Flux<DecisionRecord> forResource(ResourceIdentifier target, Instant from, Instant to) {
        Objects.requireNonNull(target, "target");
        return scan(from, to, record -> record.target().equals(target));
    }

    @Override
    public Flux<DecisionRecord> forAgent(URI webId, Instant from, Instant to) {
        Objects.requireNonNull(webId, "webId");
        return scan(from, to, record -> record.agent().webId().filter(webId::equals).isPresent());
    }

    /**
     * Every record in {@code [from, to)} that {@code keep} accepts, in the order it was taken.
     * The interval is applied twice: once to prune whole days before reading, once per record,
     * because a day file straddles the interval's ends.
     */
    private Flux<DecisionRecord> scan(Instant from, Instant to, Predicate<DecisionRecord> keep) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.isBefore(to)) {
            return Flux.empty();
        }
        return decisionLog.store().children(decisionLog.decisions())
                .flatMap(file -> Mono.justOrEmpty(decisionLog.dayOf(file).map(day -> new DayFile(day, file))))
                .filter(dayFile -> dayFile.overlaps(from, to))
                .sort(Comparator.comparing(DayFile::day))
                .concatMap(this::records)
                .filter(record -> !record.at().isBefore(from) && record.at().isBefore(to))
                .filter(keep);
    }

    /** One day file, with its day parsed once. */
    private record DayFile(LocalDate day, ResourceIdentifier file) {

        /** Whether any instant of this UTC day lies in {@code [from, to)}. */
        boolean overlaps(Instant from, Instant to) {
            Instant start = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return start.isBefore(to) && end.isAfter(from);
        }
    }

    /** Every parseable record in {@code dayFile}, in line order. */
    private Flux<DecisionRecord> records(DayFile dayFile) {
        return decisionLog.store().get(dayFile.file())
                .map(StoredResource::representation)
                .map(representation -> new String(representation.data(), StandardCharsets.UTF_8))
                .flatMapMany(content -> Flux.fromArray(content.split(DecisionLog.LINE_SEPARATOR)))
                .index()
                // concatMap, not flatMap: line order is record order, and it must survive.
                .concatMap(numbered -> {
                    String line = numbered.getT2();
                    if (line.isBlank()) {
                        // The trailing separator leaves an empty last element; nothing to say.
                        return Mono.empty();
                    }
                    Optional<DecisionRecord> record = DecisionRecordJson.parse(line);
                    if (record.isEmpty()) {
                        log.warn(WacMessage.DECISION_LINE_UNREADABLE.format(
                                dayFile.file().uri(), numbered.getT1() + FIRST_LINE));
                    }
                    return Mono.justOrEmpty(record);
                });
    }
}
