package com.mentorloop.assessment.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentorloop.assessment.domain.AnswerKey;
import com.mentorloop.assessment.domain.ItemStatus;
import com.mentorloop.assessment.domain.ItemType;
import com.mentorloop.assessment.domain.ShortAnswerGrader;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.course.application.CourseViews;
import com.mentorloop.llm.application.LlmPort;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.llm.application.LlmUnavailableException;
import com.mentorloop.shared.domain.DomainConflictException;
import com.mentorloop.shared.domain.DomainRuleException;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import com.mentorloop.tutor.application.TutorIndex;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ItemService {

    /** One of choices / value+tolerance / accepted(+keywords), matching the item type. */
    public record KeyIn(List<Integer> choices, Double value, Double tolerance, List<String> accepted,
                        List<String> keywords) {
    }

    public record ItemIn(String type, List<String> concepts, String stem, List<String> options, KeyIn key,
                         String explanation, Double difficulty) {
    }

    /** What instructors see: the item plus its key. */
    public record Authored(Item item, AnswerKey key) {
    }

    public static final int MAX_GENERATE = 10;

    private final ItemStore items;
    private final CourseService courses;
    private final TutorIndex index;
    private final LlmPort llm;
    private final ObjectMapper json;
    private final Clock clock;

    ItemService(ItemStore items, CourseService courses, TutorIndex index, LlmPort llm, ObjectMapper json, Clock clock) {
        this.items = items;
        this.courses = courses;
        this.index = index;
        this.llm = llm;
        this.json = json;
        this.clock = clock;
    }

    /** Authored items are LIVE at once; the author is the course's instructor. */
    @Transactional
    public Authored create(Caller caller, UUID courseId, ItemIn in) {
        courses.requireOwned(caller, courseId);
        ItemType type = parseType(in.type());
        if (type == null) {
            throw ApiException.invalid("type must be MCQ, NUMERIC or SHORT_ANSWER (code items need a sandboxed "
                    + "runner, which this service does not have)");
        }
        if (in.stem() == null || in.stem().isBlank() || in.stem().length() > 4000) {
            throw ApiException.invalid("stem is required, at most 4000 characters");
        }
        List<UUID> conceptIds = conceptIds(courseId, in.concepts());
        List<String> options = in.options() == null ? List.of() : List.copyOf(in.options());
        AnswerKey key = key(type, in.key(), options);
        if (in.difficulty() != null && !(Math.abs(in.difficulty()) <= 6)) {
            throw ApiException.invalid("difficulty is a logit between -6 and 6");
        }
        var item = new Item(UUID.randomUUID(), courseId, conceptIds, type, in.stem().strip(), options,
                blankToNull(in.explanation()), ItemStatus.LIVE, "AUTHORED", null,
                in.difficulty() == null ? 0 : in.difficulty(), 0, caller.subject(), clock.instant(), caller.subject());
        items.insert(item, key);
        index.indexWorkedExample(courseId, item.id(), "Worked example: " + shorten(item.stem()), item.explanation());
        return new Authored(item, key);
    }

    /**
     * Asks the model for cloze questions from the concept's lessons. They are stored as DRAFT and stay invisible
     * to learners until an instructor approves each one.
     */
    @Transactional
    public List<Authored> generate(Caller caller, UUID courseId, String conceptKey, int count) {
        courses.requireOwned(caller, courseId);
        if (count < 1 || count > MAX_GENERATE) {
            throw ApiException.invalid("count is between 1 and " + MAX_GENERATE);
        }
        UUID conceptId = conceptIds(courseId, List.of(conceptKey)).getFirst();
        List<CourseViews.Lesson> lessons = courses.lessons(courseId).stream()
                .filter(l -> l.conceptId().equals(conceptId)).toList();
        if (lessons.isEmpty()) {
            throw new DomainRuleException("no-lessons", "Concept " + conceptKey + " has no lessons to generate from");
        }
        var context = lessons.stream().map(l -> new LlmRequest.Context(l.title(), l.body())).toList();
        String reply;
        try {
            reply = llm.complete(new LlmRequest(LlmRequest.Mode.GENERATE_ITEMS, Integer.toString(count), context)).text();
        } catch (LlmUnavailableException e) {
            throw new ApiException(503, "llm-unavailable", "The item generator is unavailable; try again later");
        }
        JsonNode arr;
        try {
            arr = json.readTree(stripFence(reply));
        } catch (Exception e) {
            throw new ApiException(502, "generation-unparseable", "The model's reply was not a JSON array");
        }
        if (!arr.isArray()) {
            throw new ApiException(502, "generation-unparseable", "The model's reply was not a JSON array");
        }
        Set<String> seen = new HashSet<>();
        for (Item existing : items.items(courseId, null)) {
            seen.add(ShortAnswerGrader.normalise(existing.stem()));
        }
        List<Authored> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (out.size() == count) {
                break;
            }
            String stem = n.path("stem").asText("").strip();
            String answer = n.path("answer").asText("").strip();
            int source = n.path("source").asInt(1);
            if (stem.isEmpty() || answer.isEmpty() || stem.length() > 4000 || answer.length() > 200
                    || source < 1 || source > lessons.size() || !seen.add(ShortAnswerGrader.normalise(stem))) {
                continue;
            }
            CourseViews.Lesson lesson = lessons.get(source - 1);
            var key = new AnswerKey.ShortAnswer(List.of(answer), List.of());
            var item = new Item(UUID.randomUUID(), courseId, List.of(conceptId), ItemType.SHORT_ANSWER, stem,
                    List.of(), "See the lesson \"" + lesson.title() + "\".", ItemStatus.DRAFT, "GENERATED",
                    lesson.id(), 0, 0, caller.subject(), clock.instant(), null);
            items.insert(item, key);
            out.add(new Authored(item, key));
        }
        return out;
    }

    /** DRAFT to LIVE or REJECTED, once. Concurrent reviews of one item: the first wins, the rest get 409. */
    @Transactional
    public Item review(Caller caller, UUID itemId, boolean approve) {
        Item item = items.lock(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        courses.requireOwned(caller, item.courseId());
        if (item.status() != ItemStatus.DRAFT) {
            throw new DomainConflictException("already-reviewed", "This item is " + item.status());
        }
        ItemStatus to = approve ? ItemStatus.LIVE : ItemStatus.REJECTED;
        items.review(itemId, to, caller.subject(), clock.instant());
        if (approve) {
            index.indexWorkedExample(item.courseId(), itemId, "Worked example: " + shorten(item.stem()), item.explanation());
        }
        return items.item(itemId).orElseThrow();
    }

    @Transactional
    public Item retire(Caller caller, UUID itemId) {
        Item item = items.lock(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        courses.requireOwned(caller, item.courseId());
        if (item.status() != ItemStatus.LIVE) {
            throw new DomainConflictException("not-live", "Only LIVE items can be retired");
        }
        items.review(itemId, ItemStatus.RETIRED, caller.subject(), clock.instant());
        index.removeWorkedExample(itemId);
        return items.item(itemId).orElseThrow();
    }

    public List<Authored> list(Caller caller, UUID courseId, String status) {
        courses.requireOwned(caller, courseId);
        ItemStatus s = status == null ? null : parseStatus(status);
        List<Item> list = items.items(courseId, s);
        Map<UUID, AnswerKey> keys = items.keys(list.stream().map(Item::id).toList());
        return list.stream().map(i -> new Authored(i, keys.get(i.id()))).toList();
    }

    /** LIVE items without keys, for the recommender and quizzes. */
    public List<Item> live(UUID courseId) {
        return items.items(courseId, ItemStatus.LIVE);
    }

    public Item learnerView(Caller caller, UUID itemId) {
        Item item = items.item(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        if (!caller.isStaff()) {
            courses.requireEnrolled(caller, item.courseId());
            if (item.status() != ItemStatus.LIVE) {
                throw ApiException.notFound("Item");
            }
        } else {
            courses.requireOwned(caller, item.courseId());
        }
        return item;
    }

    private List<UUID> conceptIds(UUID courseId, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw ApiException.invalid("concepts: at least one concept key");
        }
        Map<String, UUID> byKey = courses.concepts(courseId).stream()
                .collect(Collectors.toMap(CourseViews.Concept::key, CourseViews.Concept::id));
        List<UUID> out = new ArrayList<>();
        for (String k : new LinkedHashSet<>(keys)) {
            UUID id = byKey.get(k);
            if (id == null) {
                throw new DomainRuleException("unknown-concept", "No concept " + k);
            }
            out.add(id);
        }
        return out;
    }

    static AnswerKey key(ItemType type, KeyIn in, List<String> options) {
        if (in == null) {
            throw ApiException.invalid("key is required");
        }
        return switch (type) {
            case MCQ -> {
                if (options.size() < 2 || options.size() > 8 || options.stream().anyMatch(o -> o == null || o.isBlank())) {
                    throw new DomainRuleException("bad-options", "An MCQ has 2 to 8 non-blank options");
                }
                if (new HashSet<>(options.stream().map(ShortAnswerGrader::normalise).toList()).size() != options.size()) {
                    throw new DomainRuleException("bad-options", "MCQ options must differ");
                }
                if (in.choices() == null || in.choices().stream().anyMatch(c -> c == null || c < 0 || c >= options.size())) {
                    throw new DomainRuleException("invalid-key", "key.choices must index the options");
                }
                yield new AnswerKey.Mcq(Set.copyOf(in.choices()));
            }
            case NUMERIC -> {
                if (!options.isEmpty()) {
                    throw new DomainRuleException("bad-options", "Only MCQs have options");
                }
                if (in.value() == null) {
                    throw new DomainRuleException("invalid-key", "key.value is required");
                }
                yield new AnswerKey.Numeric(in.value(), in.tolerance() == null ? 0 : in.tolerance());
            }
            case SHORT_ANSWER -> {
                if (!options.isEmpty()) {
                    throw new DomainRuleException("bad-options", "Only MCQs have options");
                }
                yield new AnswerKey.ShortAnswer(in.accepted(), in.keywords());
            }
        };
    }

    private static ItemType parseType(String s) {
        if (s == null) {
            return null;
        }
        try {
            return ItemType.valueOf(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ItemStatus parseStatus(String s) {
        try {
            return ItemStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.invalid("status must be DRAFT, LIVE, REJECTED or RETIRED");
        }
    }

    private static String stripFence(String s) {
        String t = s.strip();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("```\\s*$", "");
        }
        return t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    static String shorten(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
