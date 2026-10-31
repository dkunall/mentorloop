package com.mentorloop.shared.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Tokenising shared by the embedder, the scripted model and the guard, so they agree on what a word is. */
public final class Text {

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:[.'][\\p{L}\\p{N}]+)*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9(\"*])");

    /** Common English function words; dropping them keeps "what is a stack" from matching every chunk on "is". */
    public static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "was", "were", "be", "been", "of",
            "to", "in", "on", "for", "and", "or", "it", "its", "this", "that", "these", "those", "with", "as", "at",
            "by", "from", "what", "which", "who", "how", "why", "when", "do", "does", "did", "can", "i", "you",
            "we", "they", "me", "my", "your", "if", "then", "than", "so", "not", "no", "about", "into", "there",
            "their", "has", "have", "had", "will", "would", "should", "could", "s");

    private Text() {
    }

    public static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    public static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        var m = WORD.matcher(fold(s));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Words minus stop words, with a light plural strip so "stacks" and "stack" meet. */
    public static List<String> terms(String s) {
        List<String> out = new ArrayList<>();
        for (String w : words(s)) {
            if (!STOP.contains(w)) {
                out.add(stem(w));
            }
        }
        return out;
    }

    public static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 3 && w.endsWith("es") && (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")
                || w.endsWith("sses"))) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    public static List<String> sentences(String s) {
        List<String> out = new ArrayList<>();
        for (String part : SENTENCE_END.split(s.strip())) {
            String t = part.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** True when {@code needle}'s words appear as a contiguous run of words in {@code haystack}. */
    public static boolean containsPhrase(String haystack, String needle) {
        List<String> n = words(needle);
        if (n.isEmpty()) {
            return false;
        }
        List<String> h = words(haystack);
        outer:
        for (int i = 0; i + n.size() <= h.size(); i++) {
            for (int j = 0; j < n.size(); j++) {
                if (!h.get(i + j).equals(n.get(j))) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
