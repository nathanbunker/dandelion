package org.dandeliondaily.sessions.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.LockMode;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionSession;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The one current session on an action (action_session): which agent, which session id, the
 * folder it ran in and when it was saved. Resume is offered while the session is under 30 days
 * old by the user's local date. Writes go through {@link #mayReplace}: a callback from an older
 * launch can never overwrite a newer one, and an unattended handoff never replaces a person's own
 * session. Callers own the transaction.
 */
public class ActionSessionService {

    /** Resume is shown at 29 days and hidden from 30. */
    public static final int RESUME_DAYS = 30;

    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_BAILED = "BAILED";
    public static final String STATE_PARKED = "PARKED";

    public static final String SOURCE_LAUNCH = "LAUNCH";
    public static final String SOURCE_RUNNER = "RUNNER";
    public static final String SOURCE_HANDOFF = "HANDOFF";

    public ActionSession find(Session session, int actionNextId) {
        return (ActionSession) session.createQuery("from ActionSession where actionNextId = :id")
                .setParameter("id", Integer.valueOf(actionNextId)).uniqueResult();
    }

    /**
     * Locks the action row first (so two first-time writers queue instead of both inserting),
     * then reads the link for update.
     */
    public ActionSession findForUpdate(Session session, int actionNextId) {
        Object action = session.get(org.openimmunizationsoftware.pt.model.ActionNext.class,
                Integer.valueOf(actionNextId));
        if (action != null) {
            session.lock(action, LockMode.UPGRADE);
        }
        return (ActionSession) session.get(ActionSession.class, Integer.valueOf(actionNextId), LockMode.UPGRADE);
    }

    /**
     * Whether a write for ({@code launchId}, {@code runId}) may replace {@code current}.
     * <ul>
     * <li>No current link: yes.</li>
     * <li>A launch (a person started or resumed the session): yes over a runner/handoff link, and
     * over another launch's link only when this launch is the same or newer (launch ids only grow).
     * So Start A, Start B, then A's late callback leaves B's link alone.</li>
     * <li>An unattended run: only over its own earlier write, never over a person's launch.</li>
     * </ul>
     */
    public static boolean mayReplace(ActionSession current, Integer launchId, Integer runId) {
        if (current == null) {
            return true;
        }
        if (launchId != null) {
            return current.getLaunchId() == null || current.getLaunchId().intValue() <= launchId.intValue();
        }
        if (runId != null) {
            return current.getLaunchId() == null && current.getRunId() != null
                    && current.getRunId().intValue() == runId.intValue();
        }
        return false;
    }

    /**
     * Sets the link when {@link #mayReplace} allows it. Returns the stored link, or null when the
     * write was refused because a newer session owns the action.
     */
    public ActionSession save(Session session, int actionNextId, String agentKey, String sessionId, String root,
            String path, Integer launchId, Integer runId, String source, Date now) {
        ActionSession current = findForUpdate(session, actionNextId);
        if (!mayReplace(current, launchId, runId)) {
            return null;
        }
        boolean isNew = current == null;
        ActionSession link = isNew ? new ActionSession() : current;
        link.setActionNextId(actionNextId);
        link.setAgentKey(agentKey);
        link.setSessionId(sessionId);
        link.setRootKey(root);
        link.setRelativePath(path == null ? "" : path);
        link.setSavedDate(now);
        link.setLaunchId(launchId);
        boolean resumedHandoff = !isNew && launchId != null && sessionId.equals(current.getSessionId())
                && (SOURCE_HANDOFF.equals(current.getSource()) || SOURCE_RUNNER.equals(current.getSource()));
        if (resumedHandoff) {
            // Resuming the agent's own session: the person now owns it (launch id), but it is still the
            // handoff from that run, so the review keeps Resume as its main button.
            link.setRunId(current.getRunId());
            link.setSource(current.getSource());
        } else {
            link.setRunId(runId);
            link.setSource(source);
        }
        if (isNew || !sessionId.equals(current.getSessionId())) {
            link.setSessionState(STATE_ACTIVE);
        } else if (link.getSessionState() == null) {
            link.setSessionState(STATE_ACTIVE);
        }
        if (isNew) {
            session.save(link);
        } else {
            session.update(link);
        }
        return link;
    }

    /**
     * Marks the session saved (the window closed, Bail or Park ran) when {@code launchId} still
     * owns the link. Returns false, changing nothing, when a newer launch owns it.
     */
    public boolean touch(Session session, int actionNextId, int launchId, String state, Date now) {
        ActionSession current = findForUpdate(session, actionNextId);
        if (current == null || current.getLaunchId() == null || current.getLaunchId().intValue() != launchId) {
            return false;
        }
        current.setSavedDate(now);
        if (state != null) {
            current.setSessionState(state);
        }
        session.update(current);
        return true;
    }

    /** Whole days between the saved date and today, both in the user's time zone. */
    public static long ageDays(Date savedDate, WebUser webUser) {
        LocalDate saved = webUser.toLocalDate(savedDate);
        return ageDays(saved, webUser.getLocalDateToday());
    }

    public static long ageDays(LocalDate savedLocalDate, LocalDate todayLocal) {
        return ChronoUnit.DAYS.between(savedLocalDate, todayLocal);
    }

    public static boolean isResumable(long ageDays) {
        return ageDays >= 0 && ageDays < RESUME_DAYS;
    }

    public Map<String, Object> toMap(ActionSession link, WebUser webUser) {
        if (link == null) {
            return null;
        }
        long age = ageDays(link.getSavedDate(), webUser);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("agentKey", link.getAgentKey());
        map.put("sessionId", link.getSessionId());
        map.put("folder", FolderRoots.format(link.getRootKey(), link.getRelativePath()));
        map.put("savedAt", org.dandeliondaily.mcp.service.McpActionContextSupport.toIso(link.getSavedDate()));
        map.put("ageDays", Long.valueOf(age));
        map.put("resumable", Boolean.valueOf(isResumable(age)));
        map.put("state", link.getSessionState());
        map.put("source", link.getSource());
        return map;
    }
}
