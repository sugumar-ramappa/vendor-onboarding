package com.learning.onboarding.web;

import com.learning.onboarding.ReviewService;
import com.learning.onboarding.agents.ReviewContext;
import com.learning.onboarding.intake.IntakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The HTTP boundary only. Intake and reviewing are stubbed - what is being
 * checked is that a multipart request binds, that a rejected file comes back as
 * 400 rather than 500, and that a repeat submission is distinguishable from a
 * first one.
 */
@WebMvcTest(ApplicationController.class)
class ApplicationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IntakeService intake;

    @MockitoBean
    private ReviewService reviews;

    private static final String SUBMISSION = """
            {
              "applicationId": "APP-2026-0113",
              "vendorName": "Acme Tools Ltd",
              "category": "POWER_TOOLS",
              "deliveryModel": "DISTRIBUTION_CENTRE",
              "requestedGoLive": "2026-12-01T00:00:00Z",
              "documents": [
                { "filename": "elec-cert.pdf", "type": "ELECTRICAL_SAFETY_CERTIFICATE" }
              ]
            }
            """;

    private static MockMultipartFile submissionPart(String json) {
        return new MockMultipartFile("submission", "", MediaType.APPLICATION_JSON_VALUE,
                json.getBytes());
    }

    private static MockMultipartFile filePart(String name) {
        return new MockMultipartFile("files", name, MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.4 pretend".getBytes());
    }

    @Test
    @DisplayName("a submission is accepted and reviewed")
    void acceptsASubmission() throws Exception {
        when(intake.assemble(any(), any(), any())).thenReturn(mock(ReviewContext.class));
        when(reviews.submit(any()))
                .thenReturn(new ReviewService.Submission("APP-2026-0113", "abc123", true, 1));

        mvc.perform(multipart("/applications")
                        .file(submissionPart(SUBMISSION))
                        .file(filePart("elec-cert.pdf")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applicationId").value("APP-2026-0113"))
                .andExpect(jsonPath("$.findingCount").value(1))
                .andExpect(jsonPath("$.reviewed").value(true));
    }

    @Test
    @DisplayName("resubmitting the same pack returns 200, not 201")
    void repeatSubmissionIsDistinguishable() throws Exception {
        when(intake.assemble(any(), any(), any())).thenReturn(mock(ReviewContext.class));
        when(reviews.submit(any()))
                .thenReturn(new ReviewService.Submission("APP-2026-0113", "abc123", false, 1));

        // Nothing was created and no model call was made. A caller retrying
        // after a timeout needs to be able to tell that apart from a fresh run.
        mvc.perform(multipart("/applications")
                        .file(submissionPart(SUBMISSION))
                        .file(filePart("elec-cert.pdf")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewed").value(false));
    }

    @Test
    @DisplayName("an unreadable file is 400 with the cause, not 500")
    void intakeFailureIsTheCallersProblem() throws Exception {
        when(intake.assemble(any(), any(), any())).thenThrow(new IntakeException(
                IntakeException.Cause.ENCRYPTED, "elec-cert.pdf", "password protected"));

        // The distinction that matters: the vendor's file is theirs to fix.
        // A 500 sends someone to read our logs about someone else's problem.
        mvc.perform(multipart("/applications")
                        .file(submissionPart(SUBMISSION))
                        .file(filePart("elec-cert.pdf")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INTAKE_ENCRYPTED"))
                .andExpect(jsonPath("$.file").value("elec-cert.pdf"));

        verify(reviews, never()).submit(any());
    }

    @Test
    @DisplayName("a submission missing its category is rejected before any file is read")
    void validationRunsFirst() throws Exception {
        String noCategory = SUBMISSION.replace("\"category\": \"POWER_TOOLS\",", "");

        mvc.perform(multipart("/applications")
                        .file(submissionPart(noCategory))
                        .file(filePart("elec-cert.pdf")))
                .andExpect(status().isBadRequest());

        // Category selects the compliance rules. Reviewing without it would
        // check the wrong rulebook rather than fail.
        verify(intake, never()).assemble(any(), any(), any());
    }

    @Test
    @DisplayName("a path in the filename is stripped at the boundary")
    void filenameIsSanitised() throws Exception {
        when(intake.assemble(any(), any(), any())).thenReturn(mock(ReviewContext.class));
        when(reviews.submit(any()))
                .thenReturn(new ReviewService.Submission("APP-2026-0113", "abc", true, 0));

        mvc.perform(multipart("/applications")
                        .file(submissionPart(SUBMISSION))
                        .file(new MockMultipartFile("files", "../../etc/elec-cert.pdf",
                                MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4".getBytes())))
                .andExpect(status().isCreated());

        // It is only ever a map key and a label in a citation, never a path -
        // but stripping at the boundary beats relying on every later use
        // staying harmless.
        verify(intake).assemble(any(), argThat((Map<String, byte[]> m) ->
                m.containsKey("elec-cert.pdf") && m.size() == 1), any());
    }
}
