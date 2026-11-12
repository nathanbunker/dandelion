package org.dandeliondaily.personal.service;

import org.openimmunizationsoftware.pt.model.WebApiClient;

/**
 * What a phone (a {@code DEVICE} web_api_client) may call, by its status.
 * Agents (API keys from the Settings page) are not limited here.
 *
 * <ul>
 * <li>REVOKED: nothing (401 device_revoked), so the app clears its key.</li>
 * <li>PENDING: only {@code v1/devices/me}, so the app can wait for approval (403 device_pending).</li>
 * <li>APPROVED: {@code v1/devices/me}, {@code v1/me} and {@code v1/personal/*}. Never the agent
 * endpoints ({@code v1/actions}, {@code v1/sync}, {@code v1/mcp}, ...), which include work
 * (billable) actions. A phone only ever sees personal actions.</li>
 * </ul>
 */
public final class DeviceAccessRules {

    public static final String PATH_DEVICES_ME = "v1/devices/me";
    public static final String PATH_SIGN_IN = "v1/devices/sign-in";

    private DeviceAccessRules() {
    }

    /** A refusal: HTTP status, error code and message for {@code ApiErrorResponse}. */
    public static final class Refusal {
        public final int status;
        public final String code;
        public final String message;

        Refusal(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }
    }

    public static boolean isPublicPath(String path) {
        return PATH_SIGN_IN.equals(normalize(path));
    }

    /** Returns null when the request may go ahead. */
    public static Refusal check(String clientType, String deviceStatus, String path) {
        if (!WebApiClient.TYPE_DEVICE.equals(clientType)) {
            return null;
        }
        String p = normalize(path);
        if (p.contains("..") || p.contains("//")) {
            return new Refusal(403, "device_scope", "Devices can only use the personal endpoints.");
        }
        if (WebApiClient.STATUS_REVOKED.equals(deviceStatus)) {
            return new Refusal(401, "device_revoked", "This device's access was revoked.");
        }
        if (WebApiClient.STATUS_PENDING.equals(deviceStatus)) {
            if (PATH_DEVICES_ME.equals(p)) {
                return null;
            }
            return new Refusal(403, "device_pending", "This device is waiting for approval in Dandelion.");
        }
        if (!WebApiClient.STATUS_APPROVED.equals(deviceStatus)) {
            return new Refusal(401, "device_revoked", "This device has no valid status.");
        }
        if (PATH_DEVICES_ME.equals(p) || "v1/me".equals(p) || p.startsWith("v1/personal/")) {
            return null;
        }
        return new Refusal(403, "device_scope", "Devices can only use the personal endpoints.");
    }

    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
