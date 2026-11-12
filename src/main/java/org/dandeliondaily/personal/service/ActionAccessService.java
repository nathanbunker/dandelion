package org.dandeliondaily.personal.service;

import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The gate for every phone read or write of a single action: the action must be in the
 * device's workspace, belong to the user (owner or next contact), and be personal
 * ({@code billable = false}). Anything else is reported as "not found", so a phone can't
 * even learn that a work action exists (Sundial Plan.md, change 3).
 */
public class ActionAccessService {

    /** Returns the action, or null if this user may not see it from the phone. */
    public ActionNext findPersonalAction(Session session, WebUser webUser, int workspaceId, int actionNextId) {
        if (actionNextId <= 0) {
            return null;
        }
        ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(actionNextId));
        return isPersonalActionFor(action, webUser.getContactId(), workspaceId) ? action : null;
    }

    public static boolean isPersonalActionFor(ActionNext action, int contactId, int workspaceId) {
        if (action == null || action.isBillable()) {
            return false;
        }
        if (action.getWorkspaceId() == null || action.getWorkspaceId().intValue() != workspaceId) {
            return false;
        }
        boolean owner = action.getContactId() != null && action.getContactId().intValue() == contactId;
        boolean nextContact = action.getNextContactId() != null && action.getNextContactId().intValue() == contactId;
        return contactId > 0 && (owner || nextContact);
    }
}
