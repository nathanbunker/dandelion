package org.dandeliondaily.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.dandeliondaily.dashboard.service.DashboardCurrentActionService;
import org.dandeliondaily.dashboard.service.DashboardTodayColumnService;
import org.dandeliondaily.focus.render.FocusActionPickerRenderer;
import org.dandeliondaily.focus.render.FocusedActionPageRenderer;
import org.dandeliondaily.focus.service.FocusMeetingService;
import org.dandeliondaily.focus.service.FocusProjectActionsService;
import org.dandeliondaily.focus.service.FocusSwitchSlotService;
import org.dandeliondaily.planahead.service.PlanAheadDayCapacityService;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.ActionThreadService;
import org.hibernate.Query;
import org.openimmunizationsoftware.pt.util.WebEscaper;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.TimeEntry;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

public class FocusedActionServlet extends ClientServlet {

    private static final long serialVersionUID = -1832669295224425277L;
    private static final String ACTION_WORK_NEXT = "WorkNext";
    private static final String ACTION_COMPLETE = "COMPLETE";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_WORK_STATUS = "workStatus";
    private static final String ACTION_SCHEDULE = "Schedule";
    private static final String ACTION_SCHEDULE_AND_START = "Schedule and Start";
    private static final String PARAM_COMPLETING_ACTION_NEXT_ID = "completingActionNextId";
    private static final DateTimeFormatter SHORT_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE M/d");
    private static final DateTimeFormatter MEETING_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm");
    private static final String SESSION_PRE_MEETING_ACTION_ID = "FOCUS_PRE_MEETING_ACTION_ID";
    private static final String SESSION_MEETING_ACTIVE = "FOCUS_MEETING_ACTIVE";
    private static final String SESSION_RECENT_FOCUSED_ACTION_IDS = "FOCUS_RECENT_ACTION_IDS";
    private static final String SESSION_SWITCH_SLOT_IDS = "FOCUS_SWITCH_SLOT_IDS";

    private final DashboardCurrentActionService dashboardCurrentActionService = new DashboardCurrentActionService();
    private final DashboardTodayColumnService dashboardTodayColumnService = new DashboardTodayColumnService();
    private final FocusedActionPageRenderer focusedActionPageRenderer = new FocusedActionPageRenderer();
    private final PlanAheadDayCapacityService dayCapacityService = new PlanAheadDayCapacityService();
    private final FocusSwitchSlotService switchSlotService = new FocusSwitchSlotService();
    private final FocusMeetingService focusMeetingService = new FocusMeetingService();
    private final FocusProjectActionsService focusProjectActionsService = new FocusProjectActionsService();
    private final ActionNotesService actionNotesService = new ActionNotesService();
    private final ActionThreadService actionThreadService = new ActionThreadService();

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            if (appReq.isLoggedOut()) {
                forwardToHome(request, response);
                return;
            }

            String action = request.getParameter(PARAM_ACTION);
            if ("addCurrentActionNote".equals(action)) {
                handleAddCurrentActionNote(appReq);
                return;
            }
            if ("saveCurrentActionNotes".equals(action)) {
                handleSaveCurrentActionNotes(appReq);
                return;
            }
            if ("saveMeetingChime".equals(action)) {
                handleSaveMeetingChime(appReq);
                return;
            }
            if ("refreshFocusClock".equals(action)) {
                handleRefreshFocusClock(appReq);
                return;
            }
            if ("toggleTimer".equals(action)) {
                handleToggleTimer(appReq);
                return;
            }
            if ("selectMeetingAction".equals(action)) {
                handleSelectMeetingAction(appReq);
                return;
            }
            if ("switchAction".equals(action)) {
                handleSwitchAction(appReq);
                return;
            }

            FocusedActionPageRenderer.Layout layout = resolveLayout(appReq);
            selectActionFromRequest(appReq);
            ActionNext capturedAction = dashboardTodayColumnService.handleQuickCapture(appReq);
            int editorOpenActionNextId = capturedAction != null
                    && DashboardTodayColumnService.ACTION_SCHEDULE_AND_EDIT.equals(action)
                            ? capturedAction.getActionNextId()
                            : 0;
            dashboardCurrentActionService.ensureCurrentActionSelected(appReq);

            ActionNext workedAction = appReq.getCompletingAction();
            dashboardCurrentActionService.handleCurrentActionWork(appReq);
            maybeRestorePreMeetingActionAfterCompletion(appReq, action, workedAction);
            dashboardCurrentActionService.ensureCurrentActionSelected(appReq);

            // After completing an action, if no current action is selected, redirect to
            // dashboard
            ActionNext currentAction = reloadCurrentAction(appReq);
            List<Integer> switchSlotIds = trackFocusedActionSelection(appReq, currentAction);
            if (currentAction == null && ACTION_WORK_NEXT.equals(action)
                    && ACTION_COMPLETE.equalsIgnoreCase(n(appReq.getRequest().getParameter(PARAM_WORK_STATUS)))) {
                appReq.getResponse().sendRedirect("FocusedActionServlet");
                return;
            }
            int spentMinutes = loadCurrentActionMinutesToday(appReq, currentAction);
            int todayBillableMinutes = loadBillableMinutesToday(appReq);
            int spentMinutesThisWeek = loadBillableMinutesThisWeekRounded(appReq);
            int todayTargetMinutes = dayCapacityService.loadTargetMinutesForDay(appReq,
                    appReq.getWebUser().toDate(appReq.getWebUser().getLocalDateToday()));
            int weekTargetMinutes = dayCapacityService.loadTargetMinutesForCurrentWeek(appReq);
            DashboardTodayColumnService.DayPile dayPile = dashboardTodayColumnService.loadDayPile(appReq);
            int estimateMinutes = currentAction != null && currentAction.getNextTimeEstimate() != null
                    ? currentAction.getNextTimeEstimate().intValue()
                    : 0;

            String notes = actionNotesService.read(currentAction);
            FocusedActionPageRenderer.MeetingsData meetingsData = buildMeetingsData(appReq, currentAction);
            List<FocusedActionPageRenderer.SwitchSlot> switchSlots = loadSwitchSlots(appReq, switchSlotIds,
                    currentAction);
            boolean runningClock = appReq.getTimeTracker() != null && appReq.getTimeTracker().isRunningClock();
            int nowMinute = appReq.getWebUser().getLocalDateTimeNow().getMinute();
            FocusedActionPageRenderer.UpNextData upNextData = buildUpNextData(appReq, currentAction);
            FocusedActionPageRenderer.ProjectData projectData = buildProjectData(appReq, currentAction);
            List<String> quickCaptureProjectNames = dashboardTodayColumnService.listQuickCaptureProjectNames(appReq);
            boolean quickCaptureFocusRequested = ACTION_SCHEDULE.equals(action);
            String quickCaptureSentenceValue = (ACTION_SCHEDULE.equals(action)
                    || ACTION_SCHEDULE_AND_START.equals(action)
                    || DashboardTodayColumnService.ACTION_SCHEDULE_AND_EDIT.equals(action))
                            ? ""
                            : n(appReq.getRequest().getParameter("sentenceInput"));

            appReq.setTitle("Focused Action");
            printFocusedHead(appReq);
            focusedActionPageRenderer.render(appReq, layout, currentAction, notes, meetingsData, switchSlots,
                    spentMinutes,
                    estimateMinutes, runningClock, nowMinute, spentMinutesThisWeek, todayBillableMinutes,
                    todayTargetMinutes, weekTargetMinutes,
                    dayPile.getRemainingMinutes(), dayPile.getUnestimatedCount(),
                    upNextData, projectData,
                    quickCaptureSentenceValue, quickCaptureProjectNames,
                    quickCaptureFocusRequested, editorOpenActionNextId,
                    actionThreadService.history(appReq.getDataSession(), currentAction,
                            ActionThreadService.DEFAULT_LIMIT));
            printFocusedFoot(appReq);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            appReq.close();
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    private void selectActionFromRequest(AppReq appReq) {
        String actionIdString = appReq.getRequest().getParameter(PARAM_COMPLETING_ACTION_NEXT_ID);
        if (actionIdString == null || actionIdString.trim().length() == 0) {
            return;
        }
        try {
            int actionId = Integer.parseInt(actionIdString.trim());
            ActionNext selected = (ActionNext) appReq.getDataSession().get(ActionNext.class,
                    actionId);
            if (selected != null) {
                appReq.setCompletingAction(selected);
                if (selected.getProject() != null) {
                    appReq.setProject(selected.getProject());
                }
            }
        } catch (NumberFormatException nfe) {
            // ignore invalid action id
        }
    }

    private void maybeRestorePreMeetingActionAfterCompletion(AppReq appReq, String action,
            ActionNext workedAction) {
        String workStatus = n(appReq.getRequest().getParameter(PARAM_WORK_STATUS));
        if (!ACTION_WORK_NEXT.equals(action) || !ACTION_COMPLETE.equalsIgnoreCase(workStatus)) {
            return;
        }

        if (workedAction != null && !ProjectNextActionType.WILL_MEET.equals(workedAction.getNextActionType())) {
            clearMeetingMemory(appReq);
            return;
        }

        if (workedAction == null || !ProjectNextActionType.WILL_MEET.equals(workedAction.getNextActionType())) {
            return;
        }

        HttpSession session = appReq.getRequest().getSession(false);
        if (session == null) {
            return;
        }
        Object meetingActive = session.getAttribute(SESSION_MEETING_ACTIVE);
        Object preActionIdObj = session.getAttribute(SESSION_PRE_MEETING_ACTION_ID);
        session.removeAttribute(SESSION_MEETING_ACTIVE);
        session.removeAttribute(SESSION_PRE_MEETING_ACTION_ID);
        if (!(meetingActive instanceof Boolean) || !Boolean.TRUE.equals(meetingActive)
                || !(preActionIdObj instanceof Integer)) {
            return;
        }

        Integer preActionId = (Integer) preActionIdObj;
        ActionNext preAction = (ActionNext) appReq.getDataSession().get(ActionNext.class,
                preActionId);
        if (preAction == null || preAction.getNextActionStatus() != ProjectNextActionStatus.READY) {
            return;
        }
        appReq.setCompletingAction(preAction);
        if (preAction.getProject() != null) {
            appReq.setProject(preAction.getProject());
        }
    }

    private void handleSelectMeetingAction(AppReq appReq) throws Exception {
        String meetingActionNextId = appReq.getRequest().getParameter("meetingActionNextId");
        if (meetingActionNextId == null || meetingActionNextId.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Meeting action is required", null);
            return;
        }
        int actionNextId;
        try {
            actionNextId = Integer.parseInt(meetingActionNextId.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid meeting action", null);
            return;
        }

        Session dataSession = appReq.getDataSession();
        ActionNext selectedMeeting = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
        if (selectedMeeting == null) {
            sendJsonResponse(appReq, false, "Meeting action not found", null);
            return;
        }

        ActionNext currentAction = appReq.getCompletingAction();
        HttpSession session = appReq.getRequest().getSession(true);
        Integer existingPreMeeting = session.getAttribute(SESSION_PRE_MEETING_ACTION_ID) instanceof Integer
                ? (Integer) session.getAttribute(SESSION_PRE_MEETING_ACTION_ID)
                : null;
        if (existingPreMeeting == null
                && currentAction != null
                && currentAction.getActionNextId() != selectedMeeting.getActionNextId()
                && !ProjectNextActionType.WILL_MEET.equals(currentAction.getNextActionType())
                && currentAction.getNextActionStatus() == ProjectNextActionStatus.READY) {
            session.setAttribute(SESSION_PRE_MEETING_ACTION_ID, Integer.valueOf(currentAction.getActionNextId()));
        }
        session.setAttribute(SESSION_MEETING_ACTIVE, Boolean.TRUE);

        appReq.setCompletingAction(selectedMeeting);
        if (selectedMeeting.getProject() != null) {
            appReq.setProject(selectedMeeting.getProject());
        }

        // Switch timer context to the selected meeting action and start timing it.
        TimeTracker timeTracker = appReq.getTimeTracker();
        if (timeTracker != null && selectedMeeting.getProject() != null) {
            if (timeTracker.isRunningClock()) {
                timeTracker.stopClock(appReq.getDataSession());
            }
            timeTracker.startClock(selectedMeeting.getProject(), selectedMeeting, appReq.getDataSession());
        }
        sendJsonResponse(appReq, true, "Meeting selected", null);
    }

    /**
     * Makes the requested action current and, if the clock is running, moves the clock to it so
     * time follows the switch. Redirects so a refresh or the back button does not repeat it.
     */
    private void handleSwitchAction(AppReq appReq) throws Exception {
        selectActionFromRequest(appReq);
        ActionNext selected = appReq.getCompletingAction();
        TimeTracker timeTracker = appReq.getTimeTracker();
        if (selected != null && selected.getProject() != null && timeTracker != null
                && timeTracker.isRunningClock()) {
            timeTracker.startClock(selected.getProject(), selected, appReq.getDataSession());
        }
        appReq.getResponse().sendRedirect(selected == null ? "FocusedActionServlet"
                : "FocusedActionServlet?completingActionNextId=" + selected.getActionNextId());
    }

    private void clearMeetingMemory(AppReq appReq) {
        HttpSession session = appReq.getRequest().getSession(false);
        if (session == null) {
            return;
        }
        session.removeAttribute(SESSION_MEETING_ACTIVE);
        session.removeAttribute(SESSION_PRE_MEETING_ACTION_ID);
    }

    /**
     * Today's queue for the Up next row and drawer. Uses buildOrderedCycleList, the same list the
     * automatic next-action selection uses. Overdue meetings are added to the Overdue group as
     * display-only rows; they are not part of the queue or its count.
     */
    /** Most Up next items sent for the panel; the page shows as many as fit. */
    private static final int UP_NEXT_PANEL_LIMIT = 12;

    private FocusedActionPageRenderer.UpNextData buildUpNextData(AppReq appReq, ActionNext currentAction) {
        Session dataSession = appReq.getDataSession();
        WebUser webUser = appReq.getWebUser();
        LocalDate today = webUser.getLocalDateToday();
        List<ActionNext> orderedList = dashboardCurrentActionService.buildOrderedCycleList(webUser, dataSession);
        List<ActionNext> overdueMeetings = loadOverdueMeetings(appReq);

        List<Integer> actionIds = new ArrayList<Integer>();
        for (ActionNext queued : orderedList) {
            actionIds.add(Integer.valueOf(queued.getActionNextId()));
        }
        for (ActionNext meeting : overdueMeetings) {
            actionIds.add(Integer.valueOf(meeting.getActionNextId()));
        }
        Map<Integer, Integer> minutesToday = loadMinutesTodayByAction(appReq, actionIds);

        int currentId = currentAction == null ? 0 : currentAction.getActionNextId();
        int currentIndex = -1;
        List<FocusActionPickerRenderer.Row> queueRows = new ArrayList<FocusActionPickerRenderer.Row>();
        List<FocusActionPickerRenderer.Row> overdueRows = new ArrayList<FocusActionPickerRenderer.Row>();
        List<FocusActionPickerRenderer.Row> todayRows = new ArrayList<FocusActionPickerRenderer.Row>();
        for (ActionNext queued : orderedList) {
            boolean current = queued.getActionNextId() == currentId;
            if (current) {
                currentIndex = queueRows.size();
            }
            LocalDate date = toLocalDate(queued.getNextActionDate(), webUser);
            boolean overdue = date != null && date.isBefore(today);
            FocusActionPickerRenderer.Row row = buildPickerRow(dataSession, queued, minutesToday, current, false,
                    overdue ? formatShortDate(date) : "");
            queueRows.add(row);
            (overdue ? overdueRows : todayRows).add(row);
        }
        for (ActionNext meeting : overdueMeetings) {
            overdueRows.add(buildPickerRow(dataSession, meeting, minutesToday, meeting.getActionNextId() == currentId,
                    true, formatShortDate(toLocalDate(meeting.getNextActionDate(), webUser))));
        }

        List<FocusActionPickerRenderer.Group> groups = new ArrayList<FocusActionPickerRenderer.Group>();
        groups.add(new FocusActionPickerRenderer.Group("Overdue", overdueRows));
        groups.add(new FocusActionPickerRenderer.Group("Today", todayRows));

        // Matches selectNextAfterCurrentInList: the item after the current action, none if it is
        // last, and the first item when the current action is not in the queue.
        int nextIndex = currentIndex < 0 ? 0 : currentIndex + 1;
        FocusActionPickerRenderer.Row nextItem = nextIndex < queueRows.size() ? queueRows.get(nextIndex) : null;
        List<FocusActionPickerRenderer.Row> upcoming = nextIndex < queueRows.size()
                ? new ArrayList<FocusActionPickerRenderer.Row>(
                        queueRows.subList(nextIndex, Math.min(queueRows.size(), nextIndex + UP_NEXT_PANEL_LIMIT)))
                : new ArrayList<FocusActionPickerRenderer.Row>();
        List<FocusActionPickerRenderer.Row> remaining = new ArrayList<FocusActionPickerRenderer.Row>();
        int start = Math.max(0, currentIndex);
        remaining.addAll(queueRows.subList(start, queueRows.size()));
        remaining.addAll(queueRows.subList(0, start));
        return new FocusedActionPageRenderer.UpNextData(groups, nextItem, nextIndex + 1, queueRows.size(),
                currentIndex >= 0, upcoming).withRemaining(remaining);
    }

    private FocusedActionPageRenderer.ProjectData buildProjectData(AppReq appReq, ActionNext currentAction) {
        if (currentAction == null || currentAction.getProject() == null) {
            return new FocusedActionPageRenderer.ProjectData("", null, 0, 0, 0, 0);
        }
        Session dataSession = appReq.getDataSession();
        WebUser webUser = appReq.getWebUser();
        FocusProjectActionsService.ProjectActions actions = focusProjectActionsService.load(dataSession, webUser,
                appReq.getActiveWorkspaceId(), currentAction.getProject().getProjectId());

        List<Integer> actionIds = new ArrayList<Integer>();
        for (List<ActionNext> list : Arrays.asList(actions.getOverdue(), actions.getToday(), actions.getTomorrow(),
                actions.getLater())) {
            for (ActionNext action : list) {
                actionIds.add(Integer.valueOf(action.getActionNextId()));
            }
        }
        Map<Integer, Integer> minutesToday = loadMinutesTodayByAction(appReq, actionIds);
        int currentId = currentAction.getActionNextId();

        List<FocusActionPickerRenderer.Group> groups = new ArrayList<FocusActionPickerRenderer.Group>();
        groups.add(new FocusActionPickerRenderer.Group("Overdue",
                buildProjectRows(dataSession, webUser, actions.getOverdue(), minutesToday, currentId, true)));
        groups.add(new FocusActionPickerRenderer.Group("Today",
                buildProjectRows(dataSession, webUser, actions.getToday(), minutesToday, currentId, false)));
        groups.add(new FocusActionPickerRenderer.Group("Tomorrow",
                buildProjectRows(dataSession, webUser, actions.getTomorrow(), minutesToday, currentId, false)));
        groups.add(new FocusActionPickerRenderer.Group("Later",
                buildProjectRows(dataSession, webUser, actions.getLater(), minutesToday, currentId, true)));
        groups.add(new FocusActionPickerRenderer.Group("Done today",
                buildProjectRows(dataSession, webUser, actions.getDoneToday(), minutesToday, currentId, false), true));
        return new FocusedActionPageRenderer.ProjectData(getActionProjectDisplayName(dataSession, currentAction),
                groups, actions.getOverdue().size(), actions.getToday().size(), actions.getTomorrow().size(),
                actions.getLater().size());
    }

    private List<FocusActionPickerRenderer.Row> buildProjectRows(Session dataSession, WebUser webUser,
            List<ActionNext> actions, Map<Integer, Integer> minutesToday, int currentId, boolean showDate) {
        List<FocusActionPickerRenderer.Row> rows = new ArrayList<FocusActionPickerRenderer.Row>();
        for (ActionNext action : actions) {
            boolean meeting = ProjectNextActionType.WILL_MEET.equals(action.getNextActionType());
            rows.add(buildPickerRow(dataSession, action, minutesToday, action.getActionNextId() == currentId, meeting,
                    showDate ? formatShortDate(toLocalDate(action.getNextActionDate(), webUser)) : ""));
        }
        return rows;
    }

    private FocusActionPickerRenderer.Row buildPickerRow(Session dataSession, ActionNext action,
            Map<Integer, Integer> minutesToday, boolean current, boolean meeting, String whenLabel) {
        String description = n(action.getNextDescription());
        if (description.length() == 0) {
            description = "[No description]";
        }
        Integer minutes = minutesToday.get(Integer.valueOf(action.getActionNextId()));
        return new FocusActionPickerRenderer.Row(action.getActionNextId(),
                getActionProjectDisplayName(dataSession, action), description, action.getNextTimeEstimate(),
                minutes == null ? 0 : minutes.intValue(), whenLabel, current, meeting)
                .withRecurring(FocusProjectActionsService.isRecurring(action))
                .withProjectIcon(projectIcon(action))
                .withActionType(action.getNextActionType());
    }

    private static String projectIcon(ActionNext action) {
        String icon = action.getProject() == null ? null : action.getProject().getProjectIcon();
        return icon == null ? "" : icon.trim();
    }

    /** READY meetings dated before today (within a year, like the overdue action query). */
    private List<ActionNext> loadOverdueMeetings(AppReq appReq) {
        WebUser webUser = appReq.getWebUser();
        LocalDate today = webUser.getLocalDateToday();
        Query query = appReq.getDataSession().createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                        + "and an.nextActionStatusString = :nextActionStatus "
                        + "and an.nextActionDate >= :oldestDate and an.nextActionDate < :today "
                        + "and an.nextActionType = :meetingType "
                        + "order by an.nextActionDate, an.completionOrder, an.nextChangeDate");
        query.setParameter("workspaceId", appReq.getActiveWorkspaceId());
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("nextContactId", webUser.getContactId());
        query.setParameter("nextActionStatus", ProjectNextActionStatus.READY.getId());
        query.setParameter("oldestDate", java.sql.Date.valueOf(today.minusYears(1)));
        query.setParameter("today", java.sql.Date.valueOf(today));
        query.setParameter("meetingType", ProjectNextActionType.WILL_MEET);
        @SuppressWarnings("unchecked")
        List<ActionNext> meetings = query.list();
        return meetings;
    }

    private LocalDate toLocalDate(Date date, WebUser webUser) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return date.toInstant().atZone(webUser.getZoneId()).toLocalDate();
    }

    private String formatShortDate(LocalDate date) {
        return date == null ? "" : date.format(SHORT_DATE_FORMAT);
    }

    private void handleRefreshFocusClock(AppReq appReq) throws Exception {
        selectActionFromRequest(appReq);
        ActionNext currentAction = reloadCurrentAction(appReq);
        TimeTracker timeTracker = appReq.getTimeTracker();

        if (currentAction != null && timeTracker != null && timeTracker.isRunningClock()) {
            timeTracker.update(currentAction, appReq.getDataSession());
        }

        int spentMinutes = loadCurrentActionMinutesToday(appReq, currentAction);
        int todayBillableMinutes = loadBillableMinutesToday(appReq);
        int spentMinutesThisWeek = loadBillableMinutesThisWeekRounded(appReq);
        int todayTargetMinutes = dayCapacityService.loadTargetMinutesForDay(appReq,
                appReq.getWebUser().toDate(appReq.getWebUser().getLocalDateToday()));
        int weekTargetMinutes = dayCapacityService.loadTargetMinutesForCurrentWeek(appReq);
        int estimateMinutes = currentAction != null && currentAction.getNextTimeEstimate() != null
                ? currentAction.getNextTimeEstimate().intValue()
                : 0;

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("spentMinutes", Integer.valueOf(spentMinutes));
        data.put("todayBillableMinutes", Integer.valueOf(todayBillableMinutes));
        data.put("spentMinutesThisWeek", Integer.valueOf(spentMinutesThisWeek));
        data.put("todayTargetMinutes", Integer.valueOf(todayTargetMinutes));
        data.put("weekTargetMinutes", Integer.valueOf(weekTargetMinutes));
        data.put("estimateMinutes", Integer.valueOf(estimateMinutes));
        DashboardTodayColumnService.DayPile dayPile = dashboardTodayColumnService.loadDayPile(appReq);
        data.put("dayRemainingMinutes", Integer.valueOf(dayPile.getRemainingMinutes()));
        data.put("dayUnestimatedCount", Integer.valueOf(dayPile.getUnestimatedCount()));
        data.put("runningClock", Boolean.valueOf(timeTracker != null && timeTracker.isRunningClock()));
        data.put("nowMinute", Integer.valueOf(appReq.getWebUser().getLocalDateTimeNow().getMinute()));
        data.put("serverNowEpochMs", Long.valueOf(appReq.getWebUser().nowMillis()));
        data.put("meetingArcs", buildMeetingArcs(appReq));
        sendJsonResponse(appReq, true, "OK", data);
    }

    private void handleToggleTimer(AppReq appReq) throws Exception {
        selectActionFromRequest(appReq);
        ActionNext currentAction = reloadCurrentAction(appReq);
        TimeTracker timeTracker = appReq.getTimeTracker();
        if (timeTracker == null) {
            sendJsonResponse(appReq, false, "Timer is not available", null);
            return;
        }
        if (currentAction == null || currentAction.getProject() == null) {
            sendJsonResponse(appReq, false, "Current action is required", null);
            return;
        }

        if (timeTracker.isRunningClock()) {
            timeTracker.stopClock(appReq.getDataSession());
        } else {
            Project project = currentAction.getProject();
            appReq.setProject(project);
            appReq.setCompletingAction(currentAction);
            timeTracker.startClock(project, currentAction, appReq.getDataSession());
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("runningClock", Boolean.valueOf(timeTracker.isRunningClock()));
        sendJsonResponse(appReq, true, "OK", data);
    }

    private void handleAddCurrentActionNote(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        String nextNote = appReq.getRequest().getParameter("nextNote");
        if (actionNextIdStr == null || nextNote == null || nextNote.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Note is required", null);
            return;
        }

        int actionNextId = Integer.parseInt(actionNextIdStr);
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            ActionNotesService.Result notesResult = actionNotesService.append(dataSession, action, nextNote,
                    new Date());
            if (!notesResult.isOk()) {
                transaction.rollback();
                sendJsonResponse(appReq, false, notesResult.getErrorMessage(), null);
                return;
            }
            transaction.commit();

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("notes", notesResult.getText());
            sendJsonResponse(appReq, true, "Note added", data);
        } catch (Exception e) {
            transaction.rollback();
            sendJsonResponse(appReq, false, "Unable to add note", null);
        }
    }

    /**
     * Notepad save from the Notes drawer. baseNote is the text the drawer last loaded or saved;
     * see ActionNotesService.replace for how changes made elsewhere are handled.
     */
    private void handleSaveCurrentActionNotes(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        String nextNote = appReq.getRequest().getParameter("nextNote");
        String baseNote = appReq.getRequest().getParameter("baseNote");
        if (actionNextIdStr == null || nextNote == null || baseNote == null) {
            sendJsonResponse(appReq, false, "Notes and the text they started from are required", null);
            return;
        }

        int actionNextId = Integer.parseInt(actionNextIdStr);
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            ActionNotesService.Result notesResult = actionNotesService.replace(dataSession, action, nextNote,
                    baseNote, new Date());
            if (!notesResult.isOk()) {
                transaction.rollback();
                sendJsonResponse(appReq, false, notesResult.getErrorMessage(), null);
                return;
            }
            transaction.commit();

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("notes", notesResult.getText());
            data.put("tailReattached", Boolean.valueOf(notesResult.isTailReattached()));
            sendJsonResponse(appReq, true, "Notes saved", data);
        } catch (Exception e) {
            transaction.rollback();
            sendJsonResponse(appReq, false, "Unable to save notes", null);
        }
    }

    /** Saves the meeting-start sound picked in the Meetings drawer (a per-user tracker key). */
    private void handleSaveMeetingChime(AppReq appReq) throws Exception {
        String chime = FocusMeetingService.normalizeChime(appReq.getRequest().getParameter("chime"));
        if (chime == null) {
            sendJsonResponse(appReq, false, "Unknown sound", null);
            return;
        }
        TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_FOCUS_MEETING_CHIME, appReq.getWebUser(), chime,
                appReq.getDataSession());
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("chime", chime);
        sendJsonResponse(appReq, true, "Saved", data);
    }

    /**
     * The page version to show. A layout parameter (a or b, from the switcher) is saved as the
     * user's choice; otherwise the saved choice is used, defaulting to the classic page.
     */
    private FocusedActionPageRenderer.Layout resolveLayout(AppReq appReq) {
        FocusedActionPageRenderer.Layout requested = FocusedActionPageRenderer.Layout
                .fromKey(appReq.getRequest().getParameter("layout"));
        if (requested != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_FOCUS_LAYOUT, appReq.getWebUser(),
                    requested.getKey(), appReq.getDataSession());
            return requested;
        }
        FocusedActionPageRenderer.Layout saved = FocusedActionPageRenderer.Layout.fromKey(
                TrackerKeysManager.getUserKeyValueNoFallback(TrackerKeysManager.KEY_FOCUS_LAYOUT,
                        FocusedActionPageRenderer.Layout.CLASSIC.getKey(), appReq.getWebUser(),
                        appReq.getDataSession()));
        return saved == null ? FocusedActionPageRenderer.Layout.CLASSIC : saved;
    }

    private String loadMeetingChime(AppReq appReq) {
        String chime = FocusMeetingService.normalizeChime(TrackerKeysManager.getUserKeyValueNoFallback(
                TrackerKeysManager.KEY_FOCUS_MEETING_CHIME, FocusMeetingService.CHIME_OFF, appReq.getWebUser(),
                appReq.getDataSession()));
        return chime == null ? FocusMeetingService.CHIME_OFF : chime;
    }

    private ActionNext reloadCurrentAction(AppReq appReq) {
        ActionNext currentAction = appReq.getCompletingAction();
        if (currentAction == null) {
            return null;
        }
        ActionNext persisted = (ActionNext) appReq.getDataSession().get(ActionNext.class,
                currentAction.getActionNextId());
        if (persisted != null) {
            appReq.setCompletingAction(persisted);
            if (persisted.getProject() != null) {
                appReq.setProject(persisted.getProject());
            }
            return persisted;
        }
        return currentAction;
    }

    private List<FocusMeetingService.TodayMeeting> loadTodayMeetings(AppReq appReq) {
        return focusMeetingService.loadTodayMeetings(appReq.getDataSession(), appReq.getWebUser(),
                appReq.getActiveWorkspaceId());
    }

    private FocusedActionPageRenderer.MeetingsData buildMeetingsData(AppReq appReq, ActionNext currentAction) {
        WebUser webUser = appReq.getWebUser();
        List<FocusMeetingService.TodayMeeting> meetings = loadTodayMeetings(appReq);
        List<Integer> actionIds = new ArrayList<Integer>();
        for (FocusMeetingService.TodayMeeting meeting : meetings) {
            actionIds.add(Integer.valueOf(meeting.getAction().getActionNextId()));
        }
        Map<Integer, Integer> minutesToday = loadMinutesTodayByAction(appReq, actionIds);
        int currentId = currentAction == null ? 0 : currentAction.getActionNextId();
        List<FocusActionPickerRenderer.Row> rows = new ArrayList<FocusActionPickerRenderer.Row>();
        for (FocusMeetingService.TodayMeeting meeting : meetings) {
            ActionNext action = meeting.getAction();
            if (!meeting.isTimed()) {
                rows.add(buildPickerRow(appReq.getDataSession(), action, minutesToday,
                        action.getActionNextId() == currentId, true, "no time"));
                continue;
            }
            String startLabel = formatMeetingTime(meeting.getStartEpochMs().longValue(), webUser);
            String endLabel = formatMeetingTime(meeting.getEndEpochMs().longValue(), webUser);
            rows.add(buildPickerRow(appReq.getDataSession(), action, minutesToday,
                    action.getActionNextId() == currentId, true, startLabel + "\u2013" + endLabel)
                    .withMeetingTimes(meeting.getStartEpochMs(), meeting.getEndEpochMs(), startLabel, endLabel)
                    .withJoinUrl(meeting.getJoinUrl()));
        }
        return new FocusedActionPageRenderer.MeetingsData(rows, webUser.nowMillis(), loadMeetingChime(appReq));
    }

    /** Timed meetings for the clock, refreshed each minute with refreshFocusClock. */
    private List<Map<String, Object>> buildMeetingArcs(AppReq appReq) {
        List<Map<String, Object>> arcs = new ArrayList<Map<String, Object>>();
        for (FocusMeetingService.TodayMeeting meeting : loadTodayMeetings(appReq)) {
            if (!meeting.isTimed()) {
                continue;
            }
            Map<String, Object> arc = new LinkedHashMap<String, Object>();
            arc.put("startEpochMs", meeting.getStartEpochMs());
            arc.put("endEpochMs", meeting.getEndEpochMs());
            // Also used by the "meeting starting" card over the clock.
            arc.put("actionNextId", Integer.valueOf(meeting.getAction().getActionNextId()));
            arc.put("title", n(meeting.getAction().getNextDescription()));
            arc.put("startLabel", formatMeetingTime(meeting.getStartEpochMs().longValue(), appReq.getWebUser()));
            arc.put("endLabel", formatMeetingTime(meeting.getEndEpochMs().longValue(), appReq.getWebUser()));
            arc.put("joinUrl", meeting.getJoinUrl());
            arc.put("projectName", getActionProjectDisplayName(appReq.getDataSession(), meeting.getAction()));
            arc.put("projectIcon", projectIcon(meeting.getAction()));
            arcs.add(arc);
        }
        return arcs;
    }

    private String formatMeetingTime(long epochMs, WebUser webUser) {
        return java.time.Instant.ofEpochMilli(epochMs).atZone(webUser.getZoneId()).format(MEETING_TIME_FORMAT);
    }

    private List<FocusedActionPageRenderer.SwitchSlot> loadSwitchSlots(AppReq appReq, List<Integer> slotIds,
            ActionNext currentAction) {
        List<FocusedActionPageRenderer.SwitchSlot> slots = new ArrayList<FocusedActionPageRenderer.SwitchSlot>();
        Session dataSession = appReq.getDataSession();
        Map<Integer, Integer> minutesToday = loadMinutesTodayByAction(appReq, slotIds);
        int currentId = currentAction == null ? 0 : currentAction.getActionNextId();
        for (Integer slotId : slotIds) {
            ActionNext item = slotId.intValue() > 0 ? (ActionNext) dataSession.get(ActionNext.class, slotId) : null;
            if (item == null) {
                slots.add(FocusedActionPageRenderer.SwitchSlot.empty());
                continue;
            }
            String description = n(item.getNextDescription());
            if (description.length() == 0) {
                description = "[No description]";
            }
            Integer minutes = minutesToday.get(slotId);
            slots.add(new FocusedActionPageRenderer.SwitchSlot(item.getActionNextId(),
                    getActionProjectDisplayName(dataSession, item), description,
                    minutes == null ? 0 : minutes.intValue(),
                    item.getNextActionStatus() == ProjectNextActionStatus.COMPLETED,
                    item.getActionNextId() == currentId).withProjectIcon(projectIcon(item)));
        }
        return slots;
    }

    private List<Integer> trackFocusedActionSelection(AppReq appReq, ActionNext action) {
        HttpSession session = appReq.getRequest().getSession(true);
        List<Integer> recentActionIds = getRecentFocusedActionIds(session);
        if (action != null) {
            Integer actionId = Integer.valueOf(action.getActionNextId());
            recentActionIds.remove(actionId);
            recentActionIds.add(0, actionId);
            while (recentActionIds.size() > 10) {
                recentActionIds.remove(recentActionIds.size() - 1);
            }
            session.setAttribute(SESSION_RECENT_FOCUSED_ACTION_IDS, recentActionIds);
        }

        List<Integer> slotIds = readIntegerList(session.getAttribute(SESSION_SWITCH_SLOT_IDS));
        Set<Integer> unavailableIds = new HashSet<Integer>();
        for (Integer slotId : slotIds) {
            if (slotId.intValue() <= 0) {
                continue;
            }
            ActionNext item = (ActionNext) appReq.getDataSession().get(ActionNext.class, slotId);
            if (item == null || item.getNextActionStatus() == ProjectNextActionStatus.CANCELLED) {
                unavailableIds.add(slotId);
            }
        }
        slotIds = switchSlotService.place(slotIds, recentActionIds,
                action == null ? 0 : action.getActionNextId(), unavailableIds);
        session.setAttribute(SESSION_SWITCH_SLOT_IDS, new ArrayList<Integer>(slotIds));
        return slotIds;
    }

    private List<Integer> getRecentFocusedActionIds(HttpSession session) {
        return readIntegerList(session.getAttribute(SESSION_RECENT_FOCUSED_ACTION_IDS));
    }

    @SuppressWarnings("unchecked")
    private List<Integer> readIntegerList(Object value) {
        List<Integer> ids = new ArrayList<Integer>();
        if (!(value instanceof List<?>)) {
            return ids;
        }
        for (Object entry : (List<Object>) value) {
            if (entry instanceof Integer) {
                ids.add((Integer) entry);
            }
        }
        return ids;
    }

    private int loadBillableMinutesToday(AppReq appReq) {
        TimeTracker timeTracker = appReq.getTimeTracker();
        if (timeTracker != null) {
            return Math.max(0, timeTracker.getTotalMinsBillable());
        }
        TimeTracker fallback = new TimeTracker(appReq.getWebUser(), appReq.getDataSession());
        return Math.max(0, fallback.getTotalMinsBillable());
    }

    private int loadCurrentActionMinutesToday(AppReq appReq, ActionNext currentAction) {
        if (currentAction == null) {
            return 0;
        }
        Integer actionId = Integer.valueOf(currentAction.getActionNextId());
        Integer minutes = loadMinutesTodayByAction(appReq, Collections.singletonList(actionId)).get(actionId);
        return minutes == null ? 0 : minutes.intValue();
    }

    private Map<Integer, Integer> loadMinutesTodayByAction(AppReq appReq, List<Integer> actionIds) {
        Map<Integer, Integer> minutesByAction = new HashMap<Integer, Integer>();
        List<Integer> ids = new ArrayList<Integer>();
        for (Integer actionId : actionIds) {
            if (actionId != null && actionId.intValue() > 0) {
                ids.add(actionId);
            }
        }
        if (ids.isEmpty()) {
            return minutesByAction;
        }
        Query query = appReq.getDataSession().createQuery(
                "select action.actionNextId, sum(billMins) from BillEntry where action.actionNextId in (:actionNextIds) "
                        + "and startTime >= :today and startTime < :tomorrow group by action.actionNextId");
        query.setParameterList("actionNextIds", ids);

        Calendar calendar = appReq.getWebUser().getCalendar();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        query.setParameter("today", calendar.getTime());
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        query.setParameter("tomorrow", calendar.getTime());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.list();
        for (Object[] row : rows) {
            if (row[0] instanceof Number && row[1] instanceof Number) {
                minutesByAction.put(Integer.valueOf(((Number) row[0]).intValue()),
                        Integer.valueOf(Math.max(0, ((Number) row[1]).intValue())));
            }
        }
        return minutesByAction;
    }

    private int loadBillableMinutesThisWeekRounded(AppReq appReq) {
        TimeTracker timeTrackerForWeek = new TimeTracker(appReq.getWebUser(), new Date(), Calendar.WEEK_OF_YEAR,
                appReq.getDataSession());
        return calculateWeeklyRoundedMinutesLikeTrackServlet(appReq.getDataSession(), timeTrackerForWeek);
    }

    private int calculateWeeklyRoundedMinutesLikeTrackServlet(Session dataSession, TimeTracker timeTrackerForWeek) {
        int totalTimeInMinutes = 0;
        Map<Integer, Integer> projectMap = timeTrackerForWeek.getTotalMinsForProjectMap();
        for (Integer projectId : projectMap.keySet()) {
            Project project = (Project) dataSession.get(Project.class, projectId);
            if (project == null || project.getBillCode() == null) {
                continue;
            }
            BillCode billCode = resolveBillCode(dataSession, project);
            if (billCode == null || !"Y".equals(billCode.getBillable())) {
                continue;
            }
            Integer projectMinutes = projectMap.get(projectId);
            if (projectMinutes != null) {
                totalTimeInMinutes += TimeEntry.adjustMinutes(projectMinutes);
            }
        }
        return totalTimeInMinutes;
    }

    private void printFocusedHead(AppReq appReq) throws IOException {
        HttpServletResponse response = appReq.getResponse();
        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = appReq.getOut();
        TimeTracker timeTracker = appReq.getTimeTracker();

        out.println("<html>");
        out.println("  <head>");
        out.println("    <meta charset=\"UTF-8\">");
        if (timeTracker != null && timeTracker.isRunningClock()) {
            out.println("    <title>Focused Action - " + timeTracker.getTotalMinsBillableForDisplay() + "</title>");
        } else {
            out.println("    <title>Focused Action</title>");
        }
        String displayColor = appReq.getDisplayColor();
        String displaySize = appReq.getDisplaySize();
        out.println("    <link rel=\"stylesheet\" type=\"text/css\" href=\"CssServlet?displaySize=" + displaySize
                + "&displayColor=" + WebEscaper.urlEncode(displayColor) + "\" />");
        out.println("  </head>");
        out.println("  <body>");
        renderPageMessages(out, appReq);
    }

    private void printFocusedFoot(AppReq appReq) {
        PrintWriter out = appReq.getOut();
        out.println("  </body>");
        out.println("</html>");
    }

    private void sendJsonResponse(AppReq appReq, boolean success, String message, Map<String, Object> data)
            throws Exception {
        appReq.getResponse().setContentType("application/json; charset=UTF-8");
        PrintWriter out = appReq.getResponse().getWriter();

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\":").append(success).append(",");
        json.append("\"message\":\"").append(escapeJson(message)).append("\"");

        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                json.append(",\"").append(entry.getKey()).append("\":");
                appendJsonValue(json, entry.getValue());
            }
        }

        json.append("}");
        out.println(json.toString());
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private void appendJsonValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String) {
            json.append("\"").append(escapeJson((String) value)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Map) {
            json.append("{");
            boolean first = true;
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                if (!first) {
                    json.append(",");
                }
                json.append("\"").append(escapeJson(String.valueOf(entry.getKey()))).append("\":");
                appendJsonValue(json, entry.getValue());
                first = false;
            }
            json.append("}");
        } else if (value instanceof Iterable) {
            json.append("[");
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    json.append(",");
                }
                appendJsonValue(json, item);
                first = false;
            }
            json.append("]");
        } else {
            json.append("\"").append(escapeJson(value.toString())).append("\"");
        }
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\') {
                escaped.append("\\\\");
            } else if (ch == '"') {
                escaped.append("\\\"");
            } else if (ch == '\b') {
                escaped.append("\\b");
            } else if (ch == '\f') {
                escaped.append("\\f");
            } else if (ch == '\n') {
                escaped.append("\\n");
            } else if (ch == '\r') {
                escaped.append("\\r");
            } else if (ch == '\t') {
                escaped.append("\\t");
            } else if (ch < 0x20 || ch == '\u2028' || ch == '\u2029') {
                escaped.append(String.format("\\u%04x", Integer.valueOf(ch)));
            } else {
                escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private void renderPageMessages(PrintWriter out, AppReq appReq) {
        // Messages appear in bottom-left corner with auto-hide functionality
        if (appReq.getPageMessages().isEmpty()) {
            return;
        }
        out.println("<style>");
        out.println(
                "  .pm-stack { display:flex; flex-direction:column; align-items:flex-start; gap:6px; margin:8px 0; position:fixed; bottom:8px; left:8px; z-index:9999; }");
        out.println(
                "  .pm-msg { display:flex; align-items:flex-start; justify-content:space-between; padding:10px 14px; border-radius:4px; font-size:13px; line-height:1.4; width:auto; max-width:min(70ch, calc(100vw - 32px)); animation: fadeOut 4s ease-in-out forwards; }");
        out.println("  @keyframes fadeOut { 0% { opacity: 1; } 85% { opacity: 1; } 100% { opacity: 0; } }");
        out.println("  .pm-success { background:#e8f5e9; border-left:4px solid #388e3c; color:#1b5e20; }");
        out.println("  .pm-info    { background:#e3f2fd; border-left:4px solid #1976d2; color:#0d47a1; }");
        out.println("  .pm-warning { background:#fff8e1; border-left:4px solid #f9a825; color:#6d4c00; }");
        out.println("  .pm-error   { background:#fdecea; border-left:4px solid #c62828; color:#7f0000; }");
        out.println("</style>");
        out.println("<div class=\"pm-stack\">\n");
        for (org.openimmunizationsoftware.pt.model.PageMessage pageMessage : appReq.getPageMessages()) {
            String typeClass = "pm-info";
            if (pageMessage.getSeverity() == org.openimmunizationsoftware.pt.model.PageMessageSeverity.SUCCESS) {
                typeClass = "pm-success";
            } else if (pageMessage
                    .getSeverity() == org.openimmunizationsoftware.pt.model.PageMessageSeverity.WARNING) {
                typeClass = "pm-warning";
            } else if (pageMessage.getSeverity() == org.openimmunizationsoftware.pt.model.PageMessageSeverity.ERROR) {
                typeClass = "pm-error";
            }
            out.println("  <div class=\"pm-msg " + typeClass + "\">" + escapeHtml(pageMessage.getMessageText())
                    + "</div>");
        }
        out.println("</div>");
    }

    private String escapeHtml(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '&') {
                sb.append("&amp;");
            } else if (ch == '<') {
                sb.append("&lt;");
            } else if (ch == '>') {
                sb.append("&gt;");
            } else if (ch == '\"') {
                sb.append("&quot;");
            } else if (ch == '\'') {
                sb.append("&#39;");
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}