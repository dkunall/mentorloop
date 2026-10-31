package com.mentorloop.tutor.domain;

import com.mentorloop.shared.text.Text;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A deterministic, dependency-free text embedding: signed feature hashing of terms, word bigrams and character
 * trigrams into {@link #DIMENSIONS} dimensions, log-scaled and L2-normalised.
 *
 * <p>This is a lexical embedding. It gets plurals, word order and partial words, not synonyms or paraphrase; a
 * learned embedding model would sit behind the same {@code Embedder} port. It is here so tests, CI and the demo
 * need no model download and no API key, and give the same vectors every run.
 */
public final class HashingEmbedder {

    public static final int DIMENSIONS = 384;

    public float[] embed(String text) {
        double[] v = new double[DIMENSIONS];
        List<String> terms = Text.terms(text);
        for (int i = 0; i < terms.size(); i++) {
            String t = terms.get(i);
            add(v, "w:" + t, 1.0);
            if (i + 1 < terms.size()) {
                add(v, "b:" + t + " " + terms.get(i + 1), 0.6);
            }
            String padded = "^" + t + "$";
            for (int j = 0; j + 3 <= padded.length(); j++) {
                add(v, "c:" + padded.substring(j, j + 3), 0.25);
            }
        }
        double norm = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] = Math.signum(v[i]) * Math.log1p(Math.abs(v[i]));
            norm += v[i] * v[i];
        }
        float[] out = new float[DIMENSIONS];
        if (norm == 0) {
            return out;
        }
        double inv = 1 / Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] * inv);
        }
        return out;
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static void add(double[] v, String feature, double weight) {
        long h = fnv1a(feature);
        int idx = (int) Long.remainderUnsigned(h, DIMENSIONS);
        double sign = ((h >>> 63) == 0) ? 1 : -1;
        v[idx] += sign * weight;
    }

    static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        // FNV's high bits mix poorly for short inputs; one xorshift round spreads them before we take the sign.
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return h;
    }

    /** pgvector's text form. */
    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
