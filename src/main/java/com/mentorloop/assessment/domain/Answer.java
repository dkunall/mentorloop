package com.mentorloop.assessment.domain;

import java.util.List;

/** What a learner submitted. Exactly one of the fields is meaningful, depending on the item type. */
public record Answer(List<Integer> choices, Double number, String text) {

    public Answer {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
