package com.mentorloop.tutor.domain;

import com.mentorloop.shared.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a lesson (light markdown: '#' headings, blank-line paragraphs) into chunks of about
 * {@code targetWords} words. A chunk never crosses a heading, so its citation label (lesson title + heading)
 * describes everything in it. Paragraphs longer than {@code maxWords} are split at sentence boundaries.
 */
public final class Chunker {

    public record Piece(int ord, String heading, String text) {
    }

    private final int targetWords;
    private final int maxWords;

    public Chunker(int targetWords, int maxWords) {
        if (targetWords < 10 || maxWords < targetWords) {
            throw new IllegalArgumentException("need 10 <= targetWords <= maxWords");
        }
        this.targetWords = targetWords;
        this.maxWords = maxWords;
    }

    public static Chunker standard() {
        return new Chunker(90, 160);
    }

    public List<Piece> split(String markdown) {
        List<Piece> out = new ArrayList<>();
        String heading = "";
        StringBuilder current = new StringBuilder();
        int words = 0;
        for (String block : markdown.replace("\r\n", "\n").split("\n\\s*\n")) {
            String b = block.strip();
            if (b.isEmpty()) {
                continue;
            }
            if (b.startsWith("#")) {
                String[] lines = b.split("\n", 2);
                words = flush(out, heading, current, words);
                heading = lines[0].replaceFirst("^#+\\s*", "").strip();
                if (lines.length == 1) {
                    continue;
                }
                b = lines[1].strip();
            }
            for (String para : splitLong(b.replaceAll("\\s+", " "))) {
                int n = Text.words(para).size();
                if (words > 0 && words + n > targetWords) {
                    words = flush(out, heading, current, words);
                }
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(para);
                words += n;
            }
        }
        flush(out, heading, current, words);
        return out;
    }

    private List<String> splitLong(String para) {
        if (Text.words(para).size() <= maxWords) {
            return List.of(para);
        }
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : Text.sentences(para)) {
            int w = Text.words(s).size();
            if (n > 0 && n + w > targetWords) {
                out.add(sb.toString());
                sb.setLength(0);
                n = 0;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(s);
            n += w;
        }
        if (!sb.isEmpty()) {
            out.add(sb.toString());
        }
        return out;
    }

    private static int flush(List<Piece> out, String heading, StringBuilder current, int words) {
        if (words > 0) {
            out.add(new Piece(out.size(), heading, current.toString()));
        }
        current.setLength(0);
        return 0;
    }
}
