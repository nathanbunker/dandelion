package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;

/**
 * How an agent template hands its work to a person. Table action_agent_config (src/db/v5.17.sql).
 */
public class ActionAgentConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private int actionNextId;
    private String workloadKey;
    private String handoffMode;
    private Integer handoffProjectId;
    private Integer handoffTemplateId;

    public int getActionNextId() {
        return actionNextId;
    }

    public void setActionNextId(int actionNextId) {
        this.actionNextId = actionNextId;
    }

    public String getWorkloadKey() {
        return workloadKey;
    }

    public void setWorkloadKey(String workloadKey) {
        this.workloadKey = workloadKey;
    }

    public String getHandoffMode() {
        return handoffMode;
    }

    public void setHandoffMode(String handoffMode) {
        this.handoffMode = handoffMode;
    }

    public Integer getHandoffProjectId() {
        return handoffProjectId;
    }

    public void setHandoffProjectId(Integer handoffProjectId) {
        this.handoffProjectId = handoffProjectId;
    }

    public Integer getHandoffTemplateId() {
        return handoffTemplateId;
    }

    public void setHandoffTemplateId(Integer handoffTemplateId) {
        this.handoffTemplateId = handoffTemplateId;
    }

}
