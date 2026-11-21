package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;

/**
 * An agent: the software that does the work (Claude Code, Codex). Only its key is stored on
 * actions; the commands for each key live in the PC's trusted config. Table agent (src/db/v5.17.sql).
 */
public class Agent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String agentKey;
    private String displayName;
    private String integrationStyle;
    private String capabilities;
    private String activeFlag;
    private int sortOrder;

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getIntegrationStyle() {
        return integrationStyle;
    }

    public void setIntegrationStyle(String integrationStyle) {
        this.integrationStyle = integrationStyle;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public String getActiveFlag() {
        return activeFlag;
    }

    public void setActiveFlag(String activeFlag) {
        this.activeFlag = activeFlag;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

}
