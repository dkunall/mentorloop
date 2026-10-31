package com.mentorloop.notification.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.notification.application.Notifier;
import com.mentorloop.outbox.application.EventEnvelope;
import com.mentorloop.outbox.application.Outbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "mentorloop.jobs.enabled", havingValue = "true", matchIfMissing = true)
class NotificationAdapters {

    private static final Logger log = LoggerFactory.getLogger(NotificationAdapters.class);

    private final Notifier notifier;
    private final ObjectMapper json;
    private final MeterRegistry meters;

    NotificationAdapters(Notifier notifier, ObjectMapper json, MeterRegistry meters) {
        this.notifier = notifier;
        this.json = json;
        this.meters = meters;
    }

    @KafkaListener(topics = Outbox.LEARNER_EVENTS, groupId = "mentorloop-notifier")
    void on(String payload) throws IOException {
        EventEnvelope e = json.readValue(payload, EventEnvelope.class);
        if (notifier.handle(e)) {
            meters.counter("mentorloop.notifications.sent", "type", e.type()).increment();
        }
    }

    @Scheduled(fixedDelayString = "${mentorloop.reviews.reminder-every}", initialDelayString = "PT20S")
    void remind() {
        try {
            int n = notifier.remind();
            meters.counter("mentorloop.notifications.sent", "type", "ReviewReminder").increment(n);
        } catch (RuntimeException e) {
            log.warn("reminder run failed, next slot retries: {}", e.toString());
        }
    }
}
