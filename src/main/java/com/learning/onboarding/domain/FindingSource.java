package com.learning.onboarding.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Where a finding came from. Stamped on by the framework; never supplied by a
 * model.
 *
 * <h2>Why promptVersion is here</h2>
 * This is the field that separates a system someone can operate from a demo.
 *
 * <p>Prompts change. Six weeks after a release, a reviewer asks why this
 * supplier was flagged in March but not now. Without the prompt version, the
 * honest answer is "we don't know" - the corpus changed, or the prompt changed,
 * or the model changed, and there is no way to tell which. With it, you diff two
 * prompt versions and have an answer in a minute.
 *
 * <p>It also makes rollback possible. A prompt edit that quietly raises the
 * false-positive rate is otherwise invisible until someone complains, by which
 * point nobody remembers what changed.
 *
 * <p>Prompts therefore live in version-controlled resource files with an
 * explicit version, not in string literals scattered through Java.
 *
 * @param callId ties this finding to the exact model call in the audit log
 * @param promptVersion which prompt produced it, e.g. "compliance-v3"
 * @param modelName     the pinned model, e.g. "gemini-3.6-flash"
 * @param calledAt      when the call completed
 */
public record FindingSource(
        UUID callId,
        String promptVersion,
        String modelName,
        Instant calledAt
) implements Serializable {

    public FindingSource {
        if (callId == null) {
            throw new IllegalArgumentException("callId is required");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "promptVersion is required - a finding you cannot trace to a prompt "
                            + "cannot be explained or rolled back");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is required");
        }
        if (calledAt == null) {
            throw new IllegalArgumentException("timestamp is required");
        }
    }
}
