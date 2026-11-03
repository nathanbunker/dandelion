package org.dandeliondaily.dashboard.service;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.function.Function;

import org.dandeliondaily.outlook.service.PlanningOutlookService;
import org.dandeliondaily.outlook.service.PlanningOutlookService.OutlookResult;
import org.dandeliondaily.projectainote.service.ProjectAiNoteService;
import org.openimmunizationsoftware.pt.doa.ProjectIssueDao;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectAiNote;
import org.openimmunizationsoftware.pt.model.ProjectIssue;
import org.openimmunizationsoftware.pt.model.ProjectNarrative;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

public class ProjectDashboardAiContextService {

    private static final int MAX_RECENT_ACTION_TAKEN = 15;
    private static final int MAX_CURRENT_PLANNED_ACTION_NEXT = 20;
    private static final int MAX_OPEN_ISSUES = 20;
    private static final int MAX_RECENT_NARRATIVES = 20;
    static final int MAX_AI_THOUGHTS = 20;

    public String buildContextText(AppReq appReq, Project project) {
        StringBuilder sb = new StringBuilder();
        Session dataSession = appReq.getDataSession();
        WebUser webUser = appReq.getWebUser();

        sb.append("Project Context\n");
        sb.append("- Name: ").append(n(project.getProjectName())).append("\n");
        sb.append("- Handle: ").append(n(project.getProjectHandle())).append("\n");
        sb.append("- Status: ").append(n(project.getProjectStatus())).append("\n");
        sb.append("- Description: ").append(n(project.getDescription())).append("\n");
        sb.append("- Current Focus: ").append(n(project.getCurrentFocusText())).append("\n");
        sb.append("- Outcome: ").append(n(project.getOutcomeText())).append("\n");
        sb.append("- Success Criteria: ").append(n(project.getSuccessCriteriaText())).append("\n");

        List<String> tagNames = loadProjectTagNames(dataSession, project.getProjectId());
        sb.append("- Tags: ");
        if (tagNames.isEmpty()) {
            sb.append("(none)\n");
        } else {
            sb.append(join(tagNames, ", ")).append("\n");
        }

        sb.append("\nRecent Action Taken\n");
        List<ActionTaken> actionTakenList = loadRecentActionTaken(dataSession, project.getProjectId());
        if (actionTakenList.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (ActionTaken actionTaken : actionTakenList) {
                String dateLabel = actionTaken.getActionDate() == null ? ""
                        : webUser.getDateFormatService()
                                .formatPattern(actionTaken.getActionDate(),
                                        webUser.getDateDisplayPatternWithWeekdayShort(),
                                        webUser.getTimeZone());
                sb.append("- [").append(dateLabel).append("] ")
                        .append(n(actionTaken.getActionDescription()))
                        .append("\n");
                if (actionTaken.getActionNote() != null) {
                    sb.append("  Outcome: ").append(clipOneLine(actionTaken.getActionNote(), 400)).append("\n");
                }
            }
        }

        sb.append("\nCurrent/Planned Action Next\n");
        List<ActionNext> actionNextList = loadCurrentPlannedActionNext(dataSession, project.getProjectId());
        if (actionNextList.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (ActionNext actionNext : actionNextList) {
                String dateLabel = actionNext.getNextActionDate() == null ? "Unscheduled"
                        : webUser.getDateFormatService().formatPattern(actionNext.getNextActionDate(),
                                webUser.getDateDisplayPatternWithWeekdayShort(), webUser.getTimeZone());
                sb.append("- ")
                        .append("[").append(dateLabel).append("] ")
                        .append("[").append(n(actionNext.getNextActionType())).append("] ")
                        .append(n(actionNext.getNextDescription()))
                        .append(" (status: ").append(n(actionNext.getNextActionStatusString())).append(")")
                        .append("\n");
            }
        }

        sb.append("\nOpen Issues\n");
        List<ProjectIssue> openIssues = loadOpenIssues(dataSession, project);
        if (openIssues.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (ProjectIssue issue : openIssues) {
                sb.append("- [").append(issue.getIssueType() == null ? "UNKNOWN" : issue.getIssueType().name())
                        .append("] ")
                        .append(n(issue.getIssueText()))
                        .append("\n");
            }
        }

        sb.append("\nRecent Project Narratives\n");
        List<ProjectNarrative> narratives = loadRecentNarratives(dataSession, project.getProjectId());
        if (narratives.isEmpty()) {
            sb.append("- (none)\n");
        } else {
            for (ProjectNarrative narrative : narratives) {
                String dateLabel = narrative.getNarrativeDate() == null ? ""
                        : webUser.getDateFormatService().formatPattern(narrative.getNarrativeDate(),
                                webUser.getDateDisplayPatternWithWeekdayShort(), webUser.getTimeZone());
                sb.append("- [").append(dateLabel).append("] [")
                        .append(narrative.getNarrativeVerb() == null ? "NOTE" : narrative.getNarrativeVerb().name())
                        .append("] ").append(n(narrative.getNarrativeText()))
                        .append("\n");
            }
        }

        final WebUser user = webUser;
        appendAiThoughts(sb, new ProjectAiNoteService().list(dataSession, project.getProjectId()),
                new Function<Date, String>() {
                    @Override
                    public String apply(Date date) {
                        return user.getDateFormatService().formatPattern(date,
                                user.getDateDisplayPatternWithWeekdayShort(), user.getTimeZone());
                    }
                });

        PlanningOutlookService outlookService = new PlanningOutlookService();
        LocalDate today = webUser.getLocalDateToday();
        String month = PlanningOutlookService.PERIOD_TYPE_MONTH;
        String week = PlanningOutlookService.PERIOD_TYPE_WEEK;
        appendOutlooks(sb,
                outlookService.getOutlook(dataSession, webUser.getWebUserId(), month,
                        outlookService.periodStartFor(month, today), today),
                outlookService.getOutlook(dataSession, webUser.getWebUserId(), week,
                        outlookService.periodStartFor(week, today), today));

        return sb.toString();
    }

    /**
     * AI thoughts are the assistant's own notes from earlier sessions. They are
     * labeled so the model doesn't treat them as facts or as decisions the user made.
     */
    static void appendAiThoughts(StringBuilder sb, List<ProjectAiNote> notes, Function<Date, String> dateLabel) {
        sb.append("\nAI Thoughts (observations, questions, and ideas recorded by an AI assistant in earlier "
                + "sessions; not established facts or decisions the user made)\n");
        if (notes == null || notes.isEmpty()) {
            sb.append("- (none)\n");
            return;
        }
        int count = 0;
        for (ProjectAiNote note : notes) {
            if (count++ >= MAX_AI_THOUGHTS) {
                break;
            }
            Date when = note.getUpdatedAt() != null ? note.getUpdatedAt() : note.getCreatedAt();
            sb.append("- ");
            if (when != null) {
                sb.append("[").append(dateLabel.apply(when)).append("] ");
            }
            sb.append(note.getNoteText() == null ? "" : note.getNoteText().trim()).append("\n");
        }
    }

    /**
     * The user's current month and week outlooks. They span all projects, so the
     * model is told to use only what's relevant to this one.
     */
    static void appendOutlooks(StringBuilder sb, OutlookResult month, OutlookResult week) {
        sb.append("\nPlanning Outlook (the user's stated intent across all projects; use only what is relevant "
                + "to this project)\n");
        appendOutlook(sb, "This month (" + month.getPeriodStart() + " to " + month.getPeriodEnd() + ")", month);
        appendOutlook(sb, "This week (" + week.getPeriodStart() + " to " + week.getPeriodEnd() + ")", week);
    }

    private static void appendOutlook(StringBuilder sb, String label, OutlookResult result) {
        String text = result.getOutlook() == null ? null : result.getOutlook().getOutlookText();
        sb.append("- ").append(label).append(": ");
        if (text == null || text.trim().length() == 0) {
            sb.append("(none recorded)\n");
        } else {
            sb.append("\n").append(text.trim()).append("\n");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> loadProjectTagNames(Session dataSession, int projectId) {
        Query query = dataSession.createQuery(
                "select pt.tagName from ProjectTagMap ptm, ProjectTag pt "
                        + "where ptm.projectId = :projectId and pt.projectTagId = ptm.projectTagId "
                        + "order by pt.sortOrder, pt.tagName");
        query.setParameter("projectId", projectId);
        return query.list();
    }

    @SuppressWarnings("unchecked")
    private List<ActionTaken> loadRecentActionTaken(Session dataSession, int projectId) {
        Query query = dataSession.createQuery(
                "from ActionTaken where projectId = :projectId order by actionDate desc");
        query.setParameter("projectId", projectId);
        query.setMaxResults(MAX_RECENT_ACTION_TAKEN);
        return query.list();
    }

    @SuppressWarnings("unchecked")
    private List<ActionNext> loadCurrentPlannedActionNext(Session dataSession, int projectId) {
        Query query = dataSession.createQuery(
                "from ActionNext an where an.projectId = :projectId "
                        + "and (an.nextActionStatusString = :readyStatus or an.nextActionStatusString = :proposedStatus) "
                        + "order by an.nextActionDate asc, an.priorityLevel desc, an.nextChangeDate desc");
        query.setParameter("projectId", projectId);
        query.setParameter("readyStatus", ProjectNextActionStatus.READY.getId());
        query.setParameter("proposedStatus", ProjectNextActionStatus.PROPOSED.getId());
        query.setMaxResults(MAX_CURRENT_PLANNED_ACTION_NEXT);
        return query.list();
    }

    private List<ProjectIssue> loadOpenIssues(Session dataSession, Project project) {
        ProjectIssueDao projectIssueDao = new ProjectIssueDao(dataSession);
        List<ProjectIssue> all = projectIssueDao.listOpenIssuesForProject(project);
        if (all.size() <= MAX_OPEN_ISSUES) {
            return all;
        }
        return all.subList(0, MAX_OPEN_ISSUES);
    }

    @SuppressWarnings("unchecked")
    private List<ProjectNarrative> loadRecentNarratives(Session dataSession, int projectId) {
        Query query = dataSession.createQuery(
                "from ProjectNarrative where projectId = :projectId order by narrativeDate desc");
        query.setParameter("projectId", projectId);
        query.setMaxResults(MAX_RECENT_NARRATIVES);
        return query.list();
    }

    private String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(value.trim());
        }
        return sb.toString();
    }

    private String n(String value) {
        if (value == null || value.trim().length() == 0) {
            return "(empty)";
        }
        return value.trim();
    }

    /** Whitespace collapsed to single spaces, cut to {@code max} characters. */
    private static String clipOneLine(String value, int max) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
