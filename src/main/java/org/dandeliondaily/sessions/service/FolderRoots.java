package org.dandeliondaily.sessions.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The known folder roots and the rules for the relative path under each. A folder is stored as a
 * root key plus a relative path ("brain:projects/work-hygiene"), never as a full path, so the data
 * survives a Dropbox move or a second machine. The PC maps each root to a real folder and checks
 * the final path is still inside it; this class only keeps the stored form clean.
 */
public final class FolderRoots {

    public static final String BRAIN = "brain";
    public static final String DROPBOX = "dropbox";
    public static final String DEV = "dev";
    public static final List<String> ROOTS = Collections.unmodifiableList(Arrays.asList(BRAIN, DROPBOX, DEV));
    public static final int MAX_PATH_LENGTH = 500;
    public static final int MAX_LABEL_LENGTH = 80;

    private FolderRoots() {
    }

    /** A parsed folder: root key and normalized relative path ("" for the root itself). */
    public static final class Folder {
        public final String root;
        public final String path;

        public Folder(String root, String path) {
            this.root = root;
            this.path = path;
        }

        /** "brain:projects/work-hygiene", or "brain:" for the root itself. */
        public String spec() {
            return format(root, path);
        }
    }

    public static boolean isRoot(String root) {
        return root != null && ROOTS.contains(root);
    }

    public static String format(String root, String path) {
        return root + ":" + (path == null ? "" : path);
    }

    /**
     * Parses "root:relative/path". Throws IllegalArgumentException with a user-facing message when
     * the root is unknown or the path is absolute, escapes upward, or holds characters a folder
     * name can't carry safely.
     */
    public static Folder parse(String spec) {
        if (spec == null || spec.trim().length() == 0) {
            throw new IllegalArgumentException("A folder is required, e.g. brain:projects/work-hygiene.");
        }
        String text = spec.trim();
        int colon = text.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Folders start with a root: " + rootList()
                    + " (e.g. brain:projects/work-hygiene).");
        }
        String root = text.substring(0, colon).trim().toLowerCase();
        if (!isRoot(root)) {
            throw new IllegalArgumentException("Unknown folder root \"" + text.substring(0, colon).trim()
                    + "\". Use " + rootList() + ".");
        }
        return new Folder(root, normalizePath(text.substring(colon + 1)));
    }

    /**
     * Normalizes a relative path: backslashes become slashes, repeated and trailing slashes go.
     * Rejects absolute paths, drive letters, UNC paths, "." and ".." segments, control characters
     * and the characters Windows forbids in names.
     */
    public static String normalizePath(String raw) {
        String text = raw == null ? "" : raw.trim().replace('\\', '/');
        if (text.startsWith("/")) {
            throw new IllegalArgumentException("Use a path relative to the root, not an absolute path.");
        }
        if (text.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("Folder paths are limited to " + MAX_PATH_LENGTH + " characters.");
        }
        StringBuilder out = new StringBuilder();
        for (String segment : text.split("/")) {
            if (segment.length() == 0) {
                continue;
            }
            String trimmed = segment.trim();
            if (trimmed.equals(".") || trimmed.equals("..")) {
                throw new IllegalArgumentException("Folder paths can't contain \".\" or \"..\".");
            }
            if (!trimmed.equals(segment) || trimmed.endsWith(".")) {
                throw new IllegalArgumentException("Folder names can't start or end with a space or end with a dot.");
            }
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c < 32 || c == 127 || "<>:\"|?*%".indexOf(c) >= 0) {
                    throw new IllegalArgumentException("Folder paths can't contain \"" + (c < 32 ? "control characters" : String.valueOf(c))
                            + "\".");
                }
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(segment);
        }
        return out.toString();
    }

    public static String normalizeLabel(String raw) {
        String text = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (text.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException("Folder labels are limited to " + MAX_LABEL_LENGTH + " characters.");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 32) {
                throw new IllegalArgumentException("Folder labels can't contain control characters.");
            }
        }
        return text.length() == 0 ? null : text;
    }

    private static String rootList() {
        return "brain:, dropbox: or dev:";
    }
}
