package com.mentorloop.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties("mentorloop")
public record MentorLoopProperties(Security security, Mail mail, Mastery mastery, Reviews reviews, Quiz quiz,
                                   Calibration calibration, Tutor tutor, Llm llm, Jobs jobs) {

    /**
     * @param instructorSubjects JWT subjects that get the INSTRUCTOR role
     * @param adminSubjects      JWT subjects that get the ADMIN role
     */
    public record Security(List<String> instructorSubjects, List<String> adminSubjects) {
    }

    /** @param learnerDomain learners' mail goes to {@code <subject>} if it has an @, else {@code <subject>@learnerDomain} */
    public record Mail(String from, String learnerDomain) {
    }

    /**
     * Default Bayesian Knowledge Tracing parameters for a new concept, and the p_known at which a concept counts
     * as mastered (which unlocks the concepts that depend on it).
     */
    public record Mastery(double pInit, double pLearn, double pSlip, double pGuess, double threshold) {
    }

    /**
     * @param dayLength how long one SM-2 "day" is. P1D in production; the demo shrinks it so reviews come due
     *                  while you watch
     * @param slowMillis a correct answer slower than this is graded "correct with effort" (SM-2 quality 3)
     * @param reminderEvery how often the reminder job looks for learners with reviews due
     */
    public record Reviews(Duration dayLength, long slowMillis, Duration reminderEvery) {
    }

    public record Quiz(int size, Duration timeLimit) {
    }

    /** @param minResponses an item's difficulty is re-estimated only once it has this many graded responses */
    public record Calibration(int minResponses, Duration every) {
    }

    /**
     * @param topK            chunks handed to the model per question
     * @param perMinute       tutor questions a learner may ask per minute
     * @param maxQuestionChars longer questions are rejected
     * @param retention       transcripts older than this are deleted
     */
    public record Tutor(int topK, int perMinute, int maxQuestionChars, Duration retention) {
    }

    /**
     * @param provider {@code scripted} (deterministic, no network; the default) or {@code openai} (any
     *                 OpenAI-compatible chat completions endpoint, e.g. a TokenGate gateway)
     * @param apiKey   only read by the openai provider; never logged
     */
    public record Llm(String provider, URI baseUrl, String model, String apiKey, Duration timeout) {
    }

    public record Jobs(boolean enabled) {
    }
}
