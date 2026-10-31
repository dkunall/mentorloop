package com.mentorloop.assessment.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.assessment.application.Item;
import com.mentorloop.assessment.application.ItemStore;
import com.mentorloop.assessment.domain.AnswerKey;
import com.mentorloop.assessment.domain.ItemStatus;
import com.mentorloop.assessment.domain.ItemType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcItemStore implements ItemStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcClient db;
    private final ObjectMapper json;
    private final RowMapper<Item> mapper;

    JdbcItemStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
        this.mapper = (rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getObject("course_id", UUID.class),
                uuids(rs.getArray("concept_ids")), ItemType.valueOf(rs.getString("type")), rs.getString("stem"),
                read(rs.getString("options"), STRINGS), rs.getString("explanation"),
                ItemStatus.valueOf(rs.getString("status")), rs.getString("origin"),
                rs.getObject("source_lesson", UUID.class), rs.getDouble("difficulty"), rs.getInt("difficulty_v"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getString("reviewed_by"));
    }

    static List<UUID> uuids(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(o -> (UUID) o).toList();
    }

    @Override
    public void insert(Item i, AnswerKey key) {
        db.sql("""
                insert into item (id, course_id, concept_ids, type, stem, options, explanation, status, origin,
                                  source_lesson, difficulty, difficulty_v, created_by, created_at, reviewed_by, reviewed_at)
                values (?, ?, ?::uuid[], ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(i.id(), i.courseId(), pgArray(i.conceptIds()), i.type().name(), i.stem(), write(i.options()),
                        i.explanation(), i.status().name(), i.origin(), i.sourceLesson(), i.difficulty(),
                        i.difficultyVersion(), i.createdBy(), Timestamp.from(i.createdAt()), i.reviewedBy(),
                        i.reviewedBy() == null ? null : Timestamp.from(i.createdAt()))
                .update();
        db.sql("insert into item_key (item_id, key) values (?, ?::jsonb)").params(i.id(), encode(key)).update();
    }

    static String pgArray(Collection<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    @Override
    public Optional<Item> item(UUID id) {
        return db.sql("select * from item where id = ?").param(id).query(mapper).optional();
    }

    @Override
    public Optional<Item> lock(UUID id) {
        return db.sql("select * from item where id = ? for update").param(id).query(mapper).optional();
    }

    @Override
    public AnswerKey key(UUID itemId) {
        return decode(db.sql("select key from item_key where item_id = ?").param(itemId).query(String.class).single());
    }

    @Override
    public Map<UUID, AnswerKey> keys(Collection<UUID> itemIds) {
        Map<UUID, AnswerKey> out = new HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        db.sql("select item_id, key from item_key where item_id = any(?::uuid[])").param(pgArray(itemIds))
                .query(rs -> {
                    out.put(rs.getObject("item_id", UUID.class), decode(rs.getString("key")));
                });
        return out;
    }

    @Override
    public List<Item> items(UUID courseId, ItemStatus status) {
        if (status == null) {
            return db.sql("select * from item where course_id = ? order by created_at, id").param(courseId)
                    .query(mapper).list();
        }
        return db.sql("select * from item where course_id = ? and status = ? order by created_at, id")
                .params(courseId, status.name()).query(mapper).list();
    }

    @Override
    public List<Item> byIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Item> byId = new LinkedHashMap<>();
        db.sql("select * from item where id = any(?::uuid[])").param(pgArray(ids)).query(mapper).list()
                .forEach(i -> byId.put(i.id(), i));
        List<Item> out = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            if (byId.containsKey(id)) {
                out.add(byId.get(id));
            }
        }
        return out;
    }

    @Override
    public void review(UUID id, ItemStatus status, String reviewer, Instant at) {
        db.sql("update item set status = ?, reviewed_by = ?, reviewed_at = ? where id = ?")
                .params(status.name(), reviewer, Timestamp.from(at), id).update();
    }

    @Override
    public void setDifficulty(UUID id, double difficulty, int version, int responses, int learners, Instant at) {
        db.sql("update item set difficulty = ?, difficulty_v = ? where id = ?").params(difficulty, version, id).update();
        db.sql("""
                insert into item_calibration (item_id, version, difficulty, responses, learners, fitted_at)
                values (?, ?, ?, ?, ?, ?)""")
                .params(id, version, difficulty, responses, learners, Timestamp.from(at)).update();
    }

    String encode(AnswerKey key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", key.type().name());
        switch (key) {
            case AnswerKey.Mcq k -> m.put("choices", k.correctOptions().stream().sorted().toList());
            case AnswerKey.Numeric k -> {
                m.put("value", k.value());
                m.put("tolerance", k.tolerance());
            }
            case AnswerKey.ShortAnswer k -> {
                m.put("accepted", k.accepted());
                m.put("keywords", k.keywords());
            }
        }
        return write(m);
    }

    AnswerKey decode(String s) {
        try {
            JsonNode n = json.readTree(s);
            return switch (ItemType.valueOf(n.path("type").asText())) {
                case MCQ -> {
                    var set = new java.util.HashSet<Integer>();
                    n.path("choices").forEach(c -> set.add(c.asInt()));
                    yield new AnswerKey.Mcq(set);
                }
                case NUMERIC -> new AnswerKey.Numeric(n.path("value").asDouble(), n.path("tolerance").asDouble());
                case SHORT_ANSWER -> new AnswerKey.ShortAnswer(json.convertValue(n.path("accepted"), STRINGS),
                        json.convertValue(n.path("keywords"), STRINGS));
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> T read(String s, TypeReference<T> t) {
        try {
            return json.readValue(s, t);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
