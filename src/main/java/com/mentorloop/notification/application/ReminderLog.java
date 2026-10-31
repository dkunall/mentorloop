package com.mentorloop.notification.application;

import java.time.Instant;
import java.util.UUID;

public interface ReminderLog {

    /** @return false if this learner already got a reminder for this course in this slot */
    boolean claim(UUID courseId, String learner, long slot, Instant at);
}
