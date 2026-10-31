package com.mentorloop.llm.adapter;

import com.mentorloop.llm.application.LlmPort;
import com.mentorloop.llm.application.LlmReply;
import com.mentorloop.llm.application.LlmRequest;
import com.mentorloop.shared.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A deterministic stand-in for a language model. Not a language model: it extracts, it does not reason.
 * <ul>
 *   <li>ANSWER: the up-to-three context sentences sharing the most terms with the question, each cited;</li>
 *   <li>HINT: names the excerpt to re-read and asks a question, quoting nothing from it;</li>
 *   <li>GENERATE_ITEMS: cloze questions from sentences that define a {@code **bold**} term.</li>
 * </ul>
 * Same input, same output, which is what tests and the demo need.
 */
public final class ScriptedLlm implements LlmPort {

    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]{2,60})\\*\\*");

    @Override
    public LlmReply complete(LlmRequest request) {
        String text = switch (request.mode()) {
            case ANSWER -> answer(request);
            case HINT -> hint(request);
            case GENERATE_ITEMS -> generate(request);
        };
        return new LlmReply(text, "scripted", "scripted-extractive-v1");
    }

    private record Scored(int context, int position, String sentence, int overlap) {
    }

    private static String answer(LlmRequest r) {
        Set<String> q = new HashSet<>(Text.terms(r.question()));
        List<Scored> scored = new ArrayList<>();
        for (int c = 0; c < r.context().size(); c++) {
            List<String> sentences = Text.sentences(r.context().get(c).text().replace("**", ""));
            for (int s = 0; s < sentences.size(); s++) {
                int overlap = (int) new HashSet<>(Text.terms(sentences.get(s))).stream().filter(q::contains).count();
                if (overlap > 0) {
                    scored.add(new Scored(c + 1, s, sentences.get(s), overlap));
                }
            }
        }
        if (scored.isEmpty()) {
            return "I can't find that in the course material.";
        }
        List<Scored> top = scored.stream()
                .sorted(Comparator.comparingInt(Scored::overlap).reversed()
                        .thenComparingInt(Scored::context).thenComparingInt(Scored::position))
                .limit(3)
                .sorted(Comparator.comparingInt(Scored::context).thenComparingInt(Scored::position))
                .toList();
        StringBuilder sb = new StringBuilder();
        for (Scored s : top) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            String sentence = s.sentence().replaceAll("[.!?]+$", "");
            sb.append(sentence).append(" [").append(s.context()).append("].");
        }
        return sb.toString();
    }

    private static String hint(LlmRequest r) {
        if (r.context().isEmpty()) {
            return "I can only give hints from the course material during a quiz, and I found nothing relevant.";
        }
        return "Without giving the answer away: re-read the section \"" + r.context().getFirst().label()
                + "\" [1]. What does it say happens in the situation the question describes?";
    }

    private static String generate(LlmRequest r) {
        int wanted;
        try {
            wanted = Math.max(1, Math.min(10, Integer.parseInt(r.question().strip())));
        } catch (NumberFormatException e) {
            wanted = 3;
        }
        List<String> items = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int c = 0; c < r.context().size() && items.size() < wanted; c++) {
            for (String sentence : Text.sentences(r.context().get(c).text())) {
                var m = BOLD.matcher(sentence);
                if (!m.find() || items.size() >= wanted) {
                    continue;
                }
                String term = m.group(1).strip();
                if (!used.add(term.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String stem = sentence.replace("**" + m.group(1) + "**", "_____").replace("**", "");
                items.add("{\"stem\":" + json("Fill in the blank: " + stem) + ",\"answer\":" + json(term)
                        + ",\"source\":" + (c + 1) + "}");
            }
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
