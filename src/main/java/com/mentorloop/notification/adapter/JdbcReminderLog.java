package com.mentorloop.notification.adapter;

import com.mentorloop.notification.application.ReminderLog;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
class JdbcReminderLog implements ReminderLog {

    private final JdbcClient db;

    JdbcReminderLog(JdbcClient db) {
        this.db = db;
    }

    @Override
    public boolean claim(UUID courseId, String learner, long slot, Instant at) {
        return db.sql("""
                insert into reminder_sent (course_id, learner, slot, sent_at) values (?, ?, ?, ?)
                on conflict do nothing""").params(courseId, learner, slot, Timestamp.from(at)).update() == 1;
    }
}
