package com.learning.onboarding.agents;

import com.learning.onboarding.domain.Evidence;
import com.learning.onboarding.domain.EvidenceNeed;
import com.learning.onboarding.domain.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The verifier's model call.
 *
 * <p>Gets the same reference tools as the reviewers, deliberately. A finding
 * claiming a standard is unacceptable is refuted if that standard turns out to
 * be in the accepted list - and the verifier cannot know that without looking it
 * up. A challenger working from memory would be guessing at exactly the point
 * where being right matters.
 *
 * <h2>Why the response is a separate record from Verdict</h2>
 * {@link Verdict} throws when its reason is blank, which is correct for the
 * domain and wrong for a binding target: a model that returns an empty reason
 * would fail the whole response rather than producing a usable "could not
 * decide". So the model binds to a permissive shape, and the conversion into a
 * {@link Verdict} applies the domain rule - defaulting to the finding standing.
 */
@Component
public class SpringAiVerifierModel implements VerifierAgent.VerifierModel {

    private static final Logger log = LoggerFactory.getLogger(SpringAiVerifierModel.class);

    /**
     * What the model is asked to fill in.
     *
     * <p>Note what is absent: no severity, no ability to modify the finding, no
     * field for a replacement claim. The verifier can refute or not. It cannot
     * rewrite another reviewer's work.
     */
    public record Challenge(boolean disproved, boolean unresolved, String reason,
                            List<Evidence> evidence, String needsEvidenceFor,
                            List<EvidenceNeed> needs) {}

    private final ChatClient chat;
    private final ToolCallback[] tools;
    private final String modelName;

    public SpringAiVerifierModel(
            ChatClient.Builder builder,
            ToolCallback[] referenceDataCallbacks,
            // Provider-agnostic, matching SpringAiReviewModel. This read
            // spring.ai.google.genai.* and therefore reported the GEMINI model
            // name for findings a Groq model had verified. The reviewer had the
            // same bug and it was fixed there; this sibling was missed, which is
            // the recurring shape of this project's defects - a fix applied to
            // one class and not to the one beside it.
            @Value("${onboarding.model.name}") String modelName,
            // TOOLS OFF FOR THE MEASUREMENT, and this is the fix that matters.
            //
            // The verifier attached tool callbacks unconditionally, ignoring the
            // profile that switches them off for the reviewers. Three
            // consequences, in order of how badly they hurt:
            //
            // 1. A tool call is a SECOND billed request, which is the entire
            //    cost the measurement's pre-resolved rulebooks exist to avoid.
            //
            // 2. A tool call makes the exchange MULTI-TURN: assistant message
            //    with the call, tool result, assistant again. gpt-oss-120b is a
            //    reasoning model, so its assistant message carries
            //    `reasoning_content` - and Groq REFUSES that field on input:
            //
            //      400: 'messages.2' property 'reasoning_content' is unsupported
            //
            //    The model emits a field the same API rejects when echoed back.
            //    Eleven verifications failed this way, which silently gutted
            //    configuration 3 while every fixture still reported complete.
            //
            // 3. Single-turn removes the problem entirely rather than working
            //    around it by stripping fields from a conversation.
            @Value("${onboarding.model.tools-enabled:true}") boolean toolsEnabled) {
        this.chat = builder.build();
        this.tools = toolsEnabled ? referenceDataCallbacks : new ToolCallback[0];
        this.modelName = modelName;
    }

    @Override
    public Verdict challenge(String systemPrompt, String userPrompt) {
        Challenge response = chat.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .toolCallbacks(tools)
                .call()
                .entity(Challenge.class);

        if (response == null) {
            // Nothing came back. The finding stands - see VerifierAgent for why
            // the default runs this way round in this domain.
            return Verdict.survives("no response from the verifier");
        }

        // Checked before disproved: a response claiming both is confused, and
        // the safe reading of a confused refutation is that it did not refute.
        if (response.unresolved()) {
            String question = blankToDefault(response.needsEvidenceFor(), "");
            List<EvidenceNeed> needs =
                    response.needs() == null ? List.of() : response.needs();

            if (needs.isEmpty()) {
                // Undecided with nothing named. Honest, and not actionable -
                // there is no lookup to run, so no pass to spend.
                log.debug("verifier undecided without naming what it needs");
                return Verdict.unresolved(
                        blankToDefault(response.reason(), "could not decide"), question);
            }
            return Verdict.unresolved(
                    blankToDefault(response.reason(), "could not decide"), question, needs);
        }

        if (!response.disproved()) {
            return Verdict.survives(
                    blankToDefault(response.reason(), "could not refute this finding"));
        }

        if (response.evidence() == null || response.evidence().isEmpty()) {
            // A refutation with no evidence is an opinion. The finding was
            // raised with a citation and can only be removed with one.
            log.warn("verifier refuted a finding without evidence - keeping it");
            return Verdict.survives(
                    "refutation offered without evidence, so the finding stands: "
                            + blankToDefault(response.reason(), "no reason given"));
        }

        return Verdict.disproved(
                blankToDefault(response.reason(), "refuted"), response.evidence());
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
