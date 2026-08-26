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
            // Provider-agnostic, and it has to be: this string goes into the
            // cache key and is recorded against every finding. Reading it from
            // spring.ai.google.genai.* meant that pointing the application at a
            // different provider would have kept reporting - and CACHING under -
            // the Gemini model name, so a Groq run would have been served
            // Gemini's findings with nothing in the output looking wrong.
            //
            // That is the failure this project's cache design is explicitly
            // built to prevent, and the property reference was quietly the one
            // place it could still happen.
            @Value("${onboarding.model.name}") String modelName,
            @Value("${onboarding.model.max-attempts:3}") int maxAttempts,
            @Value("${onboarding.model.first-backoff-ms:35000}") long firstBackoffMs,
            @Value("${onboarding.model.tools-enabled:true}") boolean toolsEnabled) {
        this.chat = builder.build();
        // Off only for the measurement, where the rulebook is pre-resolved into
        // the prompt. Leaving tools available alongside it would let the model
        // fetch what it already has - and every tool call is a second API
        // request, which is the cost the pre-resolution exists to avoid.
        this.tools = toolsEnabled ? referenceDataCallbacks : new ToolCallback[0];
        this.modelName = modelName;
        this.maxAttempts = maxAttempts;
        this.firstBackoffMs = firstBackoffMs;
    }

    @Override
    public ModelReply review(String systemPrompt, String userPrompt) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // responseEntity, not entity. Both return the parsed object;
                // only this one also hands back the ChatResponse, which is where
                // the provider's usage block lives. Asking for the entity alone
                // discarded the token counts before anything could record them -
                // which is why audit_entry.prompt_tokens had been null since the
                // first migration.
                var response = chat.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .toolCallbacks(tools)     // empty array when disabled
                        .call()
                        .responseEntity(ReviewOutput.class);

                ReviewOutput output = response.entity();
                return new ModelReply(
                        output == null ? List.of() : output.findings(),
                        usageOf(response.response()));

            } catch (RuntimeException e) {
                last = e;

                if (!isRetryable(e) || attempt == maxAttempts) {
                    break;
                }
                // 35s, 70s. Deliberately past a minute on the second attempt:
                // the per-minute window has to roll over, and waiting less than
                // that is a guaranteed second failure.
                long wait = firstBackoffMs * attempt;
                // describe(), not getMessage(). The retry DECISION is made on
                // the whole cause chain, so logging only the outer message
                // shows a line that cannot explain the behaviour beside it -
                // "Failed to generate content" tells you nothing about why
                // waiting 35 seconds was thought to help.
                log.warn("model call failed (attempt {}/{}), waiting {}ms: {}",
                        attempt, maxAttempts, wait, describe(e).trim());
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
        // describe(), for the same reason as above and one more: the quota id
        // that distinguishes a per-minute limit from a daily one lives in a
        // NESTED cause. getMessage() here returned the wrapper's text, so every
        // failure looked identical in the audit trail - which is the opposite
        // of what this class exists to provide.
        throw new ReviewModelException(
                "model could not complete the review: "
                        + (last == null ? "unknown" : describe(last).trim()), last);
    }

    /**
     * Whether waiting could plausibly help.
     *
     * <p>A daily quota clears at midnight, not in a minute, so retrying it just
     * burns wall clock to fail again. Same distinction the retrieval project
     * needed, and for the same reason.
     */
    /**
     * Pull the provider's own token counts out of a response.
     *
     * <p>Defensive at every step, deliberately: usage is optional in the Spring
     * AI contract, providers differ on whether they populate it, and a null here
     * must degrade to "not reported" rather than fail a review that already
     * succeeded. Instrumentation that can break the thing it measures is worse
     * than no instrumentation.
     */
    private static TokenUsage usageOf(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return TokenUsage.unknown();
        }
        var usage = response.getMetadata().getUsage();
        if (usage == null) {
            return TokenUsage.unknown();
        }
        return new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }

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
