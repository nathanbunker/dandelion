package org.openimmunizationsoftware.pt.api.v1.resource.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Date;
import org.openimmunizationsoftware.pt.model.ActionTaken;

@Schema(name = "ActionTaken")
public class ActionTakenDto {

    @Schema(example = "456")
    private int actionTakenId;
    @Schema(example = "123")
    private int projectId;
    private int contactId;
    private Date actionDate;
    private String actionDescription;
    private String actionNote;

    public String getActionNote() {
        return actionNote;
    }

    public void setActionNote(String actionNote) {
        this.actionNote = actionNote;
    }

    public static ActionTakenDto from(ActionTaken action) {
        ActionTakenDto dto = new ActionTakenDto();
        dto.setActionTakenId(action.getActionTakenId());
        dto.setProjectId(action.getProjectId());
        dto.setContactId(action.getContactId());
        dto.setActionDate(action.getActionDate());
        dto.setActionDescription(action.getActionDescription());
        dto.setActionNote(action.getActionNote());
        return dto;
    }

    public int getActionTakenId() {
        return actionTakenId;
    }

    public void setActionTakenId(int actionTakenId) {
        this.actionTakenId = actionTakenId;
    }

    public int getProjectId() {
        return projectId;
    }

    public void setProjectId(int projectId) {
        this.projectId = projectId;
    }

    public int getContactId() {
        return contactId;
    }

    public void setContactId(int contactId) {
        this.contactId = contactId;
    }

    public Date getActionDate() {
        return actionDate;
    }

    public void setActionDate(Date actionDate) {
        this.actionDate = actionDate;
    }

    public String getActionDescription() {
        return actionDescription;
    }

    public void setActionDescription(String actionDescription) {
        this.actionDescription = actionDescription;
    }
}
