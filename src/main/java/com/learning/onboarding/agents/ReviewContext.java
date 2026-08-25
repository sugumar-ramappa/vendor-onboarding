package com.learning.onboarding.agents;

import java.io.Serializable;
import com.learning.onboarding.domain.SubmittedDocument;
import com.learning.onboarding.domain.VendorApplication;
import com.learning.onboarding.intake.DocumentFacts;
import com.learning.onboarding.intake.ExtractionSource;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Everything a reviewer is given, and nothing else.
 *
 * <p>The omission is the design. There is no field here for other reviewers'
 * findings, no running conclusion, no shared scratchpad. A compliance reviewer
 * that could see the quality reviewer's blocking finding would read the rest of
 * the pack through it - which is precisely the anchoring that having five
 * reviewers is meant to avoid.
 *
 * <p>That isolation is enforced twice: by this type having nowhere to put such
 * data, and by the agent database role having no SELECT on {@code
 * review_finding}. One is a design decision and the other is a permission, and
 * the permission is the one that survives someone editing this class.
 *
 * @param application the vendor and what they want to supply
 * @param documents   the submitted pack, as extracted text
 * @param facts       what was parsed deterministically, keyed by document id
 * @param sources     how each document's text was obtained, keyed by document id
 */
public record ReviewContext(
        VendorApplication application,
        List<SubmittedDocument> documents,
        Map<String, DocumentFacts> facts,
        Map<String, ExtractionSource> sources
) implements Serializable {

    public ReviewContext {
        documents = documents == null ? List.of() : List.copyOf(documents);
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    public String applicationId() {
        return application.applicationId();
    }

    /**
     * Builds the user message.
     *
     * <p>Order matters. The application and the extracted facts come first,
     * because they are ours and trustworthy. The vendor's documents come last,
     * each wrapped by {@link Spotlight}, so the untrusted material never sits
     * above the instructions it might try to override.
     */
    public String render() {
        StringBuilder sb = new StringBuilder();

        sb.append("APPLICATION\n")
          .append("  id: ").append(application.applicationId()).append('\n')
          .append("  vendor: ").append(application.vendorName()).append('\n')
          .append("  category: ").append(application.category()).append('\n')
          .append("  delivery model: ").append(application.deliveryModel()).append('\n')
          .append("  requested go-live: ")
          .append(LocalDate.ofInstant(application.requestedGoLive(), ZoneOffset.UTC))
          .append('\n');

        sb.append("\nSKUS APPLIED FOR (").append(application.skus().size()).append(")\n");
        application.skus().forEach(sku -> sb
                .append("  ").append(sku.vendorSku())
                .append(" | ").append(sku.description())
                .append(" | case pack ").append(sku.casePack())
                .append(" | ").append(sku.caseWeightKg()).append("kg")
                .append(sku.hazardous() ? " | HAZARDOUS" : "")
                .append('\n'));

        // Extracted facts before the documents: these were parsed by code, and
        // the prompt tells the reviewer to prefer them over its own reading.
        sb.append("\nFACTS EXTRACTED BY CODE (trust these over your own reading)\n");
        if (facts.isEmpty()) {
            sb.append("  none\n");
        } else {
            // Iterate the DOCUMENT LIST, not the facts map.
            //
            // facts is a Map, and iterating it put this section in a different
            // order on different JVM runs. The content was identical; only the
            // order moved. That is invisible to a reader and fatal to a cache
            // keyed on the rendered prompt - every run produced a new key, so
            // nothing was ever reused and each day re-paid for the previous
            // day's work against a 20-request quota.
            //
            // documents is a List, so this is stable by construction and no
            // longer depends on which Map implementation a caller happened to
            // build.
            documents.stream()
                    .map(SubmittedDocument::documentId)
                    .filter(facts::containsKey)
                    .forEach(documentId -> {
                DocumentFacts f = facts.get(documentId);
                sb.append("  ").append(documentId).append(":\n");
                f.expiry().ifPresent(e -> sb
                        .append("    expiry: ").append(e.value())
                        .append("   (from \"").append(e.sourceQuote()).append("\")\n"));
                f.issued().ifPresent(e -> sb
                        .append("    issued: ").append(e.value()).append('\n'));
                if (!f.standards().isEmpty()) {
                    sb.append("    standards referenced: ");
                    f.standards().forEach(s -> sb.append(s.value()).append("  "));
                    sb.append('\n');
                }
                f.largestAmount().ifPresent(a -> sb
                        .append("    largest amount: ").append(a.value()).append('\n'));
            });
        }

        sb.append("\nDOCUMENTS SUBMITTED BY THE VENDOR\n\n");
        for (SubmittedDocument doc : documents) {
            sb.append("--- ").append(doc.documentId())
              .append("  (vendor says: ").append(doc.type()).append(")\n");
            sb.append(Spotlight.wrap(
                    doc.documentId(),
                    sources.getOrDefault(doc.documentId(), ExtractionSource.NATIVE_TEXT),
                    doc.text()));
            sb.append('\n');
        }
        return sb.toString();
    }
}
