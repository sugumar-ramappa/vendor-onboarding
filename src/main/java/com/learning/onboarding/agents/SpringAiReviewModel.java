package com.learning.onboarding.agents;

import com.learning.onboarding.domain.AgentFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The real model call: Spring AI, Gemini, with the reference tools attached.
 *
 * <h2>The return type is the schema</h2>
 * {@code .entity(ReviewOutput.class)} makes Spring AI derive a JSON schema from
 * the record and instruct the model to fill it in. {@link AgentFinding} has no
 * field for approval, so the model has nowhere to put one - the guardrail is the
 * type, not an instruction that could be argued with.
 *
 * <h2>Tools, not pasted rules</h2>
 * The reference lookups are attached as tool callbacks, so the model fetches the
 * rules that apply rather than being handed every rule for every category. They
 * run over the read-only connection, so a tool call cannot change anything.
 *
 * <h2>Retry, and why the first wait is 30 seconds</h2>
 * The free tier limits requests per MINUTE. Retrying inside the same minute is
 * guaranteed to fail again - the window has to roll over first. A conventional
 * 1-second backoff therefore burns three attempts achieving nothing and reports
 * the reviewer as failed, which forces the whole application to a human.
 *
 * <p>This cost a day on the retrieval project in this workspace before the
 * distinction was understood, so it is worth stating plainly: <b>a per-minute
 * limit is not a transient error, it is a scheduled one.</b>
 */
@Component
public class SpringAiReviewModel implements ReviewModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiReviewModel.class);

    /**
     * The wrapper Spring AI binds to.
     *
     * <p>A list on its own is awkward to bind and gives the model nowhere to
     * report that it looked and found nothing. An explicit empty list in a
     * wrapper is a clearer answer than an absent one.
     */
    public record ReviewOutput(List<AgentFinding> findings) {
        public ReviewOutput {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    private final ChatClient chat;
    private final ToolCallback[] tools;
    private final String modelName;
    private final int maxAttempts;
    private final long firstBackoffMs;

    public SpringAiReviewModel(
            ChatClient.Builder builder,
            ToolCallback[] referenceDataCallbacks,
            @Value("${spring.ai.google.genai.chat.options.model}") String modelName,
            @Value("${onboarding.model.max-attempts:3}") int maxAttempts,
            @Value("${onboarding.model.first-backoff-ms:35000}") long firstBackoffMs) {
        this.chat = builder.build();
        this.tools = referenceDataCallbacks;
        this.modelName = modelName;
        this.maxAttempts = maxAttempts;
        this.firstBackoffMs = firstBackoffMs;
    }

    @Override
    public List<AgentFinding> review(String systemPrompt, String userPrompt) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ReviewOutput output = chat.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .toolCallbacks(tools)
                        .call()
                        .entity(ReviewOutput.class);

                return output == null ? List.of() : output.findings();

            } catch (RuntimeException e) {
                last = e;

                if (!isRetryable(e) || attempt == maxAttempts) {
                    break;
                }
                // 35s, 70s. Deliberately past a minute on the second attempt:
                // the per-minute window has to roll over, and waiting less than
                // that is a guaranteed second failure.
                long wait = firstBackoffMs * attempt;
                log.warn("model call failed (attempt {}/{}), waiting {}ms: {}",
                        attempt, maxAttempts, wait, e.getMessage());
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Never degrade to an empty list. An empty list means the reviewer
        // looked and found nothing; this means nobody looked, and the caller
        // has to escalate rather than record a clean review.
        // The original message carries the quota id, which is what
        // distinguishes a per-minute limit from a daily one. Losing it here
        // would make every failure look identical in the audit trail.
        throw new ReviewModelException(
                "model could not complete the review: "
                        + (last == null ? "unknown" : last.getMessage()), last);
    }

    /**
     * Whether waiting could plausibly help.
     *
     * <p>A daily quota clears at midnight, not in a minute, so retrying it just
     * burns wall clock to fail again. Same distinction the retrieval project
     * needed, and for the same reason.
     */
    private static boolean isRetryable(RuntimeException e) {
        String text = describe(e).toLowerCase();

        if (text.contains("perday") || text.contains("per day")
                || text.contains("daily limit")) {
            return false;
        }
        return text.contains("429") || text.contains("rate")
                || text.contains("quota") || text.contains("resource_exhausted")
                || text.contains("unavailable") || text.contains("timeout")
                || text.contains("503") || text.contains("deadline");
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            sb.append(current.getMessage()).append(' ');
        }
        return sb.toString();
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
