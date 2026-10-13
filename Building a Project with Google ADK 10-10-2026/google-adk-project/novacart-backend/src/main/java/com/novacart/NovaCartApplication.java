package com.novacart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * NovaCart operations, built on Google ADK for Java.
 *
 * <p>Start the partner warehouse first (it is a separate project), then this, then the UI.
 */
@SpringBootApplication
public class NovaCartApplication {
    public static void main(String[] args) {
        SpringApplication.run(NovaCartApplication.class, args);
    }
}
