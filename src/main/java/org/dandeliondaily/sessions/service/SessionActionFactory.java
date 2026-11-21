package org.dandeliondaily.sessions.service;

import java.time.LocalDate;
import java.util.Date;

import org.dandeliondaily.shared.service.ActionNotesService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSetType;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Builds the few actions this feature creates on its own (a review action after an agent run, a
 * failure notice, a Gmail prep), the same way MCP create_action does: owned by the user, READY,
 * with its own action set and starting notes.
 */
public class SessionActionFactory {

    public static final int MAX_DESCRIPTION = 1200;

    private final ActionNotesService notesService = new ActionNotesService();

    public ActionNext create(Session session, WebUser webUser, Project project, String description,
            String actionType, LocalDate date, String notes, Integer followsActionNextId, Date now) {
        ActionNext action = new ActionNext();
        action.setProject(project);
        action.setProjectId(project.getProjectId());
        action.setWorkspaceId(project.getWorkspaceId());
        ProjectContact ownerContact = webUser.getProjectContact();
        if (ownerContact == null) {
            ownerContact = (ProjectContact) session.get(ProjectContact.class, Integer.valueOf(webUser.getContactId()));
        }
        action.setContact(ownerContact);
        action.setContactId(webUser.getContactId());
        String type = actionType == null ? ProjectNextActionType.WILL : actionType;
        action.setNextActionType(type);
        String text = clip(description, MAX_DESCRIPTION);
        action.setNextDescription(text);
        action.setNextSummary(text);
        action.setNextTimeEstimate(Integer.valueOf(0));
        action.setNextActionStatus(ProjectNextActionStatus.READY);
        action.setNextActionDate(date == null ? null : java.sql.Date.valueOf(date));
        action.setLinkUrl("");
        action.setNextChangeDate(now);
        action.setPriorityLevel(ProjectNextActionType.defaultPriority(type));
        action.setBillable(project.getBillCode() != null && project.getBillCode().trim().length() > 0);
        action.setFollowsActionNextId(followsActionNextId);
        action.setActionSet(new ActionSetDao(session).createActionSet(webUser, ActionSetType.STANDARD));
        notesService.initialize(action, clipNotes(notes));
        session.save(action);
        return action;
    }

    static String clip(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > max ? text.substring(0, max - 3) + "..." : text;
    }

    static String clipNotes(String notes) {
        if (notes == null) {
            return null;
        }
        return notes.length() > ActionNotesService.MAX_LENGTH ? notes.substring(0, ActionNotesService.MAX_LENGTH)
                : notes;
    }
}
