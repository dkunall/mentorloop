package com.mentorloop.assessment.adapter;

import com.mentorloop.assessment.application.QuizStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcQuizStore implements QuizStore {

    private static final RowMapper<Quiz> QUIZ = (rs, n) -> {
        Timestamp sub = rs.getTimestamp("submitted_at");
        Object score = rs.getObject("score");
        return new Quiz(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class), rs.getString("learner"),
                JdbcItemStore.uuids(rs.getArray("item_ids")), Status.valueOf(rs.getString("status")),
                rs.getTimestamp("opened_at").toInstant(), rs.getTimestamp("closes_at").toInstant(),
                sub == null ? null : sub.toInstant(), score == null ? null : ((Number) score).doubleValue());
    };

    private final JdbcClient db;

    JdbcQuizStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insert(Quiz q) {
        db.sql("""
                insert into quiz (id, course_id, learner, item_ids, status, opened_at, closes_at)
                values (?, ?, ?, ?::uuid[], ?, ?, ?)""")
                .params(q.id(), q.courseId(), q.learner(), JdbcItemStore.pgArray(q.itemIds()), q.status().name(),
                        Timestamp.from(q.openedAt()), Timestamp.from(q.closesAt())).update();
    }

    @Override
    public Optional<Quiz> open(UUID courseId, String learner) {
        return db.sql("select * from quiz where course_id = ? and learner = ? and status = 'OPEN'")
                .params(courseId, learner).query(QUIZ).optional();
    }

    @Override
    public Optional<Quiz> lock(UUID id) {
        return db.sql("select * from quiz where id = ? for update").param(id).query(QUIZ).optional();
    }

    @Override
    public Optional<Quiz> byId(UUID id) {
        return db.sql("select * from quiz where id = ?").param(id).query(QUIZ).optional();
    }

    @Override
    public void close(UUID id, Status status, Instant at, Double score) {
        db.sql("update quiz set status = ?, submitted_at = ?, score = ? where id = ? and status = 'OPEN'")
                .params(status.name(), Timestamp.from(at), score, id).update();
    }

    @Override
    public java.util.List<Quiz> lockOverdue(Instant now, int limit) {
        return db.sql("select * from quiz where status = 'OPEN' and closes_at <= ? order by closes_at limit ? for update skip locked")
                .params(Timestamp.from(now), limit).query(QUIZ).list();
    }
}
