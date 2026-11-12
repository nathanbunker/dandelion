package org.dandeliondaily.personal.service;

import java.util.Date;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ApiIdempotency;

/**
 * Remembers the response to each phone write by its Idempotency-Key, so a retried tap (flaky
 * connection, double tap) gets the first answer back instead of completing or moving twice.
 */
public class IdempotencyService {

    public static final int MAX_KEY_LENGTH = 80;
    static final long KEEP_MS = 24L * 60L * 60L * 1000L;

    /** A stored response, or a key reused for a different request. */
    public static class Stored {
        public final int status;
        public final String body;
        public final boolean differentRequest;

        Stored(int status, String body, boolean differentRequest) {
            this.status = status;
            this.body = body;
            this.differentRequest = differentRequest;
        }
    }

    public static boolean isValidKey(String key) {
        return key != null && key.length() >= 8 && key.length() <= MAX_KEY_LENGTH && key.matches("[A-Za-z0-9_.:-]+");
    }

    /** The earlier response for this client and key, or null if this is the first time. */
    public Stored find(Session session, int clientId, String key, String path) {
        Query query = session.createQuery("from ApiIdempotency where clientId = :clientId and idemKey = :key");
        query.setParameter("clientId", Integer.valueOf(clientId));
        query.setParameter("key", key);
        ApiIdempotency row = (ApiIdempotency) query.uniqueResult();
        if (row == null) {
            return null;
        }
        return new Stored(row.getResponseStatus(), row.getResponseBody(), !row.getRequestPath().equals(path));
    }

    public void remember(Session session, int clientId, String key, String path, int status, String body,
            Date now) {
        Query purge = session.createQuery("delete from ApiIdempotency where createdDate < :cutoff");
        purge.setTimestamp("cutoff", new Date(now.getTime() - KEEP_MS));
        purge.executeUpdate();

        ApiIdempotency row = new ApiIdempotency();
        row.setClientId(clientId);
        row.setIdemKey(key);
        row.setRequestPath(path.length() > 200 ? path.substring(0, 200) : path);
        row.setResponseStatus(status);
        row.setResponseBody(body);
        row.setCreatedDate(now);
        session.save(row);
    }
}
