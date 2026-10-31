package com.mentorloop.tutor.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.tutor.application.TranscriptStore;
import com.mentorloop.tutor.application.TutorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcTranscriptStore implements TranscriptStore {

    private static final TypeReference<List<TutorService.Citation>> CITATIONS = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final ObjectMapper json;

    JdbcTranscriptStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public long save(Message m) {
        try {
            return db.sql("""
                    insert into tutor_message (course_id, learner, question, answer, mode, status, citations, coverage,
                                               model, latency_ms, created_at)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) returning id""")
                    .params(m.courseId(), m.learner(), m.question(), m.answer(), m.mode(), m.status(),
                            json.writeValueAsString(m.citations()), m.coverage(), m.model(), m.latencyMs(),
                            Timestamp.from(m.at()))
                    .query(Long.class).single();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Message> recent(UUID courseId, String learner, int limit) {
        return db.sql("select * from tutor_message where course_id = ? and learner = ? order by id desc limit ?")
                .params(courseId, learner, limit).query((rs, n) -> {
                    try {
                        return new Message(rs.getLong("id"), courseId, learner, rs.getString("question"),
                                rs.getString("answer"), rs.getString("mode"), rs.getString("status"),
                                json.readValue(rs.getString("citations"), CITATIONS), rs.getDouble("coverage"),
                                rs.getString("model"), rs.getLong("latency_ms"),
                                rs.getTimestamp("created_at").toInstant());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).list();
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
        return db.sql("delete from tutor_message where created_at < ?").param(Timestamp.from(cutoff)).update();
    }

    @Override
    public Map<String, Integer> statusCounts(UUID courseId) {
        Map<String, Integer> out = new LinkedHashMap<>();
        db.sql("select status, count(*) n from tutor_message where course_id = ? group by status order by status")
                .param(courseId).query(rs -> {
                    out.put(rs.getString("status"), rs.getInt("n"));
                });
        return out;
    }
}
