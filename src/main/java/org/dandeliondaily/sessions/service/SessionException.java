package org.dandeliondaily.sessions.service;

import java.util.Map;

/**
 * A refusal from the session, launch or runner services: an HTTP status, a stable code and a
 * readable message. Thrown before anything is written, or with the caller expected to roll back.
 */
public class SessionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;
    private final transient Map<String, Object> details;

    public SessionException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public SessionException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public static SessionException notFound(String what) {
        return new SessionException(404, "not_found", what + " not found.");
    }

    public static SessionException bad(String message) {
        return new SessionException(400, "bad_request", message);
    }

    public static SessionException conflict(String code, String message) {
        return new SessionException(409, code, message);
    }
}
