package org.openimmunizationsoftware.pt.mobile.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.dandeliondaily.dashboard.service.ActionSentenceImportService;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.ProjectActionBlockerManager;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectContactAssigned;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.openimmunizationsoftware.pt.servlet.ProjectServlet;

/**
 * Mobile Action page placeholder.
 * 
 * @author nathan
 */
public class ActionServlet extends MobileBaseServlet {

    private static final String SAVE_PROJECT_ACTION_FORM = "saveProjectActionForm";
    private static final String PARAM_START_SENTANCE = "startSentance";
    private static final String PARAM_NEXT_CONTACT_ID = "nextContactId";
    private static final String PARAM_NEXT_ACTION_TYPE = "nextActionType";
    private static final String PARAM_LINK_URL = "linkUrl";
    private static final String PARAM_NEXT_ACTION_DATE = "nextActionDate";
    private static final String PARAM_TIME_SLOT = "timeSlotString";
    private static final String PARAM_NEXT_DESCRIPTION = "nextDescription";
    private static final String PARAM_NEXT_NOTE = "nextNote";
    private static final String PARAM_NEXT_PROJECT_ID = "nextProjectId";
    private static final String PARAM_PROJECT_ID = "projectId";
    private static final String PARAM_EDIT_ACTION_NEXT_ID = "editActionNextId";
    private static final String PARAM_ACTION_NEXT_ID = "actionNextId";
    private static final String PARAM_VIEW_ACTION_ID = "viewActionId";
    private static final String PARAM_DATE = "date";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_BLOCKING_DESCRIPTION = "blockingDescription";
    private static final String PARAM_NEXT_SENTENCE = "nextSentence";

    private static final String ACTION_SAVE = "Save";
    private static final String ACTION_START = "Start";
    private static final String ACTION_DELETE = "Delete";
    private static final String ACTION_COMPLETE = "complete";
    private static final String ACTION_TOMORROW = "tomorrow";
    private static final String ACTION_SCHEDULE_AND_BLOCK = "scheduleAndBlock";
    private static final String ACTION_COMPLETE_AND_NEXT = "completeAndNext";

    private static final String[] ACTION_SENTENCE_VERBS = { "I will", "I might", "I am waiting",
            "I will meet", "I would like to", "I have committed", "I have set goal to" };

    private final ActionSentenceImportService actionSentenceImportService = new ActionSentenceImportService();
    private final ActionNotesService actionNotesService = new ActionNotesService();

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            if (appReq.isLoggedOut()) {
                forwardToHome(request, response);
                return;
            }

            WebUser webUser = appReq.getWebUser();
            Session dataSession = appReq.getDataSession();
            Integer workspaceId = appReq.getActiveWorkspaceId();
            String action = request.getParameter(PARAM_ACTION);

            Integer viewActionId = parseInteger(request.getParameter(PARAM_VIEW_ACTION_ID));
            if (viewActionId != null) {
                processSummaryView(appReq, webUser, dataSession, action, viewActionId);
                return;
            }

            List<Project> projectList = getProjectList(webUser, dataSession);
            ActionNext editProjectAction = readEditProjectAction(appReq);
            Project project = resolveProject(request, dataSession, projectList, editProjectAction);
            if (project != null && (project.getWorkspaceId() == null || workspaceId == null
                    || !workspaceId.equals(project.getWorkspaceId()))) {
                project = null;
                editProjectAction = null;
            }

            if (action != null) {
                if (action.equals(ACTION_SAVE) || action.equals(ACTION_START)) {
                    Project nextProject = project;
                    if (editProjectAction == null) {
                        String nextProjectIdString = request.getParameter(PARAM_NEXT_PROJECT_ID);
                        if (nextProjectIdString != null) {
                            nextProject = (Project) dataSession.get(Project.class,
                                    Integer.parseInt(nextProjectIdString));
                        }
                    }
                    String meetingError = LinkedMeetingService.actionTypeError(
                            dataSession, editProjectAction, request.getParameter(PARAM_NEXT_ACTION_TYPE));
                    if (meetingError != null) {
                        appReq.setMessageProblem(meetingError);
                    } else {
                        editProjectAction = saveProjectAction(appReq, editProjectAction, nextProject);
                        project = editProjectAction.getProject();
                        response.sendRedirect(buildTodoRedirectUrl(request));
                        return;
                    }
                } else if (action.equals(ACTION_DELETE) && editProjectAction != null && project != null) {
                    closeAction(appReq, editProjectAction, project, "", ProjectNextActionStatus.CANCELLED);
                    appReq.setMessageConfirmation("Action deleted");
                    editProjectAction = null;
                }
            }

            List<ProjectContact> projectContactList = Collections.emptyList();
            if (project != null) {
                List<ProjectContactAssigned> projectContactAssignedList = ProjectServlet
                        .getProjectContactAssignedList(dataSession, project.getProjectId());
                projectContactList = ProjectServlet.getProjectContactList(dataSession, project,
                        projectContactAssignedList);
            }

            appReq.setTitle("Action");
            printHtmlHead(appReq, "Action");
            PrintWriter out = appReq.getOut();
            out.println("<h1>Action</h1>");

            if (project == null) {
                out.println("<p class=\"fail\">No active projects found.</p>");
            } else {
                String formName = editProjectAction != null ? String.valueOf(editProjectAction.getActionNextId()) : "0";
                printEditProjectActionForm(appReq, editProjectAction, projectContactList, formName, project,
                        projectList);
            }

            printHtmlFoot(appReq);
        } catch (Exception e) {
            handleUnexpectedError(response, e);
        } finally {
            appReq.close();
        }
    }

    private void processSummaryView(AppReq appReq, WebUser webUser, Session dataSession,
            String action, Integer viewActionId) throws IOException {
        HttpServletRequest request = appReq.getRequest();
        HttpServletResponse response = appReq.getResponse();

        ActionNext projectAction = (ActionNext) dataSession.get(ActionNext.class, viewActionId);

        if (projectAction == null) {
            appReq.setTitle("Action");
            printHtmlHead(appReq, "Action");
            appReq.getOut().println("<p class=\"fail\">Action not found</p>");
            printHtmlFoot(appReq);
            return;
        }
        if (projectAction.getWorkspaceId() == null || appReq.getActiveWorkspaceId() == null
                || !appReq.getActiveWorkspaceId().equals(projectAction.getWorkspaceId())) {
            appReq.setTitle("Action");
            printHtmlHead(appReq, "Action");
            appReq.getOut().println("<p class=\"fail\">Action not found</p>");
            printHtmlFoot(appReq);
            return;
        }

        String nextNote = request.getParameter(PARAM_NEXT_NOTE);
        if (nextNote != null && nextNote.trim().length() > 0) {
            Transaction trans = dataSession.beginTransaction();
            try {
                ActionNotesService.Result notesResult = actionNotesService.append(dataSession, projectAction,
                        nextNote, new Date());
                if (notesResult.isOk()) {
                    trans.commit();
                } else {
                    trans.rollback();
                    appReq.setMessageProblem(notesResult.getErrorMessage());
                }
            } catch (Exception e) {
                trans.rollback();
                throw e;
            }
        }

        if (ACTION_COMPLETE.equals(action)) {
            completeAction(projectAction, dataSession, webUser);
            response.sendRedirect(buildTodoRedirectUrl(request));
            return;
        }
        if (ACTION_COMPLETE_AND_NEXT.equals(action)) {
            completeAndScheduleNext(projectAction, request.getParameter(PARAM_NEXT_SENTENCE),
                    dataSession, webUser);
            // Land on today rather than the completed action's (possibly overdue) date.
            response.sendRedirect("todo");
            return;
        }
        if (ACTION_SCHEDULE_AND_BLOCK.equals(action)) {
            String blockingDescription = request.getParameter(PARAM_BLOCKING_DESCRIPTION);
            scheduleAndBlock(projectAction, blockingDescription, dataSession, webUser);
            response.sendRedirect(buildActionViewRedirectUrl(request, projectAction));
            return;
        }
        if (ACTION_TOMORROW.equals(action)) {
            postponeToTomorrow(projectAction, dataSession, webUser);
            response.sendRedirect(buildTodoRedirectUrl(request));
            return;
        }

        appReq.setTitle("Action");
        printHtmlHead(appReq, "Action");
        printActionSummary(appReq, projectAction, webUser);
        printHtmlFoot(appReq);
    }

    private void printActionSummary(AppReq appReq, ActionNext action, WebUser webUser) {
        PrintWriter out = appReq.getOut();

        Date today = webUser.getToday();
        String title = formatTitle(action.getNextActionDate(), today, webUser);

        out.println("<style>");
        out.println(".mv-form{max-width:500px;margin:0 auto;padding:0 12px 24px;}");
        out.println(".mv-date{font-size:.85em;color:#777;margin:8px 0 4px;}");
        out.println(".mv-project{font-size:.9em;color:#555;margin-bottom:6px;}");
        out.println(".mv-title{font-size:1.3em;font-weight:bold;margin:0 0 14px;line-height:1.3;}");
        out.println(
                ".mv-section{margin:16px 0 6px;font-size:.78em;font-weight:600;color:#666;text-transform:uppercase;letter-spacing:.05em;}");
        out.println(".mv-notes{background:#f5f5f5;border-radius:6px;padding:8px 12px;font-size:.95em;}");
        out.println(
                ".mv-input{width:100%;box-sizing:border-box;padding:10px;font-size:1em;border:1px solid #ccc;border-radius:6px;}");
        out.println(".mv-btn-row{display:flex;gap:8px;margin-top:8px;}");
        out.println(
                ".mv-btn{flex:1;padding:14px 8px;font-size:.95em;font-weight:bold;border:none;border-radius:8px;cursor:pointer;text-align:center;text-decoration:none;display:inline-block;color:#fff;}");
        out.println(".mv-complete{background:#2c7a2c;} .mv-postpone{background:#1a5276;} .mv-edit{background:#666;}");
        out.println(".mv-note{background:#444;} .mv-block{background:#7d4c00;} .mv-nav{background:#555;}");
        out.println(".mv-next{background:#2c7a2c;}");
        out.println(
                ".mv-chip-row{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px;}");
        out.println(
                ".mv-chip{padding:8px 10px;font-size:.85em;border:1px solid #ccc;border-radius:14px;background:#f5f5f5;color:#333;cursor:pointer;}");
        out.println("</style>");

        String projectName = action.getProject() != null
                ? getProjectDisplayName(appReq.getDataSession(), action.getProject())
                : "";
        String projectIcon = action.getProject() != null ? action.getProject().getProjectIcon() : "";
        String description = action.getNextDescriptionForDisplay(action.getContact());

        out.println("<div class=\"mv-form\">");

        out.println("<div class=\"mv-date\">" + title + "</div>");
        if (!projectName.isEmpty()) {
            String iconPrefix = (projectIcon != null && !projectIcon.isEmpty()) ? escapeHtml(projectIcon) + " " : "";
            out.println("<div class=\"mv-project\">" + iconPrefix
                    + "<a href=\"project?projectId=" + action.getProjectId()
                    + "\" style=\"text-decoration:none;color:#555;\">" + escapeHtml(projectName) + "</a></div>");
        }
        out.println("<div class=\"mv-title\">" + (description == null ? "" : description) + "</div>");

        if (action.getLinkUrl() != null && !action.getLinkUrl().isEmpty()) {
            out.println("<p><a href=\"" + escapeHtml(action.getLinkUrl()) + "\" target=\"_blank\">Open Link</a></p>");
        }

        List<String> noteLines = actionNotesService.displayLines(action);
        if (!noteLines.isEmpty()) {
            out.println("<div class=\"mv-section\">Notes</div>");
            out.println("<div class=\"mv-notes\">" + convertToHtmlList(noteLines) + "</div>");
        }

        String dateParam = action.getNextActionDate() != null
                ? webUser.getDateFormatService().formatTransportDate(action.getNextActionDate(), webUser.getTimeZone())
                : "";

        out.println("<div class=\"mv-section\">Actions</div>");
        out.println("<div class=\"mv-btn-row\">");
        String completeUrl = "action?" + PARAM_VIEW_ACTION_ID + "=" + action.getActionNextId()
                + "&" + PARAM_ACTION + "=" + ACTION_COMPLETE;
        if (!dateParam.isEmpty())
            completeUrl += "&" + PARAM_DATE + "=" + dateParam;
        out.println("  <a href=\"" + completeUrl + "\" class=\"mv-btn mv-complete\">&#10004; Complete</a>");
        String postponeUrl = "action?" + PARAM_VIEW_ACTION_ID + "=" + action.getActionNextId()
                + "&" + PARAM_ACTION + "=" + ACTION_TOMORROW;
        if (!dateParam.isEmpty())
            postponeUrl += "&" + PARAM_DATE + "=" + dateParam;
        out.println("  <a href=\"" + postponeUrl + "\" class=\"mv-btn mv-postpone\">&#8594; Postpone</a>");
        out.println("  <a href=\"action?" + PARAM_ACTION_NEXT_ID + "=" + action.getActionNextId()
                + "\" class=\"mv-btn mv-edit\">&#9998; Edit</a>");
        out.println("</div>");

        out.println("<div class=\"mv-section\">Add Note</div>");
        out.println("<form method=\"post\" action=\"action\">");
        out.println("  <input type=\"hidden\" name=\"" + PARAM_VIEW_ACTION_ID + "\" value=\""
                + action.getActionNextId() + "\" />");
        if (!dateParam.isEmpty()) {
            out.println("  <input type=\"hidden\" name=\"" + PARAM_DATE + "\" value=\"" + dateParam + "\" />");
        }
        out.println("  <textarea name=\"" + PARAM_NEXT_NOTE + "\" rows=\"3\" class=\"mv-input\"></textarea>");
        out.println("  <div class=\"mv-btn-row\">");
        out.println("    <button type=\"submit\" class=\"mv-btn mv-note\">Save Note</button>");
        out.println("  </div>");
        out.println("</form>");

        out.println("<div class=\"mv-section\">Blocking Action</div>");
        out.println("<form method=\"post\" action=\"action\">");
        out.println("  <input type=\"hidden\" name=\"" + PARAM_VIEW_ACTION_ID + "\" value=\""
                + action.getActionNextId() + "\" />");
        if (!dateParam.isEmpty()) {
            out.println("  <input type=\"hidden\" name=\"" + PARAM_DATE + "\" value=\"" + dateParam + "\" />");
        }
        out.println("  <input type=\"text\" name=\"" + PARAM_BLOCKING_DESCRIPTION
                + "\" class=\"mv-input\" placeholder=\"Describe blocking action\""
                + " autocapitalize=\"none\" autocorrect=\"off\" />");
        out.println("  <div class=\"mv-btn-row\">");
        out.println("    <button type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\""
                + ACTION_SCHEDULE_AND_BLOCK + "\" class=\"mv-btn mv-block\">Schedule &amp; Block</button>");
        out.println("  </div>");
        out.println("</form>");

        printCompleteAndNextForm(out, action, dateParam);

        out.println("<div class=\"mv-section\">Navigation</div>");
        out.println("<div class=\"mv-btn-row\">");
        String todoUrl = "todo";
        if (!dateParam.isEmpty())
            todoUrl += "?" + PARAM_DATE + "=" + dateParam;
        out.println("  <a href=\"" + todoUrl + "\" class=\"mv-btn mv-nav\">&#8592; Todo</a>");
        if (action.getProject() != null) {
            out.println("  <a href=\"project?projectId=" + action.getProject().getProjectId()
                    + "\" class=\"mv-btn mv-nav\">" + escapeHtml(projectName) + "</a>");
        }
        out.println("</div>");

        out.println("</div>");
    }

    private String formatTitle(Date selectedDate, Date today, WebUser webUser) {
        if (selectedDate == null) {
            return "Action";
        }
        if (isSameDay(selectedDate, today, webUser)) {
            return "Action Today";
        }

        Calendar cal = webUser.getCalendar();
        cal.setTime(selectedDate);
        Calendar todayCal = webUser.getCalendar();
        todayCal.setTime(today);

        long daysDiff = (cal.getTimeInMillis() - todayCal.getTimeInMillis()) / (1000 * 60 * 60 * 24);

        if (daysDiff >= 1 && daysDiff <= 7) {
            return "Action " + webUser.getDateFormatService().formatWeekdayLong(selectedDate, webUser.getTimeZone());
        } else if (daysDiff >= 8 && daysDiff <= 14) {
            return "Action Next "
                    + webUser.getDateFormatService().formatWeekdayLong(selectedDate, webUser.getTimeZone());
        } else {
            return "Action " + webUser.getDateFormatService().formatDate(selectedDate, webUser.getTimeZone());
        }
    }

    private boolean isSameDay(Date date1, Date date2, WebUser webUser) {
        if (date1 == null || date2 == null) {
            return false;
        }
        Calendar cal1 = webUser.getCalendar();
        cal1.setTime(date1);
        Calendar cal2 = webUser.getCalendar();
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
                && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    private String buildTodoRedirectUrl(HttpServletRequest request) {
        String dateParam = request.getParameter(PARAM_DATE);
        if (dateParam != null && !dateParam.isEmpty()) {
            return "todo?" + PARAM_DATE + "=" + dateParam;
        }
        return "todo";
    }

    /**
     * Renders the Complete &amp; Next box. Deliberately avoids the desktop
     * typeahead: the verb chips are plain buttons so nothing intercepts the
     * phone keyboard's Enter/Go key.
     */
    private void printCompleteAndNextForm(PrintWriter out, ActionNext action, String dateParam) {
        out.println("<div class=\"mv-section\">Complete &amp; Next</div>");
        out.println("<form method=\"post\" action=\"action\">");
        out.println("  <input type=\"hidden\" name=\"" + PARAM_VIEW_ACTION_ID + "\" value=\""
                + action.getActionNextId() + "\" />");
        if (!dateParam.isEmpty()) {
            out.println("  <input type=\"hidden\" name=\"" + PARAM_DATE + "\" value=\"" + dateParam + "\" />");
        }
        out.println("  <div class=\"mv-chip-row\">");
        for (String verb : ACTION_SENTENCE_VERBS) {
            out.println("    <button type=\"button\" class=\"mv-chip\" onclick=\"mvSetNextVerb('"
                    + verb + "')\">" + escapeHtml(verb) + "</button>");
        }
        out.println("  </div>");
        out.println("  <input type=\"text\" id=\"mvNextSentence\" name=\"" + PARAM_NEXT_SENTENCE
                + "\" class=\"mv-input\" value=\"I will \""
                + " placeholder=\"I will go to the store tomorrow\""
                + " autocomplete=\"off\" autocapitalize=\"sentences\" autocorrect=\"off\""
                + " spellcheck=\"false\" enterkeyhint=\"go\" />");
        out.println("  <div class=\"mv-btn-row\">");
        out.println("    <button type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\""
                + ACTION_COMPLETE_AND_NEXT + "\" class=\"mv-btn mv-next\">&#10004; Complete &amp; Next</button>");
        out.println("  </div>");
        out.println("</form>");
        out.println("<script>");
        out.println("function mvSetNextVerb(verb){");
        out.println("  var input = document.getElementById('mvNextSentence');");
        out.println("  if (!input) { return; }");
        out.println("  input.value = verb + ' ';");
        out.println("  input.focus();");
        out.println("}");
        out.println("</script>");
    }

    /**
     * Completes the action and, when the sentence carries more than a bare
     * verb, schedules the follow-up it describes.
     */
    private void completeAndScheduleNext(ActionNext projectAction, String nextSentence,
            Session dataSession, WebUser webUser) {
        if (hasActionSentenceContent(nextSentence)) {
            actionSentenceImportService.saveNewActionFromSentence(webUser, dataSession,
                    projectAction.getProject(), getAllProjectList(webUser, dataSession), nextSentence.trim());
        }
        completeAction(projectAction, dataSession, webUser);
    }

    private boolean hasActionSentenceContent(String nextSentence) {
        if (nextSentence == null) {
            return false;
        }
        String remainder = nextSentence.trim();
        if (remainder.isEmpty()) {
            return false;
        }
        for (String verb : ACTION_SENTENCE_VERBS) {
            if (remainder.equalsIgnoreCase(verb)) {
                return false;
            }
        }
        return true;
    }

    private List<Project> getAllProjectList(WebUser webUser, Session dataSession) {
        Integer workspaceId = org.openimmunizationsoftware.pt.WorkspaceRegistry
                .getWorkspaceIdForWebUserId(webUser.getWebUserId());
        Query query = dataSession.createQuery("from Project where workspaceId = :workspaceId"
                + " and (projectStatus is null or projectStatus <> :closedStatus)"
                + " order by projectName");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("closedStatus", ProjectStatus.CLOSED.getDatabaseValue());
        @SuppressWarnings("unchecked")
        List<Project> allProjects = query.list();
        return allProjects;
    }

    private String buildActionViewRedirectUrl(HttpServletRequest request, ActionNext action) {
        StringBuilder redirect = new StringBuilder();
        redirect.append("action?").append(PARAM_VIEW_ACTION_ID).append("=").append(action.getActionNextId());
        String dateParam = request.getParameter(PARAM_DATE);
        if (dateParam != null && !dateParam.isEmpty()) {
            redirect.append("&").append(PARAM_DATE).append("=").append(dateParam);
        }
        return redirect.toString();
    }

    private void scheduleAndBlock(ActionNext actionToBlock, String blockingDescription, Session dataSession,
            WebUser webUser) {
        if (actionToBlock == null || blockingDescription == null || blockingDescription.trim().isEmpty()) {
            return;
        }
        Transaction trans = dataSession.beginTransaction();
        try {
            Calendar calendar = webUser.getCalendar();
            calendar.setTime(new Date());
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            Date today = calendar.getTime();

            Project project = actionToBlock.getProject();
            if (project == null) {
                project = (Project) dataSession.get(Project.class, actionToBlock.getProjectId());
            }

            ActionNext blockingAction = new ActionNext();
            blockingAction.setProject(project);
            blockingAction.setProjectId(actionToBlock.getProjectId());
            blockingAction.setContact(webUser.getProjectContact());
            blockingAction.setContactId(webUser.getContactId());
            blockingAction.setWorkspaceId(actionToBlock.getWorkspaceId());
            blockingAction.setNextDescription(blockingDescription.trim());
            blockingAction.setNextActionDate(today);
            blockingAction.setNextActionStatus(ProjectNextActionStatus.READY);
            blockingAction.setNextActionType(ProjectNextActionType.WILL);
            blockingAction.setBillable(resolveBillable(dataSession, project));
            blockingAction.setNextChangeDate(new Date());
            blockingAction.setActionSet(new ActionSetDao(dataSession).createStandardActionSet(webUser));
            dataSession.saveOrUpdate(blockingAction);

            actionToBlock.setBlockedBy(blockingAction);
            actionToBlock.setNextActionDate(null);
            actionToBlock.setNextChangeDate(new Date());
            dataSession.update(actionToBlock);

            trans.commit();
        } catch (Exception e) {
            trans.rollback();
            throw e;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.length() == 0) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private static String convertToHtmlList(List<String> lines) {
        StringBuilder html = new StringBuilder("<ul>\n");
        for (String line : lines) {
            String normalized = line == null ? "" : line.trim();
            if (normalized.length() > 0) {
                html.append("  <li>").append(escapeHtmlStatic(normalized)).append("</li>\n");
            }
        }

        html.append("</ul>");
        return html.toString();
    }

    private static String escapeHtmlStatic(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void handleUnexpectedError(HttpServletResponse response, Exception e) throws IOException {
        e.printStackTrace();
        if (!response.isCommitted()) {
            response.sendRedirect("oops");
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

    private Project resolveProject(HttpServletRequest request, Session dataSession, List<Project> projectList,
            ActionNext editProjectAction) {
        if (editProjectAction != null && editProjectAction.getProject() != null) {
            return editProjectAction.getProject();
        }

        String projectIdString = request.getParameter(PARAM_PROJECT_ID);
        if (projectIdString == null) {
            projectIdString = request.getParameter(PARAM_NEXT_PROJECT_ID);
        }
        if (projectIdString != null) {
            return (Project) dataSession.get(Project.class, Integer.parseInt(projectIdString));
        }

        if (projectList.isEmpty()) {
            return null;
        }
        return projectList.get(0);
    }

    private ActionNext readEditProjectAction(AppReq appReq) {
        Session dataSession = appReq.getDataSession();
        HttpServletRequest request = appReq.getRequest();
        String actionNextIdString = request.getParameter(PARAM_EDIT_ACTION_NEXT_ID);
        if (actionNextIdString == null) {
            actionNextIdString = request.getParameter(PARAM_ACTION_NEXT_ID);
        }
        ActionNext editProjectAction = null;
        if (actionNextIdString != null) {
            editProjectAction = (ActionNext) dataSession.get(ActionNext.class,
                    Integer.parseInt(actionNextIdString));
            if (editProjectAction != null && (editProjectAction.getWorkspaceId() == null
                    || appReq.getActiveWorkspaceId() == null
                    || !appReq.getActiveWorkspaceId().equals(editProjectAction.getWorkspaceId()))) {
                editProjectAction = null;
            }
        }
        return editProjectAction;
    }

    private ActionNext saveProjectAction(AppReq appReq,
            ActionNext editProjectAction, Project nextProject) {
        HttpServletRequest request = appReq.getRequest();
        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();
        boolean isNewAction = editProjectAction == null;
        if (isNewAction) {
            editProjectAction = new ActionNext();
            editProjectAction.setProject(nextProject);
            editProjectAction.setProjectId(nextProject.getProjectId());
            editProjectAction.setBillable(resolveBillable(dataSession, nextProject));
            editProjectAction.setActionSet(new ActionSetDao(dataSession).createStandardActionSet(webUser));
        }
        editProjectAction.setContactId(webUser.getContactId());
        editProjectAction.setContact(webUser.getProjectContact());
        editProjectAction.setNextDescription(trim(request.getParameter(PARAM_NEXT_DESCRIPTION), 1200));
        Date changeDate = new Date();
        editProjectAction.setNextChangeDate(changeDate);

        editProjectAction
                .setNextActionDate(appReq.getWebUser().parseDate(request.getParameter(PARAM_NEXT_ACTION_DATE)));
        String timeSlotString = request.getParameter(PARAM_TIME_SLOT);
        if (timeSlotString == null || timeSlotString.equals("")) {
            timeSlotString = TimeSlot.AFTERNOON.getId();
        }
        editProjectAction.setTimeSlotString(timeSlotString);
        String linkUrl = request.getParameter(PARAM_LINK_URL);
        if (linkUrl == null || linkUrl.equals("")) {
            editProjectAction.setLinkUrl(null);
        } else {
            editProjectAction.setLinkUrl(linkUrl);
        }

        String nextActionType = request.getParameter(PARAM_NEXT_ACTION_TYPE);
        editProjectAction.setNextActionType(nextActionType);
        int priorityLevel = editProjectAction.getProject().getPriorityLevel();
        editProjectAction.setPriorityLevel(priorityLevel);
        String nextContactIdString = request.getParameter(PARAM_NEXT_CONTACT_ID);
        if (nextContactIdString != null && nextContactIdString.length() > 0) {
            editProjectAction.setNextContactId(Integer.parseInt(nextContactIdString));
        }
        editProjectAction.setWorkspaceId(appReq.getActiveWorkspaceId());
        if (editProjectAction.hasNextDescription()
                && (editProjectAction.getNextActionStatus() == null
                        || editProjectAction.getNextActionStatus() == ProjectNextActionStatus.PROPOSED)) {
            // Mobile action entry is user-authored; no-date actions are still ready to
            // work.
            editProjectAction.setNextActionStatus(ProjectNextActionStatus.READY);
        }

        Transaction trans = dataSession.beginTransaction();
        dataSession.saveOrUpdate(editProjectAction);
        dataSession.flush();
        String nextNote = request.getParameter(PARAM_NEXT_NOTE);
        if (nextNote != null && nextNote.trim().length() > 0) {
            actionNotesService.append(dataSession, editProjectAction, nextNote, changeDate);
        }
        trans.commit();
        return editProjectAction;
    }

    private boolean resolveBillable(Session dataSession, Project project) {
        if (project == null || project.getBillCode() == null || project.getBillCode().equals("")) {
            return false;
        }
        BillCode billCode = resolveBillCode(dataSession, project);
        return billCode != null && "Y".equalsIgnoreCase(billCode.getBillable());
    }

    private void closeAction(AppReq appReq, ActionNext projectAction, Project project,
            String nextDescription, ProjectNextActionStatus nextActionStatus) {
        WebUser webUser = appReq.getWebUser();
        Session dataSession = appReq.getDataSession();
        Transaction trans = dataSession.beginTransaction();
        projectAction.setNextActionStatus(nextActionStatus);
        projectAction.setNextChangeDate(new Date());
        dataSession.update(projectAction);
        if (nextActionStatus == ProjectNextActionStatus.COMPLETED
                || nextActionStatus == ProjectNextActionStatus.CANCELLED) {
            ProjectActionBlockerManager.unblockActionsBlockedBy(dataSession, webUser, projectAction);
        }
        trans.commit();
    }

    private List<Project> getProjectList(WebUser webUser, Session dataSession) {
        Integer workspaceId = org.openimmunizationsoftware.pt.WorkspaceRegistry
                .getWorkspaceIdForWebUserId(webUser.getWebUserId());
        String queryString = "from Project where workspaceId = :workspaceId";
        queryString += " and (projectStatus is null or projectStatus <> :closedStatus)";
        queryString += " order by projectName";
        Query query = dataSession.createQuery(queryString);
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("closedStatus", ProjectStatus.CLOSED.getDatabaseValue());
        @SuppressWarnings("unchecked")
        List<Project> allProjects = query.list();
        List<Project> filteredProjects = new ArrayList<Project>();
        for (Project project : allProjects) {
            if (!resolveBillable(dataSession, project)) {
                filteredProjects.add(project);
            }
        }
        return filteredProjects;
    }

    private void printEditProjectActionForm(AppReq appReq, ActionNext editProjectAction,
            List<ProjectContact> projectContactList, String formName, Project project,
            List<Project> projectList) {
        WebUser webUser = appReq.getWebUser();
        PrintWriter out = appReq.getOut();
        out.println("<form name=\"projectAction" + formName
                + "\" method=\"post\" action=\"action\" id=\"" + SAVE_PROJECT_ACTION_FORM + formName
                + "\">");
        SimpleDateFormat sdf = webUser.getDateFormat();
        out.println(" <script>");
        out.println("    function selectProjectActionType" + formName + "(actionType)");
        out.println("    {");
        out.println("      var form = document.forms['saveProjectActionForm" + formName + "'];");
        out.println("      var label = makeIStatement" + formName
                + "(actionType, form.nextContactId.options[form.nextContactId.selectedIndex].text);");
        out.println("      form." + PARAM_START_SENTANCE + ".value = label;");
        out.println("      form.nextActionType.value = actionType;");
        out.println("      enableForm" + formName + "(); ");
        out.println("    }");
        out.println("    ");
        out.println("    function enableForm" + formName + "()");
        out.println("    {");
        out.println("      var form = document.forms['" + SAVE_PROJECT_ACTION_FORM + formName + "'];");
        out.println("      form." + PARAM_NEXT_ACTION_DATE + ".disabled = false;");
        out.println("      form." + PARAM_NEXT_DESCRIPTION + ".disabled = false;");
        out.println("      form." + PARAM_NEXT_CONTACT_ID + ".disabled = false;");
        out.println("      form." + PARAM_START_SENTANCE + ".disabled = false;");
        out.println("      form." + PARAM_LINK_URL + ".disabled = false;");
        out.println("      if (form." + PARAM_NEXT_ACTION_DATE + ".value == \"\")");
        out.println("      {");
        out.println("       document.projectAction" + formName + "." + PARAM_NEXT_ACTION_DATE + ".value = '"
                + sdf.format(new Date()) + "';");
        out.println("      }");
        out.println("    }");
        out.println("    function setNextAction" + formName + "(nextActionDate) {");
        out.println(
                "      document.projectAction" + formName + "." + PARAM_NEXT_ACTION_DATE + ".value = nextActionDate;");
        out.println("      enableForm" + formName + "(); ");
        out.println("    }");
        printMakeIStatementFunction(out, formName);
        out.println("  </script>");
        if (editProjectAction != null) {
            out.println("<input type=\"hidden\" name=\"" + PARAM_EDIT_ACTION_NEXT_ID + "\" value=\""
                    + editProjectAction.getActionNextId() + "\">");
        }
        printCurrentActionEdit(appReq, webUser, out, editProjectAction, project, projectContactList, formName,
                projectList);

        out.println("</form>");
    }

    private void printCurrentActionEdit(AppReq appReq, WebUser webUser, PrintWriter out,
            ActionNext projectAction,
            Project project,
            List<ProjectContact> projectContactList,
            String formName, List<Project> projectList) {

        out.println("<style>");
        out.println("  .mf-form{max-width:520px;padding:4px 0;}");
        out.println("  .mf-field{display:flex;flex-direction:column;margin-bottom:14px;}");
        out.println("  .mf-label{font-weight:bold;font-size:13px;color:#444;margin-bottom:5px;}");
        out.println(
                "  .mf-input{width:100%;box-sizing:border-box;font-size:16px;padding:8px 10px;border:1px solid #999;border-radius:4px;}");
        out.println("  .mf-btn-row{display:flex;flex-wrap:wrap;gap:6px;margin-top:4px;}");
        out.println(
                "  .mf-type-btn{display:inline-block;padding:8px 14px;background:#f0f0f0;border:1px solid #bbb;border-radius:20px;font-size:14px;cursor:pointer;text-decoration:none;color:#222;}");
        out.println("  .mf-type-btn:active,.mf-type-btn:hover{background:#d0d8f0;border-color:#7788cc;}");
        out.println("  .mf-radio-row{display:flex;flex-wrap:wrap;gap:14px;margin-top:4px;}");
        out.println("  .mf-radio-label{display:flex;align-items:center;gap:6px;font-size:15px;cursor:pointer;}");
        out.println("  .mf-radio-label input[type=radio]{width:18px;height:18px;flex-shrink:0;}");
        out.println("  .mf-proj-row{flex-direction:column;gap:0;}");
        out.println("  .mf-proj-label{padding:10px 12px;border:1px solid #ddd;border-bottom:none;font-size:17px;}");
        out.println("  .mf-proj-label:first-child{border-radius:6px 6px 0 0;}");
        out.println("  .mf-proj-label:last-child{border-bottom:1px solid #ddd;border-radius:0 0 6px 6px;}");
        out.println("  .mf-proj-label:has(input:checked){background:#e8f0e8;border-color:#5a8a5a;font-weight:bold;}");
        out.println("  .mf-quickrow{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px;}");
        out.println(
                "  .mf-quick{display:inline-block;padding:6px 10px;background:#e8e8e8;border:1px solid #bbb;border-radius:4px;font-size:13px;text-decoration:none;color:#222;}");
        out.println("  .mf-quick:hover{background:#d4d4d4;}");
        out.println("  .mf-submit-row{display:flex;gap:10px;margin-top:18px;}");
        out.println(
                "  .mf-submit{flex:1;padding:12px;font-size:16px;font-weight:bold;border:none;border-radius:6px;background:#3a6b3a;color:#fff;cursor:pointer;}");
        out.println("  .mf-submit-start{background:#1e5080;}");
        out.println("  .mf-submit-delete{background:#8b2020;}");
        out.println("</style>");
        out.println("<div class=\"mf-form\">");
        printEditNextAction(appReq.getRequest(), webUser, appReq.getDataSession(), out, projectAction, project,
                formName, "",
                projectContactList, projectList);
        out.println("  <div class=\"mf-submit-row\">");
        out.println("    <button class=\"mf-submit\" type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\""
                + ACTION_SAVE + "\">Save</button>");
        if (projectAction != null) {
            out.println("    <button class=\"mf-submit mf-submit-delete\" type=\"submit\" name=\"" + PARAM_ACTION
                    + "\" value=\"" + ACTION_DELETE + "\">Delete</button>");
        }
        out.println("  </div>");
        out.println("</div>");

    }

    private void printEditNextAction(HttpServletRequest request, WebUser webUser, Session dataSession, PrintWriter out,
            ActionNext projectAction, Project project, String formName, String disabled,
            List<ProjectContact> projectContactList,
            List<Project> projectList) {
        SimpleDateFormat sdf2 = webUser.getDateFormat();
        SimpleDateFormat day = webUser.getDateFormat("EEE");

        // Project
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Project</span>");
        if (projectAction == null) {
            out.println("    <div class=\"mf-radio-row mf-proj-row\">");
            for (Project p : projectList) {
                boolean selected = project != null && project.getProjectId() == p.getProjectId();
                String icon = p.getProjectIcon();
                String iconHtml = (icon != null && !icon.trim().isEmpty()) ? icon.trim() + " " : "";
                String displayName = getProjectDisplayName(dataSession, p);
                out.println("      <label class=\"mf-radio-label mf-proj-label\" onclick=\"enableForm" + formName
                        + "()\">"
                        + "<input type=\"radio\" name=\"" + PARAM_NEXT_PROJECT_ID + "\" value=\"" + p.getProjectId()
                        + "\""
                        + (selected ? " checked" : "") + "> " + iconHtml + escapeHtml(displayName) + "</label>");
            }
            out.println("    </div>");
        } else {
            String icon = project.getProjectIcon();
            String iconHtml = (icon != null && !icon.trim().isEmpty()) ? icon.trim() + " " : "";
            out.println("    <span>" + iconHtml + escapeHtml(getProjectDisplayName(dataSession, project)) + "</span>");
            out.println("    <input type=\"hidden\" name=\"" + PARAM_NEXT_PROJECT_ID + "\" value=\""
                    + projectAction.getProjectId() + "\">");
        }
        out.println("  </div>");

        // When
        String nextActionDateString = projectAction == null || projectAction.getNextActionDate() == null
                ? request.getParameter(PARAM_NEXT_ACTION_DATE)
                : sdf2.format(projectAction.getNextActionDate());
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">When</span>");
        out.println("    <input class=\"mf-input\" type=\"text\" name=\"" + PARAM_NEXT_ACTION_DATE
                + "\" value=\"" + n(nextActionDateString) + "\"" + disabled + ">");
        out.println("    <div class=\"mf-quickrow\">");
        Calendar calendar = webUser.getCalendar();
        out.println("      <a class=\"mf-quick\" href=\"javascript:void setNextAction" + formName + "('"
                + sdf2.format(calendar.getTime()) + "')\">Today</a>");
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        out.println("      <a class=\"mf-quick\" href=\"javascript:void setNextAction" + formName + "('"
                + sdf2.format(calendar.getTime()) + "')\">" + day.format(calendar.getTime()) + "</a>");
        boolean nextWeek = false;
        for (int i = 0; i < 6; i++) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
            String prefix = nextWeek ? "Next-" : "";
            out.println("      <a class=\"mf-quick\" href=\"javascript:void setNextAction" + formName + "('"
                    + sdf2.format(calendar.getTime()) + "')\">" + prefix + day.format(calendar.getTime()) + "</a>");
            if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                nextWeek = true;
            }
        }
        out.println("    </div>");
        out.println("  </div>");

        // Time Slot
        String timeSlotString = projectAction == null ? request.getParameter(PARAM_TIME_SLOT)
                : projectAction.getTimeSlotString();
        if (timeSlotString == null || timeSlotString.equals("")) {
            timeSlotString = TimeSlot.AFTERNOON.getId();
        }
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Time Slot</span>");
        out.println("    <div class=\"mf-radio-row mf-proj-row\">");
        out.println("      <label class=\"mf-radio-label mf-proj-label\"><input type=\"radio\" name=\""
                + PARAM_TIME_SLOT
                + "\" value=\"" + TimeSlot.WAKE.getId() + "\""
                + (TimeSlot.WAKE.getId().equals(timeSlotString) ? " checked" : "") + disabled + "> Wake</label>");
        out.println("      <label class=\"mf-radio-label mf-proj-label\"><input type=\"radio\" name=\""
                + PARAM_TIME_SLOT
                + "\" value=\"" + TimeSlot.MORNING.getId() + "\""
                + (TimeSlot.MORNING.getId().equals(timeSlotString) ? " checked" : "") + disabled + "> Morning</label>");
        out.println(
                "      <label class=\"mf-radio-label mf-proj-label\"><input type=\"radio\" name=\"" + PARAM_TIME_SLOT
                        + "\" value=\"" + TimeSlot.AFTERNOON.getId() + "\""
                        + (TimeSlot.AFTERNOON.getId().equals(timeSlotString) ? " checked" : "") + disabled
                        + "> Afternoon</label>");
        out.println("      <label class=\"mf-radio-label mf-proj-label\"><input type=\"radio\" name=\""
                + PARAM_TIME_SLOT
                + "\" value=\"" + TimeSlot.EVENING.getId() + "\""
                + (TimeSlot.EVENING.getId().equals(timeSlotString) ? " checked" : "") + disabled + "> Evening</label>");
        out.println("    </div>");
        out.println("  </div>");

        // Action Type
        String nextActionType = projectAction == null ? ProjectNextActionType.WILL
                : projectAction.getNextActionType();
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Action Type</span>");
        out.println("    <div class=\"mf-btn-row\">");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WILL + "')\">will</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.MIGHT + "')\">might</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WOULD_LIKE_TO + "')\">would like to</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WILL_CONTACT + "')\">will contact</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WILL_MEET + "')\">will meet</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WILL_REVIEW + "')\">will review</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WILL_DOCUMENT + "')\">will document</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WILL_FOLLOW_UP + "')\">will follow up</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.COMMITTED_TO + "')\">committed</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.GOAL + "')\">set goal</a>");
        out.println("      <a class=\"mf-type-btn\" href=\"javascript:void selectProjectActionType" + formName + "('"
                + ProjectNextActionType.WAITING + "')\">waiting</a>");
        out.println("    </div>");
        out.println(
                "    <input type=\"hidden\" name=\"" + PARAM_NEXT_ACTION_TYPE + "\" value=\"" + nextActionType + "\">");
        out.println("    <script>");
        out.println("      window.addEventListener('load', function() { selectProjectActionType" + formName + "('"
                + nextActionType + "'); });");
        out.println("    </script>");
        out.println("  </div>");

        // What
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">What</span>");
        out.println("    <input class=\"mf-input\" name=\"" + PARAM_START_SENTANCE + "\" value=\"I will:\"" + disabled
                + ">");
        out.println("  </div>");

        // Who
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Who</span>");
        out.println("    <select class=\"mf-input\" name=\"nextContactId\" onchange=\"selectProjectActionType"
                + formName + "(form.nextActionType.value);\"" + disabled + ">");
        out.println("      <option value=\"\">none</option>");
        String nextContactId = n(request.getParameter(PARAM_NEXT_CONTACT_ID));
        for (ProjectContact projectContact1 : projectContactList) {
            if (projectContact1.getContactId() != webUser.getProjectContact().getContactId()) {
                Integer selectedContactId = projectAction == null ? Integer.valueOf(projectContact1.getContactId())
                        : projectAction.getContactId();
                boolean selected = selectedContactId != null
                        && nextContactId.equals(Integer.toString(selectedContactId));
                out.println("      <option value=\"" + projectContact1.getContactId() + "\""
                        + (selected ? " selected" : "") + ">" + projectContact1.getName() + "</option>");
            }
        }
        out.println("    </select>");
        out.println("  </div>");

        // Description
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Description</span>");
        out.println("    <textarea class=\"mf-input\" name=\"" + PARAM_NEXT_DESCRIPTION
                + "\" rows=\"2\" autocapitalize=\"none\" autocorrect=\"off\""
                + disabled + ">" + (projectAction == null ? "" : projectAction.getNextDescription()) + "</textarea>");
        out.println("  </div>");

        // Note
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Note</span>");
        out.println("    <textarea class=\"mf-input\" rows=\"3\" name=\"" + PARAM_NEXT_NOTE + "\"" + disabled
                + "></textarea>");
        out.println("  </div>");

        // Link URL
        out.println("  <div class=\"mf-field\">");
        out.println("    <span class=\"mf-label\">Link URL</span>");
        out.println("    <input class=\"mf-input\" type=\"text\" name=\"" + PARAM_LINK_URL + "\" value=\""
                + n(projectAction == null || projectAction.getLinkUrl() == null ? "" : projectAction.getLinkUrl())
                + "\"" + disabled + ">");
        out.println("  </div>");
    }

    private static void printMakeIStatementFunction(PrintWriter out, String formName) {
        out.println("    function makeIStatement" + formName + "(actionType, nextContactName)");
        out.println("    {");
        out.println("      if (nextContactName == 'none')");
        out.println("      {");
        out.println("        nextContactName = '';\n      }");
        out.println("      if (actionType == '" + ProjectNextActionType.WILL_CONTACT + "')");
        out.println("      {");
        out.println("        if (nextContactName == '')");
        out.println("        {");
        out.println("          return \"I will make contact about:\";");
        out.println("        } else");
        out.println("        {");
        out.println("          return \"I will contact \" + nextContactName + \" about:\";");
        out.println("        }");
        out.println("      } else if (actionType == '" + ProjectNextActionType.COMMITTED_TO + "')");
        out.println("      {");
        out.println("        if (nextContactName == '')");
        out.println("        {");
        out.println("          return \"I have committed to:\";");
        out.println("        } else");
        out.println("        {");
        out.println("          return \"I have committed to \" + nextContactName + \" to:\";");
        out.println("        }");
        out.println("      } else if (actionType == '" + ProjectNextActionType.GOAL + "')");
        out.println("      {");
        out.println("          if (nextContactName == '')");
        out.println("          {");
        out.println("            return \"I have set goal to:\";");
        out.println("          } else");
        out.println("          {");
        out.println("            return \"I have set goal with \" + nextContactName + \" to:\";");
        out.println("          }");
        out.println("      } else if (actionType == '" + ProjectNextActionType.WAITING + "')");
        out.println("      {");
        out.println("        if (nextContactName == '')");
        out.println("        {");
        out.println("          return \"I am waiting for:\";");
        out.println("        } else");
        out.println("        {");
        out.println("          return \"I am waiting for \" + nextContactName + \" to:\";");
        out.println("        }");
        out.println("      } else if (actionType == '" + ProjectNextActionType.WILL_FOLLOW_UP + "')");
        out.println("      {");
        out.println("        if (nextContactName == '')");
        out.println("        {");
        out.println("          return \"I will follow up:\";");
        out.println("        } else");
        out.println("        {");
        out.println("          return \"I will follow up with \" + nextContactName + \" to:\";");
        out.println("        }");
        out.println("      } else if (actionType == '" + ProjectNextActionType.MIGHT + "')");
        out.println("      {");
        out.println("          return \"I might:\";");
        out.println("      } else if (actionType == '" + ProjectNextActionType.WOULD_LIKE_TO + "')");
        out.println("      {");
        out.println("          return \"I would like to:\";");
        out.println("      } else if (actionType == '" + ProjectNextActionType.GOAL + "')");
        out.println("      {");
        out.println("          return \"I have a goal to:\";");
        out.println("      } else if (actionType == '" + ProjectNextActionType.WILL_REVIEW + "')");
        out.println("      {");
        out.println("          return \"I will review:\";");
        out.println("      } else if (actionType == '" + ProjectNextActionType.WILL_DOCUMENT + "')");
        out.println("      {");
        out.println("          return \"I will document:\";");
        out.println("      } else if (actionType == '" + ProjectNextActionType.WILL_MEET + "')");
        out.println("      {");
        out.println("        if (nextContactName == '')");
        out.println("        {");
        out.println("          return \"I will meet:\";");
        out.println("        } else");
        out.println("        {");
        out.println("          return \"I will meet with \" + nextContactName + \" to:\";");
        out.println("        }");
        out.println("      } else");
        out.println("      {");
        out.println("        return \"I will:\";");
        out.println("      }");
        out.println("    }");
        out.println("    ");
    }
}
