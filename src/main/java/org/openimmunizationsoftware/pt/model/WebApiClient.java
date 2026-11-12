package org.openimmunizationsoftware.pt.model;

import java.util.Date;

public class WebApiClient implements java.io.Serializable {

    private static final long serialVersionUID = 4102430100630391979L;

    private int clientId;
    private String apiKey;
    private WebUser webUser;
    private Integer workspaceId;
    private String agentName;
    private boolean enabled = true;
    private Date createDate;
    private Date lastUsedDate;
    private String clientType = TYPE_AGENT;
    private String deviceStatus;
    private String deviceName;
    private Date approvedDate;
    private Date revokedDate;

    public static final String TYPE_AGENT = "AGENT";
    public static final String TYPE_DEVICE = "DEVICE";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REVOKED = "REVOKED";

    public int getClientId() {
        return clientId;
    }

    public void setClientId(int clientId) {
        this.clientId = clientId;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getUsername() {
        return webUser != null ? webUser.getUsername() : null;
    }

    public void setUsername(String username) {
        // no-op: use setWebUser() instead
    }

    public WebUser getWebUser() {
        return webUser;
    }

    public void setWebUser(WebUser webUser) {
        this.webUser = webUser;
    }

    public Integer getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Integer workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getProviderId() {
        return workspaceId == null ? null : String.valueOf(workspaceId);
    }

    public void setProviderId(String providerId) {
        if (providerId == null || providerId.trim().equals("")) {
            this.workspaceId = null;
            return;
        }
        this.workspaceId = Integer.valueOf(providerId.trim());
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public Date getLastUsedDate() {
        return lastUsedDate;
    }

    public void setLastUsedDate(Date lastUsedDate) {
        this.lastUsedDate = lastUsedDate;
    }

    /** {@link #TYPE_AGENT} (an API key made on the Settings page) or {@link #TYPE_DEVICE} (a phone app). */
    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType == null ? TYPE_AGENT : clientType;
    }

    public boolean isDevice() {
        return TYPE_DEVICE.equals(clientType);
    }

    /** For devices: {@link #STATUS_PENDING}, {@link #STATUS_APPROVED} or {@link #STATUS_REVOKED}. */
    public String getDeviceStatus() {
        return deviceStatus;
    }

    public void setDeviceStatus(String deviceStatus) {
        this.deviceStatus = deviceStatus;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public Date getApprovedDate() {
        return approvedDate;
    }

    public void setApprovedDate(Date approvedDate) {
        this.approvedDate = approvedDate;
    }

    public Date getRevokedDate() {
        return revokedDate;
    }

    public void setRevokedDate(Date revokedDate) {
        this.revokedDate = revokedDate;
    }
}
