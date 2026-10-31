package com.mentorloop.tutor.application;

import java.util.List;
import java.util.UUID;

public interface ChunkStore {

    record NewChunk(UUID id, UUID courseId, UUID lessonId, UUID itemId, String label, int ord, String text,
                    float[] embedding) {
    }

    record Hit(UUID id, UUID lessonId, UUID itemId, String label, String text, double score) {
    }

    void replaceForLesson(UUID lessonId, List<NewChunk> chunks);

    void replaceForItem(UUID itemId, List<NewChunk> chunks);

    void deleteForItem(UUID itemId);

    /** Nearest by cosine distance. {@code lessonsOnly} drops worked-example chunks. */
    List<Hit> nearest(UUID courseId, float[] query, int limit, boolean lessonsOnly);

    /** Postgres full-text match ranked by ts_rank_cd. */
    List<Hit> lexical(UUID courseId, List<String> terms, int limit, boolean lessonsOnly);

    int count(UUID courseId);
}
