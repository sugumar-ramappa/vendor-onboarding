package com.learning.onboarding.intake;

/**
 * Reads text off a page image, for documents that have no text layer.
 *
 * <p>An interface rather than a class so the rest of intake can be tested
 * without an API key or a network. Extraction is the one place where a stub is
 * clearly right: the tests care that a scan takes the vision path and is tagged
 * {@link ExtractionSource#MODEL_VISION}, not that a particular model reads a
 * particular JPEG correctly.
 */
public interface ScannedPageReader {

    /**
     * @param pngImage   the rendered page
     * @param documentId for logging and error messages only
     * @param page       1-based page number
     * @return the transcribed text, or empty if the page genuinely has none
     */
    String transcribe(byte[] pngImage, String documentId, int page);

    /** Used when vision is switched off: scans are then rejected outright. */
    ScannedPageReader UNAVAILABLE = (image, documentId, page) -> {
        throw new IntakeException(IntakeException.Cause.NO_TEXT_LAYER, documentId,
                "page %d has no text layer and vision reading is disabled".formatted(page));
    };
}
