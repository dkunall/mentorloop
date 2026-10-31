package com.mentorloop.tutor.domain;

import com.mentorloop.shared.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Citation markers are {@code [n]}, numbering the context chunks the model was given. Markers that point
 * outside the context are removed rather than trusted.
 *
 * @param text     reply with invalid markers stripped
 * @param cited    valid context numbers, in order of first use
 * @param coverage share of sentences carrying at least one valid marker
 */
public record Citations(String text, List<Integer> cited, double coverage, int removed) {

    private static final Pattern MARKER = Pattern.compile("\\s?\\[(\\d{1,3})]");

    public static Citations check(String reply, int contextSize) {
        Set<Integer> cited = new LinkedHashSet<>();
        int[] removed = {0};
        Matcher m = MARKER.matcher(reply);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= contextSize) {
                cited.add(n);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                removed[0]++;
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        String text = sb.toString().strip();
        List<String> sentences = Text.sentences(text);
        long withMarker = sentences.stream().filter(s -> MARKER.matcher(s).find()).count();
        double coverage = sentences.isEmpty() ? 0 : withMarker / (double) sentences.size();
        return new Citations(text, new ArrayList<>(cited), coverage, removed[0]);
    }

    public boolean grounded() {
        return !cited.isEmpty();
    }
}
