package org.dandeliondaily.shared.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.dandeliondaily.dashboard.service.ActionRecoveryService;
import org.dandeliondaily.dashboard.service.ActionSentenceImportService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.manager.ProjectActionBlockerManager;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSet;
import org.openimmunizationsoftware.pt.model.ActionSetType;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.BillEntry;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

/**
 * Shared completion logic for the Edit Action modal and the dashboard work
 * flow. Supports backdated completion with an optional time entry.
 */
public class ActionCompletionService {

    public static final int MAX_DURATION_MINS = 12 * 60;

    private final ActionRecoveryService actionRecoveryService = new ActionRecoveryService();
    private final ActionNotesService actionNotesService = new ActionNotesService();
    private final ActionSentenceImportService actionSentenceImportService = new ActionSentenceImportService();

    /** Parsed completion moment plus duration, or an error message. */
    public static class CompletionTime {
        private Date moment;
        private int durationMins;
        private String errorMessage;

        /** Builds a valid CompletionTime directly, bypassing the UI's string-parsing path. */
        public static CompletionTime of(Date moment, int durationMins) {
            CompletionTime completionTime = new CompletionTime();
            completionTime.moment = moment;
            completionTime.durationMins = durationMins;
            return completionTime;
        }

        public Date getMoment() {
            return moment;
        }

        public int getDurationMins() {
            return durationMins;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public boolean hasError() {
            return errorMessage != null;
        }
    }

    /**
     * Parses the completion date/time/duration inputs from the Edit Action modal.
     * A blank date or time falls back to the user's current date/time; a blank
     * duration means zero.
     */
    public CompletionTime parseCompletionTime(WebUser webUser, String dateValue, String timeValue,
            String durationValue) {
        CompletionTime completionTime = new CompletionTime();

        int durationMins = 0;
        String durationText = trim(durationValue);
        if (durationText.length() > 0) {
            try {
                durationMins = Integer.parseInt(durationText);
            } catch (NumberFormatException nfe) {
                completionTime.errorMessage = "Minutes spent must be a whole number.";
                return completionTime;
            }
        }
        if (durationMins < 0) {
            completionTime.errorMessage = "Minutes spent cannot be negative.";
            return completionTime;
        }
        if (durationMins > MAX_DURATION_MINS) {
            completionTime.errorMessage = "Minutes spent cannot be more than " + MAX_DURATION_MINS + ".";
            return completionTime;
        }
        completionTime.durationMins = durationMins;

        Calendar calendar = webUser.getCalendar();
        String dateText = trim(dateValue);
        if (dateText.length() > 0) {
            String[] dateParts = dateText.split("-");
            if (dateParts.length != 3) {
                completionTime.errorMessage = "Completion date must be in yyyy-MM-dd format.";
                return completionTime;
            }
            try {
                calendar.set(Calendar.YEAR, Integer.parseInt(dateParts[0]));
                calendar.set(Calendar.MONTH, Integer.parseInt(dateParts[1]) - 1);
                calendar.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dateParts[2]));
            } catch (NumberFormatException nfe) {
                completionTime.errorMessage = "Completion date must be in yyyy-MM-dd format.";
                return completionTime;
            }
        }

        String timeText = trim(timeValue);
        if (timeText.length() > 0) {
            String[] timeParts = timeText.split(":");
            if (timeParts.length < 2) {
                completionTime.errorMessage = "Completion time must be in HH:mm format.";
                return completionTime;
            }
            try {
                calendar.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeParts[0]));
                calendar.set(Calendar.MINUTE, Integer.parseInt(timeParts[1]));
            } catch (NumberFormatException nfe) {
                completionTime.errorMessage = "Completion time must be in HH:mm format.";
                return completionTime;
            }
        }
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        completionTime.moment = calendar.getTime();
        return completionTime;
    }

    /**
     * Returns an error message when the completion cannot be recorded, or null
     * when it is safe to proceed. Nothing is written by this method.
     */
    public String validateCompletion(AppReq appReq, ActionNext action, CompletionTime completionTime) {
        // Guard first, without touching appReq -- mirrors the original method's evaluation
        // order (some callers may pass an incompletely-initialized appReq for these cases).
        if (completionTime == null || completionTime.hasError()) {
            return completionTime == null ? "Completion time is required." : completionTime.getErrorMessage();
        }
        if (action == null) {
            return "Action not found.";
        }
        return validateCompletion(appReq.getWebUser(), appReq.getDataSession(), action, completionTime);
    }

    /** Core of validateCompletion, without the AppReq/web-session coupling, for non-UI callers (e.g. MCP). */
    public String validateCompletion(WebUser webUser, Session dataSession, ActionNext action,
            CompletionTime completionTime) {
        if (completionTime == null || completionTime.hasError()) {
            return completionTime == null ? "Completion time is required." : completionTime.getErrorMessage();
        }
        if (action == null) {
            return "Action not found.";
        }
        Date now = webUser.now();
        Date start = completionTime.getMoment();
        if (start.after(now)) {
            return "Completion date and time cannot be in the future.";
        }

        int durationMins = completionTime.getDurationMins();
        if (durationMins == 0) {
            return null;
        }

        Date end = addMinutes(start, durationMins);
        if (end.after(now)) {
            return "The time entry would end in the future. Reduce the minutes spent or move the start time earlier.";
        }

        Project project = resolveProject(dataSession, action);
        if (project == null) {
            return "This action has no project, so time cannot be recorded. Set minutes spent to 0.";
        }
        if (ClientServlet.resolveBillCode(dataSession, project) == null) {
            return "Project \"" + project.getProjectName()
                    + "\" has no bill code, so time cannot be recorded. Set minutes spent to 0 or add a bill code to the project.";
        }

        BillEntry conflict = findOverlappingEntry(dataSession, webUser, start, end);
        if (conflict != null) {
            return "This time overlaps an existing entry from " + formatMoment(webUser, conflict.getStartTime())
                    + " to " + formatMoment(webUser, conflict.getEndTime()) + ".";
        }
        return null;
    }

    /**
     * Completes (or cancels) an action and all shared-action-set siblings,
     * recording an action taken note and an optional time entry.
     */
    public ActionNext closeAction(AppReq appReq, ActionNext projectAction, String nextDescription,
            ProjectNextActionStatus nextActionStatus, Date completionMoment, int durationMins) {
        Session dataSession = appReq.getDataSession();
        Transaction trans = dataSession.beginTransaction();
        ActionNext unblockedAction;
        try {
            unblockedAction = applyCompletion(dataSession, appReq.getWebUser(), projectAction, nextDescription,
                    nextActionStatus, completionMoment, durationMins);
            trans.commit();
        } catch (RuntimeException re) {
            if (trans.isActive()) {
                trans.rollback();
            }
            throw re;
        }
        actionRecoveryService.remember(appReq, projectAction, nextActionStatus);
        return unblockedAction;
    }

    /**
     * Null when the outcome note and the next action's notes fit, otherwise
     * the message to show. Nothing is written by this method.
     */
    public String validateHandoff(String outcomeNote, String followUpNotes) {
        if (actionNotesService.lengthError(outcomeNote) != null) {
            return "The outcome note is limited to " + String.format("%,d", ActionNotesService.MAX_LENGTH)
                    + " characters.";
        }
        if (actionNotesService.lengthError(followUpNotes) != null) {
            return "Notes for the next action are limited to "
                    + String.format("%,d", ActionNotesService.MAX_LENGTH) + " characters.";
        }
        return null;
    }

    /**
     * closeAction plus the handoff. {@code outcomeNote} is the detail kept with
     * the action taken (what happened, decisions, where it was left). A
     * non-blank {@code followUpSentence} creates the next action in the same
     * project, in the same transaction, starting with {@code followUpNotes};
     * the sentence is read like Quick Capture ("call Chris tomorrow").
     *
     * @return the next action created, or null when none was asked for
     * @throws IllegalArgumentException when the follow-up sentence cannot be
     *                                  read; nothing is written then
     */
    public ActionNext closeActionWithHandoff(AppReq appReq, ActionNext projectAction, String nextDescription,
            String outcomeNote, ProjectNextActionStatus nextActionStatus, Date completionMoment, int durationMins,
            String followUpSentence, String followUpNotes) {
        Session dataSession = appReq.getDataSession();
        Transaction trans = dataSession.beginTransaction();
        ActionNext followUp = null;
        try {
            applyCompletion(dataSession, appReq.getWebUser(), projectAction, nextDescription, outcomeNote,
                    nextActionStatus, completionMoment, durationMins);
            if (followUpSentence != null && followUpSentence.trim().length() > 0) {
                followUp = createFollowUp(dataSession, appReq.getWebUser(), projectAction, followUpSentence,
                        followUpNotes);
            }
            trans.commit();
        } catch (RuntimeException re) {
            if (trans.isActive()) {
                trans.rollback();
            }
            throw re;
        }
        actionRecoveryService.remember(appReq, projectAction, nextActionStatus);
        return followUp;
    }

    /**
     * Creates the next action after {@code sourceAction} from a Quick Capture
     * style sentence, with {@code notes} as its starting notes. See
     * {@link #createFollowUp(Session, WebUser, ActionNext, FollowUpBuilder, String)}.
     *
     * @return the next action in {@code sourceAction}'s own project
     */
    public ActionNext createFollowUp(final Session dataSession, final WebUser webUser, ActionNext sourceAction,
            String sentence, String notes) {
        final String text = sentence == null ? "" : sentence.trim();
        return createFollowUp(dataSession, webUser, sourceAction, new FollowUpBuilder() {
            public ActionNext build(Project project) {
                ActionNext next = actionSentenceImportService.buildActionFromSentenceForProject(webUser,
                        dataSession, project, text, project.getWorkspaceId(), false);
                if (next == null) {
                    throw new IllegalArgumentException("The next action could not be read.");
                }
                return next;
            }
        }, notes).get(0);
    }

    /** Builds one new, unsaved action for a project; its action set, notes and link are set by the caller. */
    public interface FollowUpBuilder {
        ActionNext build(Project project);
    }

    /**
     * Creates the next step of {@code sourceAction}'s thread, inside the
     * caller's open transaction. A linked-project action gets a linked next
     * action: one twin per project, joined by a new SHARED action set, each
     * following the step in its own project, so either side keeps its own
     * thread if the projects are later unlinked. Every twin starts with
     * {@code notes}.
     *
     * @return the new actions, the one in {@code sourceAction}'s project first
     * @throws IllegalArgumentException when {@code sourceAction} has no project
     *                                  or the builder cannot build the action
     */
    public List<ActionNext> createFollowUp(Session dataSession, WebUser webUser, ActionNext sourceAction,
            FollowUpBuilder builder, String notes) {
        if (resolveProject(dataSession, sourceAction) == null) {
            throw new IllegalArgumentException("This action has no project, so a next action cannot be added.");
        }
        List<ActionNext> steps = new ArrayList<ActionNext>();
        steps.add(sourceAction);
        for (ActionNext sibling : SharedActionSiblings.resolve(dataSession, sourceAction)) {
            if (sibling.getActionNextId() != sourceAction.getActionNextId()) {
                steps.add(sibling);
            }
        }
        ActionSetDao actionSetDao = new ActionSetDao(dataSession);
        ActionSet sharedSet = null;
        List<ActionNext> created = new ArrayList<ActionNext>();
        for (ActionNext step : steps) {
            Project project = resolveProject(dataSession, step);
            if (project == null) {
                continue;
            }
            ActionNext next = builder.build(project);
            if (steps.size() > 1) {
                if (sharedSet == null) {
                    sharedSet = actionSetDao.createActionSet(webUser, ActionSetType.SHARED);
                }
                next.setActionSet(sharedSet);
            } else {
                next.setActionSet(actionSetDao.createStandardActionSet(webUser));
            }
            next.setFollowsActionNextId(Integer.valueOf(step.getActionNextId()));
            actionNotesService.initialize(next, notes);
            dataSession.saveOrUpdate(next);
            created.add(next);
        }
        return created;
    }

    /**
     * Core of closeAction, without the AppReq/web-session coupling (no
     * transaction management, no undo-recovery recording) -- for non-UI
     * callers (e.g. MCP) that manage their own ambient transaction, possibly
     * spanning several such calls. Never call this while a transaction is not
     * already open on dataSession.
     */
    public ActionNext applyCompletion(Session dataSession, WebUser webUser, ActionNext projectAction,
            String nextDescription, ProjectNextActionStatus nextActionStatus, Date completionMoment,
            int durationMins) {
        return applyCompletion(dataSession, webUser, projectAction, nextDescription, null, nextActionStatus,
                completionMoment, durationMins);
    }

    /**
     * applyCompletion with an outcome note kept on each action taken row. When
     * there is a note but no description, the action's own description stands
     * in, so the note is never dropped.
     */
    public ActionNext applyCompletion(Session dataSession, WebUser webUser, ActionNext projectAction,
            String nextDescription, String outcomeNote, ProjectNextActionStatus nextActionStatus,
            Date completionMoment, int durationMins) {
        String storedNote = normalizeOutcomeNote(outcomeNote);
        if (storedNote != null && (nextDescription == null || nextDescription.trim().isEmpty())
                && projectAction != null) {
            nextDescription = projectAction.getNextDescription();
        }
        ActionNext unblockedAction = null;
        List<ActionNext> actionSiblings = resolveSharedActionSiblings(dataSession, projectAction);
        Date now = completionMoment == null ? new Date() : completionMoment;
        for (ActionNext sibling : actionSiblings) {
            Project siblingProject = resolveProject(dataSession, sibling);
            if (nextDescription != null && !nextDescription.trim().isEmpty() && siblingProject != null) {
                ActionTaken actionTaken = new ActionTaken();
                actionTaken.setProject(siblingProject);
                actionTaken.setProjectId(siblingProject.getProjectId());
                actionTaken.setActionDate(now);
                actionTaken.setActionDescription(nextDescription.trim());
                actionTaken.setActionNote(storedNote);
                actionTaken.setWorkspaceId(sibling.getWorkspaceId());
                actionTaken.setContact(webUser.getProjectContact());
                actionTaken.setContactId(webUser.getContactId());
                ActionSet actionSet = sibling.getActionSet();
                if (actionSet == null) {
                    actionSet = new ActionSetDao(dataSession).createStandardActionSet(webUser);
                    sibling.setActionSet(actionSet);
                    dataSession.update(sibling);
                }
                actionTaken.setActionSet(actionSet);
                dataSession.saveOrUpdate(actionTaken);
            }

            sibling.setNextActionStatus(nextActionStatus);
            sibling.setCompletionOrder(0);
            sibling.setNextChangeDate(now);
            dataSession.update(sibling);

            if (nextActionStatus == ProjectNextActionStatus.COMPLETED
                    || nextActionStatus == ProjectNextActionStatus.CANCELLED) {
                ActionNext unblocked = ProjectActionBlockerManager.unblockActionsBlockedBy(dataSession, webUser,
                        sibling);
                if (unblocked != null) {
                    unblockedAction = unblocked;
                }
            }
        }

        if (durationMins > 0) {
            BillEntry billEntry = buildBillEntry(dataSession, webUser, projectAction, now, durationMins);
            if (billEntry != null) {
                dataSession.save(billEntry);
            }
        }
        return unblockedAction;
    }

    /** Line endings normalized, trailing whitespace trimmed; blank becomes null. */
    private static String normalizeOutcomeNote(String note) {
        if (note == null) {
            return null;
        }
        String text = note.replace("\r\n", "\n").replace('\r', '\n');
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        text = text.substring(0, end);
        return text.trim().length() == 0 ? null : text;
    }

    private BillEntry buildBillEntry(Session dataSession, WebUser webUser, ActionNext action, Date start,
            int durationMins) {
        Project project = resolveProject(dataSession, action);
        if (project == null) {
            return null;
        }
        BillCode billCode = ClientServlet.resolveBillCode(dataSession, project);
        if (billCode == null) {
            return null;
        }
        Integer workspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(webUser.getWebUserId());
        if (workspaceId == null) {
            workspaceId = project.getWorkspaceId();
        }
        BillEntry billEntry = TimeTracker.createBillEntry(project, action, billCode, workspaceId, webUser, start);
        billEntry.setEndTime(addMinutes(start, durationMins));
        billEntry.setBillMins(Integer.valueOf(durationMins));
        return billEntry;
    }

    private BillEntry findOverlappingEntry(Session dataSession, WebUser webUser, Date start, Date end) {
        Query query = dataSession.createQuery(
                "from BillEntry be where be.webUser.webUserId = :webUserId and be.billMins > 0"
                        + " and be.startTime < :endTime and be.endTime > :startTime order by be.startTime");
        query.setParameter("webUserId", Integer.valueOf(webUser.getWebUserId()));
        query.setParameter("startTime", start);
        query.setParameter("endTime", end);
        query.setMaxResults(1);
        @SuppressWarnings("unchecked")
        List<BillEntry> conflicts = query.list();
        return conflicts == null || conflicts.isEmpty() ? null : conflicts.get(0);
    }

    private List<ActionNext> resolveSharedActionSiblings(Session dataSession, ActionNext selectedAction) {
        List<ActionNext> singleAction = new ArrayList<ActionNext>();
        if (selectedAction == null) {
            return singleAction;
        }
        singleAction.add(selectedAction);
        if (selectedAction.getActionSet() == null
                || selectedAction.getActionSet().getActionSetType() != ActionSetType.SHARED) {
            return singleAction;
        }
        int actionSetId = selectedAction.getActionSet().getActionSetId();
        Query siblingQuery = dataSession.createQuery(
                "from ActionNext an where an.actionSet.actionSetId = :actionSetId order by an.actionNextId");
        siblingQuery.setParameter("actionSetId", actionSetId);
        @SuppressWarnings("unchecked")
        List<ActionNext> siblings = siblingQuery.list();
        if (siblings == null || siblings.isEmpty()) {
            return singleAction;
        }
        return siblings;
    }

    private Project resolveProject(Session dataSession, ActionNext action) {
        Project project = action.getProject();
        if (project == null && action.getProjectId() > 0) {
            project = (Project) dataSession.get(Project.class, action.getProjectId());
        }
        return project;
    }

    private String formatMoment(WebUser webUser, Date date) {
        if (date == null) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("MM/dd h:mm a");
        format.setTimeZone(webUser.getTimeZone());
        return format.format(date);
    }

    static Date addMinutes(Date start, int minutes) {
        return new Date(start.getTime() + (minutes * 60000L));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
