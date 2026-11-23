package org.dandeliondaily.planahead.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a template save or delete did to the template's generated actions. */
public class TemplateSyncCounts {

    /** Future open instances whose fields were refreshed from the template. */
    int refreshed;
    /** Future open instances cancelled (schedule no longer matches, auto-generate off, or deleted). */
    int cancelled;
    /** New instances generated in the advance window. */
    int created;

    public int getRefreshed() {
        return refreshed;
    }

    public int getCancelled() {
        return cancelled;
    }

    public int getCreated() {
        return created;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("refreshed", Integer.valueOf(refreshed));
        map.put("cancelled", Integer.valueOf(cancelled));
        map.put("created", Integer.valueOf(created));
        return map;
    }

    /** "2 refreshed, 1 cancelled, 3 created"-style summary, or "" when nothing changed. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        append(sb, refreshed, "upcoming action", "refreshed");
        append(sb, cancelled, "upcoming action", "cancelled");
        append(sb, created, "new action", "created");
        return sb.toString();
    }

    private static void append(StringBuilder sb, int count, String noun, String verb) {
        if (count <= 0) {
            return;
        }
        sb.append(sb.length() == 0 ? "" : ", ").append(count).append(' ').append(noun)
                .append(count == 1 ? "" : "s").append(' ').append(verb);
    }
}
