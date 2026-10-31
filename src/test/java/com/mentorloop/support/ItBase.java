package com.mentorloop.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.mentorloop.llm.application.LlmPort;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The whole app on a random port against real Postgres + pgvector, Kafka, Redis and Mailpit, one shared Spring
 * context. Every test builds its own course and learners, so tests never see each other's data.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ItBase.TestBeans.class)
public abstract class ItBase {

    public static final String INSTRUCTOR = "instructor@it.example.test";
    public static final String OTHER_INSTRUCTOR = "other-instructor@it.example.test";
    public static final int TUTOR_PER_MINUTE = 6;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return TestJwt.decoder();
        }

        @Bean
        @Primary
        TestLlm testLlm() {
            return new TestLlm();
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    @Autowired
    protected TestLlm llm;

    @Autowired
    protected LlmPort llmPort;

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        var pg = Containers.postgres();
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", () -> Containers.DB_PASSWORD);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "40");
        r.add("spring.kafka.bootstrap-servers", () -> Containers.kafka().getBootstrapServers());
        r.add("spring.data.redis.host", () -> Containers.redis().getHost());
        r.add("spring.data.redis.port", () -> Containers.redis().getMappedPort(6379));
        r.add("spring.data.redis.password", () -> Containers.REDIS_PASSWORD);
        r.add("spring.mail.host", () -> Containers.mailpit().getHost());
        r.add("spring.mail.port", () -> Containers.mailpit().getMappedPort(1025));
        r.add("mentorloop.security.instructor-subjects", () -> INSTRUCTOR + "," + OTHER_INSTRUCTOR);
        r.add("mentorloop.tutor.per-minute", () -> TUTOR_PER_MINUTE);
        r.add("mentorloop.calibration.min-responses", () -> 3);
        r.add("mentorloop.calibration.every", () -> "PT1H");
        r.add("mentorloop.reviews.day-length", () -> "PT10M");
        r.add("mentorloop.reviews.reminder-every", () -> "PT1H");
        r.add("mentorloop.outbox.poll", () -> "PT0.1S");
    }

    @AfterEach
    void resetLlm() {
        llm.reset();
    }

    protected Api anon() {
        return Api.at(port);
    }

    protected Api instructor() {
        return as(INSTRUCTOR);
    }

    protected Api as(String subject) {
        return Api.at(port).as(TestJwt.user(subject));
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@it.example.test";
    }

    // --- a small synthetic course: stacks, then queues (needs stacks)

    public record World(String courseId, Map<String, String> concepts, Map<String, String> lessons,
                        Map<String, String> items) {
        public String item(String name) {
            return items.get(name);
        }
    }

    public static final String STACKS_LESSON = """
            # What a stack is
            A **stack** keeps items in last in, first out order. The last element pushed is the first one popped.
            You push to add an element on top and pop to remove the top element.

            # Where stacks show up
            Undo history in an editor is a stack: the most recent edit is undone first. Function calls use the
            **call stack**, which holds one frame per active call.
            """;

    public static final String QUEUES_LESSON = """
            # What a queue is
            A **queue** keeps items in first in, first out order. Elements join at the back and leave from the front.
            Adding is called enqueue and removing is called dequeue.

            # Where queues show up
            A printer works through jobs with a queue, so the earliest job prints first. Breadth-first search
            visits nodes using a queue.
            """;

    protected World world() {
        Api in = instructor();
        String course = in.post("/v1/courses", Map.of("title", "Data Structures " + UUID.randomUUID().toString()
                .substring(0, 6))).expect(201).text("id");
        JsonNode concepts = in.post("/v1/courses/" + course + "/concepts", Map.of("concepts", List.of(
                Map.of("key", "stacks", "name", "Stacks", "prerequisites", List.of()),
                Map.of("key", "queues", "name", "Queues", "prerequisites", List.of("stacks"))))).expect(201).body();
        Map<String, String> conceptIds = new HashMap<>();
        concepts.forEach(c -> conceptIds.put(c.path("key").asText(), c.path("id").asText()));
        String module = in.post("/v1/courses/" + course + "/modules", Map.of("title", "Linear structures"))
                .expect(201).text("id");
        Map<String, String> lessons = new HashMap<>();
        lessons.put("stacks", in.post("/v1/modules/" + module + "/lessons", Map.of("concept", "stacks", "title",
                "Stacks", "body", STACKS_LESSON)).expect(201).text("id"));
        lessons.put("queues", in.post("/v1/modules/" + module + "/lessons", Map.of("concept", "queues", "title",
                "Queues", "body", QUEUES_LESSON)).expect(201).text("id"));
        Map<String, String> items = new LinkedHashMap<>();
        items.put("stack-order", mcq(in, course, "stacks", "Which order does a stack remove elements in?",
                List.of("Last in, first out", "First in, first out", "Smallest first", "Random"), 0,
                "A stack pops the element pushed most recently.", -0.5));
        items.put("stack-size", numeric(in, course, "stacks",
                "You push 3 elements onto an empty stack and pop 1. How many are left?", 2, 0,
                "Three in, one out leaves two.", 0.0));
        items.put("stack-undo", shortAnswer(in, course, "stacks", "Which data structure backs an editor's undo history?",
                List.of("stack", "a stack"), List.of(), "The most recent edit is undone first, which is stack order.",
                0.5));
        items.put("queue-order", mcq(in, course, "queues", "Which order does a queue remove elements in?",
                List.of("Last in, first out", "First in, first out", "Largest first", "Random"), 1,
                "A queue serves the element that has waited longest.", -0.5));
        items.put("queue-explain", shortAnswer(in, course, "queues", "Why does a printer use a queue for jobs?",
                List.of("so the earliest job prints first"), List.of("earliest", "first"),
                "The job sent first should print first.", 0.8));
        items.put("queue-count", numeric(in, course, "queues",
                "Enqueue 5 jobs, dequeue 2. How many are waiting?", 3, 0, "Five in, two out.", 0.2));
        in.post("/v1/courses/" + course + ":publish", Map.of()).expect(200);
        return new World(course, conceptIds, lessons, items);
    }

    protected static String mcq(Api in, String course, String concept, String stem, List<String> options, int right,
                                String explanation, double difficulty) {
        Map<String, Object> key = new HashMap<>();
        key.put("choices", List.of(right));
        return item(in, course, "MCQ", concept, stem, options, key, explanation, difficulty);
    }

    protected static String numeric(Api in, String course, String concept, String stem, double value, double tol,
                                    String explanation, double difficulty) {
        return item(in, course, "NUMERIC", concept, stem, null, Map.of("value", value, "tolerance", tol), explanation,
                difficulty);
    }

    protected static String shortAnswer(Api in, String course, String concept, String stem, List<String> accepted,
                                        List<String> keywords, String explanation, double difficulty) {
        return item(in, course, "SHORT_ANSWER", concept, stem, null, Map.of("accepted", accepted, "keywords", keywords),
                explanation, difficulty);
    }

    private static String item(Api in, String course, String type, String concept, String stem, List<String> options,
                               Map<String, Object> key, String explanation, double difficulty) {
        Map<String, Object> b = new HashMap<>();
        b.put("type", type);
        b.put("concepts", List.of(concept));
        b.put("stem", stem);
        if (options != null) {
            b.put("options", options);
        }
        b.put("key", key);
        b.put("explanation", explanation);
        b.put("difficulty", difficulty);
        return in.post("/v1/courses/" + course + "/items", b).expect(201).body().path("item").path("id").asText();
    }

    /** A learner (subject doubles as e-mail) enrolled in the world's course. */
    public record Learner(String subject, Api api) {
    }

    protected Learner learner(World w) {
        String subject = unique("learner");
        Api a = as(subject);
        a.post("/v1/courses/" + w.courseId() + "/enrollments", Map.of()).expect(201);
        return new Learner(subject, a);
    }

    // --- answering

    protected static Map<String, Object> choice(int i) {
        return Map.of("choices", List.of(i));
    }

    protected static Map<String, Object> number(double n) {
        return Map.of("number", n);
    }

    protected static Map<String, Object> text(String t) {
        return Map.of("text", t);
    }

    protected static Api.Response attempt(Learner learner, String itemId, String clientId, Map<String, Object> answer) {
        return attempt(learner, itemId, clientId, answer, null);
    }

    /** Answers right with a fresh client id and returns the body. */
    protected static JsonNode answerRight(Learner l, World w, String name) {
        return attempt(l, w.item(name), UUID.randomUUID().toString(), rightAnswer(name)).expect(200).body();
    }

    protected static JsonNode answerWrong(Learner l, World w, String name) {
        return attempt(l, w.item(name), UUID.randomUUID().toString(), wrongAnswer(name)).expect(200).body();
    }

    /** Waits until the adaptive consumer has applied everything up to {@code seq}. */
    protected void awaitApplied(Learner l, World w, long seq) {
        eventually(() -> progress(l, w).path("appliedSeq").asLong() >= seq);
    }

    protected static Api.Response attempt(Learner learner, String itemId, String clientId, Map<String, Object> answer,
                                          String quizId) {
        Map<String, Object> b = new HashMap<>();
        b.put("clientAttemptId", clientId);
        b.put("answer", answer);
        b.put("latencyMs", 4000);
        if (quizId != null) {
            b.put("quizId", quizId);
        }
        return learner.api().post("/v1/items/" + itemId + "/attempts", b);
    }

    /** Right answers for the world's items, by name. */
    protected static Map<String, Object> rightAnswer(String name) {
        return switch (name) {
            case "stack-order" -> choice(0);
            case "stack-size" -> number(2);
            case "stack-undo" -> text("A stack");
            case "queue-order" -> choice(1);
            case "queue-count" -> number(3);
            case "queue-explain" -> text("so the earliest job prints first");
            default -> throw new IllegalArgumentException(name);
        };
    }

    protected static Map<String, Object> wrongAnswer(String name) {
        return switch (name) {
            case "stack-order" -> choice(1);
            case "stack-size" -> number(3);
            case "stack-undo" -> text("a queue");
            case "queue-order" -> choice(0);
            case "queue-count" -> number(5);
            case "queue-explain" -> text("because printers are slow");
            default -> throw new IllegalArgumentException(name);
        };
    }

    protected JsonNode progress(Learner learner, World w) {
        return learner.api().get("/v1/courses/" + w.courseId() + "/progress").expect(200).body();
    }

    protected static JsonNode concept(JsonNode progress, String key) {
        for (JsonNode c : progress.path("concepts")) {
            if (c.path("key").asText().equals(key)) {
                return c;
            }
        }
        throw new AssertionError("no concept " + key + " in " + progress);
    }

    // --- mail

    protected List<JsonNode> mailsTo(String address) {
        var out = new ArrayList<JsonNode>();
        var list = Api.atUrl(Containers.mailpitApi()).get("/api/v1/messages?limit=1000").expect(200).body();
        for (JsonNode m : list.path("messages")) {
            for (JsonNode to : m.path("To")) {
                if (to.path("Address").asText().equalsIgnoreCase(address)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    protected String mailText(JsonNode summary) {
        return Api.atUrl(Containers.mailpitApi()).get("/api/v1/message/" + summary.path("ID").asText()).expect(200)
                .text("Text");
    }

    protected static void eventually(BooleanSupplier check) {
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(check::getAsBoolean);
    }

    protected long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }
}
