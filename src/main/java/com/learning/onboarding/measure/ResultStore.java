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

    public ResultStore() {
        this(DEFAULT_DIR);
    }

    public ResultStore(Path dir) {
        this.dir = dir;
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
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("config-%d.json".formatted(configNumber));
            Files.writeString(file, toJson(configNumber, result),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.out.printf("  WARNING: could not write measurements/config-%d.json: %s%n",
                    configNumber, e.getMessage());
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
            sb.append("# Measurement results\n\n")
              .append("Generated ").append(Instant.now()).append("\n\n")
              .append("Recall and false positives are reported together on purpose. ")
              .append("A system that flags\neverything has perfect recall and is useless; ")
              .append("one that flags nothing has a perfect\nfalse-positive rate and is ")
              .append("equally useless. Either number alone can be gamed.\n\n");

            List<Path> files = existingFiles();
            if (files.isEmpty()) {
                sb.append("_No configurations recorded yet._\n");
            } else {
                sb.append("| # | configuration | recall | caught/seeded | false positives | routing |\n")
                  .append("|---|---|---:|---:|---:|---:|\n");
                for (Path f : files) {
                    sb.append(rowFrom(Files.readString(f))).append('\n');
                }
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
                          "reachedCompletion": %b
                        }%s"""
                    .formatted(o.fixture().id(), o.fixture().clean(),
                            o.fixture().expected().size(), o.caught().size(),
                            o.missed().size(), o.routed().size(),
                            o.unexpected().size(), o.conflicts(),
                            o.discardedUngrounded(), o.complete(),
                            i < outcomes.size() - 1 ? ",\n" : "\n"));
        }

        return """
                {
                  "configuration": %d,
                  "label": "%s",
                  "recordedAt": "%s",
                  "fixtures": %d,
                  "seeded": %d,
                  "caught": %d,
                  "recall": %.4f,
                  "falsePositives": %d,
                  "routingMeaningful": %b,
                  "routingAccuracy": %.4f,
                  "perFixture": [
                %s  ]
                }
                """.formatted(configNumber, r.configuration(), Instant.now(),
                        outcomes.size(), r.seeded(), r.caught(), r.recall(),
                        r.falsePositives(), r.routingMeaningful(),
                        r.routingAccuracy(), perFixture);
    }

    /** Pull the handful of headline numbers back out for the summary table. */
    private String rowFrom(String json) {
        return "| %s | %s | %s | %s/%s | %s | %s |".formatted(
                field(json, "configuration"), field(json, "label"),
                field(json, "recall"), field(json, "caught"), field(json, "seeded"),
                field(json, "falsePositives"),
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
