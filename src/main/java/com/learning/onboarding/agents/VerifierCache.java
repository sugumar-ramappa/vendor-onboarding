package com.learning.onboarding.agents;

import com.learning.onboarding.domain.Verdict;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Stores one verdict per challenged finding, so a repeated measurement does not
 * pay for the verifier twice.
 *
 * <h2>Why this exists</h2>
 *
 * {@link ReviewerAgent} has had a cache since V4; {@link VerifierAgent} had
 * none. That is backwards. The verifier is the most expensive call in the
 * system - it runs only after four reviewers have produced findings, it scales
 * with the number of findings rather than the number of fixtures, and the
 * {@code gatherMore} loop can make it challenge the same finding twice.
 *
 * <p>The cost showed up as an inability to re-score. On 2026-08-28 the
 * false-positive metric was found to be counting {@code INFO} confirmations as
 * false accusations. Configurations 1 and 2 were re-scored from cache for
 * nothing; configuration 3 could not be, because re-running it meant paying for
 * every challenge again, and the attempt died on the per-minute token limit.
 *
 * <p><b>Storing raw model output and computing metrics afterwards is what makes
 * a measurement correctable.</b> That property held for the reviewers and did
 * not hold for the verifier, which is precisely the component whose value the
 * measurement was questioning.
 *
 * @see ReviewCache the same idea for reviewers, and the source of this shape
 */
public interface VerifierCache {

    Optional<Hit> get(String key);

    void put(String key, String promptVersion, String modelName,
             Verdict verdict, long originalLatencyMs);

    /**
     * @param verdict           what the verifier concluded
     * @param originalLatencyMs how long the real call took, kept so a cached run
     *                          can still report honest timings rather than
     *                          claiming the verifier answers in a millisecond
     */
    record Hit(Verdict verdict, long originalLatencyMs) {}

    /**
     * Builds the key.
     *
     * <h2>What is in it, and why that is enough</h2>
     *
     * The challenge prompt is rendered in full before the call - it carries the
     * finding being challenged, its evidence, any reference data fetched by an
     * earlier pass, and the whole application. So hashing the rendered prompt
     * covers everything that could change the answer.
     *
     * <p>This was initially thought to be harder than the reviewer's key, on the
     * grounds that the verifier's input is "a set of findings whose order is not
     * guaranteed". That was wrong: {@code challenge()} takes <b>one</b> finding
     * and renders one prompt. There is no set and no ordering.
     *
     * <p>The {@code gatherMore} loop is handled by the same mechanism rather
     * than by a special case. A second pass renders the same finding <i>plus</i>
     * the reference data the first pass asked for, which is a different string,
     * so it is a different key - correctly, because it is a different question.
     *
     * <p>Static, for the same reason {@link ReviewCache#key} is: a key
     * derivation that differs between writer and reader never hits, and the only
     * symptom is a cache that quietly appears not to work.
     */
    static String key(String promptVersion, String modelName, String prompt) {
        String material = String.join(" ", promptVersion, modelName, prompt);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Caching switched off. Used in tests, and available when a run must
     * genuinely exercise the model every time.
     */
    VerifierCache NONE = new VerifierCache() {
        @Override
        public Optional<Hit> get(String key) {
            return Optional.empty();
        }

        @Override
        public void put(String key, String promptVersion, String modelName,
                        Verdict verdict, long originalLatencyMs) {
            // deliberately nothing
        }
    };
}
