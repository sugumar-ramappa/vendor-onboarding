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
 * <p>Two things happen here that are worth naming.
 *
 * <h2>The return type is the schema</h2>
 * {@code .entity(ReviewOutput.class)} makes Spring AI derive a JSON schema from
 * the record and instruct the model to fill it in. {@link AgentFinding} has no
 * field for approval, so the model has nowhere to put one - the guardrail is the
 * type, not an instruction that could be argued with.
 *
 * <p>It also means a malformed response is a binding failure rather than
 * something to salvage with string handling.
 *
 * <h2>Tools, not pasted rules</h2>
 * The reference lookups are attached as tool callbacks, so the model fetches the
 * rules that apply rather than being handed every rule for every category. They
 * run over the read-only connection, so a tool call cannot change anything.
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

    public SpringAiReviewModel(ChatClient.Builder builder,
                               ToolCallback[] referenceDataCallbacks,
                               @Value("${spring.ai.google.genai.chat.options.model}") String modelName) {
        this.chat = builder.build();
        this.tools = referenceDataCallbacks;
        this.modelName = modelName;
    }

    @Override
    public List<AgentFinding> review(String systemPrompt, String userPrompt) {
        try {
            ReviewOutput output = chat.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .toolCallbacks(tools)
                    .call()
                    .entity(ReviewOutput.class);

            return output == null ? List.of() : output.findings();

        } catch (RuntimeException e) {
            // Never degrade to an empty list. An empty list means the reviewer
            // looked and found nothing; this means nobody looked, and the
            // caller has to escalate rather than record a clean review.
            log.warn("review call failed: {}", e.getMessage());
            throw new ReviewModelException("model could not complete the review", e);
        }
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
