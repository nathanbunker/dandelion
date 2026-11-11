package org.dandeliondaily.dashboard.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.shared.service.ActionCompletionService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ProcessStage;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.model.ActionSet;

public class DashboardCurrentActionService {

    private final ActionSentenceImportService actionSentenceImportService = new ActionSentenceImportService();
    private final ActionCompletionService actionCompletionService = new ActionCompletionService();

    private static final String PARAM_ACTION = "action";
    private static final String ACTION_WORK_NEXT = "WorkNext";
    private static final String ACTION_SELECT = "SelectAction";
    private static final String PARAM_NEXT_SUMMARY = "nextSummary";
    private static final String PARAM_WORK_STATUS = "workStatus";
    private static final String PARAM_WORK_FOLLOW_UP = "workFollowUp";
    /** Optional detail behind what was done, kept as the action taken's outcome note. */
    private static final String PARAM_OUTCOME_NOTE = "outcomeNote";
    /** "1": after saving progress, keep the same action current instead of moving on. */
    private static final String PARAM_STAY = "stay";
    private static final String PARAM_COMPLETING_ACTION_NEXT_ID = "completingActionNextId";

    private static final String WORK_STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String WORK_STATUS_COMPLETE = "COMPLETE";
    private static final String WORK_STATUS_DELETE = "DELETE";
    private static final String WORK_STATUS_BLOCKED = "BLOCKED";

    private static final int BUCKET_START_OF_WORK_DAY = 0;
    private static final int BUCKET_OVERDUE = 1;
    private static final int BUCKET_COMMITTED = 3;
    private static final int BUCKET_WILL = 4;
    private static final int BUCKET_PERSONAL_MORNING = 5;
    private static final int BUCKET_MIGHT = 6;
    private static final int BUCKET_WAITING = 7;
    private static final int BUCKET_WILL_MEET = 8;
    private static final int BUCKET_END_OF_WORK_DAY = 9;
    private static final int BUCKET_OTHER = 11;

    public void handleSelectAction(AppReq appReq) {
        String actionParam = appReq.getRequest().getParameter(PARAM_ACTION);
        if (!ACTION_SELECT.equals(actionParam)) {
            return;
        }
        String actionIdString = appReq.getRequest().getParameter(PARAM_COMPLETING_ACTION_NEXT_ID);
        if (actionIdString == null || actionIdString.trim().length() == 0) {
            return;
        }
        try {
            int actionId = Integer.parseInt(actionIdString.trim());
            Session dataSession = appReq.getDataSession();
            ActionNext selected = (ActionNext) dataSession.get(ActionNext.class, actionId);
            if (selected != null) {
                appReq.setCompletingAction(selected);
                if (selected.getProject() != null) {
                    appReq.setProject(selected.getProject());
                }
            }
        } catch (NumberFormatException nfe) {
            // ignore invalid id
        }
    }

    public void ensureCurrentActionSelected(AppReq appReq) {
        ActionNext currentAction = appReq.getCompletingAction();
        if (currentAction != null) {
            return;
        }

        ActionNext nextAction = selectNextActionForWorkFlow(appReq.getWebUser(), appReq.getDataSession(), 0);
        appReq.setCompletingAction(nextAction);
        if (nextAction != null && nextAction.getProject() != null) {
            appReq.setProject(nextAction.getProject());
        }
    }

    public boolean handoffCurrentActionIfMovedOffToday(AppReq appReq, ActionNext movedAction) {
        if (appReq == null || !shouldRefreshCurrentAction(appReq.getCompletingAction(), movedAction,
                toStoredLocalDate(movedAction == null ? null : movedAction.getNextActionDate(), appReq.getWebUser()),
                appReq.getWebUser() == null ? null : appReq.getWebUser().getLocalDateToday())) {
            return false;
        }

        return handoffCurrentAction(appReq);
    }

    public boolean handoffCurrentAction(AppReq appReq) {
        if (appReq == null || appReq.getCompletingAction() == null) {
            return false;
        }

        TimeTracker timeTracker = appReq.getTimeTracker();
        boolean timerWasRunning = timeTracker != null && timeTracker.isRunningClock();

        appReq.setCompletingAction(null);
        ensureCurrentActionSelected(appReq);

        ActionNext replacementAction = appReq.getCompletingAction();
        syncTimerWithCurrentAction(timerWasRunning, replacementAction, new TimerHandoff() {
            @Override
            public void start(ActionNext action) {
                timeTracker.startClock(action.getProject(), action, appReq.getDataSession());
            }

            @Override
            public void stop() {
                timeTracker.stopClock(appReq.getDataSession());
            }
        });

        return true;
    }

    boolean shouldRefreshCurrentAction(ActionNext currentAction, ActionNext movedAction, LocalDate movedDate,
            LocalDate today) {
        Integer currentActionId = currentAction == null ? null : Integer.valueOf(currentAction.getActionNextId());
        Integer movedActionId = movedAction == null ? null : Integer.valueOf(movedAction.getActionNextId());
        return shouldHandoffCurrentAction(currentActionId, movedActionId, movedDate, today);
    }

    /**
     * Like {@link #shouldHandoffCurrentAction(Integer, Integer, LocalDate, LocalDate)}, but only when
     * the save changed the date. A current action that was already dated in the future (switched to
     * on purpose, for example to add notes) stays current when it is edited without moving it.
     */
    public boolean shouldHandoffCurrentAction(Integer currentActionId, Integer movedActionId, LocalDate previousDate,
            LocalDate movedDate, LocalDate today) {
        if (previousDate != null && previousDate.equals(movedDate)) {
            return false;
        }
        return shouldHandoffCurrentAction(currentActionId, movedActionId, movedDate, today);
    }

    public boolean shouldHandoffCurrentAction(Integer currentActionId, Integer movedActionId, LocalDate movedDate,
            LocalDate today) {
        if (currentActionId == null || movedActionId == null || movedDate == null || today == null) {
            return false;
        }
        if (!currentActionId.equals(movedActionId)) {
            return false;
        }
        return movedDate.isAfter(today);
    }

    void syncTimerWithCurrentAction(boolean timerWasRunning, ActionNext replacementAction, TimerHandoff timerHandoff) {
        if (!timerWasRunning || timerHandoff == null) {
            return;
        }
        if (replacementAction != null && replacementAction.getProject() != null) {
            timerHandoff.start(replacementAction);
            return;
        }
        timerHandoff.stop();
    }

    interface TimerHandoff {
        void start(ActionNext action);

        void stop();
    }

    public void handleCurrentActionWork(AppReq appReq) {
        String action = appReq.getRequest().getParameter(PARAM_ACTION);
        if (!ACTION_WORK_NEXT.equals(action)) {
            return;
        }

        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();

        ActionNext actionToWork = resolveActionToWork(appReq, dataSession);
        if (actionToWork == null) {
            appReq.addErrorMessage("Current action was not found.");
            return;
        }
        String actionTakenText = readActionTakenText(appReq);
        String outcomeNote = ActionCompletionService
                .normalizeOutcomeNote(appReq.getRequest().getParameter(PARAM_OUTCOME_NOTE));
        String noteError = actionCompletionService.validateHandoff(outcomeNote, null);
        if (noteError != null) {
            appReq.addErrorMessage(noteError);
            return;
        }
        boolean stay = "1".equals(appReq.getRequest().getParameter(PARAM_STAY));

        String workStatus = normalizeWorkStatus(appReq.getRequest().getParameter(PARAM_WORK_STATUS));
        if (workStatus.length() == 0) {
            workStatus = WORK_STATUS_IN_PROGRESS;
        }

        String followUpText = appReq.getRequest().getParameter(PARAM_WORK_FOLLOW_UP);
        followUpText = followUpText == null ? "" : followUpText.trim();
        boolean hasFollowUp = followUpText.length() > 0;

        if (WORK_STATUS_BLOCKED.equals(workStatus) && !hasFollowUp) {
            appReq.addWarningMessage("Blocked requires a follow-up action.");
            return;
        }

        ActionNext followUpAction = null;
        if (hasFollowUp) {
            try {
                followUpAction = createFollowUpActionFromSentence(webUser, dataSession, actionToWork, followUpText);
            } catch (IllegalArgumentException iae) {
                appReq.addErrorMessage(iae.getMessage());
                return;
            }
        }

        if (WORK_STATUS_COMPLETE.equals(workStatus)) {
            closeAction(appReq, actionToWork, actionTakenText, outcomeNote, ProjectNextActionStatus.COMPLETED);
        } else if (WORK_STATUS_DELETE.equals(workStatus)) {
            closeAction(appReq, actionToWork, actionTakenText, outcomeNote, ProjectNextActionStatus.CANCELLED);
        } else if (WORK_STATUS_BLOCKED.equals(workStatus) && followUpAction != null) {
            saveStandaloneActionTaken(appReq, actionToWork, actionTakenText, outcomeNote);
            Transaction blockTrans = dataSession.beginTransaction();
            actionToWork.setBlockedBy(followUpAction);
            actionToWork.setNextActionDate(null);
            actionToWork.setNextChangeDate(new Date());
            dataSession.update(actionToWork);
            blockTrans.commit();
        } else if (WORK_STATUS_IN_PROGRESS.equals(workStatus)) {
            saveStandaloneActionTaken(appReq, actionToWork, actionTakenText, outcomeNote);
            appReq.addInfoMessage("Progress saved.");
        }

        rationalizeCompletionOrderForCurrentDate(webUser, dataSession);

        int actionToWorkId = actionToWork.getActionNextId();
        ActionNext nextCompletingAction;
        if (stay && WORK_STATUS_IN_PROGRESS.equals(workStatus)) {
            nextCompletingAction = actionToWork;
        } else if ((WORK_STATUS_BLOCKED.equals(workStatus) || WORK_STATUS_IN_PROGRESS.equals(workStatus))
                && followUpAction != null
                && followUpAction.getNextActionDate() != null
                && (WORK_STATUS_IN_PROGRESS.equals(workStatus)
                        || webUser.isToday(followUpAction.getNextActionDate()))) {
            nextCompletingAction = followUpAction;
        } else if (WORK_STATUS_IN_PROGRESS.equals(workStatus)) {
            ActionNext nextInOrder = selectNextAfterCurrentInList(webUser, dataSession, actionToWorkId);
            nextCompletingAction = nextInOrder == null ? actionToWork : nextInOrder;
        } else {
            nextCompletingAction = selectNextActionForWorkFlow(webUser, dataSession, actionToWorkId);
        }

        appReq.setCompletingAction(nextCompletingAction);
        if (nextCompletingAction != null && nextCompletingAction.getProject() != null) {
            appReq.setProject(nextCompletingAction.getProject());
        }

        if (WORK_STATUS_COMPLETE.equals(workStatus)) {
            appReq.addSuccessMessage(buildCompletionMessage(actionToWork));
        } else if (WORK_STATUS_DELETE.equals(workStatus)) {
            appReq.addInfoMessage("Action cancelled.");
        } else if (WORK_STATUS_BLOCKED.equals(workStatus)) {
            appReq.addInfoMessage("Action blocked and follow-up saved.");
        } else if (WORK_STATUS_IN_PROGRESS.equals(workStatus) && followUpAction != null) {
            appReq.addInfoMessage("Progress saved and next action created.");
        }
    }

    private String buildCompletionMessage(ActionNext actionToWork) {
        if (actionToWork == null || actionToWork.getNextTimeEstimate() == null
                || actionToWork.getNextTimeActual() == null) {
            return "Action completed.";
        }
        int estimateMinutes = actionToWork.getNextTimeEstimate().intValue();
        int actualMinutes = actionToWork.getNextTimeActual().intValue();
        int savedMinutes = estimateMinutes - actualMinutes;
        if (savedMinutes > 0) {
            return "Action completed. Saved " + savedMinutes + " minutes.";
        }
        return "Action completed.";
    }

    private ActionNext resolveActionToWork(AppReq appReq, Session dataSession) {
        ActionNext actionToWork = appReq.getCompletingAction();
        String actionIdString = appReq.getRequest().getParameter(PARAM_COMPLETING_ACTION_NEXT_ID);
        if (actionIdString != null && actionIdString.trim().length() > 0) {
            try {
                int actionId = Integer.parseInt(actionIdString);
                actionToWork = (ActionNext) dataSession.get(ActionNext.class, actionId);
            } catch (NumberFormatException nfe) {
                // Keep existing session action if request id is invalid.
            }
        }
        return actionToWork;
    }

    private String readActionTakenText(AppReq appReq) {
        String nextSummary = appReq.getRequest().getParameter(PARAM_NEXT_SUMMARY);
        return nextSummary == null ? "" : nextSummary.trim();
    }

    /**
     * Progress logged on an action that stays open (In Progress, Blocked). The
     * entry joins the action's own action set, so it shows in the action's
     * thread alongside what is recorded when the action is completed.
     */
    private void saveStandaloneActionTaken(AppReq appReq, ActionNext action, String actionTakenText,
            String outcomeNote) {
        Project project = action == null ? null : action.getProject();
        // Like applyCompletion: a note with no description is kept under the action's own description.
        if (outcomeNote != null && (actionTakenText == null || actionTakenText.isEmpty()) && action != null) {
            actionTakenText = action.getNextDescription();
        }
        if (actionTakenText == null || actionTakenText.isEmpty() || project == null) {
            return;
        }

        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();

        Transaction trans = dataSession.beginTransaction();
        ActionSet actionSet = action.getActionSet();
        if (actionSet == null) {
            actionSet = new ActionSetDao(dataSession).createStandardActionSet(webUser);
            action.setActionSet(actionSet);
            dataSession.update(action);
        }

        ActionTaken actionTaken = new ActionTaken();
        actionTaken.setProject(project);
        actionTaken.setProjectId(project.getProjectId());
        actionTaken.setActionDate(new Date());
        actionTaken.setActionDescription(actionTakenText);
        actionTaken.setActionNote(outcomeNote);
        actionTaken.setWorkspaceId(WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId()));
        actionTaken.setContact(webUser.getProjectContact());
        actionTaken.setContactId(webUser.getContactId());
        actionTaken.setActionSet(actionSet);
        dataSession.saveOrUpdate(actionTaken);
        trans.commit();
    }

    /**
     * The "Next" box. In the action's own project the next action continues
     * its thread (twinned when the action is linked across projects, through
     * ActionCompletionService). A sentence that names another project keeps
     * creating the action there, still marked as following this one.
     */
    private ActionNext createFollowUpActionFromSentence(WebUser webUser, Session dataSession,
            ActionNext sourceAction, String sentenceInput) {
        List<Project> projectList = loadProjectList(webUser, dataSession);
        Project defaultProject = sourceAction == null ? null : sourceAction.getProject();
        if (sourceAction != null && defaultProject != null
                && !actionSentenceImportService.startsWithProjectToken(dataSession, projectList, sentenceInput)) {
            Transaction trans = dataSession.beginTransaction();
            try {
                ActionNext followUp = actionCompletionService.createFollowUp(dataSession, webUser, sourceAction,
                        sentenceInput, null);
                trans.commit();
                return followUp;
            } catch (RuntimeException re) {
                if (trans.isActive()) {
                    trans.rollback();
                }
                throw re;
            }
        }
        ActionNext followUp = actionSentenceImportService.saveNewActionFromSentence(webUser, dataSession,
                defaultProject, projectList, sentenceInput);
        if (followUp != null && sourceAction != null) {
            Transaction trans = dataSession.beginTransaction();
            followUp.setFollowsActionNextId(Integer.valueOf(sourceAction.getActionNextId()));
            dataSession.update(followUp);
            trans.commit();
        }
        return followUp;
    }

    private ActionNext closeAction(AppReq appReq, ActionNext projectAction,
            String nextDescription, String outcomeNote, ProjectNextActionStatus nextActionStatus) {
        return actionCompletionService.closeAction(appReq, projectAction, nextDescription, outcomeNote,
                nextActionStatus,
                new Date(), 0);
    }

    private ActionNext selectNextActionForWorkFlow(WebUser webUser, Session dataSession,
            int excludeActionNextId) {
        List<ActionNext> orderedList = buildOrderedCycleList(webUser, dataSession);
        if (excludeActionNextId > 0) {
            orderedList.removeIf(pa -> pa.getActionNextId() == excludeActionNextId);
        }
        if (orderedList.isEmpty()) {
            return null;
        }
        return orderedList.get(0);
    }

    public List<ActionNext> buildOrderedCycleList(WebUser webUser, Session dataSession) {
        List<ActionNext> dueTodayList = getProjectActionListForToday(webUser, dataSession, 0);
        List<ActionNext> overdueList = getProjectActionListForToday(webUser, dataSession, -1);
        dueTodayList = filterActionsForDashboardVisibility(dueTodayList, false);
        overdueList = filterActionsForDashboardVisibility(overdueList, true);

        List<ActionNext> orderedList = new ArrayList<ActionNext>();
        orderedList.addAll(overdueList);
        orderedList.addAll(dueTodayList);
        orderedList.removeIf(this::isMeetingAction);
        if (!orderedList.isEmpty()) {
            sortProjectActionListByCompletionOrder(orderedList, webUser);
        }
        return orderedList;
    }

    public ActionNext selectNextAfterCurrentInList(WebUser webUser, Session dataSession,
            int currentActionId) {
        List<ActionNext> orderedList = buildOrderedCycleList(webUser, dataSession);
        for (int i = 0; i < orderedList.size(); i++) {
            if (orderedList.get(i).getActionNextId() != currentActionId) {
                continue;
            }
            if (i + 1 >= orderedList.size()) {
                return null;
            }
            return orderedList.get(i + 1);
        }
        return null;
    }

    private List<ActionNext> getProjectActionListForToday(WebUser webUser, Session dataSession, int dayOffset) {
        LocalDate today = webUser.getLocalDateToday();
        Integer workspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId());
        Query query;
        if (dayOffset < 0) {
            LocalDate oldestDate = today.minusYears(1);
            query = dataSession.createQuery(
                    "select distinct an from ActionNext an "
                            + "left join fetch an.project "
                            + "left join fetch an.contact "
                            + "left join fetch an.nextProjectContact "
                            + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                            + "and an.nextDescription <> '' "
                            + "and an.nextActionStatusString = :nextActionStatus "
                            + "and an.nextActionDate >= :oldestDate and an.nextActionDate < :cutoffDate "
                            + "order by an.nextActionDate, an.priorityLevel DESC, an.nextTimeEstimate, an.nextChangeDate");
            query.setParameter("oldestDate", java.sql.Date.valueOf(oldestDate));
            query.setParameter("cutoffDate", java.sql.Date.valueOf(today));
        } else {
            LocalDate targetDate = today.plusDays(dayOffset);
            query = dataSession.createQuery(
                    "select distinct an from ActionNext an "
                            + "left join fetch an.project "
                            + "left join fetch an.contact "
                            + "left join fetch an.nextProjectContact "
                            + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                            + "and an.nextDescription <> '' "
                            + "and an.nextActionStatusString = :nextActionStatus "
                            + "and an.nextActionDate = :targetDate "
                            + "order by an.nextActionDate, an.priorityLevel DESC, an.nextTimeEstimate, an.nextChangeDate");
            query.setParameter("targetDate", java.sql.Date.valueOf(targetDate));
        }
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("nextContactId", webUser.getContactId());
        query.setParameter("nextActionStatus", ProjectNextActionStatus.READY.getId());
        @SuppressWarnings("unchecked")
        List<ActionNext> projectActionList = query.list();
        sortProjectActionList(projectActionList, webUser);
        return projectActionList;
    }

    private List<ActionNext> getProjectActionListForTodayOrEarlier(WebUser webUser, Session dataSession) {
        LocalDate tomorrow = webUser.getLocalDateToday().plusDays(1);
        Query query = dataSession.createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "left join fetch an.contact "
                        + "left join fetch an.nextProjectContact "
                        + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                        + "and an.nextDescription <> '' "
                        + "and an.nextActionStatusString = :nextActionStatus "
                        + "and an.nextActionDate is not null and an.nextActionDate < :tomorrow ");
        query.setParameter("workspaceId",
                WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId()));
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("nextContactId", webUser.getContactId());
        query.setParameter("nextActionStatus", ProjectNextActionStatus.READY.getId());
        query.setParameter("tomorrow", java.sql.Date.valueOf(tomorrow));
        @SuppressWarnings("unchecked")
        List<ActionNext> projectActionList = query.list();
        return projectActionList;
    }

    private void rationalizeCompletionOrderForCurrentDate(WebUser webUser, Session dataSession) {
        List<ActionNext> candidateList = getProjectActionListForTodayOrEarlier(webUser, dataSession);
        if (candidateList.isEmpty()) {
            return;
        }

        boolean needsRationalization = false;
        for (ActionNext projectAction : candidateList) {
            if (projectAction.getCompletionOrder() <= 0) {
                needsRationalization = true;
                break;
            }
        }
        if (!needsRationalization) {
            return;
        }

        Map<Integer, List<ActionNext>> existingByBucket = new HashMap<Integer, List<ActionNext>>();
        Map<Integer, List<ActionNext>> newByBucket = new HashMap<Integer, List<ActionNext>>();
        for (ActionNext projectAction : candidateList) {
            int bucket = getCompletionBucket(projectAction, webUser);
            if (projectAction.getCompletionOrder() > 0) {
                existingByBucket.computeIfAbsent(bucket, k -> new ArrayList<ActionNext>()).add(projectAction);
            } else {
                newByBucket.computeIfAbsent(bucket, k -> new ArrayList<ActionNext>()).add(projectAction);
            }
        }

        List<ActionNext> orderedList = new ArrayList<ActionNext>();
        for (int bucket = BUCKET_START_OF_WORK_DAY; bucket <= BUCKET_OTHER; bucket++) {
            List<ActionNext> existing = existingByBucket.get(bucket);
            if (existing != null && !existing.isEmpty()) {
                existing.sort((pa1, pa2) -> pa1.getCompletionOrder() - pa2.getCompletionOrder());
                orderedList.addAll(existing);
            }
            List<ActionNext> pending = newByBucket.get(bucket);
            if (pending != null && !pending.isEmpty()) {
                pending.sort((pa1, pa2) -> compareInsideBucket(pa1, pa2));
                orderedList.addAll(pending);
            }
        }

        Transaction trans = dataSession.beginTransaction();
        int completionOrder = 1;
        for (ActionNext projectAction : orderedList) {
            if (projectAction.getCompletionOrder() != completionOrder) {
                projectAction.setCompletionOrder(completionOrder);
                projectAction.setNextChangeDate(new Date());
                dataSession.update(projectAction);
            }
            completionOrder++;
        }
        trans.commit();
    }

    private List<Project> loadProjectList(WebUser webUser, Session dataSession) {
        Query query = dataSession
                .createQuery(
                        "from Project where workspaceId = ? and (projectStatus is null or projectStatus = :activeStatus) order by projectName");
        query.setParameter(0, WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId()));
        query.setParameter("activeStatus", ProjectStatus.ACTIVE.getDatabaseValue());
        @SuppressWarnings("unchecked")
        List<Project> projectList = query.list();
        return projectList;
    }

    private List<ActionNext> filterActionsForDashboardVisibility(List<ActionNext> actions,
            boolean workOnly) {
        List<ActionNext> filtered = new ArrayList<ActionNext>();
        for (ActionNext action : actions) {
            if (action == null) {
                continue;
            }
            if (action.isBillable() || action.getTimeSlot() == TimeSlot.MORNING) {
                filtered.add(action);
            }
        }
        return filtered;
    }

    private boolean isMeetingAction(ActionNext action) {
        return action != null && ProjectNextActionType.WILL_MEET.equals(action.getNextActionType());
    }

    private static void sortProjectActionList(List<ActionNext> projectActionList, WebUser webUser) {
        Collections.sort(projectActionList, (pa1, pa2) -> {
            int c1 = pa1.getCompletionOrder();
            int c2 = pa2.getCompletionOrder();
            if (c1 > 0 && c2 <= 0) {
                return -1;
            }
            if (c2 > 0 && c1 <= 0) {
                return 1;
            }
            int bucket1 = getCompletionBucket(pa1, webUser);
            int bucket2 = getCompletionBucket(pa2, webUser);
            if (bucket1 != bucket2) {
                return bucket1 - bucket2;
            }
            return compareInsideBucket(pa1, pa2);
        });
    }

    private static void sortProjectActionListByCompletionOrder(List<ActionNext> projectActionList,
            WebUser webUser) {
        Collections.sort(projectActionList, (pa1, pa2) -> {
            int c1 = pa1.getCompletionOrder();
            int c2 = pa2.getCompletionOrder();
            int bucket1 = getCompletionBucket(pa1, webUser);
            int bucket2 = getCompletionBucket(pa2, webUser);
            if (bucket1 != bucket2) {
                return bucket1 - bucket2;
            }
            if (c1 > 0 && c2 <= 0) {
                return -1;
            }
            if (c2 > 0 && c1 <= 0) {
                return 1;
            }
            if (c1 > 0 && c2 > 0 && c1 != c2) {
                return c1 - c2;
            }
            return compareInsideBucket(pa1, pa2);
        });
    }

    private static int compareInsideBucket(ActionNext pa1, ActionNext pa2) {
        ProcessStage ps1 = pa1.getProcessStage();
        ProcessStage ps2 = pa2.getProcessStage();
        if ((ps1 != null || ps2 != null) && ps1 != ps2) {
            if (ps1 == ProcessStage.FIRST) {
                return -1;
            } else if (ps2 == ProcessStage.FIRST) {
                return 1;
            }
            if (ps1 == ProcessStage.SECOND) {
                return -1;
            } else if (ps2 == ProcessStage.SECOND) {
                return 1;
            }
            if (ps1 == ProcessStage.LAST) {
                return 1;
            } else if (ps2 == ProcessStage.LAST) {
                return -1;
            }
            if (ps1 == ProcessStage.PENULTIMATE) {
                return 1;
            } else if (ps2 == ProcessStage.PENULTIMATE) {
                return -1;
            }
        }

        int p1 = ProjectNextActionType.defaultPriority(pa1.getNextActionType());
        int p2 = ProjectNextActionType.defaultPriority(pa2.getNextActionType());
        if (p1 != p2) {
            return p2 - p1;
        }
        if (pa2.getPriorityLevel() != pa1.getPriorityLevel()) {
            return pa2.getPriorityLevel() - pa1.getPriorityLevel();
        }
        Date d1 = pa1.getNextChangeDate();
        Date d2 = pa2.getNextChangeDate();
        if (d1 != null && d2 != null) {
            int compare = d1.compareTo(d2);
            if (compare != 0) {
                return compare;
            }
        }
        return pa1.getActionNextId() - pa2.getActionNextId();
    }

    private static int getCompletionBucket(ActionNext projectAction, WebUser webUser) {
        if (projectAction == null) {
            return 99;
        }
        if (projectAction.isBillable()) {
            ProcessStage processStage = projectAction.getProcessStage();
            if (processStage == ProcessStage.FIRST || processStage == ProcessStage.SECOND) {
                return BUCKET_START_OF_WORK_DAY;
            }
            if (processStage == ProcessStage.PENULTIMATE || processStage == ProcessStage.LAST) {
                return BUCKET_END_OF_WORK_DAY;
            }
        }
        LocalDate actionDate = toStoredLocalDate(projectAction.getNextActionDate(), webUser);
        if (projectAction.isBillable() && actionDate != null && actionDate.isBefore(webUser.getLocalDateToday())) {
            return BUCKET_OVERDUE;
        }
        if (!projectAction.isBillable()) {
            if (projectAction.getTimeSlot() == TimeSlot.MORNING) {
                return BUCKET_PERSONAL_MORNING;
            }
            return 99;
        }
        String nextActionType = projectAction.getNextActionType();
        if (ProjectNextActionType.OVERDUE_TO.equals(nextActionType)) {
            return BUCKET_OVERDUE;
        }
        if (ProjectNextActionType.COMMITTED_TO.equals(nextActionType)) {
            return BUCKET_COMMITTED;
        }
        if (ProjectNextActionType.WILL.equals(nextActionType)
                || ProjectNextActionType.WILL_CONTACT.equals(nextActionType)
                || ProjectNextActionType.WILL_REVIEW.equals(nextActionType)
                || ProjectNextActionType.WILL_DOCUMENT.equals(nextActionType)
                || ProjectNextActionType.WILL_FOLLOW_UP.equals(nextActionType)) {
            return BUCKET_WILL;
        }
        if (ProjectNextActionType.MIGHT.equals(nextActionType)
                || ProjectNextActionType.GOAL.equals(nextActionType)) {
            return BUCKET_MIGHT;
        }
        if (ProjectNextActionType.WAITING.equals(nextActionType)) {
            return BUCKET_WAITING;
        }
        if (ProjectNextActionType.WILL_MEET.equals(nextActionType)) {
            return BUCKET_WILL_MEET;
        }
        return BUCKET_OTHER;
    }

    private static LocalDate toStoredLocalDate(Date date, WebUser webUser) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return webUser.toLocalDate(date);
    }

    private String n(String value) {
        if (value == null || value.trim().equals("")) {
            return "";
        }
        return value;
    }

    private String normalizeWorkStatus(String workStatus) {
        String value = n(workStatus).trim();
        if (value.length() == 0) {
            return "";
        }
        String upper = value.toUpperCase();
        if ("INPROGRESS".equals(upper) || "IN_PROGRESS".equals(upper)) {
            return WORK_STATUS_IN_PROGRESS;
        }
        if ("COMPLETE".equals(upper)) {
            return WORK_STATUS_COMPLETE;
        }
        if ("DELETE".equals(upper)) {
            return WORK_STATUS_DELETE;
        }
        if ("BLOCKED".equals(upper)) {
            return WORK_STATUS_BLOCKED;
        }
        return upper;
    }
}
