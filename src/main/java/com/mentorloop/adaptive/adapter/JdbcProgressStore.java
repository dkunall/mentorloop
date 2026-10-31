package com.mentorloop.adaptive.adapter;

import com.mentorloop.adaptive.application.PracticeEvent;
import com.mentorloop.adaptive.application.ProgressStore;
import com.mentorloop.adaptive.domain.ReviewCard;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JdbcProgressStore implements ProgressStore {

    private static final RowMapper<PracticeEvent> EVENT = (rs, n) -> new PracticeEvent(
            rs.getObject("attempt_id", UUID.class), rs.getObject("course_id", UUID.class), rs.getString("learner"),
            rs.getLong("seq"), rs.getObject("item_id", UUID.class), uuids(rs.getArray("concept_ids")),
            "GRADED".equals(rs.getString("outcome")), rs.getBoolean("correct"), rs.getLong("latency_ms"),
            rs.getTimestamp("at").toInstant());

    private static final RowMapper<Card> CARD = (rs, n) -> new Card(rs.getObject("item_id", UUID.class),
            rs.getObject("concept_id", UUID.class), new ReviewCard(rs.getInt("repetitions"), rs.getInt("interval_days"),
            rs.getDouble("ease"), rs.getInt("lapses")), rs.getTimestamp("due_at").toInstant());

    private static final RowMapper<Certificate> CERT = (rs, n) -> new Certificate(rs.getString("code"),
            rs.getObject("course_id", UUID.class), rs.getString("learner"), rs.getString("title"),
            rs.getTimestamp("issued_at").toInstant());

    private final JdbcClient db;

    JdbcProgressStore(JdbcClient db) {
        this.db = db;
    }

    static List<UUID> uuids(Array a) throws SQLException {
        return Arrays.stream((Object[]) a.getArray()).map(o -> (UUID) o).toList();
    }

    @Override
    public boolean insertEvent(PracticeEvent e) {
        String ids = "{" + String.join(",", e.conceptIds().stream().map(UUID::toString).toList()) + "}";
        return db.sql("""
                insert into practice_event (attempt_id, course_id, learner, seq, item_id, concept_ids, outcome, correct,
                                            latency_ms, at)
                values (?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, ?) on conflict do nothing""")
                .params(e.attemptId(), e.courseId(), e.learner(), e.seq(), e.itemId(), ids,
                        e.graded() ? "GRADED" : "NEEDS_REVIEW", e.correct(), e.latencyMs(), Timestamp.from(e.at()))
                .update() == 1;
    }

    @Override
    public long lockProgress(UUID courseId, String learner) {
        db.sql("insert into learner_progress (course_id, learner) values (?, ?) on conflict do nothing")
                .params(courseId, learner).update();
        return db.sql("select applied_seq from learner_progress where course_id = ? and learner = ? for update")
                .params(courseId, learner).query(Long.class).single();
    }

    @Override
    public void setAppliedSeq(UUID courseId, String learner, long seq) {
        db.sql("update learner_progress set applied_seq = ? where course_id = ? and learner = ?")
                .params(seq, courseId, learner).update();
    }

    @Override
    public long appliedSeq(UUID courseId, String learner) {
        return db.sql("select applied_seq from learner_progress where course_id = ? and learner = ?")
                .params(courseId, learner).query(Long.class).optional().orElse(0L);
    }

    @Override
    public List<PracticeEvent> pending(UUID courseId, String learner, long fromSeq, int limit) {
        return db.sql("""
                select * from practice_event where course_id = ? and learner = ? and seq >= ? and applied_at is null
                order by seq limit ?""").params(courseId, learner, fromSeq, limit).query(EVENT).list();
    }

    @Override
    public Optional<PracticeEvent> event(UUID attemptId) {
        return db.sql("select * from practice_event where attempt_id = ?").param(attemptId).query(EVENT).optional();
    }

    @Override
    public boolean applied(UUID attemptId) {
        return db.sql("select applied_at is not null from practice_event where attempt_id = ?").param(attemptId)
                .query(Boolean.class).single();
    }

    @Override
    public void markApplied(UUID attemptId, Instant at) {
        db.sql("update practice_event set applied_at = ? where attempt_id = ?").params(Timestamp.from(at), attemptId)
                .update();
    }

    @Override
    public void regrade(UUID attemptId, boolean correct) {
        db.sql("update practice_event set outcome = 'GRADED', correct = ? where attempt_id = ?")
                .params(correct, attemptId).update();
    }

    @Override
    public Map<UUID, Mastery> mastery(UUID courseId, String learner) {
        Map<UUID, Mastery> out = new HashMap<>();
        db.sql("select * from mastery where course_id = ? and learner = ?").params(courseId, learner).query(rs -> {
            Timestamp m = rs.getTimestamp("mastered_at");
            UUID c = rs.getObject("concept_id", UUID.class);
            out.put(c, new Mastery(c, rs.getDouble("p_known"), rs.getInt("attempts"), rs.getInt("correct"),
                    m == null ? null : m.toInstant()));
        });
        return out;
    }

    @Override
    public void saveMastery(UUID courseId, String learner, Mastery m, Instant at) {
        db.sql("""
                insert into mastery (course_id, learner, concept_id, p_known, attempts, correct, mastered_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (course_id, learner, concept_id) do update set p_known = excluded.p_known,
                    attempts = excluded.attempts, correct = excluded.correct, mastered_at = excluded.mastered_at,
                    updated_at = excluded.updated_at""")
                .params(courseId, learner, m.conceptId(), m.pKnown(), m.attempts(), m.correct(),
                        m.masteredAt() == null ? null : Timestamp.from(m.masteredAt()), Timestamp.from(at))
                .update();
    }

    @Override
    public Optional<Card> card(UUID courseId, String learner, UUID itemId) {
        return db.sql("select * from review_card where course_id = ? and learner = ? and item_id = ?")
                .params(courseId, learner, itemId).query(CARD).optional();
    }

    @Override
    public void saveCard(UUID courseId, String learner, Card c, Instant at) {
        db.sql("""
                insert into review_card (course_id, learner, item_id, concept_id, repetitions, interval_days, ease,
                                         lapses, due_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (course_id, learner, item_id) do update set repetitions = excluded.repetitions,
                    interval_days = excluded.interval_days, ease = excluded.ease, lapses = excluded.lapses,
                    due_at = excluded.due_at, updated_at = excluded.updated_at""")
                .params(courseId, learner, c.itemId(), c.conceptId(), c.card().repetitions(), c.card().intervalDays(),
                        c.card().ease(), c.card().lapses(), Timestamp.from(c.dueAt()), Timestamp.from(at))
                .update();
    }

    @Override
    public List<Card> cards(UUID courseId, String learner) {
        return db.sql("select * from review_card where course_id = ? and learner = ? order by due_at, item_id")
                .params(courseId, learner).query(CARD).list();
    }

    @Override
    public Set<UUID> itemsAnsweredCorrectly(UUID courseId, String learner) {
        return new HashSet<>(db.sql("""
                select distinct item_id from practice_event
                where course_id = ? and learner = ? and outcome = 'GRADED' and correct""")
                .params(courseId, learner).query(UUID.class).list());
    }

    @Override
    public boolean markLessonRead(UUID courseId, String learner, UUID lessonId, Instant at) {
        return db.sql("insert into lesson_read (course_id, learner, lesson_id, read_at) values (?, ?, ?, ?) on conflict do nothing")
                .params(courseId, learner, lessonId, Timestamp.from(at)).update() == 1;
    }

    @Override
    public Set<UUID> lessonsRead(UUID courseId, String learner) {
        return new HashSet<>(db.sql("select lesson_id from lesson_read where course_id = ? and learner = ?")
                .params(courseId, learner).query(UUID.class).list());
    }

    @Override
    public boolean insertCertificate(Certificate c) {
        return db.sql("""
                insert into certificate (code, course_id, learner, title, issued_at) values (?, ?, ?, ?, ?)
                on conflict (course_id, learner) do nothing""")
                .params(c.code(), c.courseId(), c.learner(), c.title(), Timestamp.from(c.issuedAt())).update() == 1;
    }

    @Override
    public Optional<Certificate> certificate(String code) {
        return db.sql("select * from certificate where code = ?").param(code).query(CERT).optional();
    }

    @Override
    public Optional<Certificate> certificateOf(UUID courseId, String learner) {
        return db.sql("select * from certificate where course_id = ? and learner = ?").params(courseId, learner)
                .query(CERT).optional();
    }

    @Override
    public List<DueLearner> learnersWithDueReviews(Instant now, int limit) {
        return db.sql("""
                select course_id, learner, count(*) n from review_card where due_at <= ?
                group by course_id, learner order by course_id, learner limit ?""")
                .params(Timestamp.from(now), limit)
                .query((rs, n) -> new DueLearner(rs.getObject("course_id", UUID.class), rs.getString("learner"),
                        rs.getInt("n"))).list();
    }

    @Override
    public List<ConceptStat> conceptStats(UUID courseId, double threshold) {
        return db.sql("""
                select concept_id, count(*) learners, count(*) filter (where mastered_at is not null) mastered,
                       avg(p_known) mean_p
                from mastery where course_id = ? group by concept_id order by concept_id""").param(courseId)
                .query((rs, n) -> new ConceptStat(rs.getObject("concept_id", UUID.class), rs.getInt("learners"),
                        rs.getInt("mastered"), rs.getDouble("mean_p"))).list();
    }

    @Override
    public int pendingEvents(UUID courseId) {
        return db.sql("select count(*) from practice_event where course_id = ? and applied_at is null").param(courseId)
                .query(Integer.class).single();
    }
}
