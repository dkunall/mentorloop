package com.mentorloop.assessment.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.assessment.application.AttemptStore;
import com.mentorloop.assessment.domain.Answer;
import com.mentorloop.assessment.domain.Grade;
import com.mentorloop.assessment.domain.RaschCalibrator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcAttemptStore implements AttemptStore {

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RowMapper<Attempt> mapper;

    JdbcAttemptStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.mapper = (rs, n) -> {
            Timestamp graded = rs.getTimestamp("graded_at");
            Answer answer;
            try {
                answer = json.readValue(rs.getString("answer"), Answer.class);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Attempt(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class),
                    rs.getString("learner"), rs.getObject("item_id", UUID.class), rs.getString("client_attempt_id"),
                    rs.getString("request_hash"), rs.getLong("seq"), rs.getObject("quiz_id", UUID.class), answer,
                    rs.getLong("latency_ms"), new Grade(Grade.Outcome.valueOf(rs.getString("outcome")),
                    rs.getBoolean("correct"), rs.getDouble("score")), rs.getTimestamp("created_at").toInstant(),
                    graded == null ? null : graded.toInstant(), rs.getString("graded_by"));
        };
    }

    @Override
    public void insert(Attempt a) {
        String answer;
        try {
            answer = json.writeValueAsString(a.answer());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        db.sql("""
                insert into attempt (id, course_id, learner, item_id, client_attempt_id, request_hash, seq, quiz_id,
                                     answer, latency_ms, outcome, correct, score, created_at, graded_at, graded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""")
                .params(a.id(), a.courseId(), a.learner(), a.itemId(), a.clientAttemptId(), a.requestHash(), a.seq(),
                        a.quizId(), answer, a.latencyMs(), a.grade().outcome().name(), a.grade().correct(),
                        a.grade().score(), Timestamp.from(a.createdAt()),
                        a.gradedAt() == null ? null : Timestamp.from(a.gradedAt()), a.gradedBy())
                .update();
    }

    @Override
    public Optional<Attempt> find(String learner, UUID itemId, String clientAttemptId) {
        return db.sql("select * from attempt where learner = ? and item_id = ? and client_attempt_id = ?")
                .params(learner, itemId, clientAttemptId).query(mapper).optional();
    }

    @Override
    public Optional<Attempt> lock(UUID id) {
        return db.sql("select * from attempt where id = ? for update").param(id).query(mapper).optional();
    }

    @Override
    public Optional<Attempt> byId(UUID id) {
        return db.sql("select * from attempt where id = ?").param(id).query(mapper).optional();
    }

    @Override
    public void grade(UUID id, Grade g, String by, Instant at) {
        db.sql("update attempt set outcome = ?, correct = ?, score = ?, graded_at = ?, graded_by = ? where id = ?")
                .params(g.outcome().name(), g.correct(), g.score(), Timestamp.from(at), by, id).update();
    }

    @Override
    public List<Attempt> needingReview(UUID courseId, int limit) {
        return db.sql("select * from attempt where course_id = ? and outcome = 'NEEDS_REVIEW' order by created_at limit ?")
                .params(courseId, limit).query(mapper).list();
    }

    @Override
    public List<Attempt> latestForQuiz(UUID quizId) {
        return db.sql("""
                select distinct on (item_id) * from attempt where quiz_id = ?
                order by item_id, seq desc""").param(quizId).query(mapper).list();
    }

    @Override
    public List<RaschCalibrator.Response> firstResponses(UUID courseId) {
        return db.sql("""
                select distinct on (learner, item_id) learner, item_id, correct from attempt
                where course_id = ? and outcome = 'GRADED'
                order by learner, item_id, seq""").param(courseId)
                .query((rs, n) -> new RaschCalibrator.Response(rs.getString("learner"),
                        rs.getObject("item_id", UUID.class), rs.getBoolean("correct"))).list();
    }

    @Override
    public List<ItemStat> itemStats(UUID courseId) {
        return db.sql("""
                select item_id, count(*) n,
                       count(*) filter (where outcome = 'GRADED' and correct) c,
                       count(*) filter (where outcome = 'NEEDS_REVIEW') r
                from attempt where course_id = ? group by item_id order by item_id""").param(courseId)
                .query((rs, n) -> new ItemStat(rs.getObject("item_id", UUID.class), rs.getInt("n"), rs.getInt("c"),
                        rs.getInt("r"))).list();
    }

    @Override
    public List<UUID> coursesWithAttempts() {
        return db.sql("select distinct course_id from attempt order by course_id").query(UUID.class).list();
    }
}
