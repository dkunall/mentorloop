package com.mentorloop.adaptive.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Re-estimates item difficulty from real responses with the Rasch (1PL) model, P(right) = 1 / (1 + e^-(theta - b)).
 * Joint maximum likelihood with a N(0, 1) prior on every parameter (so learners or items with all-right or
 * all-wrong answers still get finite estimates), solved by alternating one-dimensional Newton steps.
 *
 * <p>Unlike the share of right answers, this accounts for who answered: an item that only strong learners were
 * shown is not mistaken for an easy one. Difficulties are centred on mean 0.
 */
public final class RaschCalibrator {

    public record Response(String learner, UUID item, boolean correct) {
    }

    public record Fit(Map<UUID, Double> difficulty, Map<UUID, Integer> responses, Map<String, Double> ability,
                      int iterations) {
    }

    private static final double PRIOR_PRECISION = 1.0;

    public Fit fit(List<Response> responses, int maxIterations) {
        Map<UUID, Double> b = new HashMap<>();
        Map<String, Double> theta = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (Response r : responses) {
            b.putIfAbsent(r.item(), 0.0);
            theta.putIfAbsent(r.learner(), 0.0);
            counts.merge(r.item(), 1, Integer::sum);
        }
        Map<String, List<Response>> byLearner = new HashMap<>();
        Map<UUID, List<Response>> byItem = new HashMap<>();
        for (Response r : responses) {
            byLearner.computeIfAbsent(r.learner(), k -> new java.util.ArrayList<>()).add(r);
            byItem.computeIfAbsent(r.item(), k -> new java.util.ArrayList<>()).add(r);
        }
        int it = 0;
        for (; it < maxIterations; it++) {
            double change = 0;
            for (var e : byLearner.entrySet()) {
                double t = theta.get(e.getKey());
                double grad = -PRIOR_PRECISION * t;
                double info = PRIOR_PRECISION;
                for (Response r : e.getValue()) {
                    double p = sigmoid(t - b.get(r.item()));
                    grad += (r.correct() ? 1 : 0) - p;
                    info += p * (1 - p);
                }
                double step = clampStep(grad / info);
                theta.put(e.getKey(), t + step);
                change = Math.max(change, Math.abs(step));
            }
            for (var e : byItem.entrySet()) {
                double d = b.get(e.getKey());
                double grad = -PRIOR_PRECISION * d;
                double info = PRIOR_PRECISION;
                for (Response r : e.getValue()) {
                    double p = sigmoid(theta.get(r.learner()) - d);
                    grad += p - (r.correct() ? 1 : 0);
                    info += p * (1 - p);
                }
                double step = clampStep(grad / info);
                b.put(e.getKey(), d + step);
                change = Math.max(change, Math.abs(step));
            }
            if (change < 1e-6) {
                it++;
                break;
            }
        }
        double mean = b.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        b.replaceAll((k, v) -> v - mean);
        theta.replaceAll((k, v) -> v - mean);
        return new Fit(Map.copyOf(b), Map.copyOf(counts), Map.copyOf(theta), it);
    }

    private static double clampStep(double s) {
        return Math.max(-1.0, Math.min(1.0, s));
    }

    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
