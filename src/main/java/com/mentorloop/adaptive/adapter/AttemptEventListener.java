package com.mentorloop.adaptive.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.adaptive.application.PracticeEvent;
import com.mentorloop.adaptive.application.PracticeService;
import com.mentorloop.assessment.application.AssessmentEvents;
import com.mentorloop.outbox.application.EventEnvelope;
import com.mentorloop.outbox.application.Outbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "mentorloop.jobs.enabled", havingValue = "true", matchIfMissing = true)
class AttemptEventListener {

    private final PracticeService practice;
    private final ObjectMapper json;

    AttemptEventListener(PracticeService practice, ObjectMapper json) {
        this.practice = practice;
        this.json = json;
    }

    // One thread per partition. Events are keyed by learner, so a learner's events stay on one thread in order;
    // threads only ever touch different learners' rows.
    @KafkaListener(topics = Outbox.ATTEMPT_EVENTS, groupId = "mentorloop-adaptive", concurrency = "3")
    void on(String payload) throws IOException {
        EventEnvelope e = json.readValue(payload, EventEnvelope.class);
        switch (e.type()) {
            case AssessmentEvents.ATTEMPT_RECORDED -> practice.recorded(event(e.data()));
            case AssessmentEvents.ATTEMPT_REGRADED -> practice.regraded(e.eventId(), event(e.data()));
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    static PracticeEvent event(Map<String, Object> d) {
        List<UUID> concepts = ((List<String>) d.get("conceptIds")).stream().map(UUID::fromString).toList();
        return new PracticeEvent(UUID.fromString((String) d.get("attemptId")), UUID.fromString((String) d.get("courseId")),
                (String) d.get("learner"), ((Number) d.get("seq")).longValue(), UUID.fromString((String) d.get("itemId")),
                concepts, "GRADED".equals(d.get("outcome")), Boolean.TRUE.equals(d.get("correct")),
                ((Number) d.get("latencyMs")).longValue(), Instant.parse((String) d.get("at")));
    }
}
