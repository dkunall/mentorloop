package com.mentorloop.bench;

import com.mentorloop.assessment.domain.RaschCalibrator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Does the Rasch fit recover item difficulty, and what does a run cost? Draws learner ability ~ N(0,1) and item
 * difficulty ~ N(0,1), simulates each learner answering a random subset of items under the Rasch model, fits with
 * the production calibrator, and compares fitted with true difficulty (both centred, since Rasch fixes only
 * differences). Synthetic and seeded; real responses are messier than the model that generated these.
 *
 * <p>Usage: {@code CalibrationBench [learners] [items] [answeredPerLearner] [seed]}
 */
public final class CalibrationBench {

    public static void main(String[] args) {
        int learners = args.length > 0 ? Integer.parseInt(args[0]) : 2_000;
        int items = args.length > 1 ? Integer.parseInt(args[1]) : 60;
        int per = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 11;
        Random rnd = new Random(seed);
        List<UUID> ids = new ArrayList<>();
        Map<UUID, Double> trueB = new HashMap<>();
        for (int i = 0; i < items; i++) {
            UUID id = new UUID(seed, i);
            ids.add(id);
            trueB.put(id, rnd.nextGaussian());
        }
        List<RaschCalibrator.Response> responses = new ArrayList<>();
        for (int l = 0; l < learners; l++) {
            double theta = rnd.nextGaussian();
            List<UUID> pool = new ArrayList<>(ids);
            java.util.Collections.shuffle(pool, rnd);
            for (UUID item : pool.subList(0, Math.min(per, items))) {
                boolean correct = rnd.nextDouble() < RaschCalibrator.sigmoid(theta - trueB.get(item));
                responses.add(new RaschCalibrator.Response("l" + l, item, correct));
            }
        }
        RaschCalibrator rasch = new RaschCalibrator();
        for (int w = 0; w < 3; w++) {
            rasch.fit(responses, 200); // warm-up
        }
        int reps = 5;
        long best = Long.MAX_VALUE;
        RaschCalibrator.Fit fit = null;
        for (int r = 0; r < reps; r++) {
            long t0 = System.nanoTime();
            fit = rasch.fit(responses, 200);
            best = Math.min(best, System.nanoTime() - t0);
        }
        double mt = trueB.values().stream().mapToDouble(d -> d).average().orElse(0);
        RaschCalibrator.Fit f = fit;
        double mf = ids.stream().mapToDouble(id -> f.difficulty().get(id)).average().orElse(0);
        double sxy = 0, sxx = 0, syy = 0, se = 0;
        for (UUID id : ids) {
            double x = trueB.get(id) - mt;
            double y = f.difficulty().get(id) - mf;
            sxy += x * y;
            sxx += x * x;
            syy += y * y;
            se += (x - y) * (x - y);
        }
        System.out.printf("%d learners x %d answers over %d items = %d responses, seed %d%n", learners, per, items,
                responses.size(), seed);
        System.out.printf("fit: %d iterations, best of %d runs %.1f ms; difficulty recovery r=%.3f, RMSE=%.3f logits "
                        + "(true SD %.3f)%n", f.iterations(), reps, best / 1e6, sxy / Math.sqrt(sxx * syy),
                Math.sqrt(se / items), Math.sqrt(sxx / items));
    }
}
