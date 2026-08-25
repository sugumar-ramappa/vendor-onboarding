package com.learning.onboarding.web;

import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.domain.Sku;
import com.learning.onboarding.domain.SubmittedDocument;
import com.learning.onboarding.domain.VendorApplication;
import com.learning.onboarding.intake.DocumentFacts;
import com.learning.onboarding.intake.ExtractedText;
import com.learning.onboarding.intake.ExtractionSource;
import com.learning.onboarding.intake.FactExtractor;
import com.learning.onboarding.intake.IntakeException;
import com.learning.onboarding.intake.SkuSheetParser;
import com.learning.onboarding.intake.TextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns uploaded bytes into a {@link ReviewContext}.
 *
 * <p>Separate from the controller on purpose: this is the part worth testing,
 * and it has nothing to do with HTTP. Everything here runs before a model is
 * involved at all.
 *
 * <h2>What this closes</h2>
 * The review pipeline was only ever fed pre-extracted text - from fixtures, or
 * from strings in a demo runner. The extractors were unit-tested against PDFs
 * they generated themselves. Nothing had ever carried a real file from outside
 * the process to a reviewer, which is a gap you find the hard way.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final TextExtractor textExtractor;
    private final SkuSheetParser skuSheetParser;
    private final FactExtractor factExtractor;

    public IntakeService(TextExtractor textExtractor, SkuSheetParser skuSheetParser,
                         FactExtractor factExtractor) {
        this.textExtractor = textExtractor;
        this.skuSheetParser = skuSheetParser;
        this.factExtractor = factExtractor;
    }

    /**
     * @param files    uploaded file content by original filename
     * @param skuSheet the spreadsheet, or null when SKUs came in the JSON
     * @throws IntakeException when a file is unreadable, or the declarations and
     *                         the uploads do not line up
     */
    public ReviewContext assemble(ApplicationSubmission submission,
                                  Map<String, byte[]> files, byte[] skuSheet) {

        reconcileDeclarations(submission, files);

        List<Sku> skus = skuSheet != null
                ? skuSheetParser.parse(skuSheet, "sku-sheet")
                : fromJson(submission.skus());

        if (skus.isEmpty()) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, "sku list",
                    "no SKUs - attach a spreadsheet as 'skuSheet' or list them in the JSON");
        }

        var documents = new java.util.ArrayList<SubmittedDocument>();
        var sources = new LinkedHashMap<String, ExtractionSource>();
        var facts = new HashMap<String, DocumentFacts>();

        for (var declared : submission.documents()) {
            byte[] content = files.get(declared.filename());
            ExtractedText extracted = textExtractor.extract(content, declared.filename());

            documents.add(new SubmittedDocument(declared.filename(), declared.type(),
                    extracted.fullText(), extracted.pageCount()));

            // Travels with every value the reviewers see. A page read by vision
            // cannot support a DETERMINISTIC finding and cannot be grounded by
            // substring match, because there is no source text to match against.
            sources.put(declared.filename(), extracted.source());

            facts.put(declared.filename(),
                    factExtractor.extract(extracted.fullText(), 1, extracted.source()));

            log.info("{}: {} - {} page(s), {}", submission.applicationId(),
                    declared.filename(), extracted.pageCount(), extracted.source());
        }

        var application = new VendorApplication(
                submission.applicationId(), submission.vendorName(), submission.category(),
                submission.deliveryModel(), submission.requestedGoLive(), skus, documents);

        return new ReviewContext(application, documents, facts, sources);
    }

    /**
     * Every declaration has a file and every file has a declaration.
     *
     * <p>Both directions matter, and the second is the one worth arguing for. A
     * file nobody declared is not harmless: the likeliest reason is that the
     * uploader believed they had declared it, and accepting it silently means
     * the completeness gate reports a document missing that is sitting in the
     * request. Rejecting says which one.
     */
    private static void reconcileDeclarations(ApplicationSubmission submission,
                                              Map<String, byte[]> files) {
        List<String> declaredNames = submission.documents().stream()
                .map(ApplicationSubmission.DeclaredDocument::filename).toList();

        List<String> missing = declaredNames.stream()
                .filter(name -> !files.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, String.join(", ", missing),
                    "declared but not uploaded");
        }

        List<String> undeclared = files.keySet().stream()
                .filter(name -> !declaredNames.contains(name)).toList();
        if (!undeclared.isEmpty()) {
            throw new IntakeException(IntakeException.Cause.CORRUPT,
                    String.join(", ", undeclared),
                    "uploaded but not declared - every file needs a DocumentType, "
                            + "because guessing it from the filename is wrong silently");
        }

        long distinct = declaredNames.stream().distinct().count();
        if (distinct != declaredNames.size()) {
            throw new IntakeException(IntakeException.Cause.CORRUPT, "documents",
                    "the same filename is declared twice - findings cite documents by "
                            + "name, so two would be indistinguishable in the audit trail");
        }
    }

    private static List<Sku> fromJson(List<ApplicationSubmission.SkuLine> lines) {
        if (lines == null) {
            return List.of();
        }
        return lines.stream()
                .map(l -> new Sku(l.vendorSku(), l.description(), l.gtin(),
                        l.casePack() == null ? 0 : l.casePack(),
                        l.caseWeightKg() == null ? BigDecimal.ZERO : new BigDecimal(l.caseWeightKg()),
                        Boolean.TRUE.equals(l.hazardous())))
                .toList();
    }
}
