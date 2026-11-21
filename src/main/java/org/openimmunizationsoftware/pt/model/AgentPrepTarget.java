package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;
import java.util.Date;

/**
 * One prep per human target instance (PER_TARGET_INSTANCE handoff). Table agent_prep_target (src/db/v5.17.sql).
 */
public class AgentPrepTarget implements Serializable {

    private static final long serialVersionUID = 1L;

    private int prepActionNextId;
    private int targetActionNextId;
    private int prepTemplateId;
    private Date createdDate;

    public int getPrepActionNextId() {
        return prepActionNextId;
    }

    public void setPrepActionNextId(int prepActionNextId) {
        this.prepActionNextId = prepActionNextId;
    }

    public int getTargetActionNextId() {
        return targetActionNextId;
    }

    public void setTargetActionNextId(int targetActionNextId) {
        this.targetActionNextId = targetActionNextId;
    }

    public int getPrepTemplateId() {
        return prepTemplateId;
    }

    public void setPrepTemplateId(int prepTemplateId) {
        this.prepTemplateId = prepTemplateId;
    }

    public Date getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Date createdDate) {
        this.createdDate = createdDate;
    }

}
