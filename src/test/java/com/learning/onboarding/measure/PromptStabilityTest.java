package com.learning.onboarding.measure;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.SubmittedDocument;
import com.learning.onboarding.intake.DocumentFacts;
import com.learning.onboarding.intake.ExtractionSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rendered prompt must not depend on how a caller built its maps.
 *
 * <p>This exists because it did. `facts` was a HashMap and render() iterated it,
 * so the extracted-facts section came out in a different order on different JVM
 * runs. The content was identical; only the order moved.
 *
 * <p>Invisible to a reader, and fatal to a cache keyed on the rendered prompt:
 * every run produced a new key, nothing was ever reused, and each day of a
 * multi-day measurement re-paid for the previous day against a 20-request daily
 * quota. It surfaced as "the cache never hits", three days into a three-day run.
 */
class PromptStabilityTest {

    private static ReviewContext contextWith(Map<String, DocumentFacts> facts,
                                             List<SubmittedDocument> documents) {
        var loader = new FixtureLoader("fixtures");
        Fixture f = loader.loadAll().stream()
                .filter(x -> x.id().equals("F01")).findFirst().orElseThrow();
        ReviewContext base = loader.toContext(f);
        return new ReviewContext(base.application(), documents, facts, base.sources());
    }

    @Test
    @DisplayName("render() is identical whatever order the facts map iterates in")
    void renderDoesNotDependOnMapOrder() {
        var loader = new FixtureLoader("fixtures");
        Fixture f = loader.loadAll().stream()
                .filter(x -> x.id().equals("F01")).findFirst().orElseThrow();
        ReviewContext original = loader.toContext(f);

        // Same entries, three different iteration orders.
        var insertionOrder = new LinkedHashMap<>(original.facts());

        var reversed = new LinkedHashMap<String, DocumentFacts>();
        var keys = new ArrayList<>(insertionOrder.keySet());
        Collections.reverse(keys);
        keys.forEach(k -> reversed.put(k, insertionOrder.get(k)));

        var sorted = new TreeMap<>(insertionOrder);

        String a = new ReviewContext(original.application(), original.documents(),
                insertionOrder, original.sources()).render();
        String b = new ReviewContext(original.application(), original.documents(),
                reversed, original.sources()).render();
        String c = new ReviewContext(original.application(), original.documents(),
                sorted, original.sources()).render();

        assertEquals(a, b, "reversing the facts map changed the prompt - the cache "
                + "key would change with it and never hit");
        assertEquals(a, c, "sorting the facts map changed the prompt");
    }

    @Test
    @DisplayName("every fixture renders the same twice in a row")
    void renderIsRepeatable() {
        var loader = new FixtureLoader("fixtures");
        for (Fixture f : loader.loadAll()) {
            assertEquals(loader.toContext(f).render(), loader.toContext(f).render(),
                    f.id() + " rendered differently on two consecutive builds");
        }
    }
}
