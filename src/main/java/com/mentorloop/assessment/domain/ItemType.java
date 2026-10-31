package com.mentorloop.assessment.domain;

/**
 * Supported item types. Code items are not supported: running learner code safely needs a real sandbox
 * (gVisor, Firecracker or similar), which this project does not ship. See the README.
 */
public enum ItemType {
    MCQ,
    NUMERIC,
    SHORT_ANSWER
}
