package org.openimmunizationsoftware.pt.api.common;

import org.openimmunizationsoftware.pt.model.WebApiClient;

/**
 * Keeps the PC's two special keys to their own endpoints, and everyone else out of them.
 * <ul>
 * <li>RUNNER (the unattended agent runner): only {@code v1/runner/*}.</li>
 * <li>LAUNCHER (the dandelion: link handler and the /bail, /park, /close-out session commands):
 * only {@code v1/launcher/*}.</li>
 * <li>Agent keys and phones never reach {@code v1/runner/*} or {@code v1/launcher/*}, so the broad
 * Dandelion MCP key can't claim runs or redeem launches.</li>
 * </ul>
 * Revoking one key stops only that client.
 */
public final class SessionClientScope {

    public static final String RUNNER_PREFIX = "v1/runner/";
    public static final String LAUNCHER_PREFIX = "v1/launcher/";

    private SessionClientScope() {
    }

    /** Null when the request may go ahead, otherwise the message for a 403. */
    public static String refusal(String clientType, String path) {
        String p = normalize(path);
        boolean runnerPath = p.startsWith(RUNNER_PREFIX) || p.equals("v1/runner");
        boolean launcherPath = p.startsWith(LAUNCHER_PREFIX) || p.equals("v1/launcher");
        if (WebApiClient.TYPE_RUNNER.equals(clientType)) {
            return runnerPath && !p.contains("..") ? null : "Runner keys can only use the runner endpoints.";
        }
        if (WebApiClient.TYPE_LAUNCHER.equals(clientType)) {
            return launcherPath && !p.contains("..") ? null : "Launcher keys can only use the launcher endpoints.";
        }
        if (runnerPath || launcherPath) {
            return "This endpoint needs a runner or launcher key.";
        }
        return null;
    }

    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
