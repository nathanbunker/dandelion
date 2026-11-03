package org.dandeliondaily.dashboard.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.dashboard.model.DashboardTodayColumnModel;
import org.dandeliondaily.projectnarrative.model.ProjectNarrativeSummary;
import org.dandeliondaily.projectnarrative.service.ProjectNarrativeService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.TimeAdder;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.BillCodeDisplay;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

public class DashboardTodayColumnService {

    private final ActionSentenceImportService actionSentenceImportService = new ActionSentenceImportService();
    private final QuickCaptureLinkedProjectService quickCaptureLinkedProjectService = new QuickCaptureLinkedProjectService();
    private final QuickCaptureProjectTokenService projectTokenService = new QuickCaptureProjectTokenService();
    private final ProjectNarrativeService projectNarrativeService = new ProjectNarrativeService();
    private final ProjectDisplayLabelService projectDisplayLabelService = new ProjectDisplayLabelService();

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_SENTENCE_INPUT = "sentenceInput";
    private static final String ACTION_SCHEDULE = "Schedule";
    private static final String ACTION_SCHEDULE_AND_START = "Schedule and Start";
    public static final String ACTION_SCHEDULE_AND_EDIT = "Schedule and Edit";

    private static final int BUCKET_START_OF_WORK_DAY = DashboardActionOrdering.BUCKET_START_OF_WORK_DAY;
    private static final int BUCKET_OVERDUE = DashboardActionOrdering.BUCKET_OVERDUE;
    private static final int BUCKET_COMMITTED = DashboardActionOrdering.BUCKET_COMMITTED;
    private static final int BUCKET_WILL = DashboardActionOrdering.BUCKET_WILL;
    private static final int BUCKET_PERSONAL_MORNING = DashboardActionOrdering.BUCKET_PERSONAL_MORNING;
    private static final int BUCKET_MIGHT = DashboardActionOrdering.BUCKET_MIGHT;
    private static final int BUCKET_WAITING = DashboardActionOrdering.BUCKET_WAITING;
    private static final int BUCKET_WILL_MEET = DashboardActionOrdering.BUCKET_WILL_MEET;
    private static final int BUCKET_END_OF_WORK_DAY = DashboardActionOrdering.BUCKET_END_OF_WORK_DAY;
    private static final int BUCKET_OTHER = DashboardActionOrdering.BUCKET_OTHER;

    /**
     * Saves the Quick Capture sentence. "Schedule and Start" also makes the new action current;
     * "Schedule and Edit" leaves the current action alone so the page can open the editor on the
     * returned action. Returns the saved action (the first of a linked-project twin pair), or
     * null when nothing was saved.
     */
    public ActionNext handleQuickCapture(AppReq appReq) {
        String action = appReq.getRequest().getParameter(PARAM_ACTION);
        if (!ACTION_SCHEDULE.equals(action) && !ACTION_SCHEDULE_AND_START.equals(action)
                && !ACTION_SCHEDULE_AND_EDIT.equals(action)) {
            return null;
        }

        String sentenceInput = appReq.getRequest().getParameter(PARAM_SENTENCE_INPUT);
        if (sentenceInput == null || sentenceInput.trim().length() == 0) {
            appReq.addWarningMessage("Quick capture requires text before scheduling.");
            return null;
        }

        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();
        ActionNext selectedAction = appReq.getCompletingAction();
        Project selectedProject = selectedAction == null ? null : selectedAction.getProject();

        List<Project> projectList = loadProjectList(webUser, dataSession);
        List<ActionNext> savedActions = actionSentenceImportService.saveNewActionsFromSentence(webUser, dataSession,
                selectedProject, projectList, sentenceInput);
        ActionNext nextAction = savedActions.isEmpty() ? null : savedActions.get(0);
        if (nextAction == null) {
            appReq.addErrorMessage("Unable to create action from quick capture sentence.");
            return null;
        }

        if (ACTION_SCHEDULE_AND_START.equals(action)) {
            appReq.setCompletingAction(nextAction);
            appReq.setProject(nextAction.getProject());
        }
        if (savedActions.size() > 1) {
            appReq.addSuccessMessage("Saved quick capture actions across " + savedActions.size() + " linked projects.");
        } else {
            appReq.addSuccessMessage("Saved quick capture action.");
        }
        return nextAction;
    }

    /**
     * Today's pile of work for the Focused Action day bar: the estimated minutes still left on today's
     * and overdue actions (Committed, Will Meet and Will, as the dashboard Today totals count them),
     * and how many of those actions have no estimate and so add nothing.
     */
    public static class DayPile {
        private final int remainingMinutes;
        private final int unestimatedCount;

        public DayPile(int remainingMinutes, int unestimatedCount) {
            this.remainingMinutes = remainingMinutes;
            this.unestimatedCount = unestimatedCount;
        }

        public int getRemainingMinutes() {
            return remainingMinutes;
        }

        public int getUnestimatedCount() {
            return unestimatedCount;
        }
    }

    public DayPile loadDayPile(AppReq appReq) {
        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();
        List<ActionNext> todayAndOverdue = new ArrayList<ActionNext>();
        todayAndOverdue.addAll(filterActionsForDashboardVisibility(
                getProjectActionListForToday(webUser, dataSession, -1), true));
        todayAndOverdue.addAll(filterActionsForDashboardVisibility(
                getProjectActionListForToday(webUser, dataSession, 0), false));
        int unestimated = 0;
        for (ActionNext action : todayAndOverdue) {
            if (action.isBillable() && action.getNextTimeEstimate() == null && countsAsPlanned(action)) {
                unestimated++;
            }
        }
        return new DayPile(remainingPlannedMinutes(appReq, webUser, todayAndOverdue), unestimated);
    }

    /** The action types whose estimates make up the remaining planned total. */
    private static boolean countsAsPlanned(ActionNext action) {
        String type = action.getNextActionType();
        return ProjectNextActionType.COMMITTED_TO.equals(type) || ProjectNextActionType.OVERDUE_TO.equals(type)
                || ProjectNextActionType.WILL_MEET.equals(type) || ProjectNextActionType.WILL.equals(type)
                || ProjectNextActionType.WILL_CONTACT.equals(type) || ProjectNextActionType.WILL_DOCUMENT.equals(type)
                || ProjectNextActionType.WILL_FOLLOW_UP.equals(type) || ProjectNextActionType.WILL_REVIEW.equals(type);
    }

    /** Remaining estimated minutes on these actions; shared by the Today totals and the day bar. */
    private int remainingPlannedMinutes(AppReq appReq, WebUser webUser, List<ActionNext> todayAndOverdue) {
        TimeAdder timeAdderScheduled = new TimeAdder(todayAndOverdue, appReq, webUser.getToday());
        return timeAdderScheduled.getCommittedEst() + timeAdderScheduled.getWillEst()
                + timeAdderScheduled.getWillMeetEst();
    }

    public DashboardTodayColumnModel buildModel(AppReq appReq) {
        DashboardTodayColumnModel model = new DashboardTodayColumnModel();

        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();

        model.getQuickCapture().setSentenceValue("");
        String actionParam = appReq.getRequest().getParameter(PARAM_ACTION);
        if (ACTION_SCHEDULE.equals(actionParam)) {
            model.getQuickCapture().setFocusRequested(true);
        }
        List<Project> quickCaptureProjects = loadProjectList(webUser, dataSession);
        List<String> projectNames = listQuickCaptureProjectNames(quickCaptureProjects, dataSession);
        model.getQuickCapture().setProjectNames(projectNames);

        // Real data wiring starts here for the middle Today column.
        List<ActionNext> dueTodayList = getProjectActionListForToday(webUser, dataSession, 0);
        List<ActionNext> overdueList = getProjectActionListForToday(webUser, dataSession, -1);
        dueTodayList = filterActionsForDashboardVisibility(dueTodayList, false);
        overdueList = filterActionsForDashboardVisibility(overdueList, true);
        List<ActionNext> ideasList = getWouldLikeToIdeasList(webUser, dataSession);
        sortProjectActionListByCompletionOrder(dueTodayList, webUser);
        sortProjectActionListByCompletionOrder(overdueList, webUser);

        model.setActionGroups(buildTodayGroups(webUser, dataSession, dueTodayList, overdueList, ideasList));

        List<ActionNext> completedToday = getProjectActionListClosedToday(webUser, dataSession);
        model.setCompletedToday(toActionItems(webUser, dataSession, completedToday, "Completed"));
        model.setWorkdayReview(buildWorkdayReviewModel(webUser, dataSession, model.getActionGroups(), completedToday));

        List<ActionNext> todayAndOverdue = new ArrayList<ActionNext>();
        todayAndOverdue.addAll(overdueList);
        todayAndOverdue.addAll(dueTodayList);
        model.setTotals(buildTotals(appReq, webUser, todayAndOverdue));

        return model;
    }

    public List<String> listQuickCaptureProjectNames(AppReq appReq) {
        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();
        List<Project> quickCaptureProjects = loadProjectList(webUser, dataSession);
        return listQuickCaptureProjectNames(quickCaptureProjects, dataSession);
    }

    private List<String> listQuickCaptureProjectNames(List<Project> quickCaptureProjects, Session dataSession) {
        List<String> aliasNames = quickCaptureLinkedProjectService.listAliasLabels(dataSession, quickCaptureProjects);
        LinkedHashSet<String> orderedNames = buildOrderedQuickCaptureNames(quickCaptureProjects, aliasNames);
        return new ArrayList<String>(orderedNames);
    }

    LinkedHashSet<String> buildOrderedQuickCaptureNames(List<Project> quickCaptureProjects, List<String> aliasNames) {
        LinkedHashSet<String> orderedNames = new LinkedHashSet<String>();
        if (quickCaptureProjects != null) {
            for (Project project : quickCaptureProjects) {
                String projectToken = projectTokenService.getToken(project);
                if (projectToken.length() > 0) {
                    orderedNames.add(projectToken);
                }
            }
        }
        if (aliasNames != null) {
            for (String aliasName : aliasNames) {
                if (aliasName != null && aliasName.trim().length() > 0) {
                    orderedNames.add(aliasName.trim());
                }
            }
        }
        return orderedNames;
    }

    private DashboardTodayColumnModel.WorkdayReviewModel buildWorkdayReviewModel(WebUser webUser, Session dataSession,
            List<DashboardTodayColumnModel.TodayActionGroupModel> actionGroups,
            List<ActionNext> completedToday) {
        DashboardTodayColumnModel.WorkdayReviewModel model = new DashboardTodayColumnModel.WorkdayReviewModel();
        model.setTopPriority(isWorkdayComplete(actionGroups));

        if (completedToday == null || completedToday.isEmpty()) {
            model.setRenderSection(false);
            model.setAllReviewed(false);
            return model;
        }

        List<Integer> completedActionProjectIds = new ArrayList<Integer>();
        for (ActionNext action : completedToday) {
            if (action == null || action.getProject() == null || action.getProject().getProjectId() <= 0) {
                continue;
            }
            completedActionProjectIds.add(action.getProject().getProjectId());
        }

        List<ProjectNarrativeSummary> summaries = projectNarrativeService.listNarrativeSummariesForCompletedProjects(
                webUser, dataSession, webUser.getLocalDateToday(), completedActionProjectIds);

        List<DashboardTodayColumnModel.WorkdayReviewItemModel> reviewItems = new ArrayList<DashboardTodayColumnModel.WorkdayReviewItemModel>();
        boolean allReviewed = !summaries.isEmpty();
        for (ProjectNarrativeSummary summary : summaries) {
            // Filter using summed bill-entry minutes for the day (source of truth for
            // tracked time).
            if (summary.getMinutesSpent() < 5) {
                continue;
            }

            DashboardTodayColumnModel.WorkdayReviewItemModel item = new DashboardTodayColumnModel.WorkdayReviewItemModel();
            item.setProjectId(summary.getProjectId());
            item.setProjectName(summary.getProjectName());
            item.setCompletedCount(summary.getCompletedCount());
            item.setMinutesSpent(summary.getMinutesSpent());
            item.setMinutesDisplay(ActionNext.getTimeForDisplay(summary.getMinutesSpent()));
            item.setReviewed(summary.isReviewed());
            item.setNote(summary.getNarrativeEntry().getNote());
            item.setDecision(summary.getNarrativeEntry().getDecision());
            item.setInsight(summary.getNarrativeEntry().getInsight());
            item.setRisk(summary.getNarrativeEntry().getRisk());
            item.setOpportunity(summary.getNarrativeEntry().getOpportunity());

            // Add completed actions for this project
            List<String> projectCompletedActions = new ArrayList<>();
            for (ActionNext action : completedToday) {
                if (action != null && action.getProject() != null &&
                        action.getProject().getProjectId() == summary.getProjectId()) {
                    if (action.getNextDescription() != null && !action.getNextDescription().isEmpty()) {
                        projectCompletedActions.add(action.getNextDescription());
                    }
                }
            }
            item.setCompletedActions(projectCompletedActions);

            reviewItems.add(item);
            if (!summary.isReviewed()) {
                allReviewed = false;
            }
        }

        model.setProjectItems(reviewItems);
        model.setRenderSection(!reviewItems.isEmpty());
        model.setAllReviewed(allReviewed && !reviewItems.isEmpty());
        return model;
    }

    private boolean isWorkdayComplete(List<DashboardTodayColumnModel.TodayActionGroupModel> actionGroups) {
        if (actionGroups == null || actionGroups.isEmpty()) {
            return true;
        }

        return !hasItemsForSection(actionGroups, "Committed")
                && !hasItemsForSection(actionGroups, "Will Meet")
                && !hasItemsForSection(actionGroups, "Will")
                && !hasItemsForSection(actionGroups, "Start of Work Day");
    }

    private boolean hasItemsForSection(List<DashboardTodayColumnModel.TodayActionGroupModel> actionGroups,
            String title) {
        for (DashboardTodayColumnModel.TodayActionGroupModel group : actionGroups) {
            if (group == null || group.getTitle() == null) {
                continue;
            }
            if (title.equals(group.getTitle()) && group.getItems() != null && !group.getItems().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private DashboardTodayColumnModel.TodayTotalsModel buildTotals(AppReq appReq, WebUser webUser,
            List<ActionNext> todayAndOverdue) {
        DashboardTodayColumnModel.TodayTotalsModel totals = new DashboardTodayColumnModel.TodayTotalsModel();

        TimeAdder timeAdderToday = new TimeAdder(todayAndOverdue, appReq);
        TimeAdder timeAdderScheduled = new TimeAdder(todayAndOverdue, appReq, webUser.getToday());

        int completedAct = timeAdderToday.getCompletedAct();
        int committedEst = timeAdderScheduled.getCommittedEst();
        int willEst = timeAdderScheduled.getWillEst();
        int willMeetEst = timeAdderScheduled.getWillMeetEst();
        int remainingPlannedTotal = remainingPlannedMinutes(appReq, webUser, todayAndOverdue);
        int plannedTotal = completedAct + remainingPlannedTotal;

        totals.setCompletedDisplay(ActionNext.getTimeForDisplay(completedAct));
        totals.setCommittedDisplay(ActionNext.getTimeForDisplay(committedEst));
        totals.setWillDisplay(ActionNext.getTimeForDisplay(willEst));
        totals.setWillMeetDisplay(ActionNext.getTimeForDisplay(willMeetEst));
        totals.setTotalPlannedDisplay(ActionNext.getTimeForDisplay(plannedTotal));
        totals.setCompletedMinutes(completedAct);
        totals.setPlannedMinutes(plannedTotal);

        if (plannedTotal == 0) {
            totals.setGuidanceMessage("You have finished everything you said you would do today.");
            totals.setOverCommitted(false);
        } else if (completedAct > (8 * 60)) {
            totals.setGuidanceMessage("You have already logged more than a full workday.");
            totals.setOverCommitted(true);
        } else if (plannedTotal > (8 * 60)) {
            totals.setGuidanceMessage("You are over committed for today. Consider re-planning.");
            totals.setOverCommitted(true);
        } else if (completedAct < 30) {
            totals.setGuidanceMessage("Good morning. You are just getting started.");
            totals.setOverCommitted(false);
        } else {
            totals.setGuidanceMessage("You are on track for today.");
            totals.setOverCommitted(false);
        }
        return totals;
    }

    private List<DashboardTodayColumnModel.TodayActionGroupModel> buildTodayGroups(WebUser webUser,
            Session dataSession, List<ActionNext> dueTodayList, List<ActionNext> overdueList,
            List<ActionNext> ideasList) {
        Map<Integer, List<ActionNext>> bucketMap = new HashMap<Integer, List<ActionNext>>();
        for (int bucket = BUCKET_START_OF_WORK_DAY; bucket <= BUCKET_OTHER; bucket++) {
            bucketMap.put(bucket, new ArrayList<ActionNext>());
        }

        bucketMap.get(BUCKET_OVERDUE).addAll(overdueList);
        for (ActionNext projectAction : dueTodayList) {
            int bucket = getCompletionBucket(projectAction, webUser);
            if (bucket > BUCKET_OTHER) {
                continue;
            }
            bucketMap.get(bucket).add(projectAction);
        }

        for (int bucket = BUCKET_START_OF_WORK_DAY; bucket <= BUCKET_OTHER; bucket++) {
            sortProjectActionListByCompletionOrder(bucketMap.get(bucket), webUser);
        }

        List<DashboardTodayColumnModel.TodayActionGroupModel> groups = new ArrayList<DashboardTodayColumnModel.TodayActionGroupModel>();
        addGroup(groups, "Overdue", toActionItems(webUser, dataSession, bucketMap.get(BUCKET_OVERDUE), "Overdue"));
        addGroup(groups, "Start of Work Day",
                toActionItems(webUser, dataSession, bucketMap.get(BUCKET_START_OF_WORK_DAY), "Start of Work Day"));
        addGroup(groups, "Committed",
                toActionItems(webUser, dataSession, bucketMap.get(BUCKET_COMMITTED), "Committed"));
        addGroup(groups, "Will", toActionItems(webUser, dataSession, bucketMap.get(BUCKET_WILL), "Will"));
        addGroup(groups, "Personal (Morning)",
                toActionItems(webUser, dataSession, bucketMap.get(BUCKET_PERSONAL_MORNING),
                        TimeSlot.MORNING.getLabel()));
        addGroup(groups, "Might", toActionItems(webUser, dataSession, bucketMap.get(BUCKET_MIGHT), "Might"));
        addGroup(groups, "Waiting", toActionItems(webUser, dataSession, bucketMap.get(BUCKET_WAITING), "Waiting"));
        addGroup(groups, "Will Meet",
                toActionItems(webUser, dataSession, bucketMap.get(BUCKET_WILL_MEET), "Will Meet"));
        addGroup(groups, "End of Work Day",
                toActionItems(webUser, dataSession, bucketMap.get(BUCKET_END_OF_WORK_DAY), "End of Work Day"));
        addGroup(groups, "Ideas", toActionItems(webUser, dataSession, ideasList, "Ideas"));
        addGroup(groups, "Other", toActionItems(webUser, dataSession, bucketMap.get(BUCKET_OTHER), "Other"));
        return groups;
    }

    private List<ActionNext> getWouldLikeToIdeasList(WebUser webUser, Session dataSession) {
        Integer workspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId());
        Query query = dataSession.createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "left join fetch an.contact "
                        + "left join fetch an.nextProjectContact "
                        + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                        + "and an.nextDescription <> '' "
                        + "and an.nextActionStatusString = :nextActionStatus "
                        + "and an.nextActionType = :nextActionType "
                        + "and an.billable = :billable "
                        + "order by an.actionNextId DESC");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("nextContactId", webUser.getContactId());
        query.setParameter("nextActionStatus", ProjectNextActionStatus.READY.getId());
        query.setParameter("nextActionType", ProjectNextActionType.WOULD_LIKE_TO);
        query.setParameter("billable", true);
        @SuppressWarnings("unchecked")
        List<ActionNext> projectActionList = query.list();
        return projectActionList;
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

    private void addGroup(List<DashboardTodayColumnModel.TodayActionGroupModel> groups, String title,
            List<DashboardTodayColumnModel.TodayActionItemModel> items) {
        if (items.isEmpty()) {
            return;
        }
        groups.add(new DashboardTodayColumnModel.TodayActionGroupModel(title, items));
    }

    private List<DashboardTodayColumnModel.TodayActionItemModel> toActionItems(WebUser webUser,
            Session dataSession, List<ActionNext> actions, String contextLabel) {
        List<DashboardTodayColumnModel.TodayActionItemModel> items = new ArrayList<DashboardTodayColumnModel.TodayActionItemModel>();
        Integer privateWorkspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId());
        Map<Integer, String> displayNameByActionId = projectDisplayLabelService.buildActionDisplayNameMap(dataSession,
                actions,
                privateWorkspaceId);
        for (ActionNext action : actions) {
            DashboardTodayColumnModel.TodayActionItemModel item = new DashboardTodayColumnModel.TodayActionItemModel();
            item.setProjectName(action.getProject() == null ? ""
                    : resolveProjectDisplayName(action, displayNameByActionId));
            item.setDescriptionText(n(action.getNextDescription(), ""));
            item.setDescriptionHtml(action.getNextDescriptionForDisplay(webUser.getProjectContact()));
            item.setActionNextId(action.getActionNextId());
            item.setEstimateDisplay(displayTime(action.getNextTimeEstimateForDisplay()));
            item.setActualDisplay(displayTime(action.getNextTimeActualForDisplay()));
            item.setEstimateMinutes(action.getNextTimeEstimate() == null ? 0 : action.getNextTimeEstimate());
            item.setActualMinutes(action.getNextTimeActual() == null ? 0 : action.getNextTimeActual());
            applyBillCodeDisplay(item, action, dataSession);
            if (action.getTimeSlot() != null && action.getTimeSlot().getLabel() != null
                    && action.getTimeSlot().getLabel().trim().length() > 0) {
                item.setContextLabel(action.getTimeSlot().getLabel());
            } else {
                item.setContextLabel(contextLabel);
            }
            item.setStatusLabel(resolveStatusLabel(action));
            items.add(item);
        }
        return items;
    }

    private void applyBillCodeDisplay(DashboardTodayColumnModel.TodayActionItemModel item, ActionNext action,
            Session dataSession) {
        BillCode billCode = resolveBillCode(dataSession, action == null ? null : action.getProject());
        String displayColor = billCode == null ? null
                : BillCodeDisplay.normalizeDisplayColorOrNull(billCode.getDisplayColor());
        if (displayColor == null) {
            return;
        }
        item.setDisplayColor(displayColor);
        item.setDisplayDotLabel(BillCodeDisplay.getDisplayDotLabel(billCode));
    }

    private BillCode resolveBillCode(Session dataSession, Project project) {
        if (dataSession == null || project == null || project.getWorkspaceId() == null
                || project.getBillCode() == null || project.getBillCode().trim().length() == 0) {
            return null;
        }
        Query query = dataSession.createQuery(
                "from BillCode where workspaceId = :workspaceId and id.billCode = :billCode");
        query.setParameter("workspaceId", project.getWorkspaceId());
        query.setParameter("billCode", project.getBillCode());
        @SuppressWarnings("unchecked")
        List<BillCode> billCodes = query.list();
        return billCodes.isEmpty() ? null : billCodes.get(0);
    }

    private String resolveProjectDisplayName(ActionNext action, Map<Integer, String> displayNameByActionId) {
        if (action == null || action.getProject() == null) {
            return "";
        }
        String displayName = displayNameByActionId == null ? null : displayNameByActionId.get(action.getActionNextId());
        return n(displayName != null ? displayName : action.getProject().getProjectName(), "");
    }

    private List<ActionNext> getProjectActionListForToday(WebUser webUser, Session dataSession, int dayOffset) {
        Integer workspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId());
        LocalDate today = webUser.getLocalDateToday();
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

    private List<ActionNext> getProjectActionListClosedToday(WebUser webUser, Session dataSession) {
        Integer workspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId());
        Date today = TimeTracker.createToday(webUser).getTime();
        Date tomorrow = TimeTracker.createTomorrow(webUser).getTime();
        Query query = dataSession.createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "left join fetch an.contact "
                        + "left join fetch an.nextProjectContact "
                        + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                        + "and an.nextActionStatusString = :nextActionStatus and an.nextDescription <> '' "
                        + "and an.nextChangeDate >= :today and an.nextChangeDate < :tomorrow "
                        + "order by an.nextTimeActual DESC, an.nextTimeEstimate DESC");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("nextContactId", webUser.getContactId());
        query.setParameter("nextActionStatus", ProjectNextActionStatus.COMPLETED.getId());
        query.setParameter("today", today);
        query.setParameter("tomorrow", tomorrow);
        @SuppressWarnings("unchecked")
        List<ActionNext> projectActionList = query.list();
        return projectActionList;
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

    private String resolveStatusLabel(ActionNext projectAction) {
        if (projectAction.getNextActionStatus() != null) {
            return projectAction.getNextActionStatus().getLabel();
        }
        return "Unknown";
    }

    private String displayTime(String value) {
        return n(value, "-");
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
        DashboardActionOrdering.sortByBucketThenCompletionOrder(projectActionList, webUser);
    }

    private static int compareInsideBucket(ActionNext pa1, ActionNext pa2) {
        return DashboardActionOrdering.compareInsideBucket(pa1, pa2);
    }

    private static int getCompletionBucket(ActionNext projectAction, WebUser webUser) {
        return DashboardActionOrdering.getCompletionBucket(projectAction, webUser);
    }

    private String n(String value, String defaultValue) {
        if (value == null || value.trim().equals("")) {
            return defaultValue;
        }
        return value;
    }
}
