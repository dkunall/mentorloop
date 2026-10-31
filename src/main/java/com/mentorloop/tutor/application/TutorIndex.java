package com.mentorloop.tutor.application;

import com.mentorloop.tutor.domain.Chunker;
import com.mentorloop.tutor.domain.HashingEmbedder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Keeps the tutor's chunk index in step with course content. Runs in the caller's transaction. */
@Service
public class TutorIndex {

    private final ChunkStore chunks;
    private final Chunker chunker = Chunker.standard();
    private final HashingEmbedder embedder;

    TutorIndex(ChunkStore chunks, HashingEmbedder embedder) {
        this.chunks = chunks;
        this.embedder = embedder;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int indexLesson(UUID courseId, UUID lessonId, String lessonTitle, String markdown) {
        List<ChunkStore.NewChunk> out = new ArrayList<>();
        for (Chunker.Piece p : chunker.split(markdown)) {
            String label = p.heading().isBlank() || p.heading().equals(lessonTitle)
                    ? lessonTitle : lessonTitle + " › " + p.heading();
            out.add(new ChunkStore.NewChunk(UUID.randomUUID(), courseId, lessonId, null, label, p.ord(), p.text(),
                    embedder.embed(label + " " + p.text())));
        }
        chunks.replaceForLesson(lessonId, out);
        return out.size();
    }

    /**
     * A worked example is an item's explanation. It helps outside a quiz and would give answers away inside one,
     * so these chunks carry the item id and are filtered out while the learner has a quiz open.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void indexWorkedExample(UUID courseId, UUID itemId, String label, String explanation) {
        if (explanation == null || explanation.isBlank()) {
            chunks.deleteForItem(itemId);
            return;
        }
        List<ChunkStore.NewChunk> out = new ArrayList<>();
        for (Chunker.Piece p : chunker.split(explanation)) {
            out.add(new ChunkStore.NewChunk(UUID.randomUUID(), courseId, null, itemId, label, p.ord(), p.text(),
                    embedder.embed(label + " " + p.text())));
        }
        chunks.replaceForItem(itemId, out);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void removeWorkedExample(UUID itemId) {
        chunks.deleteForItem(itemId);
    }
}
