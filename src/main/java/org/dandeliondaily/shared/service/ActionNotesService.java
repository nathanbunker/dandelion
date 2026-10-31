package org.dandeliondaily.shared.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;

/**
 * The only writer of an action's notes: one plain-text value per action, kept
 * exactly as typed apart from line endings. Every UI page and MCP call it, and
 * every write reaches the action's SHARED twins so they always hold the same
 * text.
 */
public class ActionNotesService {

    public static final int MAX_LENGTH = 20000;

    public static final String CHANGED_MESSAGE = "Notes changed since you opened them; reload.";

    /** Outcome of a write. When {@code isOk()} is false nothing was changed. */
    public static class Result {
        private boolean ok;
        private boolean changed;
        private boolean tailReattached;
        private String text;
        private String errorMessage;

        private static Result refused(String errorMessage) {
            Result result = new Result();
            result.errorMessage = errorMessage;
            return result;
        }

        private static Result saved(String text, boolean changed, boolean tailReattached) {
            Result result = new Result();
            result.ok = true;
            result.text = text;
            result.changed = changed;
            result.tailReattached = tailReattached;
            return result;
        }

        public boolean isOk() {
            return ok;
        }

        /** True when the stored text differs from what was there before. */
        public boolean isChanged() {
            return changed;
        }

        /** True when text appended by someone else was kept after the new text. */
        public boolean isTailReattached() {
            return tailReattached;
        }

        /** The notes as stored after the write, "" when there are none. */
        public String getText() {
            return text;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /** The notes as stored, "" when there are none. */
    public String read(ActionNext action) {
        return action == null || action.getNextNotes() == null ? "" : action.getNextNotes();
    }

    /**
     * The notes split into lines for list-style display, skipping blank lines.
     * Lines are otherwise kept as typed.
     */
    public List<String> displayLines(ActionNext action) {
        List<String> lines = new ArrayList<String>();
        for (String line : normalize(read(action)).split("\n")) {
            if (line.trim().length() > 0) {
                lines.add(trimTrailing(line));
            }
        }
        return lines;
    }

    /**
     * Notepad save: replaces the whole text. {@code baseText} is the text the
     * editor started from; null skips the check (for callers with their own
     * conflict check, such as MCP's asOf). If someone only appended since the
     * editor opened, the appended tail is kept after {@code newText}; any other
     * change since then refuses the save, unless {@code newText} equals
     * {@code baseText} (the editor left the notes alone), which writes nothing.
     *
     * @param changeDate set as nextChangeDate on every twin that changes; null
     *                   leaves change dates to the caller
     */
    public Result replace(Session dataSession, ActionNext action, String newText, String baseText,
            Date changeDate) {
        String current = normalize(read(action));
        String replacement = normalize(newText);
        boolean tailReattached = false;
        if (baseText != null) {
            String base = normalize(baseText);
            if (replacement.equals(base)) {
                // The editor left the notes alone, so whatever is stored now stands.
                return Result.saved(current, false, false);
            }
            if (!current.equals(base)) {
                if (!current.startsWith(base)) {
                    return Result.refused(CHANGED_MESSAGE);
                }
                String tail = current.substring(base.length());
                if (base.length() > 0 && tail.startsWith("\n")) {
                    tail = tail.substring(1);
                }
                replacement = join(replacement, tail);
                tailReattached = true;
            }
        }
        return write(dataSession, action, current, replacement, tailReattached, changeDate);
    }

    /**
     * Quick add: appends {@code text} on a new line at the end of the notes.
     * Trailing whitespace is trimmed from {@code text}; nothing else is added.
     */
    public Result append(Session dataSession, ActionNext action, String text, Date changeDate) {
        String addition = trimTrailing(normalize(text));
        if (addition.trim().length() == 0) {
            return Result.refused("Note is required.");
        }
        String current = normalize(read(action));
        return write(dataSession, action, current, join(current, addition), false, changeDate);
    }

    /**
     * Notes for an action being created, which has no twins yet. Over-long
     * text is rejected with an IllegalArgumentException.
     */
    public void initialize(ActionNext newAction, String text) {
        String value = normalize(text);
        String error = lengthError(value);
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        newAction.setNextNotes(toStored(value));
    }

    /** Null when {@code text} fits, otherwise the message to show. */
    public String lengthError(String text) {
        if (normalize(text).length() > MAX_LENGTH) {
            return "Notes are limited to " + String.format(Locale.US, "%,d", MAX_LENGTH) + " characters.";
        }
        return null;
    }

    private Result write(Session dataSession, ActionNext action, String current, String replacement,
            boolean tailReattached, Date changeDate) {
        String error = lengthError(replacement);
        if (error != null) {
            return Result.refused(error);
        }
        String stored = toStored(replacement);
        for (ActionNext twin : SharedActionSiblings.resolve(dataSession, action)) {
            if (normalize(read(twin)).equals(replacement)) {
                continue;
            }
            twin.setNextNotes(stored);
            if (changeDate != null) {
                twin.setNextChangeDate(changeDate);
            }
            if (twin.getActionNextId() > 0) {
                dataSession.update(twin);
            }
        }
        return Result.saved(stored == null ? "" : stored, !current.equals(replacement), tailReattached);
    }

    /** Joins two blocks with a newline unless {@code first} already ends in one. */
    private static String join(String first, String second) {
        if (second.length() == 0) {
            return first;
        }
        if (first.length() == 0) {
            return second;
        }
        return first.endsWith("\n") ? first + second : first + "\n" + second;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String trimTrailing(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** Whitespace-only notes are stored as null. */
    private static String toStored(String text) {
        return text.trim().length() == 0 ? null : text;
    }
}
