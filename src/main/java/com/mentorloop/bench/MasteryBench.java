package com.mentorloop.bench;

import com.mentorloop.adaptive.domain.BktModel;
import com.mentorloop.adaptive.domain.BktParams;

import java.util.Arrays;
import java.util.Random;

/**
 * How trustworthy is "mastered"? Simulates learners whose hidden state follows the BKT process (known at start
 * with pInit, learns after each step with the true pLearn, answers with slip/guess), runs the production update on
 * their answers, and records, at the moment the estimate first crosses the threshold, how many answers it took and
 * whether the learner really was in the known state. Synthetic and seeded; it measures the model against its own
 * assumptions plus one mismatch (slower learners than the configured pLearn), not real students.
 *
 * <p>Usage: {@code MasteryBench [learners] [seed]}
 */
public final class MasteryBench {

    public static void main(String[] args) {
        int learners = args.length > 0 ? Integer.parseInt(args[0]) : 20_000;
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 7;
        BktParams configured = new BktParams(0.2, 0.15, 0.1, 0.2);
        double threshold = 0.95;
        System.out.printf("configured pInit=0.2 pLearn=0.15 pSlip=0.1 pGuess=0.2, threshold %.2f, %d learners, seed %d%n",
                threshold, learners, seed);
        run("true params = configured", configured, configured, threshold, learners, seed);
        run("true pLearn = 0.05 (slower)", new BktParams(0.2, 0.05, 0.1, 0.2), configured, threshold, learners, seed);
        run("true pGuess = 0.35, pSlip = 0.15", new BktParams(0.2, 0.15, 0.15, 0.35), configured, threshold, learners,
                seed);
    }

    static void run(String label, BktParams truth, BktParams model, double threshold, int learners, long seed) {
        Random rnd = new Random(seed);
        BktModel bkt = new BktModel();
        int[] steps = new int[learners];
        int reallyKnown = 0;
        int never = 0;
        long t0 = System.nanoTime();
        long updates = 0;
        for (int l = 0; l < learners; l++) {
            boolean known = rnd.nextDouble() < truth.pInit();
            double est = model.pInit();
            int n = 0;
            while (est < threshold && n < 200) {
                boolean correct = known ? rnd.nextDouble() >= truth.pSlip() : rnd.nextDouble() < truth.pGuess();
                est = bkt.update(est, correct, model);
                updates++;
                n++;
                if (!known && rnd.nextDouble() < truth.pLearn()) {
                    known = true;
                }
            }
            if (est < threshold) {
                never++;
            }
            steps[l] = n;
            // The estimate is P(known after this step's learning transition), so compare it with the state after it.
            if (est >= threshold && known) {
                reallyKnown++;
            }
        }
        long nanos = System.nanoTime() - t0;
        Arrays.sort(steps);
        System.out.printf("%-34s answers to mastery p50=%d p90=%d max=%d; known when flagged %.1f%%; never in 200: %d; "
                        + "%.0f ns/update%n", label, steps[learners / 2], steps[(int) (learners * 0.9)],
                steps[learners - 1], 100.0 * reallyKnown / (learners - never), never, (double) nanos / updates);
    }
}
