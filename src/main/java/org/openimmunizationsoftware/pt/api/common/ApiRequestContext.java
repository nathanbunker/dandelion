package org.openimmunizationsoftware.pt.api.common;

public final class ApiRequestContext {

    private static final ThreadLocal<ApiClientInfo> CURRENT = new ThreadLocal<ApiClientInfo>();

    private ApiRequestContext() {
    }

    static void set(ApiClientInfo clientInfo) {
        CURRENT.set(clientInfo);
    }

    public static ApiClientInfo getCurrentClient() {
        ApiClientInfo clientInfo = CURRENT.get();
        if (clientInfo == null) {
            throw new IllegalStateException("No API client bound to current thread.");
        }
        return clientInfo;
    }

    static void clear() {
        CURRENT.remove();
    }

    public static final class ApiClientInfo {

        private final int clientId;
        private final String username;
        private final Integer workspaceId;
        private final String agentName;
        private final String clientType;

        public ApiClientInfo(int clientId, String username, Integer workspaceId, String agentName) {
            this(clientId, username, workspaceId, agentName, "AGENT");
        }

        public ApiClientInfo(int clientId, String username, Integer workspaceId, String agentName,
                String clientType) {
            this.clientId = clientId;
            this.username = username;
            this.workspaceId = workspaceId;
            this.agentName = agentName;
            this.clientType = clientType;
        }

        /** "AGENT" or "DEVICE" (WebApiClient.TYPE_*). */
        public String getClientType() {
            return clientType;
        }

        public boolean isDevice() {
            return "DEVICE".equals(clientType);
        }

        public int getClientId() {
            return clientId;
        }

        public String getUsername() {
            return username;
        }

        public Integer getWorkspaceId() {
            return workspaceId;
        }

        public String getProviderId() {
            return workspaceId == null ? null : String.valueOf(workspaceId);
        }

        public String getAgentName() {
            return agentName;
        }
    }
}
