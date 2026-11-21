package org.openimmunizationsoftware.pt.model;

import java.util.Date;

public class ActionTaken implements java.io.Serializable {

    private static final long serialVersionUID = -5190528304466367460L;

    private int actionTakenId;
    private int projectId;
    private int contactId;
    private Integer workspaceId;
    private Date actionDate;
    private String actionDescription;
    private String actionNote;
    private ProjectContact contact;
    private Project project;
    private ActionSet actionSet;
    private String requestKey;
    private Integer survivorActionNextId;

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

    public Integer getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Integer workspaceId) {
        this.workspaceId = workspaceId;
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

    public boolean hasActionDescription() {
        return actionDescription != null && !actionDescription.equals("");
    }

    public void setActionDescription(String actionDescription) {
        this.actionDescription = actionDescription;
    }

    /** The outcome note: detail behind the short description, or null. */
    public String getActionNote() {
        return actionNote;
    }

    public void setActionNote(String actionNote) {
        this.actionNote = actionNote;
    }

    public ProjectContact getContact() {
        return contact;
    }

    public void setContact(ProjectContact contact) {
        this.contact = contact;
        if (contact != null) {
            this.contactId = contact.getContactId();
        }
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
        if (project != null) {
            this.projectId = project.getProjectId();
        }
    }

    public ActionSet getActionSet() {
        return actionSet;
    }

    public void setActionSet(ActionSet actionSet) {
        this.actionSet = actionSet;
    }

    /** Set on a History entry written by a retriable request (Park), so a retry finds it. */
    public String getRequestKey() {
        return requestKey;
    }

    public void setRequestKey(String requestKey) {
        this.requestKey = requestKey;
    }

    /** On the entry that records a merge: the action that absorbed this one. */
    public Integer getSurvivorActionNextId() {
        return survivorActionNextId;
    }

    public void setSurvivorActionNextId(Integer survivorActionNextId) {
        this.survivorActionNextId = survivorActionNextId;
    }
}
