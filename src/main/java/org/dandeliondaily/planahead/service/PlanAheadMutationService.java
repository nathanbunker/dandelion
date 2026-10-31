package org.dandeliondaily.planahead.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.dandeliondaily.dashboard.service.DashboardCurrentActionService;
import org.dandeliondaily.planahead.model.PlanAheadBoardModel;
import org.dandeliondaily.planahead.model.PlanAheadMutationResult;
import org.dandeliondaily.planahead.render.PlanAheadPageRenderer;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.openimmunizationsoftware.pt.doa.LinkedMeetingDao;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.TemplateType;
import org.openimmunizationsoftware.pt.model.TimeSlot;

public class PlanAheadMutationService {

    static final String STATUS_ACTIVE = ProjectStatus.ACTIVE.getDatabaseValue();
    static final String BILLABLE_YES = "Y";
    private static final TimeZone UTC_TIME_ZONE = TimeZone.getTimeZone("UTC");

    private final PlanAheadBoardService boardService = new PlanAheadBoardService();
    private final ActionNotesService actionNotesService = new ActionNotesService();
    private final PlanAheadPageRenderer renderer = new PlanAheadPageRenderer();
    private final DashboardCurrentActionService dashboardCurrentActionService = new DashboardCurrentActionService();

    public PlanAheadMutationResult moveCard(AppReq appReq) {
        PlanAheadMutationResult result = new PlanAheadMutationResult();
        boolean personalMode = isPersonalMode(appReq);
        String actionNextIdString = appReq.getRequest().getParameter("actionNextId");
        String targetDateString = appReq.getRequest().getParameter("targetDate");
        String targetRow = n(appReq.getRequest().getParameter("targetRow")).trim().toLowerCase();

        int actionNextId;
        try {
            actionNextId = Integer.parseInt(n(actionNextIdString).trim());
        } catch (NumberFormatException nfe) {
            result.setSuccess(false);
            result.setMessage("actionNextId must be a whole number");
            return result;
        }

        Date targetDate = parseDay(targetDateString);
        if (targetDate == null) {
            result.setSuccess(false);
            result.setMessage("targetDate must be in yyyy-MM-dd format");
            return result;
        }

        String targetActionType = boardService.resolveActionTypeForRowKey(targetRow);
        TimeSlot targetTimeSlot = boardService.resolveTimeSlotForRowKey(targetRow);
        if (!personalMode && targetActionType.length() == 0) {
            result.setSuccess(false);
            result.setMessage("targetRow is invalid");
            return result;
        }
        if (personalMode && targetTimeSlot == null) {
            result.setSuccess(false);
            result.setMessage("targetRow is invalid");
            return result;
        }

        String todayKey = toDayKey(stripToDate(appReq.getWebUser().getToday(), appReq));
        if (isBeforeDay(targetDate, todayKey)) {
            result.setSuccess(false);
            result.setMessage("Cannot move cards to a past date");
            return result;
        }

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        String sourceDayKey;
        String sourceRowKey;
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action not found");
                return result;
            }
            if (!isOwnedByCurrentWorkspace(action.getWorkspaceId(), appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action is not available for this workspace");
                return result;
            }
            if (!isActionCompatibleWithMode(action, appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage(personalMode ? "Only personal actions are available in Personal mode"
                        : "Only work actions are available in Work mode");
                return result;
            }

            String meetingError = LinkedMeetingService.actionTypeError(
                    dataSession, action, personalMode ? ProjectNextActionType.WILL : targetActionType);
            if (meetingError != null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage(meetingError);
                return result;
            }
            Date actionDate = action.getNextActionDate();
            if (actionDate != null && toDayKey(actionDate).compareTo(todayKey) < 0) {
                sourceDayKey = todayKey;
                sourceRowKey = PlanAheadBoardService.ROW_OVERDUE;
            } else {
                sourceDayKey = toDayKey(actionDate);
                sourceRowKey = resolveRowKeyForAction(action, appReq);
            }

            if (personalMode) {
                action.setTimeSlot(targetTimeSlot);
                action.setNextActionType(ProjectNextActionType.WILL);
            } else {
                action.setNextActionType(targetActionType);
            }
            action.setNextActionDate(targetDate);
            action.setNextChangeDate(new Date());
            dataSession.update(action);
            transaction.commit();
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }

        dashboardCurrentActionService.handoffCurrentActionIfMovedOffToday(appReq,
                (ActionNext) dataSession.get(ActionNext.class, actionNextId));

        Date windowStart = boardService.resolveWindowStart(appReq);
        PlanAheadBoardModel boardModel = boardService.buildBoard(appReq, windowStart);

        Set<String> cellIds = new LinkedHashSet<String>();
        if (sourceDayKey.length() > 0 && sourceRowKey.length() > 0) {
            cellIds.add(PlanAheadPageRenderer.kanbanCellDomId(sourceDayKey, sourceRowKey));
        }
        String targetDayKey = toDayKey(targetDate);
        cellIds.add(PlanAheadPageRenderer.kanbanCellDomId(targetDayKey, targetRow));

        String overdueCellId = PlanAheadPageRenderer.kanbanCellDomId(todayKey,
                PlanAheadBoardService.ROW_OVERDUE);
        if (cellIds.contains(overdueCellId)) {
            result.getAffectedCellsHtml().put(overdueCellId,
                    renderer.renderOverdueCellHtml(boardModel.getOverdueRow(), boardModel.isWorkMode()));
            cellIds.remove(overdueCellId);
        }
        for (PlanAheadBoardModel.RowModel rowModel : boardModel.getRows()) {
            for (PlanAheadBoardModel.CellModel cellModel : rowModel.getCells()) {
                String domId = PlanAheadPageRenderer.kanbanCellDomId(cellModel.getDayKey(), cellModel.getRowKey());
                if (cellIds.contains(domId)) {
                    result.getAffectedCellsHtml().put(domId,
                            renderer.renderKanbanCellHtml(cellModel, boardModel.isWorkMode()));
                }
            }
        }

        Set<String> headerDayKeys = new LinkedHashSet<String>();
        if (sourceDayKey.length() > 0) {
            headerDayKeys.add(sourceDayKey);
        }
        headerDayKeys.add(targetDayKey);
        for (PlanAheadBoardModel.DayHeaderModel dayHeaderModel : boardModel.getDayHeaders()) {
            if (headerDayKeys.contains(dayHeaderModel.getDayKey())) {
                result.getAffectedHeadersHtml().put(
                        PlanAheadPageRenderer.dayHeaderDomId(dayHeaderModel.getDayKey()),
                        renderer.renderDayHeader(dayHeaderModel));
            }
        }

        result.setSuccess(true);
        result.setMessage("Card moved");
        return result;
    }

    public PlanAheadMutationResult saveCardEstimate(AppReq appReq) {
        PlanAheadMutationResult result = new PlanAheadMutationResult();
        boolean personalMode = isPersonalMode(appReq);
        if (personalMode) {
            result.setSuccess(false);
            result.setMessage("Estimate editing is only available in Work mode");
            return result;
        }
        String actionNextIdString = appReq.getRequest().getParameter("actionNextId");
        int actionNextId;
        try {
            actionNextId = Integer.parseInt(n(actionNextIdString).trim());
        } catch (NumberFormatException nfe) {
            result.setSuccess(false);
            result.setMessage("actionNextId must be a whole number");
            return result;
        }

        Integer nextTimeEstimate = parseIntegerOrNull(appReq.getRequest().getParameter("nextTimeEstimate"));
        if (nextTimeEstimate == null) {
            nextTimeEstimate = Integer.valueOf(0);
        }
        if (nextTimeEstimate.intValue() < 0) {
            result.setSuccess(false);
            result.setMessage("nextTimeEstimate cannot be negative");
            return result;
        }

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        String dayKey;
        String rowKey;
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action not found");
                return result;
            }
            if (!isOwnedByCurrentWorkspace(action.getWorkspaceId(), appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action is not available for this workspace");
                return result;
            }
            if (!isActionCompatibleWithMode(action, appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Only work actions are available in Work mode");
                return result;
            }

            dayKey = toDayKey(action.getNextActionDate());
            rowKey = boardService.resolveRowKeyForActionType(action.getNextActionType());
            String estTodayKey = toDayKey(stripToDate(appReq.getWebUser().getToday(), appReq));
            if (dayKey.compareTo(estTodayKey) < 0) {
                dayKey = estTodayKey;
                rowKey = PlanAheadBoardService.ROW_OVERDUE;
            } else if (rowKey.length() == 0) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action type is not editable in Plan Ahead");
                return result;
            }

            action.setNextTimeEstimate(nextTimeEstimate);
            action.setNextChangeDate(new Date());
            dataSession.update(action);
            transaction.commit();
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }

        Date windowStart = boardService.resolveWindowStart(appReq);
        PlanAheadBoardModel boardModel = boardService.buildBoard(appReq, windowStart);

        String cellId = PlanAheadPageRenderer.kanbanCellDomId(dayKey, rowKey);
        if (PlanAheadBoardService.ROW_OVERDUE.equals(rowKey)) {
            result.getAffectedCellsHtml().put(cellId,
                    renderer.renderOverdueCellHtml(boardModel.getOverdueRow(), boardModel.isWorkMode()));
        } else {
            for (PlanAheadBoardModel.RowModel rowModel : boardModel.getRows()) {
                for (PlanAheadBoardModel.CellModel cellModel : rowModel.getCells()) {
                    String domId = PlanAheadPageRenderer.kanbanCellDomId(cellModel.getDayKey(),
                            cellModel.getRowKey());
                    if (cellId.equals(domId)) {
                        result.getAffectedCellsHtml().put(domId,
                                renderer.renderKanbanCellHtml(cellModel, boardModel.isWorkMode()));
                    }
                }
            }
        }

        for (PlanAheadBoardModel.DayHeaderModel dayHeaderModel : boardModel.getDayHeaders()) {
            if (dayKey.equals(dayHeaderModel.getDayKey())) {
                result.getAffectedHeadersHtml().put(
                        PlanAheadPageRenderer.dayHeaderDomId(dayHeaderModel.getDayKey()),
                        renderer.renderDayHeader(dayHeaderModel));
                break;
            }
        }

        result.setSuccess(true);
        result.setMessage("Estimate updated");
        return result;
    }

    public PlanAheadMutationResult saveCardDescriptionInline(AppReq appReq) {
        PlanAheadMutationResult result = new PlanAheadMutationResult();
        boolean personalMode = isPersonalMode(appReq);
        String actionNextIdString = appReq.getRequest().getParameter("actionNextId");
        int actionNextId;
        try {
            actionNextId = Integer.parseInt(n(actionNextIdString).trim());
        } catch (NumberFormatException nfe) {
            result.setSuccess(false);
            result.setMessage("actionNextId must be a whole number");
            return result;
        }

        String nextDescription = clip(n(appReq.getRequest().getParameter("nextDescription")), 12000);

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        String dayKey;
        String rowKey;
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action not found");
                return result;
            }
            if (!isOwnedByCurrentWorkspace(action.getWorkspaceId(), appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action is not available for this workspace");
                return result;
            }
            if (!isActionCompatibleWithMode(action, appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage(personalMode ? "Only personal actions are available in Personal mode"
                        : "Only work actions are available in Work mode");
                return result;
            }
            if (action.getNextActionDate() == null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action date is required for inline description editing");
                return result;
            }

            dayKey = toDayKey(action.getNextActionDate());
            rowKey = resolveRowKeyForAction(action, appReq);
            String descTodayKey = toDayKey(stripToDate(appReq.getWebUser().getToday(), appReq));
            if (dayKey.compareTo(descTodayKey) < 0) {
                dayKey = descTodayKey;
                rowKey = PlanAheadBoardService.ROW_OVERDUE;
            } else if (rowKey.length() == 0) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Action type is not editable in Plan Ahead");
                return result;
            }

            action.setNextDescription(nextDescription);
            action.setNextChangeDate(new Date());
            dataSession.update(action);
            transaction.commit();
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }

        Date windowStart = boardService.resolveWindowStart(appReq);
        PlanAheadBoardModel boardModel = boardService.buildBoard(appReq, windowStart);

        String cellId = PlanAheadPageRenderer.kanbanCellDomId(dayKey, rowKey);
        if (PlanAheadBoardService.ROW_OVERDUE.equals(rowKey)) {
            result.getAffectedCellsHtml().put(cellId,
                    renderer.renderOverdueCellHtml(boardModel.getOverdueRow(), boardModel.isWorkMode()));
        } else {
            for (PlanAheadBoardModel.RowModel rowModel : boardModel.getRows()) {
                for (PlanAheadBoardModel.CellModel cellModel : rowModel.getCells()) {
                    String domId = PlanAheadPageRenderer.kanbanCellDomId(cellModel.getDayKey(),
                            cellModel.getRowKey());
                    if (cellId.equals(domId)) {
                        result.getAffectedCellsHtml().put(domId,
                                renderer.renderKanbanCellHtml(cellModel, boardModel.isWorkMode()));
                    }
                }
            }
        }

        for (PlanAheadBoardModel.DayHeaderModel dayHeaderModel : boardModel.getDayHeaders()) {
            if (dayKey.equals(dayHeaderModel.getDayKey())) {
                result.getAffectedHeadersHtml().put(
                        PlanAheadPageRenderer.dayHeaderDomId(dayHeaderModel.getDayKey()),
                        renderer.renderDayHeader(dayHeaderModel));
                break;
            }
        }

        result.setSuccess(true);
        result.setMessage("Description updated");
        return result;
    }

    public PlanAheadMutationResult saveTemplateEdit(AppReq appReq) {
        PlanAheadMutationResult result = new PlanAheadMutationResult();
        boolean personalMode = isPersonalMode(appReq);

        String actionNextIdString = n(appReq.getRequest().getParameter("actionNextId")).trim();
        int actionNextId = 0;
        if (actionNextIdString.length() > 0) {
            try {
                actionNextId = Integer.parseInt(actionNextIdString);
            } catch (NumberFormatException nfe) {
                result.setSuccess(false);
                result.setMessage("actionNextId must be a whole number");
                return result;
            }
        }

        String nextDescription = clip(n(appReq.getRequest().getParameter("nextDescription")), 12000);
        if (nextDescription.length() == 0) {
            result.setSuccess(false);
            result.setMessage("Description is required");
            return result;
        }

        String templateTypeId = n(appReq.getRequest().getParameter("templateType")).trim();
        TemplateType templateType = TemplateType.getTemplateType(templateTypeId);
        if (templateType == null) {
            result.setSuccess(false);
            result.setMessage("templateType is required");
            return result;
        }

        String nextActionType = null;
        TimeSlot timeSlot = null;
        if (personalMode) {
            timeSlot = TimeSlot.getTimeSlot(n(appReq.getRequest().getParameter("timeSlot")).trim());
            if (timeSlot == null) {
                timeSlot = TimeSlot.AFTERNOON;
            }
        } else {
            nextActionType = n(appReq.getRequest().getParameter("nextActionType")).trim();
            if (nextActionType.length() == 0
                    || boardService.resolveRowKeyForActionType(nextActionType).length() == 0) {
                result.setSuccess(false);
                result.setMessage("nextActionType is invalid");
                return result;
            }
        }

        Integer nextTimeEstimate = parseIntegerOrNull(appReq.getRequest().getParameter("nextTimeEstimate"));
        if (nextTimeEstimate == null) {
            nextTimeEstimate = Integer.valueOf(0);
        }
        if (nextTimeEstimate.intValue() < 0) {
            result.setSuccess(false);
            result.setMessage("nextTimeEstimate cannot be negative");
            return result;
        }

        String missedActionBehavior = n(appReq.getRequest().getParameter("missedActionBehavior")).trim();
        if (missedActionBehavior.length() == 0) {
            missedActionBehavior = "AUTO_CANCEL";
        }

        boolean autoGenerate = "Y".equals(n(appReq.getRequest().getParameter("autoGenerate")).trim());
        String scheduleDaysOfWeek = clip(n(appReq.getRequest().getParameter("scheduleDaysOfWeek")), 200);
        String scheduleDaysOfMonth = clip(n(appReq.getRequest().getParameter("scheduleDaysOfMonth")), 200);
        String scheduleDaysOfQuarter = clip(n(appReq.getRequest().getParameter("scheduleDaysOfQuarter")), 200);
        String scheduleDaysOfYear = clip(n(appReq.getRequest().getParameter("scheduleDaysOfYear")), 200);
        String linkUrl = clip(n(appReq.getRequest().getParameter("linkUrl")), 1200);
        String nextNotes = clip(n(appReq.getRequest().getParameter("nextNote")), 4000);
        Integer projectId = null;
        try {
            String pidStr = n(appReq.getRequest().getParameter("projectId")).trim();
            if (pidStr.length() > 0) {
                projectId = Integer.parseInt(pidStr);
            }
        } catch (NumberFormatException nfe) {
            // no project
        }

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action;
            if (actionNextId > 0) {
                action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
                if (action == null) {
                    transaction.rollback();
                    result.setSuccess(false);
                    result.setMessage("Template action not found");
                    return result;
                }
                if (!isOwnedByCurrentWorkspace(action.getWorkspaceId(), appReq)) {
                    transaction.rollback();
                    result.setSuccess(false);
                    result.setMessage("Template action is not available for this workspace");
                    return result;
                }
            } else {
                // contact_id is mapped both as a scalar and as a non-insertable
                // many-to-one, so the association must be set or Hibernate nulls
                // the column back out on flush.
                ProjectContact userContact = appReq.getWebUser().getProjectContact();
                if (userContact == null) {
                    userContact = (ProjectContact) dataSession.get(ProjectContact.class,
                            Integer.valueOf(appReq.getWebUser().getContactId()));
                }
                action = new ActionNext();
                action.setWorkspaceId(appReq.getActiveWorkspaceId());
                action.setContact(userContact);
                action.setNextProjectContact(userContact);
                action.setBillable(!personalMode);
                action.setNextActionStatus(ProjectNextActionStatus.READY);
            }

            if (action.isWillMeet() && action.getActionNextId() > 0 && action.getWorkspaceId() != null
                    && new LinkedMeetingDao(dataSession)
                            .findByAction(action.getWorkspaceId(), action.getActionNextId()) != null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Explicitly unlink the meeting through MCP before converting this action to a template.");
                return result;
            }
            action.setTemplateType(templateType);
            action.setNextDescription(nextDescription);
            action.setNextTimeEstimate(nextTimeEstimate);
            action.setLinkUrl(linkUrl);
            actionNotesService.replace(dataSession, action, nextNotes, null, null);
            action.setNextChangeDate(new Date());

            if (personalMode) {
                action.setTimeSlot(timeSlot);
                action.setNextActionType(ProjectNextActionType.WILL);
            } else {
                action.setNextActionType(nextActionType);
            }

            // Set project for both modes
            if (projectId != null && projectId.intValue() > 0) {
                Project project = (Project) dataSession.get(Project.class, projectId);
                if (project != null && project.getWorkspaceId() != null
                        && project.getWorkspaceId().intValue() == appReq.getActiveWorkspaceId()) {
                    action.setProject(project);
                }
            } else {
                action.setProject(null);
                action.setProjectId(0);
            }

            if (actionNextId > 0) {
                dataSession.update(action);
            } else {
                dataSession.save(action);
                actionNextId = action.getActionNextId();
            }

            ActionNextTemplateConfig config = (ActionNextTemplateConfig) dataSession.get(ActionNextTemplateConfig.class,
                    actionNextId);
            if (config == null) {
                config = new ActionNextTemplateConfig();
                config.setActionNextId(actionNextId);
                config.setAutoGenerate(autoGenerate);
                config.setMissedActionBehavior(missedActionBehavior);
                config.setScheduleDaysOfWeek(scheduleDaysOfWeek.length() > 0 ? scheduleDaysOfWeek : null);
                config.setScheduleDaysOfMonth(scheduleDaysOfMonth.length() > 0 ? scheduleDaysOfMonth : null);
                config.setScheduleDaysOfQuarter(scheduleDaysOfQuarter.length() > 0 ? scheduleDaysOfQuarter : null);
                config.setScheduleDaysOfYear(scheduleDaysOfYear.length() > 0 ? scheduleDaysOfYear : null);
                dataSession.save(config);
            } else {
                config.setAutoGenerate(autoGenerate);
                config.setMissedActionBehavior(missedActionBehavior);
                config.setScheduleDaysOfWeek(scheduleDaysOfWeek.length() > 0 ? scheduleDaysOfWeek : null);
                config.setScheduleDaysOfMonth(scheduleDaysOfMonth.length() > 0 ? scheduleDaysOfMonth : null);
                config.setScheduleDaysOfQuarter(scheduleDaysOfQuarter.length() > 0 ? scheduleDaysOfQuarter : null);
                config.setScheduleDaysOfYear(scheduleDaysOfYear.length() > 0 ? scheduleDaysOfYear : null);
                dataSession.update(config);
            }

            transaction.commit();
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }

        // Immediately sync schedule: propagate field changes to existing instances,
        // cancel instances whose dates no longer match, and generate new ones.
        final int savedActionNextId = actionNextId;
        Transaction syncTx = dataSession.beginTransaction();
        try {
            ActionNext reloadedTemplate = (ActionNext) dataSession.get(ActionNext.class, savedActionNextId);
            if (reloadedTemplate != null) {
                LocalDate today = LocalDate.now(appReq.getWebUser().getTimeZone().toZoneId());
                int advanceDays;
                try {
                    advanceDays = Integer.parseInt(TrackerKeysManager.getKeyValue(
                            TrackerKeysManager.KEY_TEMPLATE_ADVANCE_DAYS,
                            TrackerKeysManager.KEY_TYPE_GLOBAL,
                            TrackerKeysManager.KEY_ID_GLOBAL,
                            "14", dataSession).trim());
                } catch (NumberFormatException nfe) {
                    advanceDays = 14;
                }
                new TemplateGenerationService().syncAfterEdit(
                        dataSession, reloadedTemplate,
                        appReq.getActiveWorkspaceId(),
                        appReq.getWebUser().getContactId(),
                        today, advanceDays);
            }
            syncTx.commit();
        } catch (RuntimeException e) {
            syncTx.rollback();
            System.err.println("[TemplateManagement] Schedule sync failed for template "
                    + savedActionNextId + ": " + e.getMessage());
        }

        result.setSuccess(true);
        result.setMessage(actionNextId > 0 ? "Template saved" : "Template created");
        return result;
    }

    public PlanAheadMutationResult deleteTemplateEdit(AppReq appReq) {
        PlanAheadMutationResult result = new PlanAheadMutationResult();
        String actionNextIdString = n(appReq.getRequest().getParameter("actionNextId")).trim();
        int actionNextId;
        try {
            actionNextId = Integer.parseInt(actionNextIdString);
        } catch (NumberFormatException nfe) {
            result.setSuccess(false);
            result.setMessage("actionNextId must be a whole number");
            return result;
        }

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Template action not found");
                return result;
            }
            if (!isOwnedByCurrentWorkspace(action.getWorkspaceId(), appReq)) {
                transaction.rollback();
                result.setSuccess(false);
                result.setMessage("Template action is not available for this workspace");
                return result;
            }

            action.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
            action.setNextChangeDate(new Date());
            dataSession.update(action);
            transaction.commit();
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }

        result.setSuccess(true);
        result.setMessage("Template deleted");
        return result;
    }

    Date parseDay(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setLenient(false);
        sdf.setTimeZone(UTC_TIME_ZONE);
        try {
            return sdf.parse(value.trim());
        } catch (ParseException e) {
            return null;
        }
    }

    private Date stripToDate(Date date, AppReq appReq) {
        java.util.Calendar calendar = appReq.getWebUser().getCalendar();
        calendar.setTime(date);
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    String toDayKey(Date day) {
        if (day == null) {
            return "";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(UTC_TIME_ZONE);
        return sdf.format(day);
    }

    boolean isBeforeDay(Date day, String referenceDayKey) {
        if (day == null || referenceDayKey == null) {
            return false;
        }
        String dayKey = toDayKey(day);
        if (dayKey.length() == 0 || referenceDayKey.length() == 0) {
            return false;
        }
        return dayKey.compareTo(referenceDayKey) < 0;
    }

    private boolean isOwnedByCurrentWorkspace(Integer actionWorkspaceId, AppReq appReq) {
        if (actionWorkspaceId == null || appReq.getWebUser() == null || appReq.getActiveWorkspaceId() == null) {
            return false;
        }
        return actionWorkspaceId.equals(appReq.getActiveWorkspaceId());
    }

    String n(String value) {
        return value == null ? "" : value;
    }

    Integer parseIntegerOrNull(String value) {
        if (value == null || value.trim().length() == 0) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String v = value.trim();
        if (v.length() <= max) {
            return v;
        }
        return v.substring(0, max);
    }

    private String resolveMode(AppReq appReq) {
        return boardService.resolveMode(appReq);
    }

    private boolean isPersonalMode(AppReq appReq) {
        return PlanAheadBoardService.MODE_PERSONAL.equalsIgnoreCase(resolveMode(appReq));
    }

    private boolean isActionCompatibleWithMode(ActionNext action, AppReq appReq) {
        if (action == null) {
            return false;
        }
        boolean personalMode = isPersonalMode(appReq);
        return personalMode ? !action.isBillable() : action.isBillable();
    }

    private String resolveRowKeyForAction(ActionNext action, AppReq appReq) {
        if (action == null) {
            return "";
        }
        if (isPersonalMode(appReq)) {
            return boardService.resolveRowKeyForTimeSlot(action.getTimeSlot());
        }
        return boardService.resolveRowKeyForActionType(action.getNextActionType());
    }

    List<Project> filterProjectsForTemplateMode(List<Project> projects, Map<String, BillCode> billCodeMap,
            boolean personalMode) {
        List<Project> result = new ArrayList<Project>();
        for (Project project : projects) {
            boolean billable = isBillableProject(project, billCodeMap);
            if (personalMode ? !billable : billable) {
                result.add(project);
            }
        }
        return result;
    }

    boolean isBillableProject(Project project, Map<String, BillCode> billCodeMap) {
        if (project == null) {
            return false;
        }
        String code = project.getBillCode();
        if (code == null || code.trim().length() == 0) {
            return false;
        }
        BillCode billCode = billCodeMap.get(code);
        if (billCode == null) {
            return false;
        }
        return BILLABLE_YES.equals(billCode.getBillable());
    }
}
