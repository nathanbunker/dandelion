package org.openimmunizationsoftware.pt.mobile.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.personal.service.PersonalDayService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Mobile Todo landing page showing READY ActionNext items.
 * 
 * @author nathan
 */
public class TodoServlet extends MobileBaseServlet {

    private static final String PARAM_DATE = "date";
    private static final String PARAM_ACTION = "action";
    private static final String PARAM_ACTION_ID = "actionId";

    private static final String ACTION_COMPLETE = "complete";
    private static final String ACTION_CANCEL = "cancel";
    private static final String ACTION_TOMORROW = "tomorrow";
    private static final String ACTION_RESCHEDULE = "reschedule";

    private final PersonalDayService dayService = new PersonalDayService();

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

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            if (appReq.isLoggedOut()) {
                response.sendRedirect("../LoginServlet?uiMode=mobile");
                return;
            }

            WebUser webUser = appReq.getWebUser();
            Session dataSession = appReq.getDataSession();
            Integer workspaceId = appReq.getActiveWorkspaceId();

            // Handle action processing (Complete/Tomorrow) - works for both GET and POST
            String paramAction = request.getParameter(PARAM_ACTION);
            String actionIdStr = request.getParameter(PARAM_ACTION_ID);

            if (paramAction != null && actionIdStr != null) {
                try {
                    int actionId = Integer.parseInt(actionIdStr);
                    ActionNext projectAction = (ActionNext) dataSession.get(
                            ActionNext.class, actionId);

                    if (projectAction != null) {
                        if (ACTION_COMPLETE.equals(paramAction)) {
                            completeAction(projectAction, dataSession, webUser);
                        } else if (ACTION_CANCEL.equals(paramAction)) {
                            cancelAction(projectAction, dataSession, webUser);
                        } else if (ACTION_TOMORROW.equals(paramAction)) {
                            postponeToTomorrow(projectAction, dataSession, webUser);
                        } else if (ACTION_RESCHEDULE.equals(paramAction)) {
                            rescheduleAction(projectAction, request, webUser, dataSession);
                        }
                    }

                    // Redirect back to the todo page with the same date
                    String redirectUrl = buildRedirectUrl(request);
                    response.sendRedirect(redirectUrl);
                    return;
                } catch (Exception e) {
                    handleUnexpectedError(request, response, e);
                    return;
                }
            }

            // Get selected date (default to today)
            Date selectedDate = getSelectedDate(request, webUser);
            Date today = TimeTracker.createToday(webUser).getTime();
            String selectedDateKey = toUserDateKey(selectedDate, webUser);
            String todayDateKey = toUserDateKey(today, webUser);

            // Fetch READY actions for the selected date using UTC-based key for database
            // query
            String selectedDateKeyUtc = toDatabaseDateKey(selectedDate);
            List<ActionNext> allActions = dayService.fetchReadyActions(dataSession, webUser, workspaceId,
                    selectedDateKeyUtc);
            boolean isToday = selectedDateKey.equals(todayDateKey);
            PersonalDayService.Day day = PersonalDayService.organize(allActions, selectedDateKeyUtc,
                    selectedDateKey, isToday);
            List<ActionNext> overdueActions = day.getOverdue();

            // Render page
            appReq.setTitle("Todo");
            printHtmlHead(appReq, "Todo");
            PrintWriter out = appReq.getOut();

            String title = formatTitle(selectedDate, today, webUser);
            out.println("<h1>" + title + "</h1>");

            // Overdue section (only on today)
            if (isToday && !overdueActions.isEmpty()) {
                out.println("<h2>Overdue</h2>");
                printActionList(out, overdueActions, true, selectedDate, webUser, dataSession);
            }

            List<ActionNext> wakeActions = day.getPart(TimeSlot.WAKE);
            List<ActionNext> morningActions = day.getPart(TimeSlot.MORNING);
            List<ActionNext> afternoonActions = day.getPart(TimeSlot.AFTERNOON);
            List<ActionNext> eveningActions = day.getPart(TimeSlot.EVENING);

            // Print tables for each category (only if there are items)
            printActionTable(out, "Wake", wakeActions, selectedDate, webUser, dataSession);
            printActionTable(out, "Morning", morningActions, selectedDate, webUser, dataSession);
            printActionTable(out, "Afternoon", afternoonActions, selectedDate, webUser, dataSession);
            printActionTable(out, "Evening", eveningActions, selectedDate, webUser, dataSession);

            // Date navigation
            printDateNavigation(out, selectedDate, today, webUser);

            printHtmlFoot(appReq);
        } catch (Exception e) {
            handleUnexpectedError(request, response, e);
        } finally {
            appReq.close();
        }
    }

    private void handleUnexpectedError(HttpServletRequest request, HttpServletResponse response, Exception e)
            throws IOException {
        e.printStackTrace();
        if (!response.isCommitted()) {
            response.sendRedirect("oops");
        }
    }

    private Date getSelectedDate(HttpServletRequest request, WebUser webUser) {
        String dateParam = request.getParameter(PARAM_DATE);
        if (dateParam != null && !dateParam.isEmpty()) {
            try {
                return webUser.getDateFormatService().parseTransportDate(dateParam, webUser.getTimeZone());
            } catch (Exception e) {
                // Fall through to today
            }
        }
        return TimeTracker.createToday(webUser).getTime();
    }

    private boolean isSameDay(Date date1, Date date2, WebUser webUser) {
        if (date1 == null || date2 == null)
            return false;
        Calendar cal1 = webUser.getCalendar();
        cal1.setTime(date1);
        Calendar cal2 = webUser.getCalendar();
        cal2.setTime(date2);
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    private String formatTitle(Date selectedDate, Date today, WebUser webUser) {
        if (isSameDay(selectedDate, today, webUser)) {
            return "Todo Today";
        }

        Calendar cal = webUser.getCalendar();
        cal.setTime(selectedDate);
        Calendar todayCal = webUser.getCalendar();
        todayCal.setTime(today);

        long daysDiff = (cal.getTimeInMillis() - todayCal.getTimeInMillis()) / (1000 * 60 * 60 * 24);

        if (daysDiff >= 1 && daysDiff <= 7) {
            return "Todo " + webUser.getDateFormatService().formatWeekdayLong(selectedDate, webUser.getTimeZone());
        } else if (daysDiff >= 8 && daysDiff <= 14) {
            return "Todo Next " + webUser.getDateFormatService().formatWeekdayLong(selectedDate, webUser.getTimeZone());
        } else {
            return "Todo " + webUser.getDateFormatService().formatDate(selectedDate, webUser.getTimeZone());
        }
    }

    private void printActionList(PrintWriter out, List<ActionNext> actions,
            boolean isOverdue, Date selectedDate, WebUser webUser, Session dataSession) {
        if (actions.isEmpty()) {
            out.println("<table class=\"boxed-mobile\">");
            out.println("  <tr class=\"boxed\">");
            out.println("    <td class=\"boxed\">No items</td>");
            out.println("  </tr>");
            out.println("</table>");
            return;
        }
        printActionTableContent(out, actions, isOverdue, selectedDate, webUser, dataSession);
    }

    private void printActionTable(PrintWriter out, String title, List<ActionNext> actions,
            Date selectedDate, WebUser webUser, Session dataSession) {
        if (actions.isEmpty()) {
            return; // Don't show header or table if no items
        }
        out.println("<h2>" + escapeHtml(title) + "</h2>");
        printActionTableContent(out, actions, false, selectedDate, webUser, dataSession);
    }

    private void printActionTableContent(PrintWriter out, List<ActionNext> actions,
            boolean isOverdue, Date selectedDate, WebUser webUser, Session dataSession) {

        String dateParam = selectedDate != null
                ? webUser.getDateFormatService().formatTransportDate(selectedDate, webUser.getTimeZone())
                : "";

        out.println("<table class=\"boxed-mobile\">");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">To Do</th>");
        out.println("    <th class=\"boxed\" style=\"text-align:center;\">Action</th>");
        out.println("  </tr>");
        for (ActionNext action : actions) {
            String projectName = action.getProject() != null ? getProjectDisplayName(dataSession, action.getProject())
                    : "";
            String projectIcon = action.getProject() != null ? action.getProject().getProjectIcon() : "";
            boolean hasProjectIcon = projectIcon != null && projectIcon.trim().length() > 0;
            String projectLabel = hasProjectIcon ? projectIcon.trim() : projectName;
            String projectLabelSuffix = hasProjectIcon ? "" : ":";
            String description = action.getNextDescriptionForDisplay(action.getContact());
            Integer projectId = action.getProjectId();

            // Build details link
            String viewUrl = "action?viewActionId=" + action.getActionNextId();
            if (!dateParam.isEmpty()) {
                viewUrl += "&" + PARAM_DATE + "=" + dateParam;
            }

            out.println("  <tr class=\"boxed\">");
            out.println("    <td class=\"boxed\">");
            if (!projectLabel.isEmpty()) {
                if (projectId != null && projectId.intValue() > 0) {
                    out.println("      <strong><a href=\"project?projectId=" + projectId
                            + "\" style=\"text-decoration: none;\">" + escapeHtml(projectLabel)
                            + "</a>" + projectLabelSuffix + "</strong> ");
                } else {
                    out.println("      <strong>" + escapeHtml(projectLabel) + projectLabelSuffix + "</strong> ");
                }
            }
            out.println("      <a href=\"" + viewUrl + "\" style=\"text-decoration: none; color: inherit;\">");
            out.println("        " + (description == null ? "" : description));
            out.println("      </a>");
            if (isOverdue) {
                out.println("      <span class=\"fail\">Overdue</span>");
            }
            out.println("    </td>");

            // Action column (complete + postpone + cancel)
            String completeUrl = "todo?" + PARAM_ACTION_ID + "=" + action.getActionNextId() + "&"
                    + PARAM_ACTION + "=" + ACTION_COMPLETE;
            if (!dateParam.isEmpty()) {
                completeUrl += "&" + PARAM_DATE + "=" + dateParam;
            }
            String cancelUrl = "todo?" + PARAM_ACTION_ID + "=" + action.getActionNextId() + "&"
                    + PARAM_ACTION + "=" + ACTION_CANCEL;
            if (!dateParam.isEmpty()) {
                cancelUrl += "&" + PARAM_DATE + "=" + dateParam;
            }
            out.println("    <td class=\"boxed\" style=\"text-align:center;\">");
            out.println("      <span style=\"white-space: nowrap;\">");
            out.println("        <a href=\"" + completeUrl
                    + "\" class=\"action-icon\" title=\"Complete\" style=\"margin-right: 8px;\">&#10004;</a>");
            out.println("        <a href=\"javascript:void(0);\" onclick=\"showPostponeMenu(" + action.getActionNextId()
                    + "); return false;\" class=\"action-icon\" title=\"Postpone/Reschedule\" style=\"margin-right: 8px;\">&#8594;</a>");
            out.println("        <a href=\"" + cancelUrl
                    + "\" class=\"action-icon\" title=\"Cancel\" style=\"margin-right: 8px;\">&#10006;</a>");
            out.println("      </span>");

            // Print the shared postpone menu
            printPostponeMenu(out, action, dateParam, webUser, dataSession);

            out.println("    </td>");
            out.println("  </tr>");
        }
        out.println("</table>");
    }

    private void printDateNavigation(PrintWriter out, Date selectedDate, Date today,
            WebUser webUser) {
        Calendar cal = webUser.getCalendar();

        boolean isToday = isSameDay(selectedDate, today, webUser);

        out.println("<p>");

        // Previous (hidden when viewing today)
        if (!isToday) {
            cal.setTime(selectedDate);
            cal.add(Calendar.DAY_OF_MONTH, -1);
            String prevDate = webUser.getDateFormatService().formatTransportDate(cal.getTime(), webUser.getTimeZone());
            out.println("  <a href=\"todo?" + PARAM_DATE + "=" + prevDate + "\" class=\"box\">Previous</a>");
        }

        // Today
        String todayDate = webUser.getDateFormatService().formatTransportDate(today, webUser.getTimeZone());
        out.println("  <a href=\"todo?" + PARAM_DATE + "=" + todayDate + "\" class=\"button\">Today</a>");

        // Next
        cal.setTime(selectedDate);
        cal.add(Calendar.DAY_OF_MONTH, 1);
        String nextDate = webUser.getDateFormatService().formatTransportDate(cal.getTime(), webUser.getTimeZone());
        out.println("  <a href=\"todo?" + PARAM_DATE + "=" + nextDate + "\" class=\"box\">Next</a>");

        out.println("</p>");
    }

    private String buildRedirectUrl(HttpServletRequest request) {
        String dateParam = request.getParameter(PARAM_DATE);
        if (dateParam != null && !dateParam.isEmpty()) {
            return "todo?" + PARAM_DATE + "=" + dateParam;
        }
        return "todo";
    }
}
