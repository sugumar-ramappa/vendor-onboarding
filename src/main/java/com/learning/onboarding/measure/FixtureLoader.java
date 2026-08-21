package com.learning.onboarding.measure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.*;
import com.learning.onboarding.intake.DocumentFacts;
import com.learning.onboarding.intake.ExtractionSource;
import com.learning.onboarding.intake.FactExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads the hand-written fixtures off disk.
 *
 * <p>Fixtures are JSON files rather than Java so they can be written and
 * reviewed as data. Adding one is a file, not a compile - which matters when the
 * useful thing to do after a measurement is usually "add three more cases like
 * the one that failed".
 */
@Component
public class FixtureLoader {

    private static final Logger log = LoggerFactory.getLogger(FixtureLoader.class);

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final FactExtractor factExtractor = new FactExtractor();
    private final Path directory;

    public FixtureLoader(@Value("${onboarding.fixtures.dir:fixtures}") String directory) {
        this.directory = Path.of(directory);
    }

    public List<Fixture> loadAll() {
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("no fixture directory at " + directory.toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(directory)) {
            List<Fixture> fixtures = files
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .map(this::read)
                    .toList();

            long clean = fixtures.stream().filter(Fixture::clean).count();
            log.info("loaded {} fixture(s): {} with defects, {} clean, {} planted defect(s)",
                    fixtures.size(), fixtures.size() - clean, clean,
                    fixtures.stream().mapToInt(f -> f.expected().size()).sum());

            if (clean == 0) {
                // Without clean fixtures the false-positive rate is undefined,
                // and recall on its own can be gamed by flagging everything.
                log.warn("no clean fixtures - the false-positive rate cannot be measured");
            }
            return fixtures;

        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + directory, e);
        }
    }

    private Fixture read(Path file) {
        try {
            return json.readValue(Files.readString(file), Fixture.class);
        } catch (IOException e) {
            // A fixture that will not parse must stop the run. Skipping it would
            // silently shrink the measurement and change the numbers.
            throw new UncheckedIOException("could not read fixture " + file, e);
        }
    }

    /**
     * Turns a fixture into what a reviewer receives.
     *
     * <p>Runs the same deterministic extraction the real pipeline does, so the
     * measurement exercises the actual code path rather than a shortcut. A
     * harness that fed reviewers pre-digested facts would be measuring something
     * the system never does.
     */
    public ReviewContext toContext(Fixture fixture) {
        var source = fixture.applicationJson();

        List<Sku> skus = source.skus().stream()
                .map(s -> new Sku(s.vendorSku(), s.description(), s.gtin(),
                        s.casePack(), new BigDecimal(s.caseWeightKg()), s.hazardous()))
                .toList();

        List<SubmittedDocument> documents = new ArrayList<>();
        Map<String, DocumentFacts> facts = new HashMap<>();
        Map<String, ExtractionSource> sources = new HashMap<>();

        for (var d : source.documents()) {
            documents.add(new SubmittedDocument(
                    d.documentId(), DocumentType.valueOf(d.type()), d.text(), 1));
            facts.put(d.documentId(), factExtractor.extract(d.text(), 1));
            sources.put(d.documentId(), ExtractionSource.NATIVE_TEXT);
        }

        var application = new VendorApplication(
                source.applicationId(), source.vendorName(),
                ProductCategory.valueOf(source.category()),
                DeliveryModel.valueOf(source.deliveryModel()),
                Instant.parse(source.requestedGoLive()),
                skus, documents);

        return new ReviewContext(application, documents, facts, sources);
    }
}
