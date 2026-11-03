package org.dandeliondaily.shared.service;

import org.openimmunizationsoftware.pt.model.Project;

/**
 * The one place a project's icon (an emoji shown on the Focused Action page, Todo lists and the
 * like) is cleaned up and saved, used by the dashboard project form and the update_project_icon MCP
 * tool so both apply the same rules.
 *
 * <p>The project_icon column is varchar(8) in utf8mb4, which counts characters (code points), not
 * Java chars. An emoji outside the Basic Multilingual Plane is two Java chars, and a compound emoji
 * (family, skin tone, flag) is several code points, so limits here are counted in code points and a
 * value is never cut through the middle of a character.
 */
public class ProjectIconService {

    /** Matches the project_icon column length. */
    public static final int MAX_CODE_POINTS = 8;

    /** Thrown when a requested icon cannot be stored as given. */
    public static class InvalidIconException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public InvalidIconException(String message) {
            super(message);
        }
    }

    /**
     * Trims the value and returns null for blank (no icon). Rejects a value longer than the column
     * holds rather than silently cutting it, since a cut can leave a broken emoji.
     */
    public static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return null;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints > MAX_CODE_POINTS) {
            throw new InvalidIconException("Project icon is too long (" + codePoints
                    + " characters; the limit is " + MAX_CODE_POINTS + "). Use a single emoji.");
        }
        return value;
    }

    /**
     * Lenient form for older pages that cannot report an error: trims, then cuts to the column
     * length on a code point boundary. Returns null for blank.
     */
    public static String normalizeLenient(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return null;
        }
        if (value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            value = value.substring(0, value.offsetByCodePoints(0, MAX_CODE_POINTS)).trim();
        }
        return value.length() == 0 ? null : value;
    }

    /** Normalizes and sets the icon; returns the previous icon (null when there was none). */
    public String setIcon(Project project, String raw) {
        String icon = normalize(raw);
        String previous = project.getProjectIcon();
        project.setProjectIcon(icon);
        return previous;
    }
}
