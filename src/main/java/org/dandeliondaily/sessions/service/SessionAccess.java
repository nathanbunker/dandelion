package org.dandeliondaily.sessions.service;

import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Who may launch, resume or close an action from a session: the same rule the Focused Action page
 * uses (FocusedActionServlet.isUsersAction): the action is in the caller's workspace and the user
 * owns it or is its next contact. Anything else is reported as "not found".
 */
public final class SessionAccess {

    private SessionAccess() {
    }

    public static boolean canUse(ActionNext action, WebUser webUser, int workspaceId) {
        if (action == null || webUser == null) {
            return false;
        }
        if (action.getWorkspaceId() == null || action.getWorkspaceId().intValue() != workspaceId) {
            return false;
        }
        int contactId = webUser.getContactId();
        boolean owner = action.getContactId() != null && action.getContactId().intValue() == contactId;
        boolean next = action.getNextContactId() != null && action.getNextContactId().intValue() == contactId;
        return contactId > 0 && (owner || next);
    }

    /** The action if this user may use it, otherwise null. */
    public static ActionNext find(Session session, WebUser webUser, int workspaceId, int actionNextId) {
        if (actionNextId <= 0) {
            return null;
        }
        ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(actionNextId));
        return canUse(action, webUser, workspaceId) ? action : null;
    }

    /** A project the user owns in the workspace (handoff targets, failure actions). */
    public static boolean canUseProject(Project project, WebUser webUser, int workspaceId) {
        return project != null && project.getWorkspaceId() != null
                && project.getWorkspaceId().intValue() == workspaceId
                && project.getWebUser() != null && project.getWebUser().getWebUserId() == webUser.getWebUserId();
    }

    public static Project project(Session session, ActionNext action) {
        Project project = action.getProject();
        if (project == null && action.getProjectId() > 0) {
            project = (Project) session.get(Project.class, Integer.valueOf(action.getProjectId()));
        }
        return project;
    }
}
