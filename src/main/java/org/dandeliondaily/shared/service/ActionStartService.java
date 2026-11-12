package org.dandeliondaily.shared.service;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Starting work on an action. Whenever an open action is picked to work on (dashboard Start,
 * Focused Action switch or search), it moves to today if it is scheduled for another day or has
 * no date, so it shows in today's list, time and review. Linked-project twins move with it.
 * Completed and cancelled actions keep their dates.
 */
public class ActionStartService {

    /** Caller owns the transaction. Returns true when the action's date was changed. */
    public boolean moveToTodayIfOpen(Session dataSession, WebUser webUser, ActionNext action) {
        if (!needsMoveToToday(action, webUser)) {
            return false;
        }
        java.sql.Date today = java.sql.Date.valueOf(webUser.getLocalDateToday());
        Date now = new Date();
        List<ActionNext> siblings = SharedActionSiblings.resolve(dataSession, action);
        for (ActionNext sibling : siblings) {
            sibling.setNextActionDate(today);
            sibling.setNextChangeDate(now);
            dataSession.update(sibling);
        }
        return true;
    }

    static boolean needsMoveToToday(ActionNext action, WebUser webUser) {
        if (action == null || !isOpen(action)) {
            return false;
        }
        LocalDate date = toLocalDate(action.getNextActionDate(), webUser);
        return date == null || !date.equals(webUser.getLocalDateToday());
    }

    private static boolean isOpen(ActionNext action) {
        String status = action.getNextActionStatusString();
        return ProjectNextActionStatus.READY.getId().equals(status)
                || ProjectNextActionStatus.PROPOSED.getId().equals(status);
    }

    private static LocalDate toLocalDate(Date date, WebUser webUser) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return date.toInstant().atZone(webUser.getZoneId()).toLocalDate();
    }
}
