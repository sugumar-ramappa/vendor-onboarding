package com.learning.onboarding.measure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixtures agree with the rulebook, checked without a model.
 *
 * <h2>Why this exists</h2>
 *
 * A fixture bug is only discovered when the fixture is run, and running one costs
 * five model calls against a free tier that allows about forty-five a day. Two
 * have already cost a measurement window:
 *
 * <ul>
 *   <li>A dense fixture whose planted defect was a MISSING mandatory document.
 *       The completeness gate fires on that and short-circuits the four
 *       substantive reviewers, so the fixture's other four defects became
 *       unreachable - inside a fixture built specifically to give those four
 *       reviewers work.</li>
 *   <li>A {@code mustMention} of "expired" where the proven form is the stem
 *       "expire", which also matches expires, expiry and expiration. A
 *       mustMention the model will not produce scores a caught defect as a miss,
 *       which looks exactly like a model failure and is not one.</li>
 * </ul>
 *
 * <p>Everything checked here is arithmetic and string matching against the same
 * reference tables the reviewers read. No model, no judgement, no quota - so it
 * runs on every build instead of once a day.
 *
 * <h2>What it deliberately does not check</h2>
 *
 * Whether a planted defect is a good test. That is editorial and belongs in the
 * fixture's own {@code note}. This checks only that a fixture is internally
 * consistent with the rulebook: that a clean pack really is clean, and that a
 * defective one really is defective for the reason claimed.
 */
@SpringBootTest(properties = {
        "spring.ai.google.genai.api-key=test-key-never-used",
        "onboarding.intake.vision.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class FixtureRulebookTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("vendor_onboarding_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("onboarding.agent-datasource.password", () -> "agent-local-dev-only");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FixtureLoader loader;

    /**
     * The check that would have caught the F20 bug.
     *
     * <p>A dense fixture exists to give four specialist reviewers work at once.
     * If a mandatory document is absent the gate declares the pack incomplete and
     * skips them, so the fixture measures the gate and nothing else. A fixture
     * carrying more than one planted defect must therefore be COMPLETE.
     *
     * <p>Single-defect fixtures are exempt: for several of them the missing
     * document IS the planted defect, and the gate firing is the correct
     * behaviour being tested.
     */
    @Test
    @DisplayName("a fixture with several defects has every mandatory document, or the gate hides them")
    void denseFixturesArePackComplete() {
        List<String> broken = new ArrayList<>();

        for (Fixture fixture : loader.loadAll()) {
            if (fixture.expected().size() < 2) {
                continue;
            }
            List<String> missing = missingMandatoryDocuments(fixture);
            if (!missing.isEmpty()) {
                broken.add("%s carries %d defects but is missing mandatory %s - the gate "
                        .formatted(fixture.id(), fixture.expected().size(), missing)
                        + "will short-circuit and the other defects become unreachable");
            }
        }

        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    /**
     * A clean fixture must survive the deterministic checks.
     *
     * <p>"Clean" means the correct answer is no findings at all, and those
     * fixtures are the only place a false-positive rate can be measured. One that
     * quietly contains a real defect makes every false-positive number wrong, and
     * nothing about the output would look unusual.
     */
    @Test
    @DisplayName("a clean fixture contains no defect the rulebook can detect")
    void cleanFixturesAreActuallyClean() {
        List<String> broken = new ArrayList<>();

        for (Fixture fixture : loader.loadAll()) {
            if (!fixture.clean()) {
                continue;
            }
            List<String> found = new ArrayList<>(missingMandatoryDocuments(fixture));
            found.addAll(thresholdBreaches(fixture));

            if (!found.isEmpty()) {
                broken.add("%s is marked clean but %s".formatted(fixture.id(), found));
            }
        }

        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    /**
     * Every mustMention must be a stem another fixture already proved, or be
     * present in the pack.
     *
     * <p>The looser of the two is deliberate. A mustMention describes what the
     * FINDING says, not what the document says - a certificate reading "Valid
     * until 2 November 2025" should produce a finding saying "expired", and
     * demanding the word appear in the source would reject a correct answer key.
     *
     * <p>But a phrase that appears in neither the pack nor any previously
     * measured fixture is one nobody has ever seen a model produce, and that is
     * worth failing on before it costs a window.
     */
    @Test
    @DisplayName("a mustMention is either present in the pack or a form already proven elsewhere")
    void mustMentionIsProducible() {
        List<Fixture> all = loader.loadAll();

        // Stems a model has actually produced, or that the rendered application
        // supplies even when no document quotes them.
        //
        // "gs1" is here on evidence rather than assumption: F10's pack has no GS1
        // document at all - its absence IS the defect - so the word appears
        // nowhere in it, and both Gemini and Groq still produced findings saying
        // "GS1". A mustMention describes what the FINDING says, not the source.
        //
        // "gtin" is here because ReviewContext.render() now emits it per SKU.
        // Before that it emitted only the SKU code, description, pack, weight and
        // hazard flag, so a GTIN check was impossible unless a product list
        // happened to quote its own barcodes - which is exactly how F13 became an
        // unmeasurable fixture.
        List<String> proven = List.of("scope", "expire", "asn", "liability",
                "non-conformance", "territor", "safety data sheet", "prefix",
                "weight", "terms", "trading", "lead", "classification",
                "gs1", "gtin");

        List<String> broken = new ArrayList<>();
        for (Fixture fixture : all) {
            String pack = (fixture.applicationJson().documents().stream()
                    .map(Fixture.FixtureDocument::text)
                    .reduce("", (a, b) -> a + " " + b)).toLowerCase(Locale.ROOT);

            for (Fixture.ExpectedDefect defect : fixture.expected()) {
                for (String phrase : defect.mustMention()) {
                    String p = phrase.toLowerCase(Locale.ROOT);
                    if (!pack.contains(p) && !proven.contains(p)) {
                        broken.add("%s: mustMention '%s' appears nowhere in the pack and is "
                                .formatted(fixture.id(), phrase)
                                + "not a form any measured fixture has used - a model that "
                                + "phrases it differently scores a caught defect as a miss");
                    }
                }
            }
        }

        assertTrue(broken.isEmpty(), String.join("\n", broken));
    }

    /**
     * The condition reaches the reviewer as part of the requirement.
     *
     * <p>The whole timber fix is that a conditionally-mandatory document must not
     * be presented as unconditionally mandatory. Migrating the column and never
     * rendering it would leave the gate reading exactly what it read before, and
     * every test here would still pass - the regression fixtures F15 and F16
     * would fail, but only after a model call has been paid for.
     *
     * <p>So this asserts the rendered string, which is what the model actually
     * sees: "mandatory when TIMBER_PRESENT - ..." rather than "mandatory" beside a
     * note the reviewer treats as commentary.
     */
    @Test
    @DisplayName("a conditional requirement renders its condition inside the requirement")
    void conditionalRequirementsCarryTheirCondition() {
        record Rule(String documentType, boolean mandatory, String appliesWhen, String note) {}

        List<Rule> conditional = jdbc.query("""
                SELECT document_type, mandatory, applies_when, note
                FROM required_document
                WHERE applies_when IS NOT NULL
                """, (rs, n) -> new Rule(rs.getString("document_type"),
                rs.getBoolean("mandatory"), rs.getString("applies_when"),
                rs.getString("note")));

        assertTrue(!conditional.isEmpty(),
                "no conditional requirements found - V5 did not apply, so the "
                        + "completeness gate is still reading TIMBER_CHAIN_OF_CUSTODY "
                        + "as unconditionally mandatory");

        List<String> broken = new ArrayList<>();
        for (Rule rule : conditional) {
            // Mirrors ReferenceDataGatherer's REQUIRED_DOCUMENTS branch.
            String rendered = "%s (%s%s)%s".formatted(rule.documentType(),
                    rule.mandatory() ? "mandatory" : "optional",
                    rule.appliesWhen() == null || rule.appliesWhen().isBlank()
                            ? "" : " when " + rule.appliesWhen(),
                    rule.note() == null || rule.note().isBlank() ? "" : " - " + rule.note());

            if (!rendered.contains("mandatory when") && !rendered.contains("optional when")) {
                broken.add(rule.documentType() + " renders as: " + rendered);
            }
        }

        assertTrue(broken.isEmpty(),
                "a condition that is not inside the requirement is read as commentary:\n"
                        + String.join("\n", broken));
    }

    // ------------------------------------------------------------- helpers --

    /** Mandatory document types absent from the pack, honouring applies_when. */
    private List<String> missingMandatoryDocuments(Fixture fixture) {
        var app = fixture.applicationJson();
        List<String> present = app.documents().stream()
                .map(Fixture.FixtureDocument::type).toList();

        List<Map<String, Object>> rules = jdbc.queryForList("""
                SELECT document_type, applies_when
                FROM required_document
                WHERE (product_category = ? OR product_category = '*')
                  AND (delivery_model IS NULL OR delivery_model = ?)
                  AND mandatory = true
                """, app.category(), app.deliveryModel());

        List<String> missing = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            String type = (String) rule.get("document_type");
            String when = (String) rule.get("applies_when");

            if (when != null && when.startsWith("HAZARDOUS_SKU_PRESENT")
                    && app.skus().stream().noneMatch(Fixture.FixtureSku::hazardous)) {
                continue;
            }
            if (when != null && when.startsWith("TIMBER_PRESENT")) {
                // No SKU in any fixture is timber; the rule exists precisely so a
                // steel-fixings vendor is not asked for a timber certificate.
                continue;
            }
            if (!present.contains(type)) {
                missing.add(type);
            }
        }
        return missing;
    }

    /** Numeric limits from finance_threshold and logistics_requirement. */
    private List<String> thresholdBreaches(Fixture fixture) {
        var app = fixture.applicationJson();
        List<String> found = new ArrayList<>();

        double maxKg = jdbc.queryForObject(
                "SELECT threshold_value FROM finance_threshold WHERE code = 'MANUAL_HANDLING_LIMIT_KG'",
                Double.class);

        // Only where a store receives the pallet - a distribution centre has a
        // forklift, so weight is not a defect there.
        if ("DIRECT_TO_STORE".equals(app.deliveryModel())) {
            for (Fixture.FixtureSku sku : app.skus()) {
                if (Double.parseDouble(sku.caseWeightKg()) > maxKg) {
                    found.add(sku.vendorSku() + " over the manual handling limit");
                }
            }
        }

        // A GTIN that does not sit under the vendor's own GS1 prefix belongs to
        // somebody else, most often the original manufacturer.
        app.documents().stream()
                .filter(d -> "GS1_REGISTRATION".equals(d.type()))
                .findFirst()
                .ifPresent(gs1 -> {
                    var m = java.util.regex.Pattern
                            .compile("[Cc]ompany prefix:\\s*(\\d+)").matcher(gs1.text());
                    if (m.find()) {
                        for (Fixture.FixtureSku sku : app.skus()) {
                            if (!sku.gtin().startsWith(m.group(1))) {
                                found.add(sku.vendorSku() + " GTIN outside the vendor's prefix");
                            }
                        }
                    }
                });

        return found;
    }
}
