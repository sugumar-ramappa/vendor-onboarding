package com.learning.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Step 0: prove the stack works before building anything on it.
 *
 * <p>Run with:  {@code mvn spring-boot:run -Dspring-boot.run.profiles=stackcheck}
 *
 * <p>Answers three questions that are cheap now and expensive later:
 * does Spring Boot 4.1 start with Spring AI 2.0 on the classpath, is the Gemini
 * key wired correctly, and does structured output work? Everything after this
 * assumes all three.
 *
 * <p>Guarded by a profile so it does not fire on every start - a model call on
 * each boot burns free-tier quota for nothing.
 */
@Component
@Profile("stackcheck")
public class StackCheck implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StackCheck.class);

    private final ChatClient chat;

    /**
     * Spring AI auto-configures a {@code ChatClient.Builder} from the starter on
     * the classpath plus the properties in application.yml. Injecting the builder
     * rather than a finished client is the idiomatic form: each caller adds its
     * own defaults - and later, its own advisors, which is where the guardrails
     * will attach.
     */
    public StackCheck(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    /** Structured output target. The record IS the schema Spring AI sends. */
    public record Verdict(String certificateScheme, String expiryDate, boolean expired) {}

    @Override
    public void run(String... args) {
        log.info("=== step 0: stack check ===");

        // 1. Plain text call - does the key work at all?
        String plain = chat.prompt()
                .user("Reply with exactly: stack ok")
                .call()
                .content();
        log.info("plain text call  -> {}", plain);

        // 2. Structured output - the mechanism every agent will use.
        //
        // .entity(Verdict.class) is the whole feature: Spring AI derives a JSON
        // schema from the record, instructs the model to fill it, and binds the
        // response back. If this works, typed agents work.
        Verdict v = chat.prompt()
                .user("""
                      Extract the certificate details from this text.

                      "Electrical Safety Certificate, scheme EN 62841,
                       valid until 12 April 2026."

                      Today is 21 August 2026.
                      """)
                .call()
                .entity(Verdict.class);

        log.info("structured call  -> scheme={} expiry={} expired={}",
                v.certificateScheme(), v.expiryDate(), v.expired());

        log.info("=== stack check passed ===");
    }
}
