package com.mentorloop.assessment.domain;

/** One grader per item type. */
public interface GradingStrategy {

    ItemType type();

    Grade grade(AnswerKey key, Answer answer, int optionCount);
}
