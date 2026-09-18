package com.braintwinx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * BrainTwinX application API entry point.
 *
 * <p>BrainTwinX is an AI-assisted brain MRI analysis platform for research and
 * clinical decision support. It is <strong>not</strong> a diagnostic device: every
 * result it produces is a model output requiring review by a qualified healthcare
 * professional.
 *
 * <p>JPA auditing is enabled so {@code createdAt} / {@code updatedAt} are populated
 * consistently by the persistence layer rather than by each service, which removes a
 * class of bug where a timestamp is silently forgotten on a new write path.
 */
@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
public class BrainTwinXApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrainTwinXApplication.class, args);
    }
}
