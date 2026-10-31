package com.mentorloop.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.support.Api;
import com.mentorloop.support.ItBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptsIT extends ItBase {

    @Test
    void aRetriedAttemptIsGradedOnceAndADifferentAnswerUnderTheSameIdIsRejected() {
        World w = world();
        Learner l = learner(w);
        String id = "retry-" + UUID.randomUUID();
        JsonNode first = attempt(l, w.item("stack-size"), id, number(2)).expect(200).body();
        JsonNode again = attempt(l, w.item("stack-size"), id, number(2)).expect(200).body();
        assertThat(again.path("attemptId").asText()).isEqualTo(first.path("attemptId").asText());
        assertThat(again.path("replayed").asBoolean()).isTrue();
        assertThat(first.path("replayed").asBoolean()).isFalse();
        var mismatch = attempt(l, w.item("stack-size"), id, number(7)).expect(422);
        assertThat(mismatch.text("code")).isEqualTo("idempotency-mismatch");
        assertThat(count("select count(*) from attempt where learner = ?", l.subject())).isEqualTo(1);
        assertThat(count("select count(*) from outbox where type = 'AttemptRecorded' and payload->'data'->>'learner' = ?",
                l.subject())).isEqualTo(1);
    }

    @Test
    void racingRetriesOfOneAttemptStoreOneRow() throws Exception {
        World w = world();
        Learner l = learner(w);
        String id = "race-" + UUID.randomUUID();
        List<Api.Response> results = race(16, () -> attempt(l, w.item("stack-order"), id, choice(0)));
        assertThat(results).allMatch(r -> r.status() == 200);
        assertThat(results.stream().map(r -> r.text("attemptId")).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.body().path("replayed").asBoolean())).hasSize(1);
        assertThat(count("select count(*) from attempt where learner = ?", l.subject())).isEqualTo(1);
    }

    @Test
    void concurrentDistinctAttemptsGetGaplessSequenceNumbers() throws Exception {
        World w = world();
        Learner l = learner(w);
        List<String> names = List.of("stack-order", "stack-size", "stack-undo");
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        List<Api.Response> results = race(24, () -> {
            String name = names.get(counter.getAndIncrement() % names.size());
            return attempt(l, w.item(name), UUID.randomUUID().toString(), rightAnswer(name));
        });
        assertThat(results).allMatch(r -> r.status() == 200);
        List<Long> seqs = db.sql("select seq from attempt where learner = ? order by seq").param(l.subject())
                .query(Long.class).list();
        assertThat(seqs).hasSize(24);
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
        awaitApplied(l, w, 24);
        assertThat(concept(progress(l, w), "stacks").path("attempts").asInt()).isEqualTo(24);
    }

    @Test
    void aBorderlineShortAnswerWaitsForAnInstructorAndThenCountsOnce() {
        World w = world();
        Learner l = learner(w);
        JsonNode r = attempt(l, w.item("queue-explain"), "q1", text("the first and earliest job goes out"))
                .expect(200).body();
        assertThat(r.path("outcome").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(r.path("explanation").isNull()).isTrue();
        awaitApplied(l, w, r.path("seq").asLong());
        assertThat(concept(progress(l, w), "queues").path("attempts").asInt()).isZero();

        JsonNode queue = instructor().get("/v1/courses/" + w.courseId() + "/attempts:needing-review").expect(200).body();
        assertThat(queue.findValuesAsText("id")).contains(r.path("attemptId").asText());
        as(OTHER_INSTRUCTOR).post("/v1/attempts/" + r.path("attemptId").asText() + ":grade", Map.of("correct", true))
                .expect(403);
        l.api().post("/v1/attempts/" + r.path("attemptId").asText() + ":grade", Map.of("correct", true)).expect(403);
        instructor().post("/v1/attempts/" + r.path("attemptId").asText() + ":grade", Map.of("correct", true))
                .expect(200);
        instructor().post("/v1/attempts/" + r.path("attemptId").asText() + ":grade", Map.of("correct", false))
                .expect(409);
        eventually(() -> concept(progress(l, w), "queues").path("attempts").asInt() == 1);
        JsonNode q = concept(progress(l, w), "queues");
        assertThat(q.path("correct").asInt()).isEqualTo(1);
        assertThat(q.path("pKnown").asDouble()).isGreaterThan(0.2);
    }

    @Test
    void badInputIsRejectedBeforeAnythingIsStored() {
        World w = world();
        Learner l = learner(w);
        attempt(l, w.item("stack-order"), "bad id with spaces", choice(0)).expect(400);
        assertThat(attempt(l, w.item("stack-order"), "x1", choice(9)).expect(422).text("code")).isEqualTo("invalid-answer");
        attempt(l, w.item("stack-undo"), "x2", text(" ")).expect(422);
        attempt(l, UUID.randomUUID().toString(), "x3", choice(0)).expect(404);
        as(unique("not-enrolled")).post("/v1/items/" + w.item("stack-order") + "/attempts",
                Map.of("clientAttemptId", "x4", "answer", choice(0), "latencyMs", 1)).expect(404);
        assertThat(count("select count(*) from attempt where course_id = ?::uuid", w.courseId())).isZero();
    }

    static List<Api.Response> race(int n, Callable<Api.Response> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Api.Response>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<Api.Response> out = new ArrayList<>();
            for (var f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
