package com.learning.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VendorOnboardingApplication {

    public static void main(String[] args) {
        // PDF page rendering uses AWT. Without this, macOS launches a Java
        // application icon in the dock for a headless web service.
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(VendorOnboardingApplication.class, args);
    }
}
