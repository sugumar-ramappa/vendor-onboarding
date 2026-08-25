package com.learning.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} is not implied by
 * {@code @SpringBootApplication}. Without it a {@code @ConfigurationProperties}
 * record is annotated, validated, documented - and never registered, so the
 * first bean to ask for it fails at startup rather than at compile time.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VendorOnboardingApplication {

    public static void main(String[] args) {
        // PDF page rendering uses AWT. Without this, macOS launches a Java
        // application icon in the dock for a headless web service.
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(VendorOnboardingApplication.class, args);
    }
}
