package org.dandeliondaily.personal.service;

import java.util.TimeZone;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Loads the signed-in user for a phone request the way a web login does
 * (LoginServlet.initializeUserSession): with their contact and time zone, so "today"
 * and the action wording match the web pages.
 */
public final class PersonalUserSupport {

    private PersonalUserSupport() {
    }

    /** Returns null if the user no longer exists. */
    public static WebUser loadWebUser(Session session, String username) {
        Query query = session.createQuery("from WebUser where username = :username");
        query.setString("username", username);
        WebUser webUser = (WebUser) query.uniqueResult();
        if (webUser == null) {
            return null;
        }
        if (webUser.getProjectContact() == null && webUser.getContactId() > 0) {
            webUser.setProjectContact(
                    (ProjectContact) session.get(ProjectContact.class, Integer.valueOf(webUser.getContactId())));
        }
        webUser.setTimeZone(TimeZone.getTimeZone(TrackerKeysManager.getKeyValue(
                TrackerKeysManager.KEY_TIME_ZONE, WebUser.AMERICA_DENVER, webUser, session)));
        return webUser;
    }
}
