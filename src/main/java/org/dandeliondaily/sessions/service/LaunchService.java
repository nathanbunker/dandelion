package org.dandeliondaily.sessions.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.hibernate.LockMode;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionLaunch;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSession;
import org.openimmunizationsoftware.pt.model.Agent;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectFolder;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The Focused Action buttons, end to end on the server.
 * <ol>
 * <li>{@link #issue}: the signed-in page asks for a launch. Dandelion checks the action, folder,
 * agent and session, stores a single-use nonce (only its SHA-256) that expires in 60 seconds, and
 * returns a {@code dandelion:} URI carrying only the verb, numeric ids, the environment key and the
 * nonce. Never a path, command, prompt or credential.</li>
 * <li>{@link #redeem}: the PC's link handler (LAUNCHER key of the same user) trades the nonce for
 * what to open: the folder, the agent, the session to resume. A nonce works once, on the server
 * that minted it.</li>
 * <li>{@link #bind}: the session's SessionStart hook reports its session id; only then does it
 * become the action's session (guarded, see ActionSessionService.mayReplace).</li>
 * <li>{@link #end}: the window closed; the session is marked saved if this launch still owns it.</li>
 * </ol>
 * Callers own the transaction.
 */
public class LaunchService {

    public static final String VERB_FOLDER = "folder";
    public static final String VERB_TERMINAL = "terminal";
    public static final String VERB_START = "start";
    public static final String VERB_NEW = "new";
    public static final String VERB_RESUME = "resume";

    public static final String ENV_LOCAL = "local";
    public static final String ENV_PROD = "prod";

    /**
     * Five minutes, single use. The first click shows the browser's own "Open dandelion?" prompt,
     * which can take longer than a minute to read and accept; the nonce stays useless to anyone
     * else (only this user's LAUNCHER key on this server can redeem it, once).
     */
    public static final long NONCE_LIFETIME_MS = 5L * 60L * 1000L;
    static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{43}");
    static final Pattern SESSION_ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    static final Pattern ENV_KEY = Pattern.compile("[a-z]{2,12}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectFolderService folderService = new ProjectFolderService();
    private final AgentCatalogService agentCatalog = new AgentCatalogService();
    private final ActionSessionService sessionService = new ActionSessionService();

    /** What {@link #issue} returns to the page. */
    public static class Issued {
        public final int launchId;
        public final String uri;

        Issued(int launchId, String uri) {
            this.launchId = launchId;
            this.uri = uri;
        }
    }

    public static boolean isVerb(String verb) {
        return VERB_FOLDER.equals(verb) || VERB_TERMINAL.equals(verb) || VERB_START.equals(verb)
                || VERB_NEW.equals(verb) || VERB_RESUME.equals(verb);
    }

    /**
     * The environment a request came to: "local" for localhost, 127.0.0.1 and ::1, "prod" for
     * anything else, unless the deployment sets an explicit override (context param
     * dandelion.envKey). Local and production share ids, so the PC checks this key before it opens
     * anything.
     */
    /**
     * The environment for a launch minted by the page: from the browser's Origin header (set by
     * the browser, so a reverse proxy rewriting Host can't change it), else the Host header.
     */
    public static String environmentForPage(String origin, String host, String override) {
        if (origin != null && origin.trim().length() > 0 && !"null".equals(origin.trim())) {
            try {
                java.net.URI uri = new java.net.URI(origin.trim());
                if (uri.getHost() != null) {
                    return environmentFor(uri.getHost(), override);
                }
            } catch (java.net.URISyntaxException ignored) {
                // fall through to Host
            }
        }
        return environmentFor(host, override);
    }

    public static String environmentFor(String host, String override) {
        if (override != null && ENV_KEY.matcher(override.trim()).matches()) {
            return override.trim();
        }
        String h = host == null ? "" : host.trim().toLowerCase();
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end > 0 ? h.substring(1, end) : h;
        } else if (h.indexOf(':') >= 0 && h.indexOf(':') == h.lastIndexOf(':')) {
            h = h.substring(0, h.indexOf(':'));
        }
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")) {
            return ENV_LOCAL;
        }
        return ENV_PROD;
    }

    public Issued issue(Session session, WebUser webUser, int workspaceId, int actionNextId, String verb,
            String agentKey, Integer folderId, String envKey, Date now) {
        if (!isVerb(verb)) {
            throw SessionException.bad("Unknown launch \"" + verb + "\".");
        }
        ActionNext action = SessionAccess.find(session, webUser, workspaceId, actionNextId);
        if (action == null) {
            throw SessionException.notFound("Action");
        }
        if (action.isTemplate()) {
            throw SessionException.bad("Templates can't be opened in a session; open one of its actions.");
        }
        Resolved resolved = resolve(session, webUser, action, verb, agentKey, folderId);
        ActionLaunch launch = new ActionLaunch();
        String nonce = newNonce();
        launch.setNonceHash(sha256(nonce));
        launch.setWebUserId(webUser.getWebUserId());
        launch.setWorkspaceId(workspaceId);
        launch.setActionNextId(actionNextId);
        launch.setVerb(verb);
        launch.setAgentKey(resolved.agentKey);
        launch.setAgentModel(resolved.model);
        launch.setProjectFolderId(VERB_FOLDER.equals(verb) ? folderId : null);
        launch.setEnvKey(envKey);
        launch.setIssuedDate(now);
        launch.setExpiresDate(new Date(now.getTime() + NONCE_LIFETIME_MS));
        session.save(launch);
        StringBuilder uri = new StringBuilder("dandelion:").append(verb).append('/').append(actionNextId);
        if (VERB_FOLDER.equals(verb) && folderId != null) {
            uri.append('/').append(folderId.intValue());
        }
        uri.append("?e=").append(envKey).append("&n=").append(nonce);
        return new Issued(launch.getLaunchId(), uri.toString());
    }

    /** What the PC is told to open for a verb, checked against current data. */
    static class Resolved {
        String agentKey;
        String model;
        ProjectFolder folder;
        ActionSession resumeLink;
    }

    private Resolved resolve(Session session, WebUser webUser, ActionNext action, String verb, String agentKey,
            Integer folderId) {
        Resolved resolved = new Resolved();
        Project project = SessionAccess.project(session, action);
        int projectId = project == null ? 0 : project.getProjectId();
        if (VERB_RESUME.equals(verb)) {
            ActionSession link = sessionService.find(session, action.getActionNextId());
            if (link == null) {
                throw SessionException.conflict("no_session", "This action has no saved session.");
            }
            if (!ActionSessionService.isResumable(ActionSessionService.ageDays(link.getSavedDate(), webUser))) {
                throw SessionException.conflict("session_expired",
                        "This session is " + ActionSessionService.RESUME_DAYS
                                + " or more days old and can't be resumed. Pick up from the notes.");
            }
            Agent agent = agentCatalog.findActive(session, link.getAgentKey());
            if (!AgentCatalogService.hasCapability(agent, AgentCatalogService.CAP_RESUME)) {
                throw SessionException.conflict("no_resume", "This agent can't resume sessions.");
            }
            resolved.agentKey = link.getAgentKey();
            resolved.model = agentCatalog.model(session, webUser, link.getAgentKey());
            resolved.resumeLink = link;
            return resolved;
        }
        if (VERB_FOLDER.equals(verb) && folderId != null) {
            ProjectFolder folder = (ProjectFolder) session.get(ProjectFolder.class, folderId);
            if (folder == null || folder.getProjectId() != projectId) {
                throw SessionException.notFound("Folder");
            }
            resolved.folder = folder;
            return resolved;
        }
        ProjectFolder primary = projectId == 0 ? null : folderService.primary(session, projectId);
        if (primary == null) {
            throw SessionException.conflict("no_folder", "This project has no primary folder. Set one on the project.");
        }
        resolved.folder = primary;
        if (VERB_START.equals(verb) || VERB_NEW.equals(verb)) {
            String key = agentKey == null || agentKey.length() == 0 ? agentCatalog.defaultAgent(session, webUser)
                    : agentKey;
            Agent agent = agentCatalog.findActive(session, key);
            String capability = VERB_START.equals(verb) ? AgentCatalogService.CAP_START : AgentCatalogService.CAP_NEW;
            if (agent == null) {
                throw SessionException.bad("Unknown agent \"" + key + "\".");
            }
            if (!AgentCatalogService.hasCapability(agent, capability)) {
                throw SessionException.bad(agent.getDisplayName() + " can't do that.");
            }
            resolved.agentKey = key;
            resolved.model = agentCatalog.model(session, webUser, key);
        }
        return resolved;
    }

    /**
     * Redeems a nonce for the LAUNCHER client's user. Single use: a second redeem, an expired
     * nonce, or one minted for another user, verb, action or folder is refused, and nothing is
     * launched.
     */
    public Map<String, Object> redeem(Session session, WebUser webUser, int workspaceId, int clientId, String nonce,
            String verb, int actionNextId, Integer folderId, String serverEnvKey, String appVersion, Date now) {
        if (nonce == null || !NONCE.matcher(nonce).matches()) {
            throw SessionException.bad("Malformed launch.");
        }
        Query query = session.createQuery("from ActionLaunch al where al.nonceHash = :hash");
        query.setParameter("hash", sha256(nonce));
        ActionLaunch launch = LockingReads.refresh(session, (ActionLaunch) query.uniqueResult());
        if (launch == null || launch.getWebUserId() != webUser.getWebUserId()
                || launch.getWorkspaceId() != workspaceId) {
            throw new SessionException(404, "unknown_launch", "This launch link isn't valid here.");
        }
        if (!launch.getVerb().equals(verb) || launch.getActionNextId() != actionNextId
                || !same(launch.getProjectFolderId(), folderId)) {
            throw new SessionException(400, "launch_mismatch", "This launch link was changed.");
        }
        if (launch.getRedeemedDate() != null) {
            throw SessionException.conflict("launch_used", "This launch link was already used (each link opens once). Click the button in Dandelion again.");
        }
        if (now.after(launch.getExpiresDate())) {
            throw SessionException.conflict("launch_expired", "This launch link is more than 5 minutes old, so it expired. Click the button in Dandelion again.");
        }
        ActionNext action = SessionAccess.find(session, webUser, workspaceId, actionNextId);
        if (action == null) {
            throw SessionException.notFound("Action");
        }
        Resolved resolved = resolve(session, webUser, action, verb,
                launch.getAgentKey() == null ? null : launch.getAgentKey(), folderId);
        if (launch.getAgentKey() != null && resolved.agentKey != null
                && !launch.getAgentKey().equals(resolved.agentKey)) {
            throw SessionException.conflict("launch_changed", "The saved session changed. Click the button again.");
        }
        launch.setRedeemedDate(now);
        launch.setRedeemedClientId(Integer.valueOf(clientId));
        Map<String, Object> folder = null;
        if (resolved.resumeLink != null) {
            launch.setFolderRootKey(resolved.resumeLink.getRootKey());
            launch.setFolderRelativePath(resolved.resumeLink.getRelativePath());
            launch.setPendingSessionId(resolved.resumeLink.getSessionId());
        } else if (resolved.folder != null) {
            launch.setFolderRootKey(resolved.folder.getRootKey());
            launch.setFolderRelativePath(resolved.folder.getRelativePath());
            Agent agent = agentCatalog.findActive(session, resolved.agentKey);
            if (AgentCatalogService.hasCapability(agent, AgentCatalogService.CAP_SESSION_ID_AT_LAUNCH)) {
                launch.setPendingSessionId(UUID.randomUUID().toString());
            }
        }
        if (launch.getFolderRootKey() != null) {
            folder = new LinkedHashMap<String, Object>();
            folder.put("root", launch.getFolderRootKey());
            folder.put("path", launch.getFolderRelativePath());
            if (resolved.folder != null && resolved.folder.getLabel() != null) {
                folder.put("label", resolved.folder.getLabel());
            }
        }
        session.update(launch);

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        Map<String, Object> server = new LinkedHashMap<String, Object>();
        server.put("app", "dandelion");
        // The environment this nonce was minted under (from the signed-in page's own origin).
        // A nonce exists only on the server that minted it, so this is that server's identity.
        server.put("envKey", launch.getEnvKey());
        server.put("hostEnvKey", serverEnvKey);
        server.put("version", appVersion);
        response.put("server", server);
        response.put("launchId", Integer.valueOf(launch.getLaunchId()));
        response.put("verb", verb);
        Map<String, Object> actionMap = new LinkedHashMap<String, Object>();
        actionMap.put("actionNextId", Integer.valueOf(action.getActionNextId()));
        actionMap.put("projectId", Integer.valueOf(action.getProjectId()));
        response.put("action", actionMap);
        if (resolved.agentKey != null) {
            Map<String, Object> agent = new LinkedHashMap<String, Object>();
            agent.put("agentKey", resolved.agentKey);
            agent.put("model", resolved.model);
            response.put("agent", agent);
        }
        response.put("folder", folder);
        if (resolved.resumeLink != null) {
            Map<String, Object> resume = sessionService.toMap(resolved.resumeLink, webUser);
            response.put("resume", resume);
        }
        response.put("sessionId", launch.getPendingSessionId());
        return response;
    }

    /**
     * The SessionStart callback: this launch's process started with {@code sessionId}. Promotes
     * it to the action's session unless a newer launch owns the action. A Claude launch must
     * report the id it was given; a Codex launch binds the first id it reports and refuses any
     * other later.
     */
    public Map<String, Object> bind(Session session, WebUser webUser, int launchId, String sessionId, Date now) {
        ActionLaunch launch = requireRedeemed(session, webUser, launchId);
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw SessionException.bad("\"sessionId\" must be a UUID.");
        }
        if (launch.getAgentKey() == null) {
            throw SessionException.bad("This launch opened no agent.");
        }
        if (launch.getPendingSessionId() != null && !launch.getPendingSessionId().equalsIgnoreCase(sessionId)) {
            throw SessionException.conflict("session_mismatch", "This launch was given a different session id.");
        }
        if (launch.getBoundSessionId() != null && !launch.getBoundSessionId().equalsIgnoreCase(sessionId)) {
            throw SessionException.conflict("session_mismatch", "This launch is already bound to another session.");
        }
        launch.setBoundSessionId(sessionId.toLowerCase());
        if (launch.getBoundDate() == null) {
            launch.setBoundDate(now);
        }
        session.update(launch);
        ActionSession saved = sessionService.save(session, launch.getActionNextId(), launch.getAgentKey(),
                sessionId.toLowerCase(), launch.getFolderRootKey(), launch.getFolderRelativePath(),
                Integer.valueOf(launch.getLaunchId()), null, ActionSessionService.SOURCE_LAUNCH, now);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("launchId", Integer.valueOf(launchId));
        result.put("actionNextId", Integer.valueOf(launch.getActionNextId()));
        result.put("applied", Boolean.valueOf(saved != null));
        if (saved == null) {
            result.put("reason", "A newer session owns this action; this one was not linked.");
        }
        return result;
    }

    /** The window closed (SessionEnd, or the wrapper saw the process exit). */
    public Map<String, Object> end(Session session, WebUser webUser, int launchId, Date now) {
        ActionLaunch launch = requireRedeemed(session, webUser, launchId);
        boolean touched = launch.getBoundSessionId() != null
                && sessionService.touch(session, launch.getActionNextId(), launchId, null, now);
        if (launch.getEndedDate() == null) {
            launch.setEndedDate(now);
            session.update(launch);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("launchId", Integer.valueOf(launchId));
        result.put("applied", Boolean.valueOf(touched));
        return result;
    }

    /** A redeemed launch of this user, or a refusal. */
    public ActionLaunch requireRedeemed(Session session, WebUser webUser, int launchId) {
        ActionLaunch launch = (ActionLaunch) session.get(ActionLaunch.class, Integer.valueOf(launchId),
                LockMode.UPGRADE);
        if (launch == null || launch.getWebUserId() != webUser.getWebUserId() || launch.getRedeemedDate() == null) {
            throw SessionException.notFound("Launch");
        }
        return launch;
    }

    static String newNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }
}
