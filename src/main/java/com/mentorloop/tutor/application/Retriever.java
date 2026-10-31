package com.mentorloop.tutor.application;

import com.mentorloop.shared.text.Text;
import com.mentorloop.tutor.domain.HashingEmbedder;
import com.mentorloop.tutor.domain.RankFusion;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hybrid retrieval: vector and full-text candidate lists fused with reciprocal rank fusion. */
@Service
public class Retriever {

    public enum Strategy { LEXICAL, VECTOR, HYBRID }

    static final int CANDIDATES = 20;

    private final ChunkStore chunks;
    private final HashingEmbedder embedder;

    Retriever(ChunkStore chunks, HashingEmbedder embedder) {
        this.chunks = chunks;
        this.embedder = embedder;
    }

    public List<ChunkStore.Hit> retrieve(UUID courseId, String question, int k, boolean lessonsOnly) {
        return retrieve(courseId, question, k, lessonsOnly, Strategy.HYBRID);
    }

    public List<ChunkStore.Hit> retrieve(UUID courseId, String question, int k, boolean lessonsOnly, Strategy s) {
        List<String> terms = Text.terms(question);
        List<ChunkStore.Hit> lexical = s == Strategy.VECTOR || terms.isEmpty()
                ? List.of() : chunks.lexical(courseId, terms, CANDIDATES, lessonsOnly);
        if (s == Strategy.LEXICAL) {
            return lexical.stream().limit(k).toList();
        }
        float[] q = embedder.embed(question);
        boolean empty = true;
        for (float f : q) {
            if (f != 0) {
                empty = false;
                break;
            }
        }
        List<ChunkStore.Hit> vector = empty ? List.of() : chunks.nearest(courseId, q, CANDIDATES, lessonsOnly);
        if (s == Strategy.VECTOR) {
            return vector.stream().limit(k).toList();
        }
        Map<UUID, ChunkStore.Hit> byId = new HashMap<>();
        lexical.forEach(h -> byId.putIfAbsent(h.id(), h));
        vector.forEach(h -> byId.putIfAbsent(h.id(), h));
        return RankFusion.fuse(List.of(lexical.stream().map(ChunkStore.Hit::id).toList(),
                vector.stream().map(ChunkStore.Hit::id).toList()), k).stream().map(byId::get).toList();
    }
}
