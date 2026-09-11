package com.gitlab.sync.confluence.comments.git;

import com.gitlab.sync.confluence.macro.MarkdownToStorageConverter;
import com.gitlab.sync.confluence.macro.MarkdownToStorageConverter.BlockSpan;

import java.util.List;

/**
 * Maps between the frontend's block index space ({@code lineAnchor}) and the
 * markdown source lines, and matches a block's text across file versions.
 *
 * <p>The block list comes from {@link MarkdownToStorageConverter}'s line
 * sink: the converter records one {@link BlockSpan} per commentable element
 * ({@code p, li, h1..h6, td}) in the exact emission order, so span index ==
 * the frontend's 0-based {@code lineAnchor} (see
 * {@code BLOCK_SELECTOR} in {@code comments.js}).</p>
 *
 * <p>The similarity function is a port of {@code textSimilarity()} from
 * {@code comments.js} — the R9 reanchor algorithm — so server-side reanchor
 * decisions match what the client already computes (threshold 0.8).</p>
 */
public final class LineAnchorMapper {

    /** R9 reanchor threshold — the same value as in comments.js. */
    public static final double SIMILARITY_THRESHOLD = 0.8;

    private LineAnchorMapper() {
        // static utility
    }

    /**
     * @return the commentable blocks of a markdown document, in the
     *         frontend's {@code lineAnchor} order, with 1-based source lines
     */
    public static List<BlockSpan> blockSpans(String markdown, String krokiUrl) {
        List<BlockSpan> spans = new java.util.ArrayList<>();
        MarkdownToStorageConverter.convert(markdown, krokiUrl, spans);
        return spans;
    }

    /**
     * Find the block containing a source line.
     *
     * @param line 1-based source line
     * @return the 0-based block index, or -1 when no block covers the line
     */
    public static int blockIndexAtLine(List<BlockSpan> spans, int line) {
        for (int i = 0; i < spans.size(); i++) {
            BlockSpan s = spans.get(i);
            if (line >= s.startLine && line <= s.endLine) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find the best matching block in {@code spans} for a block's text in an
     * older/newer version of the file.
     *
     * <p>Matching mirrors the client R9 rules: an exact normalized match
     * wins (closest to {@code preferIndex} among duplicates); otherwise the
     * highest-similarity block at or above {@link #SIMILARITY_THRESHOLD}
     * (closest to {@code preferIndex} among near-ties).</p>
     *
     * @param preferIndex the index in the CURRENT document the block held
     *                    before the version change (used to break ties)
     * @return the matched span, or {@code null} (the block was deleted or
     *         rewritten beyond recognition — an orphan)
     */
    public static BlockSpan bestMatch(List<BlockSpan> spans, String text, int preferIndex) {
        String stored = normText(text);
        if (stored.isEmpty()) {
            return null;
        }
        int exact = -1;
        int best = -1;
        double bestScore = SIMILARITY_THRESHOLD;
        for (int j = 0; j < spans.size(); j++) {
            String nb = normText(spans.get(j).text);
            if (nb.isEmpty()) {
                continue;
            }
            if (nb.equals(stored)) {
                if (exact < 0
                        || Math.abs(j - preferIndex) < Math.abs(exact - preferIndex)) {
                    exact = j;
                }
                continue;
            }
            double score = similarity(text, spans.get(j).text);
            if (score > bestScore
                    || (score == bestScore && best >= 0
                            && Math.abs(j - preferIndex) < Math.abs(best - preferIndex))) {
                bestScore = score;
                best = j;
            }
        }
        if (exact >= 0) {
            return spans.get(exact);
        }
        return best >= 0 ? spans.get(best) : null;
    }

    // ------------------------------------------------------------------
    //  text similarity — port of comments.js normText/textSimilarity
    // ------------------------------------------------------------------

    public static String normText(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    public static double similarity(String a, String b) {
        String na = normText(a);
        String nb = normText(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0;
        }
        if (na.equals(nb)) {
            return 1;
        }
        if (na.indexOf(nb) >= 0 || nb.indexOf(na) >= 0) {
            return 0.9;
        }
        int d = levenshtein(na, nb);
        int max = Math.max(na.length(), nb.length());
        return 1 - (double) d / max;
    }

    private static int levenshtein(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.isEmpty()) {
            return b.length();
        }
        if (b.isEmpty()) {
            return a.length();
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int k = 1; k <= b.length(); k++) {
                int cost = a.charAt(i - 1) == b.charAt(k - 1) ? 0 : 1;
                cur[k] = Math.min(prev[k] + 1, Math.min(cur[k - 1] + 1, prev[k - 1] + cost));
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
