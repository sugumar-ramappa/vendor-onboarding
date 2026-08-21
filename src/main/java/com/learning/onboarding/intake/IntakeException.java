package com.learning.onboarding.intake;

/**
 * A submitted file could not be turned into text.
 *
 * <p>Typed causes rather than a bare message, because the caller has to react
 * differently to each: an oversized file is a vendor problem to bounce back, an
 * encrypted one needs a password request, and a corrupt one may just need
 * re-uploading.
 *
 * <p>None of these are ever swallowed. A document that cannot be read must not
 * be silently skipped - a missing certificate and an unreadable certificate look
 * identical downstream, and only one of them is the vendor's fault.
 */
public class IntakeException extends RuntimeException {

    public enum Cause {
        /** Beyond the configured size limit. */
        TOO_LARGE,
        /** Password protected - we cannot read it and will not guess. */
        ENCRYPTED,
        /** Not a format we handle. */
        UNSUPPORTED_FORMAT,
        /** Claims to be a PDF but will not parse. */
        CORRUPT,
        /**
         * Parsed, but produced no text - almost always a scan.
         * Escalates rather than passing as an empty document.
         */
        NO_TEXT_LAYER
    }

    private final Cause cause;
    private final String documentId;

    public IntakeException(Cause cause, String documentId, String detail) {
        super("%s: %s (%s)".formatted(cause, documentId, detail));
        this.cause = cause;
        this.documentId = documentId;
    }

    public Cause reason() {
        return cause;
    }

    public String documentId() {
        return documentId;
    }
}
