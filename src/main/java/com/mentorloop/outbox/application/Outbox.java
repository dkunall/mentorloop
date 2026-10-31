package com.mentorloop.outbox.application;

import java.util.Map;

/**
 * Port for events that must leave the process exactly when the state change commits. Implementations write to
 * the outbox table in the caller's transaction; a relay publishes them afterwards.
 */
public interface Outbox {

    /** AttemptGraded, keyed by learner subject. Drives mastery and review scheduling. */
    String ATTEMPT_EVENTS = "mentorloop.attempt-events";

    /** ConceptMastered, CertificateIssued, keyed by learner subject. Drives notifications. */
    String LEARNER_EVENTS = "mentorloop.learner-events";

    void append(String topic, String key, String type, Map<String, Object> data);
}
