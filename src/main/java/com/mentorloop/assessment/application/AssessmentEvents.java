package com.mentorloop.assessment.application;

import com.mentorloop.assessment.application.AttemptStore.Attempt;
import com.mentorloop.outbox.application.Outbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Event types on {@link Outbox#ATTEMPT_EVENTS}, keyed by learner so one learner's events stay in order. */
public final class AssessmentEvents {

    /** Every attempt, in per-learner seq order. NEEDS_REVIEW attempts are sent too so the sequence has no gaps. */
    public static final String ATTEMPT_RECORDED = "AttemptRecorded";

    /** An instructor graded an attempt that was NEEDS_REVIEW. Arrives after later attempts by design. */
    public static final String ATTEMPT_REGRADED = "AttemptRegraded";

    private AssessmentEvents() {
    }

    static Map<String, Object> payload(Attempt a, List<UUID> conceptIds) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attemptId", a.id().toString());
        m.put("courseId", a.courseId().toString());
        m.put("learner", a.learner());
        m.put("seq", a.seq());
        m.put("itemId", a.itemId().toString());
        m.put("conceptIds", conceptIds.stream().map(UUID::toString).toList());
        m.put("outcome", a.grade().outcome().name());
        m.put("correct", a.grade().correct());
        m.put("latencyMs", a.latencyMs());
        m.put("at", a.createdAt().toString());
        return m;
    }
}
