package com.learning.onboarding.measure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Writes each configuration's outcome to a file the moment it completes.
 *
 * <h2>Why this exists</h2>
 *
 * The review cache lives in Postgres, and Postgres lived inside a container
 * created without a volume. When that container was removed, two days of
 * measurement went with it - roughly 36 model requests against a free tier of
 * 20 per day, and nothing to show for them.
 *
 * A named volume fixes that particular accident. It does not fix the shape of
 * the problem, which is that <b>the only record of the experiment was inside a
 * local database</b>. A volume still dies to {@code docker system prune
 * --volumes} or a factory reset, and it certainly does not follow the project
 * onto another machine.
 *
 * So the outcome is written here as a file, in the repository, committed to
 * git. That record survives a lost container, a wiped volume, a new laptop, and
 * a demo given six months later on someone else's machine.
 *
 * <h2>The distinction worth keeping straight</h2>
 *
 * The cache and the results protect different things and are not
 * interchangeable:
 *
 * <ul>
 *   <li><b>The cache</b> saves <i>quota</i>. Losing it means paying again.</li>
 *   <li><b>These files</b> save the <i>result</i>. Losing them means the
 *       experiment never happened.</li>
 * </ul>
 *
 * The second is much worse, and until now only the first was protected.
 *
 * <h2>Written per configuration, not at the end</h2>
 *
 * The daily quota is about the size of this experiment, so running out partway
 * through is the expected case rather than bad luck. A configuration that
 * finished is a result worth keeping even if the next one dies mid-call, so
 * each is flushed as it completes.
 */
public final class ResultStore {

    /** Deliberately inside the repository, not under target/ - target is disposable. */
    static final Path DEFAULT_DIR = Path.of("measurements");

    private final Path dir;

    /** Which model produced these results, or null when not recorded. */
    private final String modelLabel;

    public ResultStore(Path dir) {
        this(dir, null);
    }

    public ResultStore(Path dir, String modelLabel) {
        this.dir = dir;
        this.modelLabel = modelLabel;
    }

    /**
     * Results for one model, in their own directory.
     *
     * <h2>Why the model is in the path and not just in the file</h2>
     *
     * {@code config-2.json} names a configuration. It does not name a model - so
     * measuring configuration 2 on a second provider would overwrite the first
     * provider's result with a file of the same name and the same shape, and the
     * summary table would render perfectly either way.
     *
     * That is the same failure the review cache key is built to prevent: a
     * comparison across two models reported as though it were one. The cache
     * refuses to serve a Gemini finding to a Groq run because the model name is
     * in the key. This does the equivalent for the results, because the cache
     * protecting quota is no use if the file protecting the RESULT does not.
     *
     * <p>Concretely: the free Gemini tier caps the experiment at 20 requests a
     * day, so a second provider is the difference between a repeatable
     * experiment and a six-day one. The moment that became worth doing, this
     * directory needed splitting.
     */
    public static ResultStore forModel(String modelName) {
        return new ResultStore(DEFAULT_DIR.resolve(slug(modelName)), modelName);
    }

    /**
     * A model name that is safe as a directory name.
     *
     * <p>Provider model ids carry slashes - {@code openai/gpt-oss-120b} - and a
     * slash in a path segment silently creates a nested directory rather than
     * failing, which would scatter one run across two levels.
     */
    static String slug(String modelName) {
        String cleaned = modelName == null ? "unknown"
                : modelName.replaceAll("[^A-Za-z0-9._-]+", "-")
                           .replaceAll("(^-+)|(-+$)", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /**
     * Persist one configuration's outcome.
     *
     * <p>Failure to write is logged and swallowed. That is a deliberate
     * asymmetry: this is a safety net, and a safety net that can abort the run
     * it protects is worse than none. The numbers are also on stdout, so a
     * failed write degrades to "you have the console" rather than losing the
     * configuration.
     */
    public void save(int configNumber, MeasurementHarness.Result result) {
        // A fixture whose reviewers did not all run has a recall figure that
        // looks exactly like a real one and means nothing. Such a run is written
        // to incomplete/ instead - kept for inspection, but out of the directory
        // writeReport() reads, so it can never be rendered as a result and can
        // never overwrite a good earlier result for the same configuration.
        //
        // WHY THIS EXISTS
        // Configurations 2 and 3 were once saved with six of fourteen fixtures
        // unfinished, after a daily token cap was reached mid-run. Configuration
        // 3 then appeared to cut false positives from 7 to 2 - precisely the
        // improvement a verifier is supposed to produce. It was an artefact: the
        // reviewers that would have raised those findings never ran, so there was
        // nothing to refute.
        //
        // A number that moves the way the hypothesis predicts, for a reason
        // unrelated to the hypothesis, is the most dangerous output a measurement
        // can produce - it is the one nobody questions. This class already
        // refuses to imply comparability across different fixture COUNTS; not
        // checking whether those fixtures actually finished was the same bug with
        // the guard missing.
        long unfinished = result.outcomes().stream()
                .filter(o -> !o.complete()).count();
        Path target = unfinished == 0 ? dir : dir.resolve("incomplete");

        // A failed attempt at a configuration that ALREADY has a clean recorded
        // result is not evidence of anything - the good file is the answer, and
        // quarantining the failure only makes writeReport() name configuration 3
        // as discarded while the table above it shows configuration 3 as a
        // result. Two true statements that read as a contradiction.
        //
        // This is the mirror of the removal below, and it is not hypothetical:
        // VerifierAgent has no cache, so re-running configuration 3 pays for
        // every verifier call again and is exposed to the per-minute token
        // limit every time. A configuration that is expensive to repeat is
        // exactly the one that will keep producing these files.
        //
        // The already-good result is left untouched. Nothing is overwritten and
        // nothing is quarantined; the run simply reports that it added nothing.
        if (unfinished > 0
                && Files.isRegularFile(dir.resolve("config-%d.json".formatted(configNumber)))) {
            System.out.printf("""
                      DISCARDED, not quarantined: configuration %d had %d of %d
                      fixture(s) where a reviewer or the verifier did not run, but a
                      COMPLETE result for configuration %d is already recorded. The
                      earlier result stands; this attempt is dropped rather than
                      filed, because a failed retry of an answered question is not a
                      finding.
                    %n""", configNumber, unfinished, result.outcomes().size(),
                    configNumber);
            return;
        }

        try {
            Files.createDirectories(target);
            Path file = target.resolve("config-%d.json".formatted(configNumber));
            Files.writeString(file, toJson(configNumber, result),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // A configuration that has now been measured cleanly supersedes any
            // earlier quarantined attempt at the SAME configuration. Left in
            // place, that stale file makes writeReport() announce a discarded run
            // that no longer exists - so the table shows configuration 3 as a
            // result and the paragraph below it says configuration 3 was
            // discarded. Both statements are generated from real files, and the
            // reader has no way to tell which is current.
            //
            // Only the same configuration number is removed, and only on a clean
            // save. A quarantined run for a configuration that has never
            // succeeded still has to be visible - that is the whole point of the
            // directory.
            if (unfinished == 0) {
                Path superseded = dir.resolve("incomplete")
                        .resolve("config-%d.json".formatted(configNumber));
                if (Files.deleteIfExists(superseded)) {
                    System.out.printf("  superseded: removed the earlier quarantined "
                            + "%s, which this run replaces%n", superseded);
                }
            }

            if (unfinished > 0) {
                System.out.printf("""
                          NOT RECORDED AS A RESULT: configuration %d had %d of %d
                          fixture(s) where a reviewer did not run, so its recall is
                          not measuring what it appears to. Written to
                          %s for inspection instead.

                          Re-run this configuration once the limit that stopped it
                          has cleared. Every call that DID succeed is cached, so the
                          re-run pays only for what this one never reached.
                        %n""", configNumber, unfinished, result.outcomes().size(),
                        file);
            }
        } catch (IOException e) {
            System.out.printf("  WARNING: could not write %s/config-%d.json: %s%n",
                    target, configNumber, e.getMessage());
        }
    }

    /**
     * Rewrite the human-readable summary across every configuration recorded so
     * far, including ones from earlier days.
     *
     * <p>Regenerated from the files rather than from this run's in-memory
     * results, so a run that only completes configuration 3 still produces a
     * report containing 1 and 2 from the days they were measured on. Without
     * that, the report would silently shrink to whatever today happened to
     * cover.
     */
    public void writeReport() {
        try {
            Files.createDirectories(dir);
            var sb = new StringBuilder();
            sb.append("# Measurement results\n\n");
            if (modelLabel != null) {
                // Stated at the top, not in a footnote. Every row below was
                // produced by this model, and a recall figure from one model
                // means nothing next to a recall figure from another - the
                // reader has to know which before reading the table.
                sb.append("**Model: `").append(modelLabel).append("`.** ")
                  .append("Every row below was measured on it. Numbers from a ")
                  .append("different model live in a sibling directory and are ")
                  .append("NOT comparable with these - the comparison here is ")
                  .append("between configurations on one model.\n\n");
            }
            sb.append("Generated ").append(Instant.now()).append("\n\n")
              .append("Recall and false positives are reported together on purpose. ")
              .append("A system that flags\neverything has perfect recall and is useless; ")
              .append("one that flags nothing has a perfect\nfalse-positive rate and is ")
              .append("equally useless. Either number alone can be gamed.\n\n");

            List<Path> files = existingFiles();
            if (files.isEmpty()) {
                sb.append("_No configurations recorded yet._\n");
            } else {
                sb.append("| # | configuration | fixtures | recall | caught/seeded | FP (all) | FP (actionable) | confirmations | routing |\n")
                  .append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
                var fixtureCounts = new java.util.LinkedHashSet<String>();
                for (Path f : files) {
                    String json = Files.readString(f);
                    fixtureCounts.add(field(json, "fixtures"));
                    sb.append(rowFrom(json)).append('\n');
                }

                // A recall figure over two fixtures looks exactly like a recall
                // figure over seven. The daily quota is smaller than a single
                // configuration, so a half-finished one is the normal state
                // rather than an accident - and the moment two configurations
                // cover different fixtures, this table stops being a comparison
                // and starts being two unrelated numbers side by side.
                //
                // Said loudly here rather than left to the reader, because the
                // failure is silent: the table renders perfectly either way.
                if (fixtureCounts.size() > 1) {
                    sb.append("\n> **NOT COMPARABLE YET.** These configurations were ")
                      .append("measured over different numbers of fixtures (")
                      .append(String.join(", ", fixtureCounts))
                      .append("). Recall is only comparable across configurations ")
                      .append("run on the same set, so treat the rows above as ")
                      .append("progress, not as a result.\n");
                }
            }

            // Named, not omitted. A configuration missing from the table above
            // because its run was discarded looks identical to one that was never
            // attempted, and the difference matters: the first needs re-running
            // and the second needs starting.
            List<Path> discarded = incompleteFiles();
            if (!discarded.isEmpty()) {
                sb.append("\n> **")
                  .append(discarded.size())
                  .append(discarded.size() == 1 ? " configuration was" : " configurations were")
                  .append(" measured but DISCARDED**, because at least one fixture had a ")
                  .append("reviewer that never ran - a recall figure over partly-reviewed ")
                  .append("fixtures looks exactly like a real one. Kept in `incomplete/` ")
                  .append("for inspection: ")
                  .append(discarded.stream().map(p -> "`" + p.getFileName() + "`")
                          .collect(java.util.stream.Collectors.joining(", ")))
                  .append(". Re-run them; cached calls make the retry cheap.\n");
            }

            if (!files.isEmpty()) {
                sb.append("\n## Raw\n\n");
                for (Path f : files) {
                    sb.append("`").append(f.getFileName()).append("`\n\n```json\n")
                      .append(Files.readString(f)).append("\n```\n\n");
                }
            }
            Files.writeString(dir.resolve("RESULTS.md"), sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.out.printf("  WARNING: could not write measurements/RESULTS.md: %s%n",
                    e.getMessage());
        }
    }

    /** Which configurations already have a recorded result. */
    public List<Integer> recorded() {
        return existingFiles().stream()
                .map(p -> p.getFileName().toString())
                .map(n -> n.replaceAll("\\D+", ""))
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .sorted()
                .toList();
    }

    /** Runs that were measured and discarded, kept for inspection. */
    private List<Path> incompleteFiles() {
        Path incomplete = dir.resolve("incomplete");
        if (!Files.isDirectory(incomplete)) {
            return List.of();
        }
        try (var stream = Files.list(incomplete)) {
            return stream.filter(p -> p.getFileName().toString().matches("config-\\d+\\.json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            // A report that cannot list the discarded runs is still a valid
            // report of the good ones. Not worth failing for.
            return List.of();
        }
    }

    private List<Path> existingFiles() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().matches("config-\\d+\\.json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Hand-rolled rather than pulled through Jackson.
     *
     * <p>The Result graph reaches into Fixture and ReviewFinding, which carry
     * document text and model output. Serialising the whole object would put
     * application content into a file destined for a public-ish repository, and
     * would break the moment either record gains a field. Naming the fields
     * explicitly keeps the file to the numbers the experiment is about.
     */
    private String toJson(int configNumber, MeasurementHarness.Result r) {
        var perFixture = new StringBuilder();
        List<MeasurementHarness.FixtureOutcome> outcomes = r.outcomes();
        for (int i = 0; i < outcomes.size(); i++) {
            var o = outcomes.get(i);
            perFixture.append("""
                        {
                          "fixture": "%s",
                          "clean": %b,
                          "seeded": %d,
                          "caught": %d,
                          "missed": %d,
                          "routedCorrectly": %d,
                          "unexpectedFindings": %d,
                          "conflicts": %d,
                          "discardedUngrounded": %d,
                          "reachedCompletion": %b,
                          "wallClockMs": %d
                        }%s"""
                    .formatted(o.fixture().id(), o.fixture().clean(),
                            o.fixture().expected().size(), o.caught().size(),
                            o.missed().size(), o.routed().size(),
                            o.unexpected().size(), o.conflicts(),
                            o.discardedUngrounded(), o.complete(),
                            o.wallClockMs(),
                            i < outcomes.size() - 1 ? ",\n" : "\n"));
        }

        return """
                {
                  "configuration": %d,
                  "label": "%s",
                  "recordedAt": "%s",
                  "fixtures": %d,
                  "incompleteFixtures": %d,
                  "seeded": %d,
                  "caught": %d,
                  "recall": %.4f,
                  "falsePositives": %d,
                  "actionableFalsePositives": %d,
                  "confirmatoryFindings": %d,
                  "falsePositiveNote": "falsePositives counts EVERY finding on a clean pack. actionableFalsePositives counts only those at MAJOR or above - the ones that would actually stop a vendor. The difference is confirmatoryFindings: INFO entries saying the pack passed a check, which are an audit trail rather than an accusation. Both are reported because narrowing the metric after seeing the number it made look bad is the move PREDICTIONS.md exists to prevent.",
                  "routingMeaningful": %b,
                  "routingAccuracy": %.4f,
                  "medianWallClockMs": %d,
                  "wallClockCaveat": "Median wall clock per fixture, NOT the sum of model calls - configuration 2 runs four reviewers concurrently, so cost and latency do not scale together. Comparable across configurations ONLY within a single uncached, unthrottled run: a cached call returns in about a millisecond and a rate-limited one spends 20 seconds in backoff, and either dominates this number completely.",
                  "perFixture": [
                %s  ]
                }
                """.formatted(configNumber, r.configuration(), Instant.now(),
                        outcomes.size(),
                        // Recorded in the file, not only in the directory it
                        // landed in. A file copied out of incomplete/ loses that
                        // context; this field travels with it.
                        outcomes.stream().filter(o -> !o.complete()).count(),
                        r.seeded(), r.caught(), r.recall(),
                        r.falsePositives(), r.actionableFalsePositives(),
                        r.confirmatoryFindings(), r.routingMeaningful(),
                        r.routingAccuracy(),
                        // Median, not mean. One rate-limited fixture spends a
                        // minute in backoff and drags a mean far past anything
                        // the system actually does.
                        medianWallClockMs(outcomes),
                        perFixture);
    }

    /**
     * Median wall clock across fixtures.
     *
     * <p>Median rather than mean because the distribution is not one a mean
     * describes: most fixtures take seconds and a rate-limited one takes a
     * minute of backoff. A single throttled fixture moves a mean past anything
     * the system actually does, while the median still reports a typical review.
     */
    private static long medianWallClockMs(List<MeasurementHarness.FixtureOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return 0;
        }
        long[] sorted = outcomes.stream()
                .mapToLong(MeasurementHarness.FixtureOutcome::wallClockMs)
                .sorted()
                .toArray();
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[mid]
                : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /** Pull the handful of headline numbers back out for the summary table. */
    private String rowFrom(String json) {
        // False positives are split into two columns rather than one, because a
        // single number here conflates "wrongly told a compliant vendor it is
        // uninsured" with "noted that the insurance is fine". Older files have
        // no split; they render "-" rather than a misleading zero.
        // field() answers "?" for a key that is not there, which is what a file
        // written before the split looks like.
        String actionable = field(json, "actionableFalsePositives");
        String confirmatory = field(json, "confirmatoryFindings");
        return "| %s | %s | %s | %s | %s/%s | %s | %s | %s | %s |".formatted(
                field(json, "configuration"), field(json, "label"),
                field(json, "fixtures"),
                field(json, "recall"), field(json, "caught"), field(json, "seeded"),
                field(json, "falsePositives"),
                "?".equals(actionable) ? "-" : actionable,
                "?".equals(confirmatory) ? "-" : confirmatory,
                "true".equals(field(json, "routingMeaningful"))
                        ? field(json, "routingAccuracy") : "n/a");
    }

    private String field(String json, String name) {
        var m = java.util.regex.Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"?([^\",\\n}]+)\"?")
                .matcher(json);
        return m.find() ? m.group(1).trim() : "?";
    }

    static {
        // Locale-independent formatting. %.4f under a comma-decimal locale
        // writes "0,8571", which is not valid JSON and fails to parse on a
        // machine configured differently from the one that wrote it.
        Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
    }
}
