package com.mentorloop.tutor.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion (Cormack et al., 2009): score(d) = sum over lists of 1 / (k + rank). Rank-based, so the
 * lexical and vector scores never need to be on the same scale.
 */
public final class RankFusion {

    public static final int K = 60;

    private RankFusion() {
    }

    public static <T extends Comparable<T>> List<T> fuse(List<List<T>> rankings, int limit) {
        Map<T, Double> score = new HashMap<>();
        for (List<T> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                score.merge(ranking.get(i), 1.0 / (K + i + 1), Double::sum);
            }
        }
        List<Map.Entry<T, Double>> entries = new ArrayList<>(score.entrySet());
        entries.sort(Map.Entry.<T, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return entries.stream().limit(limit).map(Map.Entry::getKey).toList();
    }
}
