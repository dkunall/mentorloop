package com.mentorloop.assessment.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Looks up the strategy for an item type. */
public final class Graders {

    private final Map<ItemType, GradingStrategy> byType = new EnumMap<>(ItemType.class);

    public Graders(List<GradingStrategy> strategies) {
        strategies.forEach(s -> byType.put(s.type(), s));
        for (ItemType t : ItemType.values()) {
            if (!byType.containsKey(t)) {
                throw new IllegalStateException("no grader for " + t);
            }
        }
    }

    public static Graders standard() {
        return new Graders(List.of(new McqGrader(), new NumericGrader(), new ShortAnswerGrader()));
    }

    public Grade grade(ItemType type, AnswerKey key, Answer answer, int optionCount) {
        if (key.type() != type) {
            throw new IllegalStateException("key type " + key.type() + " for a " + type + " item");
        }
        return byType.get(type).grade(key, answer, optionCount);
    }
}
