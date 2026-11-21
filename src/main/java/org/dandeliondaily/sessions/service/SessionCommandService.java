package org.dandeliondaily.sessions.service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.mcp.service.McpActionContextSupport;
import org.dandeliondaily.shared.render.ActionThreadView;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.ActionThreadService;
import org.dandeliondaily.shared.service.SharedActionSiblings;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionLaunch;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSession;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * What a session's own commands do, always for the action its launch is bound to (never the
 * globally current action, never through the broad MCP):
 * <ul>
 * <li>{@link #brief}: the "start on this action" read: the action, its notes and the accessible
 * chain it follows.</li>
 * <li>{@link #bail}: a few lines appended to the notes. Nothing else changes: no History, no
 * completion, no date, no time.</li>
 * <li>{@link #park}: the handoff appended to the notes, exactly one History entry on the
 * still-open action, and the action moved to tomorrow (or the chosen day). No time.</li>
 * </ul>
 * Close out goes through CloseOutService. Callers own the transaction.
 */
public class SessionCommandService {

    public static final int MAX_BAIL_LENGTH = 1000;
    public static final int MAX_BAIL_LINES = 5;
    public static final int MAX_PARK_LENGTH = 4000;
    public static final int BRIEF_STEPS = 10;

    private final LaunchService launchService = new LaunchService();
    private final ActionSessionService sessionService = new ActionSessionService();
    private final ActionNotesService notesService = new ActionNotesService();
    private final ActionThreadService threadService = new ActionThreadService();
    private final HistoryEntryWriter historyWriter = new HistoryEntryWriter();

    /** The bound action, open or not, if the user may still use it. */
    ActionNext boundAction(Session session, WebUser webUser, ActionLaunch launch) {
        ActionNext action = SessionAccess.find(session, webUser, launch.getWorkspaceId(), launch.getActionNextId());
        if (action == null) {
            throw SessionException.notFound("Action");
        }
        return action;
    }

    public Map<String, Object> brief(Session session, final WebUser webUser, int launchId) {
        ActionLaunch launch = launchService.requireRedeemed(session, webUser, launchId);
        ActionNext action = boundAction(session, webUser, launch);
        final int workspaceId = launch.getWorkspaceId();
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("launchId", Integer.valueOf(launchId));
        map.put("verb", launch.getVerb());
        map.put("action", actionMap(session, webUser, action));
        ActionSession link = sessionService.find(session, action.getActionNextId());
        map.put("session", sessionService.toMap(link, webUser));
        String state = link == null ? null : link.getSessionState();
        map.put("offerFinishOrCloseOut", Boolean.valueOf(ActionSessionService.STATE_BAILED.equals(state)
                || ActionSessionService.STATE_PARKED.equals(state)));
        ActionThreadService.Thread thread = threadService.history(session, action, BRIEF_STEPS, true,
                new ActionThreadService.StepFilter() {
                    public boolean allow(ActionNext step) {
                        return SessionAccess.canUse(step, webUser, workspaceId);
                    }
                });
        map.put("thread", ActionThreadView.toJsonList(webUser, thread));
        map.put("threadHasMore", Boolean.valueOf(thread.isMore()));
        return map;
    }

    Map<String, Object> actionMap(Session session, WebUser webUser, ActionNext action) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("actionNextId", Integer.valueOf(action.getActionNextId()));
        map.put("description", action.getNextDescription());
        map.put("status", action.getNextActionStatusString());
        map.put("scheduledDate", action.getNextActionDate() == null ? null
                : webUser.toLocalDate(action.getNextActionDate()).toString());
        map.put("asOf", McpActionContextSupport.toIso(action.getNextChangeDate()));
        Project project = SessionAccess.project(session, action);
        map.put("projectId", Integer.valueOf(action.getProjectId()));
        map.put("projectName", project == null ? null : project.getProjectName());
        map.put("notes", notesService.read(action));
        map.put("performedBy", action.getPerformedByAgent());
        return map;
    }

    /** Lines a Bail may append: 1 to 5 non-blank lines, at most 1,000 characters. */
    public static String validateBail(String text) {
        String value = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (value.length() == 0) {
            throw SessionException.bad("Bail needs a few lines saying where you were.");
        }
        if (value.length() > MAX_BAIL_LENGTH || value.split("\n").length > MAX_BAIL_LINES) {
            throw SessionException.bad("Bail is a quick note: at most " + MAX_BAIL_LINES + " lines.");
        }
        return value;
    }

    public Map<String, Object> bail(Session session, WebUser webUser, int launchId, String text, Date now) {
        String lines = validateBail(text);
        ActionLaunch launch = launchService.requireRedeemed(session, webUser, launchId);
        ActionNext action = boundAction(session, webUser, launch);
        String block = "Where I was (bail " + stamp(webUser, now) + "):\n" + lines;
        if (!notesService.read(action).trim().endsWith(lines)) {
            ActionNotesService.Result result = notesService.append(session, action, block, now);
            if (!result.isOk()) {
                throw SessionException.bad(result.getErrorMessage());
            }
        }
        boolean sessionSaved = sessionService.touch(session, action.getActionNextId(), launchId,
                ActionSessionService.STATE_BAILED, now);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("actionNextId", Integer.valueOf(action.getActionNextId()));
        map.put("notesAppended", Boolean.TRUE);
        map.put("sessionSaved", Boolean.valueOf(sessionSaved));
        return map;
    }

    /**
     * Park. {@code requestKey} is required: a retry with the same key (lost response, queued
     * callback) returns the first result and writes nothing.
     */
    public Map<String, Object> park(Session session, WebUser webUser, int launchId, String handoff, String dateText,
            String requestKey, Date now) {
        if (requestKey == null || !requestKey.matches("[A-Za-z0-9_.:-]{8,70}")) {
            throw SessionException.bad("\"requestKey\" is required (8-70 letters, digits, . _ : -).");
        }
        String text = handoff == null ? "" : handoff.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.length() == 0) {
            throw SessionException.bad("Park needs a short handoff.");
        }
        if (text.length() > MAX_PARK_LENGTH) {
            throw SessionException.bad("The handoff is limited to " + MAX_PARK_LENGTH + " characters.");
        }
        ActionLaunch launch = launchService.requireRedeemed(session, webUser, launchId);
        ActionNext action = boundAction(session, webUser, launch);
        List<ActionTaken> earlier = historyWriter.findByRequestKey(session, requestKey);
        if (!earlier.isEmpty()) {
            Map<String, Object> map = parkResult(webUser, action, earlier.get(0));
            map.put("replayed", Boolean.TRUE);
            return map;
        }
        if (!ProjectNextActionStatus.READY.getId().equals(action.getNextActionStatusString())) {
            throw SessionException.conflict("not_open", "This action is no longer open.");
        }
        LocalDate today = webUser.getLocalDateToday();
        LocalDate date;
        if (dateText == null || dateText.trim().length() == 0) {
            date = today.plusDays(1);
        } else {
            try {
                date = LocalDate.parse(dateText.trim());
            } catch (DateTimeParseException e) {
                throw SessionException.bad("\"date\" must be yyyy-MM-dd.");
            }
            if (date.isBefore(today)) {
                throw SessionException.bad("Park can't move an action into the past.");
            }
        }
        String block = "Parked " + stamp(webUser, now) + " to " + date + ":\n" + text;
        ActionNotesService.Result result = notesService.append(session, action, block, now);
        if (!result.isOk()) {
            throw SessionException.bad(result.getErrorMessage());
        }
        String firstLine = text.split("\n")[0];
        String description = "Parked: " + (firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine);
        List<ActionTaken> written = historyWriter.write(session, webUser, action, description, text, requestKey,
                null, now);
        for (ActionNext twin : SharedActionSiblings.resolve(session, action)) {
            twin.setNextActionDate(java.sql.Date.valueOf(date));
            twin.setNextChangeDate(now);
            session.update(twin);
        }
        sessionService.touch(session, action.getActionNextId(), launchId, ActionSessionService.STATE_PARKED, now);
        return parkResult(webUser, action, written.isEmpty() ? null : written.get(0));
    }

    private Map<String, Object> parkResult(WebUser webUser, ActionNext action, ActionTaken taken) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("actionNextId", Integer.valueOf(action.getActionNextId()));
        map.put("scheduledDate", action.getNextActionDate() == null ? null
                : webUser.toLocalDate(action.getNextActionDate()).toString());
        map.put("historyEntryId", taken == null ? null : Integer.valueOf(taken.getActionTakenId()));
        map.put("replayed", Boolean.FALSE);
        return map;
    }

    static String stamp(WebUser webUser, Date now) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        format.setTimeZone(webUser.getTimeZone());
        return format.format(now);
    }

    /** Lists the steps shown to a session; used by tests. */
    static List<Integer> stepIds(ActionThreadService.Thread thread) {
        List<Integer> ids = new ArrayList<Integer>();
        for (ActionThreadService.Step step : thread.getSteps()) {
            ids.add(Integer.valueOf(step.getActionNextId()));
        }
        return ids;
    }
}
