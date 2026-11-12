package org.dandeliondaily.narrative.render;

/** A small line-by-line diff (longest common subsequence) rendered as HTML. */
final class LineDiff {

    /** Above this many line pairs the diff is skipped and both texts are shown whole. */
    private static final long MAX_CELLS = 400_000L;
    /** Unchanged lines kept on each side of an edit. */
    private static final int CONTEXT = 2;

    private LineDiff() {
    }

    static String html(String before, String after) {
        String[] a = split(before);
        String[] b = split(after);
        StringBuilder sb = new StringBuilder("<div class=\"nr-diff\">");
        if ((long) a.length * b.length > MAX_CELLS) {
            for (String line : a) {
                row(sb, "d-del", line);
            }
            for (String line : b) {
                row(sb, "d-add", line);
            }
            return sb.append("</div>").toString();
        }
        int[][] lcs = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        boolean changed = false;
        java.util.List<String> same = new java.util.ArrayList<String>();
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) {
                same.add(a[i]);
                i++;
                j++;
                continue;
            }
            flushSame(sb, same, changed, false);
            changed = true;
            if (j < b.length && (i == a.length || lcs[i][j + 1] >= lcs[i + 1][j])) {
                row(sb, "d-add", b[j++]);
            } else {
                row(sb, "d-del", a[i++]);
            }
        }
        flushSame(sb, same, changed, true);
        if (!changed) {
            sb.setLength(0);
            return "<p class=\"nr-muted\">No edits: the final text is exactly what was generated.</p>";
        }
        return sb.append("</div>").toString();
    }

    /** Writes a run of unchanged lines, keeping only a little context around the edits. */
    private static void flushSame(StringBuilder sb, java.util.List<String> same, boolean afterChange,
            boolean atEnd) {
        int keepBefore = afterChange ? CONTEXT : 0;
        int keepAfter = atEnd ? 0 : CONTEXT;
        if (same.size() <= keepBefore + keepAfter + 1) {
            for (String line : same) {
                row(sb, "d-same", line);
            }
        } else {
            for (int k = 0; k < keepBefore; k++) {
                row(sb, "d-same", same.get(k));
            }
            int hidden = same.size() - keepBefore - keepAfter;
            sb.append("<div class=\"d-gap\">&middot;&middot;&middot; ").append(hidden).append(" unchanged line")
                    .append(hidden == 1 ? "" : "s").append("</div>");
            for (int k = same.size() - keepAfter; k < same.size(); k++) {
                row(sb, "d-same", same.get(k));
            }
        }
        same.clear();
    }

    private static String[] split(String text) {
        return text == null ? new String[0] : text.replace("\r\n", "\n").split("\n", -1);
    }

    private static void row(StringBuilder sb, String cls, String line) {
        sb.append("<div class=\"").append(cls).append("\">")
                .append(line.isEmpty() ? "&nbsp;" : NarrativeReviewRenderer.esc(line)).append("</div>");
    }
}
