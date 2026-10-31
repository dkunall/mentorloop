package com.mentorloop.tutor.adapter;

import com.mentorloop.tutor.application.ChunkStore;
import com.mentorloop.tutor.domain.HashingEmbedder;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
class JdbcChunkStore implements ChunkStore {

    private static final RowMapper<Hit> HIT = (rs, n) -> new Hit(rs.getObject("id", UUID.class),
            rs.getObject("lesson_id", UUID.class), rs.getObject("item_id", UUID.class), rs.getString("label"),
            rs.getString("text"), rs.getDouble("score"));

    private final JdbcClient db;

    JdbcChunkStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void replaceForLesson(UUID lessonId, List<NewChunk> chunks) {
        db.sql("delete from chunk where lesson_id = ?").param(lessonId).update();
        chunks.forEach(this::insert);
    }

    @Override
    public void replaceForItem(UUID itemId, List<NewChunk> chunks) {
        deleteForItem(itemId);
        chunks.forEach(this::insert);
    }

    @Override
    public void deleteForItem(UUID itemId) {
        db.sql("delete from chunk where item_id = ?").param(itemId).update();
    }

    private void insert(NewChunk c) {
        db.sql("""
                insert into chunk (id, course_id, lesson_id, item_id, label, ord, text, embedding)
                values (?, ?, ?, ?, ?, ?, ?, ?::vector)""")
                .params(c.id(), c.courseId(), c.lessonId(), c.itemId(), c.label(), c.ord(), c.text(),
                        HashingEmbedder.literal(c.embedding())).update();
    }

    @Override
    public List<Hit> nearest(UUID courseId, float[] query, int limit, boolean lessonsOnly) {
        return db.sql("""
                select id, lesson_id, item_id, label, text, 1 - (embedding <=> ?::vector) as score
                from chunk where course_id = ? and (not ? or item_id is null)
                order by embedding <=> ?::vector, id limit ?""")
                .params(HashingEmbedder.literal(query), courseId, lessonsOnly, HashingEmbedder.literal(query), limit)
                .query(HIT).list();
    }

    @Override
    public List<Hit> lexical(UUID courseId, List<String> terms, int limit, boolean lessonsOnly) {
        String q = terms.stream().map(t -> t.replaceAll("[^\\p{L}\\p{N}]", "")).filter(t -> !t.isEmpty())
                .distinct().collect(Collectors.joining(" | "));
        if (q.isEmpty()) {
            return List.of();
        }
        return db.sql("""
                select id, lesson_id, item_id, label, text, ts_rank_cd(tsv, q) as score
                from chunk, to_tsquery('english', ?) q
                where course_id = ? and tsv @@ q and (not ? or item_id is null)
                order by score desc, id limit ?""")
                .params(q, courseId, lessonsOnly, limit).query(HIT).list();
    }

    @Override
    public int count(UUID courseId) {
        return db.sql("select count(*) from chunk where course_id = ?").param(courseId).query(Integer.class).single();
    }
}
