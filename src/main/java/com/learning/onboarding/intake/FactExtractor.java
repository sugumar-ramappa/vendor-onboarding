package com.learning.onboarding.intake;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls checkable facts out of document text. No model involved.
 *
 * <p>Everything here is deliberately boring: regular expressions and date
 * parsing. That is the point. These are the checks that must be exact, and an
 * exact answer from twenty lines of Java beats a probable answer from a model
 * that costs a network call and cannot be unit tested.
 *
 * <p><b>Deliberately conservative.</b> When a value is ambiguous this returns
 * nothing rather than guessing. An unparsed expiry becomes "a human or a model
 * needs to look at this", which is safe. A wrongly parsed expiry becomes a
 * confident, incorrect, deterministic finding - which is worse than no finding
 * at all, because it looks trustworthy.
 */
@Component
public class FactExtractor {

    // ---------------------------------------------------------------- dates --

    /**
     * Words that mark the date after them as an expiry.
     *
     * <p>Matching the label rather than just any date matters: a certificate
     * carries an issue date, an expiry, an audit date and often a print date.
     * Taking "the first date in the document" would pick the wrong one most of
     * the time.
     */
    private static final Pattern EXPIRY_LABEL = Pattern.compile(
            "(?i)\\b(valid\\s+until|valid\\s+to|expiry(?:\\s+date)?|expires?(?:\\s+on)?|"
                    + "renewal\\s+date|end\\s+date)\\b[\\s:.-]*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ISSUE_LABEL = Pattern.compile(
            "(?i)\\b(issued?(?:\\s+on)?(?:\\s+date)?|date\\s+of\\s+issue|valid\\s+from|"
                    + "start\\s+date)\\b[\\s:.-]*",
            Pattern.CASE_INSENSITIVE);

    /** The date formats that actually turn up on certificates. */
    private static final Pattern DATE = Pattern.compile(
            "(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{4})"       // 12 April 2026
                    + "|([A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{4})"   // April 12, 2026
                    + "|(\\d{4}-\\d{2}-\\d{2})"                    // 2026-04-12
                    + "|(\\d{1,2}[/.]\\d{1,2}[/.]\\d{4})");        // 12/04/2026

    private static final List<DateTimeFormatter> FORMATS = List.of(
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            // Day-first. See parseDate() for why this is not configurable.
            DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d.M.yyyy", Locale.ENGLISH));

    // ------------------------------------------------------------ standards --

    /**
     * Standard identifiers: a body followed by a number, e.g. EN 62841,
     * IEC 62133, BS 7671, ISO 9001, EN 13501-1.
     */
    private static final Pattern STANDARD = Pattern.compile(
            "\\b(EN|IEC|ISO|BS|ASTM|UL|DIN|UN)\\s?-?\\s?(\\d{3,5}(?:-\\d{1,2})?)\\b",
            // Case-insensitive because vendors write "en62841", "EN 62841" and
            // "En-62841" for the same standard. group(1) is upper-cased when
            // building the value, so the output is normalised either way.
            Pattern.CASE_INSENSITIVE);

    // --------------------------------------------------------------- money ---

    private static final Pattern MONEY = Pattern.compile(
            "(?:GBP|USD|EUR|£|\\$|€)\\s?([\\d,]+(?:\\.\\d{2})?)(\\s?(?:m|million|k))?",
            Pattern.CASE_INSENSITIVE);

    // ----------------------------------------------------------- references --

    private static final Pattern REFERENCE = Pattern.compile(
            "(?i)\\b(?:certificate|cert|policy|licence|license|registration)\\s*"
                    + "(?:no\\.?|number|ref\\.?|#)?[\\s:]*([A-Z0-9][A-Z0-9/-]{4,})\\b");

    /**
     * @param text the document's extracted text
     * @param page which page it came from, or null
     */
    public DocumentFacts extract(String text, Integer page) {
        if (text == null || text.isBlank()) {
            return DocumentFacts.none();
        }
        return new DocumentFacts(
                labelledDate(text, EXPIRY_LABEL, page),
                labelledDate(text, ISSUE_LABEL, page),
                standards(text, page),
                amounts(text, page),
                references(text, page));
    }

    /**
     * Finds a date that follows one of the given labels.
     *
     * <p>Only looks a short distance past the label. A certificate laid out in
     * columns can put an unrelated date fifty characters later, and grabbing it
     * would attach the wrong value to the right label - the kind of error that
     * produces a confident wrong answer rather than an obvious failure.
     */
    private Optional<ParsedValue<LocalDate>> labelledDate(String text, Pattern label, Integer page) {
        Matcher labelMatch = label.matcher(text);
        while (labelMatch.find()) {
            int from = labelMatch.end();
            int to = Math.min(text.length(), from + 40);

            Matcher dateMatch = DATE.matcher(text.substring(from, to));
            if (dateMatch.find()) {
                Optional<LocalDate> parsed = parseDate(dateMatch.group());
                if (parsed.isPresent()) {
                    // Quote the label AND the date, so the citation shows which
                    // date this was - "12 April 2026" alone proves nothing.
                    String quote = text.substring(labelMatch.start(), from + dateMatch.end()).trim();
                    return Optional.of(new ParsedValue<>(parsed.get(), quote, page));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Tries each known format, in order.
     *
     * <p><b>Day-first, and not configurable.</b> 03/04/2026 is genuinely
     * ambiguous - March in the US, April in the UK - and no amount of parsing
     * resolves it. Making it a setting would mean the same document produces
     * different findings depending on configuration, which is worse than a
     * fixed convention documented in one place.
     *
     * <p>The real answer is to prefer the unambiguous formats, which is why
     * "12 April 2026" and "2026-04-12" are tried first.
     */
    private Optional<LocalDate> parseDate(String raw) {
        String cleaned = raw.replace(",", " ").replaceAll("\\s+", " ").trim();
        for (DateTimeFormatter f : FORMATS) {
            try {
                return Optional.of(LocalDate.parse(cleaned, f));
            } catch (DateTimeParseException ignored) {
                // Next format. Exhausting them all means we do not know the
                // date, which is reported as "not found" rather than guessed.
            }
        }
        return Optional.empty();
    }

    private List<ParsedValue<String>> standards(String text, Integer page) {
        List<ParsedValue<String>> found = new ArrayList<>();
        Matcher m = STANDARD.matcher(text);
        while (m.find()) {
            String normalised = m.group(1).toUpperCase() + " " + m.group(2);
            boolean alreadySeen = found.stream().anyMatch(p -> p.value().equals(normalised));
            if (!alreadySeen) {
                found.add(new ParsedValue<>(normalised, quoteAround(text, m.start(), m.end()), page));
            }
        }
        return found;
    }

    private List<ParsedValue<BigDecimal>> amounts(String text, Integer page) {
        List<ParsedValue<BigDecimal>> found = new ArrayList<>();
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            try {
                BigDecimal value = new BigDecimal(m.group(1).replace(",", ""));
                // "£5m" and "£5 million" both mean 5,000,000.
                String suffix = m.group(2) == null ? "" : m.group(2).trim().toLowerCase();
                if (suffix.startsWith("m")) {
                    value = value.multiply(BigDecimal.valueOf(1_000_000));
                } else if (suffix.startsWith("k")) {
                    value = value.multiply(BigDecimal.valueOf(1_000));
                }
                found.add(new ParsedValue<>(value, m.group().trim(), page));
            } catch (NumberFormatException ignored) {
                // Malformed number - skip rather than guess.
            }
        }
        return found;
    }

    private List<ParsedValue<String>> references(String text, Integer page) {
        List<ParsedValue<String>> found = new ArrayList<>();
        Matcher m = REFERENCE.matcher(text);
        while (m.find()) {
            String ref = m.group(1);
            if (found.stream().noneMatch(p -> p.value().equals(ref))) {
                found.add(new ParsedValue<>(ref, m.group().trim(), page));
            }
        }
        return found;
    }

    /** A little context either side, so the citation is checkable by eye. */
    private static String quoteAround(String text, int start, int end) {
        int from = Math.max(0, start - 30);
        int to = Math.min(text.length(), end + 30);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
