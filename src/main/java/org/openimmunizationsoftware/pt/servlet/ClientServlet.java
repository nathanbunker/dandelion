/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.dashboard.service.ProjectDisplayLabelService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.SoftwareVersion;
import org.openimmunizationsoftware.pt.manager.TimeEntry;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.PageMessage;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.util.WebEscaper;

/**
 * 
 * @author nathan
 */
public class ClientServlet extends HttpServlet {

  private static final ProjectDisplayLabelService projectDisplayLabelService = new ProjectDisplayLabelService();

  protected static String getProjectDisplayNameStatic(Session dataSession, Project project) {
    return projectDisplayLabelService.buildDisplayName(dataSession, project);
  }

  protected static String getActionProjectDisplayNameStatic(Session dataSession, ActionNext action) {
    return action == null ? "" : getProjectDisplayNameStatic(dataSession, action.getProject());
  }

  protected String getProjectDisplayName(Session dataSession, Project project) {
    return getProjectDisplayNameStatic(dataSession, project);
  }

  protected String getActionProjectDisplayName(Session dataSession, ActionNext action) {
    return getActionProjectDisplayNameStatic(dataSession, action);
  }

  private static final long serialVersionUID = 8641751774755499569L;

  public static String PARAM_ACTION = "action";

  public static final HashMap<String, Date> webUserLastUsedDate = new HashMap<String, Date>();
  public static String systemWideMessage = "";

  public static String getSystemWideMessage() {
    return systemWideMessage;
  }

  public static void setSystemWideMessage(String systemWideMessage) {
    ClientServlet.systemWideMessage = systemWideMessage;
  }

  protected void printHtmlHead(AppReq appReq) {
    HttpServletResponse response = appReq.getResponse();
    response.setContentType("text/html;charset=UTF-8");
    PrintWriter out = appReq.getOut();
    String title = appReq.getTitle();
    TimeTracker timeTracker = appReq.getTimeTracker();

    out.println("<html>");
    out.println("  <head>");
    out.println("   <meta charset=\"UTF-8\">");

    if (timeTracker != null) {
      if (timeTracker.isRunningClock()) {
        out.println("    <title>Dandelion " + timeTracker.getTotalMinsBillableForDisplay() + " " + title
            + "</title>");
      } else {
        out.println("    <title>Dandelion (Idle) " + title + "</title>");
      }
    } else {
      out.println("    <title>Dandelion " + title + "</title>");
    }

    String displayColor = appReq.getDisplayColor();
    String displaySize = appReq.getDisplaySize();

    out.println("    <link rel=\"stylesheet\" type=\"text/css\" href=\"CssServlet?displaySize="
        + displaySize + "&displayColor=" + WebEscaper.urlEncode(displayColor) + "\" />");
    out.println("    <link rel=\"icon\" href=\"favicon.ico\" sizes=\"any\">\n"
        + "    <link rel=\"shortcut icon\" href=\"favicon.ico\">\n"
        + "    <link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"favicon-32x32.png\">\n"
        + "    <link rel=\"icon\" type=\"image/png\" sizes=\"16x16\" href=\"favicon-16x16.png\">");
    out.println("    <script>");
    out.println("      function toggleLayer(whichLayer) ");
    out.println("      {");
    out.println("        var elem, vis;");
    out.println("        if (document.getElementById) ");
    out.println("          elem = document.getElementById(whichLayer);");
    out.println("        else if (document.all) ");
    out.println("          elem = document.all[whichLayer] ");
    out.println("        else if (document.layers) ");
    out.println("          elem = document.layers[whichLayer]");
    out.println("        vis = elem.style;");
    out.println(
        "        if (vis.display == '' && elem.offsetWidth != undefined && elem.offsetHeight != undefined) ");
    out.println(
        "          vis.display = (elem.offsetWidth != 0 && elem.offsetHeight != 0) ? 'block' : 'none';");
    out.println(
        "        vis.display = (vis.display == '' || vis.display == 'block') ? 'none' : 'block';");
    out.println("      }");
    out.println("      function toggleVisibility(id) ");
    out.println("      {");
    out.println("        var box = document.getElementById(id);");
    out.println("        if (box.style.visibility == 'visible') { ");
    out.println("          box.style.visibility = 'hidden'; ");
    out.println("        } else {");
    out.println("          box.style.visibility = 'visible'; ");
    out.println("        }");
    out.println("      }");

    out.println("      var timeout = 5000;");
    out.println("      var closeTimer = 0;");
    out.println("      var ddBox = 0;");
    out.println("      function boxOpen(id) ");
    out.println("      { ");
    out.println("        boxCancelCloseTime(); ");
    out.println("        if (ddBox) ddBox.style.visibility = 'hidden'; ");
    out.println("        ddBox = document.getElementById(id);");
    out.println("        ddBox.style.visibility = 'visible';");
    out.println("      } ");
    out.println("      function boxClose() ");
    out.println("      {");
    out.println("        if(ddBox) ddBox.style.visibility = 'hidden'");
    out.println("      }");
    out.println("      function boxCloseTime() { ");
    out.println("        closetimer = window.setTimeout(boxClose, timeout); ");
    out.println("      }");
    out.println("      function boxCancelCloseTime() ");
    out.println("      {");
    out.println("        if (closeTimer)");
    out.println("        { ");
    out.println("          window.clearTimeout(closeTimer);");
    out.println("          closeTimer = null;");
    out.println("        }");
    out.println("      }");

    out.println("      function showHide(shID) {");
    out.println("        if (document.getElementById(shID)) {");
    out.println("          if (document.getElementById(shID).style.display != 'none') {");
    out.println("            document.getElementById(shID).style.display = 'none';");
    out.println("          }");
    out.println("          else {");
    out.println("            document.getElementById(shID).style.display = 'none';");
    out.println("          }");
    out.println("        }");
    out.println("      }");
    out.println("    </script>");
    out.println("  </head>");
    out.println("  <body>");
    out.println(makeMenu(appReq));

    {
      String message = appReq.getMessageProblem();
      if (message != null) {
        out.println("<p class=\"fail\">" + message + "</p>");
      }
    }
    {
      String message = appReq.getMessageConfirmation();
      if (message != null) {
        out.println("<p>" + message + "</p>");
      }
    }

    if (!systemWideMessage.equals("")) {
      out.println("<p class=\"fail\">" + systemWideMessage + "</p>");
    }
    renderPageMessages(out, appReq);
  }

  private void renderPageMessages(PrintWriter out, AppReq appReq) {
    List<PageMessage> messages = appReq.getPageMessages();
    if (messages.isEmpty()) {
      return;
    }
    out.println("<style>");
    out.println("  .pm-stack { display:flex; flex-direction:column; align-items:flex-start; gap:6px; margin:8px 0; }");
    out.println("  .pm-stack.pm-overlay { position:fixed; z-index:10000; }");
    out.println("  .pm-msg { display:flex; align-items:flex-start; justify-content:space-between;");
    out.println("            padding:10px 14px; border-radius:4px; font-size:13px; line-height:1.4;");
    out.println("            width:auto; max-width:min(70ch, calc(100vw - 32px)); }");
    out.println("  .pm-success { background:#e8f5e9; border-left:4px solid #388e3c; color:#1b5e20; }");
    out.println("  .pm-info    { background:#e3f2fd; border-left:4px solid #1976d2; color:#0d47a1; }");
    out.println("  .pm-warning { background:#fff8e1; border-left:4px solid #f9a825; color:#6d4c00; }");
    out.println("  .pm-error   { background:#fdecea; border-left:4px solid #c62828; color:#7f0000; }");
    out.println("  .pm-close { background:none; border:none; cursor:pointer; font-size:16px;");
    out.println("              line-height:1; padding:0 0 0 12px; opacity:0.6; flex-shrink:0; }");
    out.println("  .pm-close:hover { opacity:1; }");
    out.println("</style>");
    out.println("<script>");
    out.println("  function pmPinStack() {");
    out.println("    var stack = document.getElementById('pm-stack');");
    out.println("    if (!stack) { return; }");
    out.println("    stack.classList.remove('pm-overlay');");
    out.println("    var rect = stack.getBoundingClientRect();");
    out.println("    stack.style.top = rect.top + 'px';");
    out.println("    stack.style.left = rect.left + 'px';");
    out.println("    stack.classList.add('pm-overlay');");
    out.println("  }");
    out.println("  function pmDismiss(btn) {");
    out.println("    var el = btn.parentElement;");
    out.println("    el.style.transition = 'opacity 0.3s';");
    out.println("    el.style.opacity = '0';");
    out.println("    setTimeout(function(){ el.style.display='none'; }, 300);");
    out.println("  }");
    out.println("  if (window.addEventListener) {");
    out.println("    window.addEventListener('load', pmPinStack);");
    out.println("    window.addEventListener('resize', pmPinStack);");
    out.println("  }");
    out.println("</script>");
    out.println("<div class=\"pm-stack\" id=\"pm-stack\">");
    for (int i = 0; i < messages.size(); i++) {
      PageMessage msg = messages.get(i);
      String sevClass = "pm-" + msg.getSeverity().name().toLowerCase();
      String msgId = "pm-msg-" + i;
      String escaped = msg.getMessageText()
          .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
      out.println("  <div class=\"pm-msg " + sevClass + "\" id=\"" + msgId + "\">");
      out.println("    <span>" + escaped + "</span>");
      out.println("    <button class=\"pm-close\" onclick=\"pmDismiss(this)\" title=\"Dismiss\">&#x2715;</button>");
      out.println("  </div>");
      if (msg.isAutoDismiss() && msg.getDismissAfterMs() > 0) {
        out.println("  <script>setTimeout(function(){");
        out.println("    var el=document.getElementById('" + msgId + "');");
        out.println("    if(el){el.style.transition='opacity 0.3s';el.style.opacity='0';");
        out.println("    setTimeout(function(){el.style.display='none';},300);}");
        out.println("  }," + msg.getDismissAfterMs() + ");</script>");
      }
    }
    out.println("</div>");
  }

  public static String makeMenu(AppReq appReq) {
    String title = appReq.getTitle();

    boolean loggedIn = appReq.isLoggedIn();
    TimeTracker timeTracker = appReq.getTimeTracker();
    WebUser webUser = appReq.getWebUser();

    // { href, label, page title that marks this entry as the current page }
    List<String[]> menuList = new ArrayList<String[]>();

    if (loggedIn) {
      menuList.add(new String[] { Authenticate.APP_DEFAULT_HOME, "&#9776;", "Dandelion" });
      menuList.add(new String[] { "FocusedActionServlet", "Focus", "Focused Action" });
      menuList.add(new String[] { "DandelionDashboardServlet", "Dashboard", "Dandelion Dashboard" });
      menuList.add(new String[] { "PlanAheadServlet", "Plan Ahead", "Plan Ahead" });
      menuList.add(new String[] { "ReviewDashboardServlet", "Review", "Review" });
    } else {
      menuList.add(new String[] { Authenticate.APP_DEFAULT_HOME, "&#9776;", "Dandelion" });
      menuList.add(new String[] { "LoginServlet", "Login", "Login" });
      menuList.add(new String[] { "RegistrationServlet", "Register", "Register" });
    }
    StringBuilder result = new StringBuilder();
    result.append("    <table class=\"menu\"><tr><td>");
    for (int i = 0; i < menuList.size(); i++) {
      String[] menu = menuList.get(i);
      if (i > 0) {
        // result.append(" &bull; ");
        result.append(" ");
      }
      String styleClass = "menuLink";
      if (menu[2].equals(title)) {
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

    result.append("</td><td class=\"right\">");
    if (loggedIn) {
      Project project = appReq.getProject();
      ActionNext completingAction = appReq.getCompletingAction();
      if (project == null && completingAction != null) {
        project = completingAction.getProject();
      }
      if (project != null) {
        result.append("<span class=\"menuLink\">" + getProjectDisplayNameStatic(appReq.getDataSession(), project)
            + "</span>");
      }
      if (timeTracker != null) {
        String time = timeTracker.getTotalMinsBillableForDisplay();
        int minsForWeek = 0;
        {
          Session dataSession = appReq.getDataSession();
          Date today = new Date();
          TimeTracker timeTrackerForWeek = new TimeTracker(webUser, today, Calendar.WEEK_OF_YEAR, dataSession);
          minsForWeek = calculateWeeklyRoundedMinutesLikeTrackServlet(dataSession, timeTrackerForWeek);
        }
        if (minsForWeek > 0) {
          time += " <font size=\"-1\">" + TimeTracker.formatTime(minsForWeek) + "</font>";
        }
        result.append(" <span class=\"");
        result.append(timeTracker.isRunningClock() ? "workStatusWorking\">WORKING" : "workStatusStopped\">NOT WORKING");
        result.append("</span>");
        result.append(" <a href=\"");
        result.append(createWorkToggleLink(project, completingAction, timeTracker));
        result.append("\" class=\"workToggleButton\" title=\"");
        result.append(timeTracker.isRunningClock() ? "Stop Working" : "Start Working");
        result.append("\">");
        result.append(timeTracker.isRunningClock() ? "&#9632;" : "&#9654;");
        result.append("</a>");
        result.append(" <span class=\"");
        result.append(timeTracker.isRunningClock() ? "timerRunningLabel\">" : "timerStoppedLabel\">");
        result.append(time);
        result.append("</span>");
      }
    } else {
      result.append("&nbsp;");
    }
    result.append("</td></tr></table><br>");
    return result.toString();
  }

  private static String createWorkToggleLink(Project project, ActionNext completingAction,
      TimeTracker timeTracker) {
    if (timeTracker != null && timeTracker.isRunningClock()) {
      return "TrackServlet?action=StopTimer";
    }
    StringBuilder link = new StringBuilder("DandelionDashboardServlet");
    boolean hasParam = false;
    if (project != null || completingAction != null) {
      link.append("?");
      if (project != null) {
        link.append("projectId=").append(project.getProjectId());
        hasParam = true;
      }
      if (completingAction != null) {
        if (hasParam) {
          link.append("&");
        }
        link.append("completingActionNextId")
            .append("=")
            .append(completingAction.getActionNextId());
        hasParam = true;
      }
      if (hasParam) {
        link.append("&");
      }
      link.append("action=StartTimer");
      return link.toString();
    }
    return "DandelionDashboardServlet";
  }

  protected void printDandelionLocation(PrintWriter out, String sectionName) {
    out.println("<table class=\"boxed\">");
    out.println("  <tr class=\"boxed\">");
    out.println("    <td class=\"boxed\"><a href=\"HomeServlet\">Dandelion</a> &raquo; "
        + n(sectionName) + "</td>");
    out.println("  </tr>");
    out.println("</table><br/>");
  }

  protected void printBillingAdminNav(PrintWriter out, String currentPageLabel) {
    out.println("<table class=\"boxed\">");
    out.println("  <tr class=\"boxed\">");
    out.println("    <td class=\"boxed\">"
        + createBillingNavItem("Billing Home", "HomeServlet", currentPageLabel)
        + " | "
        + createBillingNavItem("Funding Sources", "BillFundingSourcesServlet", currentPageLabel)
        + " | "
        + createBillingNavItem("Bill Codes", "BillCodesServlet", currentPageLabel)
        + " | "
        + createBillingNavItem("Contract Budgets", "BillBudgetsServlet", currentPageLabel)
        + " | "
        + createBillingNavItem("Allocation Plans", "BillPlansServlet", currentPageLabel)
        + " | "
        + createBillingNavItem("Project Assignments", "BillingProjectAssignmentsServlet", currentPageLabel)
        + " | "
        + createBillingNavItem("Project Report", "BillingProjectReportServlet", currentPageLabel)
        + "</td>");
    out.println("  </tr>");
    out.println("</table><br/>");
  }

  private String createBillingNavItem(String label, String href, String currentPageLabel) {
    if (label.equals(currentPageLabel)) {
      return "<strong>" + label + "</strong>";
    }
    return "<a href=\"" + href + "\">" + label + "</a>";
  }

  public static void printFooter(PrintWriter out) {
    out.println("    <p>Open Immunization Software - Dandelion - Version "
        + SoftwareVersion.VERSION + "</p>");
  }

  public static BillCode resolveBillCode(Session dataSession, Integer workspaceId,
      String billCodeString) {
    if (workspaceId == null || billCodeString == null || billCodeString.equals("")) {
      return null;
    }
    Query query = dataSession.createQuery(
        "from BillCode where workspaceId = :workspaceId and id.billCode = :billCode");
    query.setParameter("workspaceId", workspaceId);
    query.setParameter("billCode", billCodeString);
    @SuppressWarnings("unchecked")
    List<BillCode> billCodeList = query.list();
    return billCodeList.isEmpty() ? null : billCodeList.get(0);
  }

  public static BillCode resolveBillCode(Session dataSession, Project project) {
    if (project == null) {
      return null;
    }
    return resolveBillCode(dataSession, project.getWorkspaceId(), project.getBillCode());
  }

  /** Billable minutes for the week, rounded the way the menu's weekly total and Track show them. */
  protected static int loadWeeklyBillableMinutesRounded(Session dataSession, TimeTracker timeTrackerForWeek) {
    return calculateWeeklyRoundedMinutesLikeTrackServlet(dataSession, timeTrackerForWeek);
  }

  private static int calculateWeeklyRoundedMinutesLikeTrackServlet(Session dataSession,
      TimeTracker timeTrackerForWeek) {
    int totalTimeInMinutes = 0;
    HashMap<Integer, Integer> projectMap = timeTrackerForWeek.getTotalMinsForProjectMap();
    for (Integer projectId : projectMap.keySet()) {
      Project project = (Project) dataSession.get(Project.class, projectId);
      if (project == null) {
        continue;
      }
      String billCodeString = project.getBillCode();
      if (billCodeString == null) {
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

  protected void printHtmlFoot(AppReq appReq) {
    PrintWriter out = appReq.getOut();
    switch (appReq.getAppType()) {
      case SENTIMENT:
        out.println("    </div>");
        out.println("  </body>");
        out.println("</html>");
        break;
      case TRACKER:
        printFooter(out);
        out.println("  </body>");
        out.println("</html>");
        break;
    }
  }

  /**
   * Returns a short description of the servlet.
   * 
   * @return a String containing servlet description
   */
  @Override
  public String getServletInfo() {
    return "Short description";
  }// </editor-fold>

  protected static String nbsp(String s) {
    if (s == null || s.equals("")) {
      return "&nbsp;";
    }
    return s;
  }

  protected static String n(String s) {
    if (s == null || s.equals("")) {
      return "";
    }
    return s;
  }

  protected static String n(Integer i) {
    if (i == null) {
      return "";
    }
    return "" + i;
  }

  protected static String n(Object value) {
    if (value == null) {
      return "";
    }
    return String.valueOf(value);
  }

  protected String addBreaks(String s) {
    if (s == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i = i + 20) {
      String t;
      if ((i + 20) < s.length()) {
        t = s.substring(i, i + 20);
        sb.append(t);
      } else {
        t = s.substring(i);
        sb.append(t);
        int pos = t.indexOf(" ");
        if (pos == -1) {
          sb.append(" ");
        }
      }
    }
    return sb.toString();
  }

  protected String n(String s, String d) {
    if (s == null || s.equals("")) {
      return d;
    }
    return s;
  }

  protected static String escapeHtmlAttribute(String value) {
    return WebEscaper.escapeHtml(value);
  }

  protected static String trim(String s, int length) {
    if (s == null) {
      return "";
    }
    if (s.length() < length) {
      return s;
    }
    return s.substring(0, length);
  }

  protected String trimForDisplay(String s, int length) {
    if (s == null) {
      return "";
    }
    if (s.length() < length) {
      return s;
    }
    return s.substring(0, length) + "...";
  }

  protected void forwardToHome(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    RequestDispatcher dispatcher = request.getRequestDispatcher("HomeServlet");
    dispatcher.forward(request, response);
  }
}
