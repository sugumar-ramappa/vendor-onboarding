package com.learning.onboarding.agents;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompts from version-controlled files, by explicit version.
 *
 * <p><b>Prompts are not string literals.</b> Every finding records which prompt
 * produced it, and that only means something if prompts have versions you can go
 * and read. Six weeks after a release someone asks why a vendor was flagged in
 * March and not now; with a version on the finding you diff two files and answer
 * in a minute. With the prompt inlined in a Java class that has since been
 * edited, the honest answer is "we don't know".
 *
 * <p>It also makes rollback possible. A prompt change that quietly raises the
 * false-positive rate is otherwise invisible until someone complains, by which
 * point nobody remembers what changed.
 *
 * <h2>Versions are files, not numbers in a database</h2>
 * {@code compliance-v1.txt} lives in {@code src/main/resources/prompts}. Editing
 * it in place is a mistake - you create {@code compliance-v2.txt} and change
 * which version the reviewer asks for. Old findings then still point at the
 * prompt that actually produced them.
 */
@Component
public class PromptLibrary {

    private static final String LOCATION = "prompts/";

    // Prompts are immutable files; reading each one once is enough.
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * @param version e.g. "compliance-v1", matching prompts/compliance-v1.txt
     * @throws IllegalArgumentException if no such prompt exists - a missing
     *         prompt must fail at startup, not produce an empty system message
     *         that a model will cheerfully improvise around
     */
    public String get(String version) {
        return cache.computeIfAbsent(version, v -> {
            ClassPathResource resource = new ClassPathResource(LOCATION + v + ".txt");
            if (!resource.exists()) {
                throw new IllegalArgumentException(
                        "no prompt '" + v + "' in " + LOCATION + " - a reviewer with no "
                                + "instructions will still produce findings, and they will be nonsense");
            }
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("could not read prompt " + v, e);
            }
        });
    }
}
