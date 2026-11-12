package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Date;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.dashboard.service.DashboardTodayColumnService;
import org.dandeliondaily.planahead.service.PlanAheadDayCapacityService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.util.WebEscaper;

/**
 * The launchpad: three cards into the daily work pages (Focus, Dashboard, Plan Ahead), each with
 * one line of live status, then every other page grouped into collapsible sections.
 */
public class HomeServlet extends ClientServlet {

  private static final long serialVersionUID = 1L;

  private final DashboardTodayColumnService dashboardTodayColumnService = new DashboardTodayColumnService();
  private final PlanAheadDayCapacityService dayCapacityService = new PlanAheadDayCapacityService();

  protected void processRequest(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    AppReq appReq = new AppReq(request, response);
    try {
      WebUser webUser = appReq.getWebUser();
      PrintWriter out = appReq.getOut();
      appReq.setTitle("Dandelion");
      printHtmlHead(appReq);
      if (webUser == null) {
        printLoggedOut(out);
      } else {
        printLaunchpad(appReq, webUser, out);
      }
      printHtmlFoot(appReq);
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      appReq.close();
    }
  }

  private void printLoggedOut(PrintWriter out) {
    out.println("<div class=\"main\">");
    out.println("<h1>Dandelion</h1>");
    out.println("<p>Dandelion is a system for managing daily tasks and increasing your productivity at work.</p>");
    out.println("<p>It also helps organize your personal life and reduce stress while being more productive.</p>");
    out.println("<p><a href=\"LoginServlet\">Log in</a> or <a href=\"RegistrationServlet\">register for a new account</a>.</p>");
    out.println("</div>");
  }

  private void printLaunchpad(AppReq appReq, WebUser webUser, PrintWriter out) {
    printStyle(out);
    out.println("<div class=\"main lp\">");

    out.println("<div class=\"lp-cards\">");
    printFocusCard(appReq, out);
    printDashboardCard(appReq, out);
    printPlanAheadCard(appReq, out);
    out.println("</div>");

    out.println("<div class=\"lp-more\">");
    printGroup(out, "review", "Review &amp; reporting", new String[][] {
        { "ReviewDashboardServlet", "Review &amp; Report" },
        { "WeeklyReportsServlet", "Manage weekly reports" },
        { "WeeklyReportServlet", "View weekly report" },
        { "OutlooksServlet", "Outlooks" },
        { "WorkObligationsServlet", "Work obligations" },
        { "TrackServlet", "Time log" },
        webUser.isUserTypeAdmin() ? new String[] { "ReportsServlet", "Reports" } : null,
    });
    printGroup(out, "projects", "Projects &amp; people", new String[][] {
        { "ProjectHealthServlet", "Project Health" },
        { "DandelionPatchServlet", "Dandelion Patches" },
        { "CategoryManagementServlet", "Categories" },
        { "ProjectContactsServlet", "Contacts" },
        { "TemplateManagementServlet", "Templates" },
    });
    printGroup(out, "account", "Account", new String[][] {
        { "SettingsServlet", "Settings" },
        { "DependentAccountsServlet", "Dependent accounts" },
        { "StudentOfferSetupServlet", "Manage student store" },
        { "student/school", "Student view" },
        { "m/todo?filterSubmitted=Y&amp;showPersonal=Y", "Mobile view" },
        { "LoginServlet?action=Logout", "Log out" },
    });
    if (webUser.isUserTypeAdmin()) {
      printGroup(out, "admin", "Admin", new String[][] {
          { "AdminSettingsServlet", "Admin settings" },
          { "AdminUserRegistrationStatusServlet", "Registered and pending users" },
          { "TemplateSchedulerStatusServlet", "Template scheduler status" },
          { "BillFundingSourcesServlet", "Billing: funding sources" },
          { "BillCodesServlet", "Billing: bill codes" },
          { "BillBudgetsServlet", "Billing: contract budgets" },
          { "BillPlansServlet", "Billing: allocation plans" },
          { "BillingProjectAssignmentsServlet", "Billing: project assignments" },
          { "BillingProjectReportServlet", "Billing: project report" },
      });
    }
    printGroup(out, "legacy", "Legacy", new String[][] {
        { "ProjectsServlet", "Projects (old list)" },
        { "ProjectNarrativeReviewServlet", "Project narrative review" },
        { "trackerNarrative", "Narrative" },
    });
    out.println("</div>");
    out.println("</div>");
    printGroupScript(out);
  }

  private void printFocusCard(AppReq appReq, PrintWriter out) {
    String status;
    String button = "Start";
    try {
      ActionNext current = appReq.getCompletingAction();
      TimeTracker timeTracker = appReq.getTimeTracker();
      boolean running = timeTracker != null && timeTracker.isRunningClock();
      if (current != null) {
        button = "Resume";
        String project = current.getProject() == null ? ""
            : getProjectDisplayName(appReq.getDataSession(), current.getProject());
        status = (running ? "&#9654; " : "")
            + "<span class=\"lp-strong\">"
            + WebEscaper.escapeHtml(trimForDisplay(n(current.getNextDescription()), 70)) + "</span>"
            + (project.length() > 0 ? "<br><span class=\"lp-dim\">" + WebEscaper.escapeHtml(project) + "</span>" : "");
      } else {
        status = running ? "Clock running, no action selected" : "Picks up your next action for today";
      }
    } catch (Exception e) {
      e.printStackTrace();
      status = "Work one action at a time";
    }
    printCard(out, "FocusedActionServlet", "Focus", status, button);
  }

  private void printDashboardCard(AppReq appReq, PrintWriter out) {
    String status;
    try {
      WebUser webUser = appReq.getWebUser();
      int billed = loadBillableMinutesToday(appReq);
      int target = dayCapacityService.loadTargetMinutesForDay(appReq, webUser.toDate(webUser.getLocalDateToday()));
      DashboardTodayColumnService.DayPile pile = dashboardTodayColumnService.loadDayPile(appReq);
      status = "<span class=\"lp-strong\">" + TimeTracker.formatTime(billed) + "</span> of "
          + TimeTracker.formatTime(target) + " today"
          + "<br><span class=\"lp-dim\">" + TimeTracker.formatTime(pile.getRemainingMinutes()) + " planned left"
          + (pile.getUnestimatedCount() > 0 ? ", " + pile.getUnestimatedCount() + " unestimated" : "")
          + "</span>";
    } catch (Exception e) {
      e.printStackTrace();
      status = "Today's actions and time";
    }
    printCard(out, "DandelionDashboardServlet", "Dashboard", status, "Open");
  }

  private void printPlanAheadCard(AppReq appReq, PrintWriter out) {
    String status;
    try {
      Session dataSession = appReq.getDataSession();
      TimeTracker weekTracker = new TimeTracker(appReq.getWebUser(), new Date(), Calendar.WEEK_OF_YEAR,
          dataSession);
      int billed = loadWeeklyBillableMinutesRounded(dataSession, weekTracker);
      int target = dayCapacityService.loadTargetMinutesForCurrentWeek(appReq);
      status = "<span class=\"lp-strong\">" + TimeTracker.formatTime(billed) + "</span> of "
          + TimeTracker.formatTime(target) + " this week"
          + "<br><span class=\"lp-dim\">Schedule the days ahead</span>";
    } catch (Exception e) {
      e.printStackTrace();
      status = "Schedule the days ahead";
    }
    printCard(out, "PlanAheadServlet", "Plan Ahead", status, "Open");
  }

  private int loadBillableMinutesToday(AppReq appReq) {
    TimeTracker timeTracker = appReq.getTimeTracker();
    if (timeTracker == null) {
      timeTracker = new TimeTracker(appReq.getWebUser(), appReq.getDataSession());
    }
    return Math.max(0, timeTracker.getTotalMinsBillable());
  }

  private void printCard(PrintWriter out, String href, String title, String statusHtml, String button) {
    out.println("  <a class=\"lp-card\" href=\"" + href + "\">");
    out.println("    <span class=\"lp-title\">" + title + "</span>");
    out.println("    <span class=\"lp-status\">" + statusHtml + "</span>");
    out.println("    <span class=\"lp-go\">" + button + " &rarr;</span>");
    out.println("  </a>");
  }

  private void printGroup(PrintWriter out, String key, String title, String[][] links) {
    out.println("  <details class=\"lp-group\" data-key=\"" + key + "\">");
    out.println("    <summary>" + title + "</summary>");
    out.println("    <ul>");
    for (String[] link : links) {
      if (link != null) {
        out.println("      <li><a href=\"" + link[0] + "\">" + link[1] + "</a></li>");
      }
    }
    out.println("    </ul>");
    out.println("  </details>");
  }

  private void printStyle(PrintWriter out) {
    out.println("<style>");
    out.println("  .lp { max-width:720px; }");
    out.println("  .lp-cards { display:grid; grid-template-columns:repeat(auto-fit, minmax(200px, 1fr)); gap:12px; margin:8px 0 20px; }");
    out.println("  a.lp-card, a.lp-card:visited { display:flex; flex-direction:column; gap:8px; padding:14px 16px;");
    out.println("    border:1px solid #2B3E42; border-radius:6px; background:#FFFFFF; color:#222222; text-decoration:none; }");
    out.println("  a.lp-card:hover { background:#F4F8F4; }");
    out.println("  .lp-title { font-size:1.3em; font-weight:bold; }");
    out.println("  .lp-status { flex:1; line-height:1.4; }");
    out.println("  .lp-strong { font-weight:bold; }");
    out.println("  .lp-dim { color:#666666; font-size:0.9em; }");
    out.println("  .lp-go { align-self:flex-start; font-weight:bold; }");
    out.println("  .lp-group { border-top:1px solid #CCCCCC; padding:6px 0; }");
    out.println("  .lp-group summary { cursor:pointer; font-weight:bold; padding:4px 0; }");
    out.println("  .lp-group ul { margin:4px 0 6px; padding-left:20px; columns:2; }");
    out.println("  @media (max-width:480px) { .lp-group ul { columns:1; } }");
    out.println("</style>");
  }

  private void printGroupScript(PrintWriter out) {
    out.println("<script>");
    out.println("  (function() {");
    out.println("    var groups = document.querySelectorAll('details.lp-group');");
    out.println("    for (var i = 0; i < groups.length; i++) {");
    out.println("      (function(d) {");
    out.println("        var key = 'dandelion.home.' + d.getAttribute('data-key');");
    out.println("        try { if (localStorage.getItem(key) === 'open') { d.open = true; } } catch (e) {}");
    out.println("        d.addEventListener('toggle', function() {");
    out.println("          try { localStorage.setItem(key, d.open ? 'open' : 'closed'); } catch (e) {}");
    out.println("        });");
    out.println("      })(groups[i]);");
    out.println("    }");
    out.println("  })();");
    out.println("</script>");
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

  @Override
  public String getServletInfo() {
    return "Dandelion launchpad";
  }
}
