package org.openimmunizationsoftware.pt.mobile.servlet;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.dandeliondaily.personal.service.ActionQuickChangeService;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;
import org.openimmunizationsoftware.pt.util.WebEscaper;

/**
 * Base servlet for mobile views with shared header/footer rendering.
 * 
 * @author nathan
 */
public abstract class MobileBaseServlet extends ClientServlet {

    protected void printHtmlHead(AppReq appReq) {
        printHtmlHead(appReq, "Project");
    }

    protected void printHtmlHead(AppReq appReq, String activeNavItem) {
        HttpServletResponse response = appReq.getResponse();
        response.setContentType("text/html;charset=UTF-8");
        PrintWriter out = appReq.getOut();

        out.println("<html>");
        out.println("  <head>");
        out.println("    <meta charset=\"UTF-8\">");
        out.println("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        out.println("    <title>Dandelion Daily</title>");
        String displayColor = appReq.getDisplayColor();
        String displaySize = appReq.getDisplaySize();
        out.println("    <link rel=\"stylesheet\" type=\"text/css\" href=\"../CssServlet?displaySize="
                + displaySize + "&displayColor=" + WebEscaper.urlEncode(displayColor) + "\" />");
        out.println("    <link rel=\"icon\" href=\"../favicon.ico\" sizes=\"any\">\n"
                + "    <link rel=\"shortcut icon\" href=\"../favicon.ico\">\n"
                + "    <link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"../favicon-32x32.png\">\n"
                + "    <link rel=\"icon\" type=\"image/png\" sizes=\"16x16\" href=\"../favicon-16x16.png\">");
        out.println("  </head>");
        out.println("  <body>");
        out.println(makeMobileMenu(activeNavItem));
        out.println("    <div class=\"mainMobile\">");
    }

    protected String makeMobileMenu(String activeNavItem) {
        List<String[]> menuList = new ArrayList<String[]>();
        menuList.add(new String[] { "project", "Project" });
        menuList.add(new String[] { "todo", "Todo" });
        menuList.add(new String[] { "action", "Action" });

        StringBuilder result = new StringBuilder();
        result.append("    <table class=\"menu\"><tr><td>");
        for (int i = 0; i < menuList.size(); i++) {
            String[] menu = menuList.get(i);
            if (i > 0) {
                result.append(" ");
            }
            String styleClass = "menuLink";
            if (menu[1].equals(activeNavItem)) {
                styleClass = "menuLinkSelected";
            }
            result.append("<a class=\"");
            result.append(styleClass);
            result.append("\" href=\"");
            result.append(menu[0]);
            result.append("\">");
            result.append(menu[1]);
            result.append("</a>");
        }
        result.append("</td></tr></table>");
        return result.toString();
    }

    protected void printHtmlFoot(AppReq appReq) {
        PrintWriter out = appReq.getOut();
        out.println("      <p>Open Immunization Software - Dandelion</p>");
        out.println("      <p><a href=\"../HomeServlet\" class=\"box\">Desktop Mode</a></p>");
        out.println("    </div>");
        out.println("  </body>");
        out.println("</html>");
    }

    protected boolean isShowWork(HttpServletRequest request) {
        return false;
    }

    protected boolean isShowPersonal(HttpServletRequest request) {
        return true;
    }

    // ==================== Shared Action Methods ====================

    // Complete, cancel and move go through ActionQuickChangeService, the same code as the phone app
    // (Sundial); completing uses ActionCompletionService like the desktop dashboard and MCP.

    private final ActionQuickChangeService quickChangeService = new ActionQuickChangeService();

    private static ActionQuickChangeService.Actor mobileActor(WebUser webUser) {
        return new ActionQuickChangeService.Actor(webUser.getUsername(), "mobile_web");
    }

    protected void completeAction(ActionNext action, Session dataSession, WebUser webUser) {
        Transaction trans = dataSession.beginTransaction();
        try {
            quickChangeService.complete(dataSession, webUser, action, mobileActor(webUser));
            trans.commit();
        } catch (RuntimeException e) {
            trans.rollback();
            throw e;
        }
    }

    protected void cancelAction(ActionNext action, Session dataSession, WebUser webUser) {
        Transaction trans = dataSession.beginTransaction();
        try {
            quickChangeService.cancel(dataSession, webUser, action, mobileActor(webUser));
            trans.commit();
        } catch (RuntimeException e) {
            trans.rollback();
            throw e;
        }
    }

    protected void rescheduleAction(ActionNext action, HttpServletRequest request,
            WebUser webUser, Session dataSession) {
        LocalDate targetDate = ActionQuickChangeService.localDate(action.getNextActionDate());
        String targetDateString = request.getParameter("targetDate");
        if (targetDateString != null && !targetDateString.isEmpty()) {
            try {
                targetDate = LocalDate.parse(targetDateString.trim());
            } catch (Exception e) {
                // Keep the current date, as before.
            }
        }
        if (targetDate == null) {
            targetDate = webUser.getLocalDateToday();
        }
        TimeSlot slot = TimeSlot.getTimeSlot(request.getParameter("timeSlotString"));
        if (slot == null) {
            slot = action.getTimeSlot() == null ? TimeSlot.AFTERNOON : action.getTimeSlot();
        }
        Transaction trans = dataSession.beginTransaction();
        try {
            quickChangeService.move(dataSession, action, targetDate, slot, mobileActor(webUser));
            trans.commit();
        } catch (RuntimeException e) {
            trans.rollback();
            throw e;
        }
    }

    protected void postponeToTomorrow(ActionNext action, Session dataSession, WebUser webUser) {
        LocalDate target = ActionQuickChangeService.postponeTarget(
                ActionQuickChangeService.localDate(action.getNextActionDate()), webUser.getLocalDateToday());
        Transaction trans = dataSession.beginTransaction();
        try {
            quickChangeService.move(dataSession, action, target, null, mobileActor(webUser));
            trans.commit();
        } catch (RuntimeException e) {
            trans.rollback();
            throw e;
        }
    }

    protected void rescheduleActionSimple(ActionNext action, Session dataSession, WebUser webUser) {
        Transaction trans = dataSession.beginTransaction();
        try {
            Date today = webUser.getToday();
            Date tomorrow = webUser.getTomorrow();
            Date nextActionDate = action.getNextActionDate();
            Date nextActionDay = nextActionDate == null ? null : webUser.startOfDay(nextActionDate);

            if (nextActionDay == null || nextActionDay.before(today)) {
                action.setNextActionDate(today);
            } else if (nextActionDay.before(tomorrow)) {
                action.setNextActionDate(tomorrow);
            } else {
                action.setNextActionDate(null);
            }
            action.setNextChangeDate(new Date());
            dataSession.saveOrUpdate(action);
            trans.commit();
        } catch (Exception e) {
            trans.rollback();
            throw e;
        }
    }

    // ==================== Shared Rendering Methods ====================

    protected void printPostponeMenu(PrintWriter out, ActionNext action, String dateParam, WebUser webUser,
            Session dataSession) {
        printPostponeMenuInternal(out, action, dateParam, webUser, dataSession, "todo");
    }

    protected void printPostponeMenuForProject(PrintWriter out, ActionNext action, String dateParam,
            WebUser webUser, Session dataSession, int projectId) {
        printPostponeMenuInternal(out, action, dateParam, webUser, dataSession, "project", projectId);
    }

    private void printPostponeMenuInternal(PrintWriter out, ActionNext action, String dateParam, WebUser webUser,
            Session dataSession, String servletName) {
        printPostponeMenuInternal(out, action, dateParam, webUser, dataSession, servletName, -1);
    }

    private void printPostponeMenuInternal(PrintWriter out, ActionNext action, String dateParam, WebUser webUser,
            Session dataSession, String servletName, int projectId) {
        int actionNextId = action.getActionNextId();
        String actionDay = action.getNextActionDate() == null
                ? formatActionDateAsTransport(webUser.getToday())
                : formatActionDateAsTransport(action.getNextActionDate());

        TimeSlot currentTimeSlot = action.getTimeSlot();
        if (currentTimeSlot == null) {
            currentTimeSlot = TimeSlot.AFTERNOON;
        }
        String currentSlotId = currentTimeSlot.getId();

        out.println("<script>");
        out.println("function showPostponeMenu(actionId){");
        out.println("  var menu = document.getElementById('postpone-menu-' + actionId);");
        out.println("  if (menu) { menu.style.display = 'block'; }");
        out.println("}");
        out.println("function hidePostponeMenu(actionId){");
        out.println("  var menu = document.getElementById('postpone-menu-' + actionId);");
        out.println("  if (menu) { menu.style.display = 'none'; }");
        out.println("}");
        out.println("</script>");

        String popupTitle = action.getNextDescriptionForDisplay(action.getContact());
        String projectLabel = dataSession == null ? "" : getActionProjectDisplayName(dataSession, action);
        String projectIcon = action.getProject() != null ? action.getProject().getProjectIcon() : "";
        boolean hasProjectIcon = projectIcon != null && projectIcon.trim().length() > 0;
        projectLabel = hasProjectIcon ? projectIcon.trim() : projectLabel;
        if (!projectLabel.isEmpty()) {
            popupTitle = escapeHtml(projectLabel) + " " + popupTitle;
        }

        out.println("      <div id=\"postpone-menu-" + actionNextId
                + "\" style=\"display:none; position:fixed; left:10px; right:10px; top:20%; z-index:9999; background:#fff; border:1px solid #666; padding:10px; text-align:left;\">");
        out.println(
                "        <div style=\"margin-bottom: 8px;\"><strong>Reschedule: " + popupTitle
                        + "</strong> <a href=\"javascript:void(0);\" onclick=\"hidePostponeMenu("
                        + actionNextId
                        + "); return false;\" style=\"float:right; text-decoration:none;\">&#10005;</a></div>");
        out.println("        <div style=\"margin-bottom: 8px;\">\n");
        printPostponeMenuSlotOption(out, actionNextId, dateParam, actionDay,
                TimeSlot.WAKE, "&#9200; Wake", currentSlotId, servletName, projectId);
        printPostponeMenuSlotOption(out, actionNextId, dateParam, actionDay,
                TimeSlot.MORNING, "&#127749; Morning", currentSlotId, servletName, projectId);
        printPostponeMenuSlotOption(out, actionNextId, dateParam, actionDay,
                TimeSlot.AFTERNOON, "&#127774; Afternoon", currentSlotId, servletName, projectId);
        printPostponeMenuSlotOption(out, actionNextId, dateParam, actionDay,
                TimeSlot.EVENING, "&#127769; Evening", currentSlotId, servletName, projectId);
        out.println("        </div>");
        out.println("        <div>");
        Calendar dayCal = webUser.getCalendar();
        dayCal.setTime(webUser.getToday());
        printPostponeMenuDayOption(out, actionNextId, dateParam, currentSlotId,
                actionDay,
                webUser.getDateFormatService().formatTransportDate(dayCal.getTime(), webUser.getTimeZone()),
                "Today", servletName, projectId);
        for (int i = 0; i < 7; i++) {
            dayCal.add(Calendar.DAY_OF_MONTH, 1);
            printPostponeMenuDayOption(out, actionNextId, dateParam, currentSlotId,
                    actionDay,
                    webUser.getDateFormatService().formatTransportDate(dayCal.getTime(), webUser.getTimeZone()),
                    webUser.getDateFormatService().formatWeekdayShort(dayCal.getTime(), webUser.getTimeZone()),
                    servletName, projectId);
        }
        out.println("        </div>");
        out.println("      </div>");
    }

    protected void printPostponeMenuSlotOption(PrintWriter out, int actionNextId,
            String dateParam, String targetDate, TimeSlot slot, String label,
            String currentSlotId) {
        printPostponeMenuSlotOption(out, actionNextId, dateParam, targetDate, slot, label, currentSlotId, "todo", -1);
    }

    protected void printPostponeMenuSlotOption(PrintWriter out, int actionNextId,
            String dateParam, String targetDate, TimeSlot slot, String label,
            String currentSlotId, String servletName, int projectId) {
        boolean isCurrent = slot.getId().equals(currentSlotId);
        String style = "display:inline-block; margin-right:6px; margin-bottom:6px; padding:4px 6px; text-decoration:none;";
        if (isCurrent) {
            out.println("          <span style=\"" + style + " border:1px solid #666;\">" + label + "</span>");
            return;
        }
        String url = servletName + "?actionId=" + actionNextId
                + "&action=reschedule"
                + "&targetDate=" + targetDate
                + "&timeSlotString=" + slot.getId();
        if (projectId > 0) {
            url = servletName + "?projectId=" + projectId + "&actionId=" + actionNextId
                    + "&action=reschedule"
                    + "&targetDate=" + targetDate
                    + "&timeSlotString=" + slot.getId();
        }
        if (!dateParam.isEmpty()) {
            url += "&date=" + dateParam;
        }
        out.println("          <a href=\"" + url + "\" style=\"" + style + " color: inherit;\">"
                + label + "</a>");
    }

    protected void printPostponeMenuDayOption(PrintWriter out, int actionNextId,
            String dateParam, String slotId, String currentDate, String targetDate, String label) {
        printPostponeMenuDayOption(out, actionNextId, dateParam, slotId, currentDate, targetDate, label, "todo", -1);
    }

    protected void printPostponeMenuDayOption(PrintWriter out, int actionNextId,
            String dateParam, String slotId, String currentDate, String targetDate, String label, String servletName,
            int projectId) {
        boolean isCurrent = targetDate.equals(currentDate);
        String style = "display:inline-block; margin-right:6px; margin-bottom:6px; padding:4px 6px; text-decoration:none;";
        if (isCurrent) {
            out.println("          <span style=\"" + style + " border:1px solid #666;\">" + label + "</span>");
            return;
        }
        String url = servletName + "?actionId=" + actionNextId
                + "&action=reschedule"
                + "&targetDate=" + targetDate
                + "&timeSlotString=" + slotId;
        if (projectId > 0) {
            url = servletName + "?projectId=" + projectId + "&actionId=" + actionNextId
                    + "&action=reschedule"
                    + "&targetDate=" + targetDate
                    + "&timeSlotString=" + slotId;
        }
        if (!dateParam.isEmpty()) {
            url += "&date=" + dateParam;
        }
        out.println("          <a href=\"" + url + "\" style=\"" + style + " color: inherit;\">"
                + label + "</a>");
    }

    // ==================== Utility Methods ====================

    protected String toUserDateKey(Date date, WebUser webUser) {
        if (date == null) {
            return null;
        }
        return webUser.getDateFormatService().formatTransportDate(date, webUser.getTimeZone());
    }

    protected String toDatabaseDateKey(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    protected Date parseUTCDate(String dateString) throws Exception {
        // Parse date string (yyyy-MM-dd) as UTC without timezone conversion
        // This ensures date-only database values are not shifted by timezone
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.parse(dateString);
    }

    protected String formatActionDateAsTransport(Date date) {
        // Format date (yyyy-MM-dd) as UTC without timezone conversion
        // This ensures date-only database values are not shifted by timezone
        if (date == null) {
            return "";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }

    protected String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
