package org.dandeliondaily.sessions.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.dandeliondaily.shared.service.SharedActionSiblings;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSet;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Writes a History entry (an action taken row) on an action that stays open, the way Save
 * progress does: it joins the action's own action set, once per linked twin, and records no time.
 * A request key, when given, makes the write findable so a retry adds nothing.
 */
public class HistoryEntryWriter {

    public static final int MAX_DESCRIPTION = 12000;

    /** The entries already written for this request key (empty if none). */
    @SuppressWarnings("unchecked")
    public List<ActionTaken> findByRequestKey(Session session, String requestKey) {
        if (requestKey == null) {
            return new ArrayList<ActionTaken>();
        }
        Query query = session.createQuery("from ActionTaken where requestKey = :key or requestKey like :twinKey "
                + "order by actionTakenId");
        query.setParameter("key", requestKey);
        query.setParameter("twinKey", requestKey + "#%");
        return query.list();
    }

    /**
     * One entry per twin (the action itself first). The first carries {@code requestKey}, the
     * others {@code requestKey#n}, so the unique key holds and a retry finds them all.
     */
    public List<ActionTaken> write(Session session, WebUser webUser, ActionNext action, String description,
            String note, String requestKey, Integer survivorActionNextId, Date when) {
        List<ActionTaken> written = new ArrayList<ActionTaken>();
        String text = description == null ? "" : description.trim();
        if (text.length() == 0) {
            text = action.getNextDescription();
        }
        if (text.length() > MAX_DESCRIPTION) {
            text = text.substring(0, MAX_DESCRIPTION);
        }
        int index = 0;
        for (ActionNext twin : SharedActionSiblings.resolve(session, action)) {
            Project project = SessionAccess.project(session, twin);
            if (project == null) {
                continue;
            }
            ActionTaken taken = new ActionTaken();
            taken.setProject(project);
            taken.setProjectId(project.getProjectId());
            taken.setActionDate(when);
            taken.setActionDescription(text);
            taken.setActionNote(note);
            taken.setWorkspaceId(twin.getWorkspaceId());
            taken.setContact(webUser.getProjectContact());
            taken.setContactId(webUser.getContactId());
            ActionSet actionSet = twin.getActionSet();
            if (actionSet == null) {
                actionSet = new ActionSetDao(session).createStandardActionSet(webUser);
                twin.setActionSet(actionSet);
                session.update(twin);
            }
            taken.setActionSet(actionSet);
            if (requestKey != null) {
                taken.setRequestKey(index == 0 ? requestKey : requestKey + "#" + index);
            }
            taken.setSurvivorActionNextId(survivorActionNextId);
            session.save(taken);
            written.add(taken);
            index++;
        }
        return written;
    }
}
