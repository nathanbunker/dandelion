package org.dandeliondaily.weeklyreport.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.dandeliondaily.weeklyreport.model.WeeklyReportViewModel;
import org.dandeliondaily.weeklyreport.model.WeeklyReportViewModel.ActivityItem;
import org.dandeliondaily.weeklyreport.model.WeeklyReportViewModel.AllocationRow;
import org.dandeliondaily.weeklyreport.model.WeeklyReportViewModel.ProjectActivity;
import org.dandeliondaily.weeklyreport.model.WeeklyTimeSummary;
import org.dandeliondaily.weeklyreport.model.WeeklyTimeSummary.ProjectTime;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.BillPlanDao;
import org.openimmunizationsoftware.pt.doa.BillPlanTargetDao;
import org.openimmunizationsoftware.pt.doa.TrackerNarrativeDao;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.BillFundingSource;
import org.openimmunizationsoftware.pt.model.BillPlan;
import org.openimmunizationsoftware.pt.model.BillPlanTarget;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.model.WeeklyReport;
import org.openimmunizationsoftware.pt.model.WorkObligation;

public class WeeklyReportDataService {
    private final WeeklyTimeSummaryService timeService = new WeeklyTimeSummaryService();
    private final SafeMarkdownRenderer markdownRenderer = new SafeMarkdownRenderer();
    private final WorkObligationService obligationService = new WorkObligationService();

    public WeeklyReportViewModel load(Session session, WeeklyReport report, WebUser owner, LocalDate weekStart) {
        WeeklyReportViewModel model = new WeeklyReportViewModel();
        model.setReport(report);
        model.setWeekStart(weekStart);

        LocalDate historyStart = weekStart.minusWeeks(7);
        List<WeeklyTimeSummary> history = timeService.load(session, owner, report.getRootWorkspaceId(),
                report.getRootBillCode(), historyStart, 8);
        model.getHistory().addAll(history);
        WeeklyTimeSummary selected = history.get(history.size() - 1);
        model.setSelectedWeek(selected);

        TrackerNarrative narrative = new TrackerNarrativeDao(session).findApprovedByContactTypeAndPeriod(
                owner.getContactId(), "WEEKLY", weekStart, weekStart.plusDays(6));
        if (narrative != null && narrative.getMarkdownFinal() != null
                && narrative.getMarkdownFinal().trim().length() > 0) {
            model.setApprovedNarrativeHtml(markdownRenderer.render(narrative.getMarkdownFinal()));
        }

        BillPlan plan = new BillPlanDao(session).findActiveApprovedPlan(report.getRootWorkspaceId(),
                owner.getWebUserId(), toDate(weekStart.plusDays(6), owner.getZoneId()));
        List<WeeklyTimeSummary> fiscalWeeks = new ArrayList<WeeklyTimeSummary>();
        LocalDate firstFiscalWeek = null;
        if (plan != null) {
            LocalDate fiscalStart = owner.toLocalDate(plan.getFiscalStartDate());
            model.setFiscalPlanCode(plan.getBillPlanCode());
            model.setFiscalPlanLabel(plan.getPlanLabel());
            model.setFiscalStartDate(fiscalStart);
            model.setFiscalEndDate(plan.getFiscalEndDate() == null ? null
                    : owner.toLocalDate(plan.getFiscalEndDate()));
            firstFiscalWeek = fiscalStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
            int fiscalWeekCount = (int) java.time.temporal.ChronoUnit.WEEKS.between(firstFiscalWeek, weekStart) + 1;
            fiscalWeeks = timeService.load(session, owner, report.getRootWorkspaceId(), report.getRootBillCode(),
                    firstFiscalWeek, fiscalWeekCount, fiscalStart, weekStart.plusWeeks(1));
        }
        for (WeeklyTimeSummary fiscalWeek : fiscalWeeks) {
            for (ProjectTime time : fiscalWeek.getProjects()) {
                String key = WeeklyReportViewModel.projectKey(time.getProjectId(), time.getBillCode());
                Integer existing = model.getFiscalProjectMinutes().get(key);
                model.getFiscalProjectMinutes().put(key, Integer.valueOf(
                        (existing == null ? 0 : existing.intValue()) + time.getRoundedMinutes()));
                model.getFiscalProjectNames().put(key, time.getProjectName());
            }
        }
        LocalDate obligationRangeStart = firstFiscalWeek != null && firstFiscalWeek.isBefore(historyStart)
                ? firstFiscalWeek
                : historyStart;
        Map<LocalDate, WorkObligation> obligations = obligationService.loadRaw(session, owner.getWebUserId(),
                obligationRangeStart, weekStart.plusWeeks(1));
        buildAllocations(session, model, history, fiscalWeeks, plan, report.getRootWorkspaceId(), obligations);
        buildProjectActivity(session, model, owner, weekStart);
        return model;
    }

    @SuppressWarnings("unchecked")
    private void buildAllocations(Session session, WeeklyReportViewModel model, List<WeeklyTimeSummary> history,
            List<WeeklyTimeSummary> fiscalWeeks, BillPlan plan, int workspaceId,
            Map<LocalDate, WorkObligation> obligations) {
        Query codeQuery = session.createQuery("from BillCode where workspaceId = :workspaceId order by id.billCode");
        codeQuery.setInteger("workspaceId", workspaceId);
        List<BillCode> codes = codeQuery.list();
        Map<String, BillCode> codesByValue = new HashMap<String, BillCode>();
        for (BillCode code : codes)
            codesByValue.put(code.getBillCode(), code);
        Query sourceQuery = session.createQuery("from BillFundingSource where workspaceId = :workspaceId");
        sourceQuery.setInteger("workspaceId", workspaceId);
        List<BillFundingSource> sources = sourceQuery.list();
        Map<Integer, String> sourceLabels = new HashMap<Integer, String>();
        for (BillFundingSource source : sources)
            sourceLabels.put(Integer.valueOf(source.getFundingSourceId()), source.getFundingSourceLabel());

        Map<String, BillPlanTarget> targets = new HashMap<String, BillPlanTarget>();
        if (plan != null) {
            for (BillPlanTarget target : new BillPlanTargetDao(session).listTargetsForPlan(plan.getBillPlanId())) {
                targets.put(target.getBillCode(), target);
            }
        }
        Set<String> includedCodes = new TreeSet<String>();
        includedCodes.addAll(targets.keySet());
        for (WeeklyTimeSummary summary : history)
            includedCodes.addAll(summary.getMinutesByBillCode().keySet());
        for (WeeklyTimeSummary summary : fiscalWeeks)
            includedCodes.addAll(summary.getMinutesByBillCode().keySet());
        for (BillCode code : codes) {
            if (WeeklyTimeSummaryService.matchesPrefix(code.getBillCode(), model.getReport().getRootBillCode())) {
                includedCodes.add(code.getBillCode());
            }
        }
        int fourWeekDenominator = sumAllWorked(history, Math.max(0, history.size() - 4));
        int fiscalDenominator = sumAllWorked(fiscalWeeks, 0);
        model.setFourWeekAllWorkedMinutes(fourWeekDenominator);
        model.setFiscalYearAllWorkedMinutes(fiscalDenominator);

        int fourWeekObligatedMinutes = sumObligated(obligations, history, Math.max(0, history.size() - 4));
        int fiscalYearObligatedMinutes = sumObligated(obligations, fiscalWeeks, 0);
        model.setFourWeekObligatedMinutes(fourWeekObligatedMinutes);
        model.setFiscalYearObligatedMinutes(fiscalYearObligatedMinutes);
        WorkObligation selectedObligation = obligations.get(model.getWeekStart());
        int selectedObligatedMinutes = obligationService.resolveMinutes(obligations, model.getWeekStart());
        model.setObligatedMinutes(selectedObligatedMinutes);
        model.setObligatedIsDefault(selectedObligation == null);
        model.setObligatedNote(selectedObligation == null ? null : selectedObligation.getNote());
        for (WeeklyTimeSummary week : history) {
            model.getObligatedMinutesByWeek().put(week.getWeekStart(),
                    Integer.valueOf(obligationService.resolveMinutes(obligations, week.getWeekStart())));
        }

        WeeklyTimeSummary selected = model.getSelectedWeek();
        for (String billCode : includedCodes) {
            if (!WeeklyTimeSummaryService.matchesPrefix(billCode, model.getReport().getRootBillCode()))
                continue;
            BillCode code = codesByValue.get(billCode);
            int weekMinutes = value(selected.getMinutesByBillCode(), billCode);
            int fourWeekMinutes = sumCode(history, Math.max(0, history.size() - 4), billCode);
            int fiscalMinutes = sumCode(fiscalWeeks, 0, billCode);
            BillPlanTarget target = targets.get(billCode);
            if (weekMinutes == 0 && fourWeekMinutes == 0 && fiscalMinutes == 0 && target == null)
                continue;
            AllocationRow row = new AllocationRow();
            row.setBillCode(billCode);
            row.setBillLabel(code == null ? "" : code.getBillLabel());
            row.setFundingSource(code == null || code.getFundingSourceId() == null
                    ? ""
                    : sourceLabels.get(code.getFundingSourceId()));
            row.setWeekMinutes(weekMinutes);
            row.setFourWeekMinutes(fourWeekMinutes);
            row.setFiscalYearMinutes(fiscalMinutes);
            row.setWeekPercent(percent(weekMinutes, selectedObligatedMinutes));
            row.setFourWeekPercent(percent(fourWeekMinutes, fourWeekObligatedMinutes));
            row.setFiscalYearPercent(percent(fiscalMinutes, fiscalYearObligatedMinutes));
            if (target != null) {
                row.setAnnualTargetPercent(basisPoints(target.getAnnualTargetBps()));
                row.setSteeringTargetPercent(basisPoints(target.getSteeringTargetBps()));
            }
            model.getAllocationRows().put(row.getBillCode(), row);
        }
    }

    @SuppressWarnings("unchecked")
    private void buildProjectActivity(Session session, WeeklyReportViewModel model, WebUser owner,
            LocalDate weekStart) {
        for (ProjectTime time : model.getSelectedWeek().getProjects()) {
            String key = WeeklyReportViewModel.projectKey(time.getProjectId(), time.getBillCode());
            ProjectActivity activity = model.getProjectActivities().get(key);
            if (activity == null) {
                activity = new ProjectActivity();
                activity.setProjectId(time.getProjectId());
                activity.setProject((Project) session.get(Project.class, time.getProjectId()));
                activity.setBillCode(time.getBillCode());
                model.getProjectActivities().put(key, activity);
            }
            activity.setRoundedMinutes(activity.getRoundedMinutes() + time.getRoundedMinutes());
        }
        Date start = toDate(weekStart, owner.getZoneId());
        Date end = toDate(weekStart.plusWeeks(1), owner.getZoneId());
        Query takenQuery = session.createQuery(
                "from ActionTaken where contactId = :contactId and actionDescription <> '' and actionDate >= :start and actionDate < :end order by actionDate");
        takenQuery.setInteger("contactId", owner.getContactId());
        takenQuery.setTimestamp("start", start);
        takenQuery.setTimestamp("end", end);
        for (ActionTaken action : (List<ActionTaken>) takenQuery.list()) {
            addActivityItem(model, action.getProjectId(),
                    new ActivityItem(action.getActionDate(), action.getActionDescription(),
                            action.getActionNote()));
        }
        Query completedQuery = session.createQuery(
                "from ActionNext where contactId = :contactId and nextDescription <> '' and nextActionStatusString = :status and nextChangeDate >= :start and nextChangeDate < :end order by nextChangeDate");
        completedQuery.setInteger("contactId", owner.getContactId());
        completedQuery.setString("status", ProjectNextActionStatus.COMPLETED.getId());
        completedQuery.setTimestamp("start", start);
        completedQuery.setTimestamp("end", end);
        for (ActionNext action : (List<ActionNext>) completedQuery.list()) {
            addActivityItem(model, action.getProjectId(),
                    new ActivityItem(action.getNextChangeDate(), printableDescription(action, owner),
                            action.getNextNotes(), true));
        }
    }

    static String printableDescription(ActionNext action, WebUser owner) {
        return action.getNextDescriptionForDisplay(owner.getProjectContact())
                .replace("<i>", "")
                .replace("</i>", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void addActivityItem(WeeklyReportViewModel model, int projectId, ActivityItem item) {
        for (ProjectActivity activity : model.getProjectActivities().values()) {
            if (activity.getProjectId() == projectId)
                activity.getCompletedItems().add(item);
        }
    }

    private static int sumAllWorked(List<WeeklyTimeSummary> summaries, int start) {
        int total = 0;
        for (int index = start; index < summaries.size(); index++)
            total += summaries.get(index).getAllWorkedMinutes();
        return total;
    }

    private int sumObligated(Map<LocalDate, WorkObligation> obligations, List<WeeklyTimeSummary> summaries,
            int start) {
        int total = 0;
        for (int index = start; index < summaries.size(); index++)
            total += obligationService.resolveMinutes(obligations, summaries.get(index).getWeekStart());
        return total;
    }

    private static int sumCode(List<WeeklyTimeSummary> summaries, int start, String code) {
        int total = 0;
        for (int index = start; index < summaries.size(); index++)
            total += value(summaries.get(index).getMinutesByBillCode(), code);
        return total;
    }

    private static int value(Map<String, Integer> values, String key) {
        Integer value = values.get(key);
        return value == null ? 0 : value.intValue();
    }

    static BigDecimal percent(int numerator, int denominator) {
        if (denominator <= 0)
            return BigDecimal.ZERO.setScale(2);
        return new BigDecimal(numerator).multiply(new BigDecimal("100"))
                .divide(new BigDecimal(denominator), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal basisPoints(Integer value) {
        return value == null ? null
                : new BigDecimal(value.intValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }

    private static Date toDate(LocalDate date, ZoneId zoneId) {
        return Date.from(date.atStartOfDay(zoneId).toInstant());
    }
}