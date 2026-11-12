package org.dandeliondaily.personal.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.model.WebApiClient;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Phones (Dandelion Sundial) as API clients. A phone signs in with a username and
 * password and gets a device key once; only the key's SHA-256 is stored, as
 * {@code "sha256:<hex>"} in {@code web_api_client.api_key}. The device starts PENDING
 * and works after its owner approves it on the Devices page. Revoking it stops it on
 * its next request. See docs/plans/Android App/Sundial Plan.md, step 1.
 */
public class DeviceService {

    public static final String HASH_PREFIX = "sha256:";
    public static final int MAX_DEVICE_NAME = 80;

    static final int MAX_FAILURES = 5;
    static final long FAILURE_WINDOW_MS = 15L * 60L * 1000L;
    private static final Map<String, Deque<Long>> FAILURES = new ConcurrentHashMap<String, Deque<Long>>();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The outcome of a sign-in: either a new device and its raw key, or an error. */
    public static final class SignInResult {
        public final WebApiClient client;
        public final String deviceKey;
        public final String errorCode;
        public final String errorMessage;

        private SignInResult(WebApiClient client, String deviceKey, String errorCode, String errorMessage) {
            this.client = client;
            this.deviceKey = deviceKey;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        static SignInResult ok(WebApiClient client, String deviceKey) {
            return new SignInResult(client, deviceKey, null, null);
        }

        static SignInResult error(String code, String message) {
            return new SignInResult(null, null, code, message);
        }

        public boolean isOk() {
            return errorCode == null;
        }
    }

    public SignInResult signIn(Session session, String username, String password, String deviceName) {
        String user = username == null ? "" : username.trim();
        String name = cleanDeviceName(deviceName);
        if (user.isEmpty() || password == null || password.isEmpty()) {
            return SignInResult.error("bad_request", "Username and password are required.");
        }
        if (name.isEmpty()) {
            return SignInResult.error("bad_request", "A device name is required.");
        }
        String throttleKey = user.toLowerCase();
        if (tooManyFailures(throttleKey, System.currentTimeMillis())) {
            return SignInResult.error("too_many_attempts", "Too many failed sign-ins. Try again in 15 minutes.");
        }
        // The same check LoginServlet.login makes.
        Query query = session.createQuery("from WebUser where username = :username and password = :password");
        query.setString("username", user);
        query.setString("password", password);
        @SuppressWarnings("unchecked")
        List<WebUser> users = query.list();
        if (users.isEmpty()) {
            recordFailure(throttleKey, System.currentTimeMillis());
            return SignInResult.error("invalid_credentials", "Invalid username or password.");
        }
        WebUser webUser = users.get(0);
        Integer workspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(session, webUser.getWebUserId());
        if (workspaceId == null) {
            return SignInResult.error("no_workspace", "This account has no workspace.");
        }
        String rawKey = newDeviceKey();
        WebApiClient client = new WebApiClient();
        client.setWebUser(webUser);
        client.setWorkspaceId(workspaceId);
        client.setClientType(WebApiClient.TYPE_DEVICE);
        client.setDeviceStatus(WebApiClient.STATUS_PENDING);
        client.setDeviceName(name);
        client.setAgentName(name);
        client.setApiKey(hashKey(rawKey));
        client.setEnabled(true);
        client.setCreateDate(new Date());
        session.save(client);
        FAILURES.remove(throttleKey);
        return SignInResult.ok(client, rawKey);
    }

    /** The user's devices, newest first, including revoked ones (the Devices page shows them all). */
    @SuppressWarnings("unchecked")
    public List<WebApiClient> listDevices(Session session, WebUser owner) {
        Query query = session.createQuery(
                "from WebApiClient where webUser = :webUser and clientType = :type order by createDate desc");
        query.setParameter("webUser", owner);
        query.setString("type", WebApiClient.TYPE_DEVICE);
        return query.list();
    }

    /** Approves one of the owner's own devices. Returns false if it isn't theirs or is revoked. */
    public boolean approve(Session session, WebUser owner, int clientId) {
        WebApiClient client = findOwnDevice(session, owner, clientId);
        if (client == null || WebApiClient.STATUS_REVOKED.equals(client.getDeviceStatus())) {
            return false;
        }
        client.setDeviceStatus(WebApiClient.STATUS_APPROVED);
        client.setApprovedDate(new Date());
        session.update(client);
        return true;
    }

    /** Revokes one of the owner's own devices. Returns false if it isn't theirs. */
    public boolean revoke(Session session, WebUser owner, int clientId) {
        WebApiClient client = findOwnDevice(session, owner, clientId);
        if (client == null) {
            return false;
        }
        revoke(session, client);
        return true;
    }

    /** Revokes a device directly (the phone signing itself out). */
    public void revoke(Session session, WebApiClient client) {
        client.setDeviceStatus(WebApiClient.STATUS_REVOKED);
        client.setRevokedDate(new Date());
        client.setEnabled(false);
        session.update(client);
    }

    private WebApiClient findOwnDevice(Session session, WebUser owner, int clientId) {
        WebApiClient client = (WebApiClient) session.get(WebApiClient.class, Integer.valueOf(clientId));
        if (client == null || !client.isDevice() || client.getWebUser() == null
                || client.getWebUser().getWebUserId() != owner.getWebUserId()) {
            return null;
        }
        return client;
    }

    /** 256 random bits, URL-safe Base64 without padding (43 characters). */
    static String newDeviceKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_PREFIX);
            for (byte b : hash) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    static String cleanDeviceName(String deviceName) {
        if (deviceName == null) {
            return "";
        }
        String name = deviceName.replaceAll("[\\p{Cntrl}]", " ").trim().replaceAll("\\s+", " ");
        return name.length() > MAX_DEVICE_NAME ? name.substring(0, MAX_DEVICE_NAME) : name;
    }

    static boolean tooManyFailures(String key, long now) {
        Deque<Long> times = FAILURES.get(key);
        if (times == null) {
            return false;
        }
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > FAILURE_WINDOW_MS) {
                times.pollFirst();
            }
            return times.size() >= MAX_FAILURES;
        }
    }

    static void recordFailure(String key, long now) {
        Deque<Long> times = FAILURES.computeIfAbsent(key, k -> new ArrayDeque<Long>());
        synchronized (times) {
            times.addLast(now);
        }
    }

    static void clearFailures() {
        FAILURES.clear();
    }
}
