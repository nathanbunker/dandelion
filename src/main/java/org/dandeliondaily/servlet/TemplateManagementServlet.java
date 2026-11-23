package org.dandeliondaily.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.dashboard.service.DashboardTodayColumnService;
import org.dandeliondaily.planahead.model.PlanAheadMutationResult;
import org.dandeliondaily.planahead.service.PlanAheadBoardService;
import org.dandeliondaily.planahead.service.PlanAheadMutationService;
import org.dandeliondaily.shared.render.QuickCaptureRenderer;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.TemplateType;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

/**
 * Dedicated page for listing and managing recurring action templates.
 * GET → renders full HTML template list + add form.
 * POST → handles AJAX mutations: saveTemplate, deleteTemplate,
 * reactivateTemplate.
 */
public class TemplateManagementServlet extends ClientServlet {

        private static final long serialVersionUID = 1L;

        private final PlanAheadMutationService mutationService = new PlanAheadMutationService();
        private final PlanAheadBoardService boardService = new PlanAheadBoardService();
        private final DashboardTodayColumnService dashboardTodayColumnService = new DashboardTodayColumnService();
        private final QuickCaptureRenderer quickCaptureRenderer = new QuickCaptureRenderer();

        protected void processRequest(HttpServletRequest request, HttpServletResponse response)
                        throws ServletException, IOException {
                AppReq appReq = new AppReq(request, response);
                try {
                        if (appReq.isLoggedOut()) {
                                forwardToHome(request, response);
                                return;
                        }

                        String action = request.getParameter("action");
                        if ("saveTemplate".equals(action)) {
                                handleMutationResult(appReq, mutationService.saveTemplateEdit(appReq));
                                return;
                        }
                        if ("deleteTemplate".equals(action)) {
                                handleMutationResult(appReq, mutationService.deleteTemplateEdit(appReq));
                                return;
                        }
                        if ("reactivateTemplate".equals(action)) {
                                handleMutationResult(appReq, mutationService.reactivateTemplate(appReq));
                                return;
                        }
                        if ("setMode".equals(action)) {
                                String mode = request.getParameter("mode");
                                boardService.setMode(appReq, mode);
                                response.sendRedirect("TemplateManagementServlet");
                                return;
                        }
                        // Default: render the page
                        appReq.setTitle("Plan Ahead");
                        printHtmlHead(appReq);
                        renderPage(appReq);
                        printHtmlFoot(appReq);
                } catch (Exception e) {
                        e.printStackTrace();
                } finally {
                        appReq.close();
                }
        }

        // =========================================================================
        // Page rendering
        // =========================================================================

        private void renderPage(AppReq appReq) throws Exception {
                PrintWriter out = appReq.getOut();
                String mode = boardService.resolveMode(appReq);
                boolean personalMode = PlanAheadBoardService.MODE_PERSONAL.equalsIgnoreCase(mode);

                // Today key for nav links
                java.text.SimpleDateFormat todaySdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                todaySdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                String todayKey = todaySdf.format(appReq.getWebUser().getToday());

                // Project names for Quick Capture autocomplete
                List<String> qcProjectNames = dashboardTodayColumnService.listQuickCaptureProjectNames(appReq);

                out.println("<style>");
                // PA header styles
                out.println(
                                ".pa-page{padding:12px 18px 24px 18px;background:linear-gradient(180deg,#f4f0e8 0%,#efe7db 40%,#f8f6f1 100%);min-height:100vh;}");
                out.println(
                                ".pa-intro-bar{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;flex-wrap:wrap;margin-bottom:8px;}");
                out.println(".pa-intro h1{margin:0;color:#2d3a2d;}");
                out.println(".pa-intro p{margin:6px 0 14px 0;color:#425541;}");
                out.println(".pa-quick-capture{margin-left:auto;min-width:360px;max-width:520px;flex:1 1 420px;}");
                out.println(".pa-controls{display:flex;gap:8px;margin-bottom:16px;}");
                out.println(
                                ".pa-shift{display:inline-block;padding:6px 10px;background:#49654a;color:#fff;text-decoration:none;border-radius:4px;font-size:0.9em;}");
                out.println(".pa-shift-active{background:#2d3d2d;box-shadow:inset 0 2px 4px rgba(0,0,0,.25);cursor:default;}");
                // Template-specific styles
                out.println(".tmpl-content{max-width:960px;}");
                out.println(".tmpl-mode-toggle{margin-bottom:16px;}");
                out.println(
                                ".tmpl-mode-toggle a{padding:6px 14px;border:1px solid #aaa;border-radius:3px;text-decoration:none;color:#333;margin-right:4px;}");
                out.println(".tmpl-mode-toggle a.active{background:#49654a;color:#fff;border-color:#2d3d2d;}");
                out.println(".tmpl-table{width:100%;border-collapse:collapse;margin-bottom:24px;font-size:0.9em;}");
                out.println(
                                ".tmpl-table th{background:#f1f5f9;text-align:left;padding:6px 8px;border-bottom:2px solid #cbd5e1;}");
                out.println(".tmpl-table td{padding:6px 8px;border-bottom:1px solid #e2e8f0;vertical-align:top;}");
                out.println(".tmpl-row{cursor:pointer;}");
                out.println(".tmpl-row:hover td{background:#eef3ee !important;}");
                out.println(".tmpl-row-active td{background:#e6ede6 !important;}");
                out.println(".tmpl-edit-row td{background:#f1f5ef;}");
                out.println(
                                ".tmpl-form{background:#f8fafc;border:1px solid #e2e8f0;border-radius:4px;padding:16px;margin-bottom:24px;}");
                out.println(".tmpl-form h3{margin:0 0 12px;font-size:1em;}");
                out.println(
                                ".tmpl-form label{display:block;margin-bottom:4px;font-size:0.85em;font-weight:600;color:#475569;}");
                out.println(
                                ".tmpl-form input[type=text],.tmpl-form select,.tmpl-form textarea{width:100%;box-sizing:border-box;padding:5px 7px;border:1px solid #cbd5e1;border-radius:3px;font-size:0.9em;margin-bottom:10px;}");
                out.println(".tmpl-form .row2{display:grid;grid-template-columns:1fr 1fr;gap:12px;}");
                out.println(".tmpl-form .row3{display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px;}");
                out.println(".tmpl-btn{padding:6px 14px;border:none;border-radius:3px;cursor:pointer;font-size:0.88em;}");
                out.println(".tmpl-btn-primary{background:#49654a;color:#fff;}");
                out.println(".tmpl-btn-secondary{background:#e2e8f0;color:#334155;}");
                out.println(".tmpl-btn-danger{background:#ef4444;color:#fff;margin-left:6px;}");
                out.println(".tmpl-btn-warn{background:#f59e0b;color:#fff;margin-left:6px;}");
                out.println(".tmpl-msg{padding:10px 14px;border-radius:3px;margin-bottom:12px;display:none;}");
                out.println(".tmpl-msg.success{background:#dcfce7;color:#166534;border:1px solid #bbf7d0;}");
                out.println(".tmpl-msg.error{background:#fee2e2;color:#991b1b;border:1px solid #fecaca;}");
                out.println("</style>");

                out.println("<div class='pa-page'>");

                // PA-style intro bar with Quick Capture
                out.println("  <div class='pa-intro-bar'>");
                out.println("    <div class='pa-intro'>");
                out.println("      <h1>Plan Ahead</h1>");
                out.println("      <p>"
                                + (personalMode ? "Manage personal recurring templates."
                                                : "Manage work recurring templates.")
                                + "</p>");
                out.println("    </div>");
                quickCaptureRenderer.render(out, new QuickCaptureRenderer.Config("PlanAheadServlet")
                                .projectNames(qcProjectNames)
                                .boxClass("pa-quick-capture"));
                out.println("  </div>");

                // PA-style nav controls
                out.println("  <div class='pa-controls'>");
                out.println("    <a class='pa-shift' href='PlanAheadServlet?windowStart=" + todayKey + "'>Today</a>");
                out.println("    <a class='pa-shift' href='PlanAheadServlet?action=shiftWindowForward&days=1&windowStart="
                                + todayKey + "'>Next Day &#9654;</a>");
                out.println("    <span class='pa-shift pa-shift-active'>Manage Templates</span>");
                out.println("  </div>");

                out.println("<div class='tmpl-content'>");
                out.println("<div id='tmplMsg' class='tmpl-msg'></div>");

                // Mode toggle
                out.println("<div class='tmpl-mode-toggle'>");
                out.println("<a href='TemplateManagementServlet?action=setMode&mode=WORK' class='"
                                + (personalMode ? "" : "active") + "'>Work</a>");
                out.println("<a href='TemplateManagementServlet?action=setMode&mode=PERSONAL' class='"
                                + (personalMode ? "active" : "") + "'>Personal</a>");
                out.println("</div>");

                // Template list
                out.println("<h2 style='font-size:1em;margin-bottom:8px;'>"
                                + (personalMode ? "Personal" : "Work") + " Templates</h2>");
                renderTemplateTable(appReq, out, personalMode);

                // Add form
                out.println("<div style='margin-bottom:12px;'>");
                out.println(
                                "<button class='tmpl-btn tmpl-btn-primary' onclick='tmplShowAddForm()'>+ Add New Template</button>");
                out.println("</div>");
                renderAddForm(appReq, out, personalMode);

                // JS
                renderJavaScript(out, personalMode);

                out.println("</div>"); // end tmpl-content
                out.println("</div>"); // end pa-page
        }

        @SuppressWarnings("unchecked")
        private void renderTemplateTable(AppReq appReq, PrintWriter out, boolean personalMode) throws Exception {
                Session session = appReq.getDataSession();
                boolean billable = !personalMode;

                Query query = session.createQuery(
                                "from ActionNext an where an.workspaceId = :workspaceId "
                                                + "and an.contactId = :contactId "
                                                + "and an.billable = :billable "
                                                + "and an.templateTypeString is not null and an.templateTypeString <> '' "
                                                + "and (an.templateActionNextId is null or an.templateActionNextId = 0) "
                                                + "order by an.nextDescription");
                query.setParameter("workspaceId", appReq.getActiveWorkspaceId());
                query.setParameter("contactId", appReq.getWebUser().getContactId());
                query.setParameter("billable", billable);

                List<ActionNext> templates = query.list();
                List<Project> projects = loadFilteredProjects(session, appReq.getActiveWorkspaceId(), personalMode);
                int colspan = 6;

                out.println("<table class='tmpl-table'>");
                out.println("<thead><tr>");
                out.println("<th>Description</th>");
                out.println("<th>Type</th>");
                out.println("<th>Project</th>");
                out.println("<th>Schedule</th>");
                out.println("<th>Auto-Gen</th>");
                out.println("<th>Missed</th>");
                out.println("</tr></thead><tbody>");

                if (templates.isEmpty()) {
                        out.println("<tr><td colspan='" + colspan
                                        + "' style='color:#94a3b8;font-style:italic;'>No templates found. Click \"+ Add New Template\" below.</td></tr>");
                }

                for (ActionNext t : templates) {
                        ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(
                                        ActionNextTemplateConfig.class, t.getActionNextId());
                        boolean cancelled = ProjectNextActionStatus.CANCELLED.equals(t.getNextActionStatus());
                        if (cancelled) {
                                continue;
                        }
                        int id = t.getActionNextId();
                        boolean autoGenOn = config != null && config.isAutoGenerate();
                        String missedBehavior = config != null && config.getMissedActionBehavior() != null
                                        ? config.getMissedActionBehavior()
                                        : "AUTO_CANCEL";
                        String schedDow = config != null && config.getScheduleDaysOfWeek() != null
                                        ? config.getScheduleDaysOfWeek()
                                        : "";
                        String schedDom = config != null && config.getScheduleDaysOfMonth() != null
                                        ? config.getScheduleDaysOfMonth()
                                        : "";
                        String schedDoq = config != null && config.getScheduleDaysOfQuarter() != null
                                        ? config.getScheduleDaysOfQuarter()
                                        : "";
                        String schedDoy = config != null && config.getScheduleDaysOfYear() != null
                                        ? config.getScheduleDaysOfYear()
                                        : "";
                        String editProjectId = t.getProject() != null ? String.valueOf(t.getProject().getProjectId())
                                        : "";
                        String editActionType = t.getNextActionType() != null ? t.getNextActionType() : "";
                        int editTimeEst = t.getNextTimeEstimate() != null ? t.getNextTimeEstimate().intValue() : 0;
                        String editTimeSlot = t.getTimeSlot() != null ? t.getTimeSlot().getId() : "";
                        String editTmplType = t.getTemplateType() != null ? t.getTemplateType().getId() : "D";

                        // Main clickable row
                        out.println("<tr class='tmpl-row' id='tmplRow_" + id + "' onclick='tmplToggleEdit(" + id
                                        + ")'>");
                        out.println("<td>" + t.getNextDescriptionForDisplay(null) + "</td>");
                        out.println("<td>" + (t.getTemplateType() != null ? t.getTemplateType().getLabel() : "")
                                        + "</td>");
                        String projectName = t.getProject() != null ? getProjectDisplayName(session, t.getProject())
                                        : "";
                        out.println("<td>" + esc(projectName) + "</td>");
                        String schedule = config != null ? summarizeSchedule(t.getTemplateType(), config) : "";
                        out.println("<td>" + esc(schedule) + "</td>");
                        out.println("<td>" + (autoGenOn ? "Yes" : "No") + "</td>");
                        out.println("<td>" + formatMissed(missedBehavior) + "</td>");
                        out.println("</tr>");

                        // Inline edit row — hidden until the row above is clicked
                        out.println("<tr id='tmplEditRow_" + id + "' class='tmpl-edit-row' style='display:none;'>");
                        out.println("<td colspan='" + colspan + "' style='padding:0;'>");
                        out.println("<div class='tmpl-form' style='margin:0;border-radius:0;border-top:none;'>");

                        // Project + estimate/timeslot
                        out.println("<div class='row2'><div>");
                        out.println("<label>Project</label>");
                        out.println("<select id='tmplPid_" + id + "'>");
                        out.println("<option value=''>-- None --</option>");
                        for (Project p : projects) {
                                out.println("<option value='" + p.getProjectId() + "'"
                                                + sel(editProjectId, String.valueOf(p.getProjectId())) + ">"
                                                + esc(getProjectDisplayName(session, p)) + "</option>");
                        }
                        out.println("</select></div>");
                        if (!personalMode) {
                                out.println("<div><label>Estimate (minutes)</label>");
                                out.println("<input type='text' id='tmplEst_" + id + "' value='" + editTimeEst
                                                + "'></div>");
                        } else {
                                out.println("<div><label>Time Slot</label>");
                                out.println("<select id='tmplTS_" + id + "'>");
                                for (TimeSlot ts : TimeSlot.values()) {
                                        out.println("<option value='" + ts.getId() + "'" + sel(editTimeSlot, ts.getId())
                                                        + ">"
                                                        + ts.getLabel() + "</option>");
                                }
                                out.println("</select></div>");
                        }
                        out.println("</div>"); // end row2

                        // Commitment type (full width)
                        out.println("<label>Commitment Type</label>");
                        out.println("<select id='tmplNAT_" + id + "'>");
                        out.println("<option value='" + ProjectNextActionType.WILL + "'"
                                        + sel(editActionType, ProjectNextActionType.WILL) + ">I will</option>");
                        out.println("<option value='" + ProjectNextActionType.WILL_MEET + "'"
                                        + sel(editActionType, ProjectNextActionType.WILL_MEET)
                                        + ">I will meet</option>");
                        out.println("<option value='" + ProjectNextActionType.WILL_CONTACT + "'"
                                        + sel(editActionType, ProjectNextActionType.WILL_CONTACT)
                                        + ">I will contact</option>");
                        out.println("<option value='" + ProjectNextActionType.WILL_FOLLOW_UP + "'"
                                        + sel(editActionType, ProjectNextActionType.WILL_FOLLOW_UP)
                                        + ">I will follow up</option>");
                        out.println("<option value='" + ProjectNextActionType.WILL_REVIEW + "'"
                                        + sel(editActionType, ProjectNextActionType.WILL_REVIEW)
                                        + ">I will review</option>");
                        out.println("<option value='" + ProjectNextActionType.WILL_DOCUMENT + "'"
                                        + sel(editActionType, ProjectNextActionType.WILL_DOCUMENT)
                                        + ">I will document</option>");
                        out.println("<option value='" + ProjectNextActionType.MIGHT + "'"
                                        + sel(editActionType, ProjectNextActionType.MIGHT) + ">I might</option>");
                        out.println("<option value='" + ProjectNextActionType.WOULD_LIKE_TO + "'"
                                        + sel(editActionType, ProjectNextActionType.WOULD_LIKE_TO)
                                        + ">I would like to</option>");
                        out.println("<option value='" + ProjectNextActionType.COMMITTED_TO + "'"
                                        + sel(editActionType, ProjectNextActionType.COMMITTED_TO)
                                        + ">I have committed to</option>");
                        out.println("<option value='" + ProjectNextActionType.GOAL + "'"
                                        + sel(editActionType, ProjectNextActionType.GOAL) + ">Goal</option>");
                        out.println("<option value='" + ProjectNextActionType.WAITING + "'"
                                        + sel(editActionType, ProjectNextActionType.WAITING) + ">Waiting</option>");
                        out.println("</select>");

                        out.println("<label>Description</label>");
                        out.println("<textarea id='tmplDesc_" + id + "' rows='2'>" + esc(t.getNextDescription())
                                        + "</textarea>");

                        out.println("<div class='row2'><div>");
                        out.println("<label>Recurrence</label>");
                        out.println("<select id='tmplTmplType_" + id + "' onchange='tmplUpdateSched(" + id + ")'>");
                        for (TemplateType tt : TemplateType.values()) {
                                out.println("<option value='" + tt.getId() + "'" + sel(editTmplType, tt.getId()) + ">"
                                                + tt.getLabel()
                                                + "</option>");
                        }
                        out.println("</select></div><div>");
                        out.println("<label>Missed Action</label>");
                        out.println("<select id='tmplMissed_" + id + "'>");
                        out.println("<option value='AUTO_CANCEL'" + sel(missedBehavior, "AUTO_CANCEL")
                                        + ">Auto Cancel</option>");
                        out.println(
                                        "<option value='CARRY_FORWARD'" + sel(missedBehavior, "CARRY_FORWARD")
                                                        + ">Carry Forward</option>");
                        out.println("<option value='IGNORE'" + sel(missedBehavior, "IGNORE") + ">Ignore</option>");
                        out.println("</select></div></div>");

                        out.println("<label style='display:inline;'><input type='checkbox' id='tmplAutoGen_" + id + "'"
                                        + (autoGenOn ? " checked" : "") + "> Auto-generate instances</label><br><br>");

                        out.println("<div id='tmplSchedW_" + id + "'"
                                        + ("W".equals(editTmplType) ? "" : " style='display:none;'")
                                        + ">");
                        out.println("<label>Days of Week (e.g. MON,WED,FRI)</label>");
                        out.println("<input type='text' id='tmplDow_" + id + "' value='" + esc(schedDow)
                                        + "' placeholder='MON,TUE,WED,THU,FRI'>");
                        out.println("</div>");
                        out.println("<div id='tmplSchedM_" + id + "'"
                                        + ("M".equals(editTmplType) ? "" : " style='display:none;'")
                                        + ">");
                        out.println("<label>Days of Month (e.g. 1,15 or L for last day)</label>");
                        out.println("<input type='text' id='tmplDom_" + id + "' value='" + esc(schedDom)
                                        + "' placeholder='1,15'>");
                        out.println("</div>");
                        out.println("<div id='tmplSchedQ_" + id + "'"
                                        + ("Q".equals(editTmplType) ? "" : " style='display:none;'")
                                        + ">");
                        out.println("<label>Days of Quarter (e.g. 1,45,90)</label>");
                        out.println("<input type='text' id='tmplDoq_" + id + "' value='" + esc(schedDoq)
                                        + "' placeholder='1'>");
                        out.println("</div>");
                        out.println("<div id='tmplSchedY_" + id + "'"
                                        + ("Y".equals(editTmplType) ? "" : " style='display:none;'")
                                        + ">");
                        out.println("<label>Days of Year (e.g. 01-01,07-04)</label>");
                        out.println(
                                        "<input type='text' id='tmplDoy_" + id + "' value='" + esc(schedDoy)
                                                        + "' placeholder='01-01'>");
                        out.println("</div>");

                        out.println(agentFields(appReq, t, String.valueOf(id)));
                        out.println("<label>Link URL (optional)</label>");
                        out.println("<input type='text' id='tmplLink_" + id + "' value='"
                                        + esc(t.getLinkUrl() != null ? t.getLinkUrl() : "")
                                        + "' placeholder='https://'>");
                        out.println("<label>Notes (optional)</label>");
                        out.println("<textarea id='tmplNote_" + id + "' rows='2'>"
                                        + esc(t.getNextNotes() != null ? t.getNextNotes() : "") + "</textarea>");

                        out.println("<div style='margin-top:8px;'>");
                        out.println("<button class='tmpl-btn tmpl-btn-primary' onclick='tmplSave(" + id
                                        + ")'>Save</button>");
                        out.println("<button class='tmpl-btn tmpl-btn-secondary' style='margin-left:8px;' onclick='tmplToggleEdit("
                                        + id + ")'>Cancel</button>");
                        out.println("<button class='tmpl-btn tmpl-btn-danger' style='margin-left:8px;' onclick='tmplDelete("
                                        + id
                                        + ")'>Delete</button>");
                        if (!autoGenOn) {
                                out.println("<button class='tmpl-btn tmpl-btn-warn' style='margin-left:8px;' onclick='tmplReactivate("
                                                + id + ")'>Reactivate Auto-Gen</button>");
                        }
                        out.println("</div>");
                        out.println("</div></td></tr>"); // end form, td, edit row
                }

                out.println("</tbody></table>");
        }

        private void renderAddForm(AppReq appReq, PrintWriter out, boolean personalMode) throws Exception {
                Session session = appReq.getDataSession();
                List<Project> projects = loadFilteredProjects(session, appReq.getActiveWorkspaceId(), personalMode);

                out.println("<div class='tmpl-form' id='tmplForm'>");
                out.println("<h3 id='tmplFormTitle'>Add Template</h3>");
                out.println("<input type='hidden' id='tmplMode' value='add'>");
                out.println("<input type='hidden' id='tmplActionNextId' value=''>");

                // Project selector (both modes, filtered by work/personal)
                out.println("<label>Project</label>");
                out.println("<select id='tmplProjectId' name='projectId'>");
                out.println("<option value=''>-- None --</option>");
                for (Project p : projects) {
                        out.println("<option value='" + p.getProjectId() + "'>" + esc(getProjectDisplayName(session, p))
                                        + "</option>");
                }
                out.println("</select>");

                if (!personalMode) {
                        out.println("<label>Estimate (minutes)</label>");
                        out.println("<input type='text' id='tmplNextTimeEstimate' name='nextTimeEstimate' placeholder='0'>");
                } else {
                        // Personal: time slot
                        out.println("<label>Time Slot</label>");
                        out.println("<select id='tmplTimeSlot' name='timeSlot'>");
                        for (TimeSlot ts : TimeSlot.values()) {
                                out.println("<option value='" + ts.getId() + "'>" + ts.getLabel() + "</option>");
                        }
                        out.println("</select>");
                }

                // Commitment Type — shown after project/time fields, before description
                out.println("<label>Commitment Type</label>");
                out.println("<select id='tmplNextActionType' name='nextActionType'>");
                out.println("<option value='" + ProjectNextActionType.WILL + "'>I will</option>");
                out.println("<option value='" + ProjectNextActionType.WILL_MEET + "'>I will meet</option>");
                out.println("<option value='" + ProjectNextActionType.WILL_CONTACT + "'>I will contact</option>");
                out.println("<option value='" + ProjectNextActionType.WILL_FOLLOW_UP + "'>I will follow up</option>");
                out.println("<option value='" + ProjectNextActionType.WILL_REVIEW + "'>I will review</option>");
                out.println("<option value='" + ProjectNextActionType.WILL_DOCUMENT + "'>I will document</option>");
                out.println("<option value='" + ProjectNextActionType.MIGHT + "'>I might</option>");
                out.println("<option value='" + ProjectNextActionType.WOULD_LIKE_TO + "'>I would like to</option>");
                out.println("<option value='" + ProjectNextActionType.COMMITTED_TO + "'>I have committed to</option>");
                out.println("<option value='" + ProjectNextActionType.GOAL + "'>Goal</option>");
                out.println("<option value='" + ProjectNextActionType.WAITING + "'>Waiting</option>");
                out.println("</select>");

                out.println("<label>Description</label>");
                out.println("<textarea id='tmplNextDescription' name='nextDescription' rows='2'></textarea>");

                out.println("<div class='row2'>");
                out.println("<div>");
                out.println("<label>Template Type (Recurrence)</label>");
                out.println("<select id='tmplTemplateType' name='templateType' onchange='tmplUpdateScheduleFields()'>");
                for (TemplateType tt : TemplateType.values()) {
                        out.println("<option value='" + tt.getId() + "'>" + tt.getLabel() + "</option>");
                }
                out.println("</select>");
                out.println("</div>");
                out.println("<div>");
                out.println("<label>Missed Action Behavior</label>");
                out.println("<select id='tmplMissedActionBehavior' name='missedActionBehavior'>");
                out.println("<option value='AUTO_CANCEL'>Auto Cancel</option>");
                out.println("<option value='CARRY_FORWARD'>Carry Forward</option>");
                out.println("<option value='IGNORE'>Ignore</option>");
                out.println("</select>");
                out.println("</div>");
                out.println("</div>");

                // Auto-generate toggle
                out.println(
                                "<label><input type='checkbox' id='tmplAutoGenerate' name='autoGenerate' value='Y' checked> Auto-generate instances</label><br><br>");

                // Schedule patterns (shown/hidden based on type)
                out.println("<div id='tmplScheduleWeekly' style='display:none;'>");
                out.println("<label>Days of Week (e.g. MON,WED,FRI)</label>");
                out.println(
                                "<input type='text' id='tmplScheduleDaysOfWeek' name='scheduleDaysOfWeek' placeholder='MON,TUE,WED,THU,FRI'>");
                out.println("</div>");

                out.println("<div id='tmplScheduleMonthly' style='display:none;'>");
                out.println("<label>Days of Month (e.g. 1,15 or L for last day)</label>");
                out.println("<input type='text' id='tmplScheduleDaysOfMonth' name='scheduleDaysOfMonth' placeholder='1,15'>");
                out.println("</div>");

                out.println("<div id='tmplScheduleQuarterly' style='display:none;'>");
                out.println("<label>Days of Quarter (e.g. 1,45,90)</label>");
                out.println("<input type='text' id='tmplScheduleDaysOfQuarter' name='scheduleDaysOfQuarter' placeholder='1'>");
                out.println("</div>");

                out.println("<div id='tmplScheduleYearly' style='display:none;'>");
                out.println("<label>Days of Year (e.g. 01-01,07-04 or MM-DD format)</label>");
                out.println("<input type='text' id='tmplScheduleDaysOfYear' name='scheduleDaysOfYear' placeholder='01-01'>");
                out.println("</div>");

                out.println(agentFields(appReq, null, "New"));
                out.println("<label>Link URL (optional)</label>");
                out.println("<input type='text' id='tmplLinkUrl' name='linkUrl' placeholder='https://'>");

                out.println("<label>Notes (optional)</label>");
                out.println("<textarea id='tmplNextNote' name='nextNote' rows='2'></textarea>");

                out.println("<div style='margin-top:8px;'>");
                out.println("<button class='tmpl-btn tmpl-btn-primary' onclick='tmplSubmitForm()'>Save Template</button>");
                out.println(
                                "<button class='tmpl-btn tmpl-btn-secondary' style='margin-left:8px;' onclick='tmplShowAddForm()'>Clear</button>");
                out.println(
                                "<button id='tmplDeleteBtn' class='tmpl-btn tmpl-btn-danger' style='margin-left:8px;display:none;' onclick='tmplDeleteCurrent()'>Delete Template</button>");

                out.println("</div>");
                out.println("</div>");
        }

        private void renderJavaScript(PrintWriter out, boolean personalMode) {
                out.println("<script>");

                // Shared message utility
                out.println("function tmplShowMsg(msg, ok) {");
                out.println("  var el = document.getElementById('tmplMsg');");
                out.println("  el.textContent = msg;");
                out.println("  el.className = 'tmpl-msg ' + (ok ? 'success' : 'error');");
                out.println("  el.style.display = 'block';");
                out.println("  window.scrollTo(0, 0);");
                out.println("}");

                // Add-form schedule visibility
                out.println("function tmplUpdateScheduleFields() {");
                out.println("  var t = document.getElementById('tmplTemplateType').value;");
                out.println("  document.getElementById('tmplScheduleWeekly').style.display = (t==='W') ? '' : 'none';");
                out.println("  document.getElementById('tmplScheduleMonthly').style.display = (t==='M') ? '' : 'none';");
                out.println("  document.getElementById('tmplScheduleQuarterly').style.display = (t==='Q') ? '' : 'none';");
                out.println("  document.getElementById('tmplScheduleYearly').style.display = (t==='Y') ? '' : 'none';");
                out.println("}");
                out.println("tmplUpdateScheduleFields();");

                out.println("function tmplGetField(id) {");
                out.println("  var el = document.getElementById(id);");
                out.println("  return el ? el.value : '';");
                out.println("}");

                out.println("function tmplAppendAgent(params, key) {");
                out.println("  var a = document.getElementById('tmplAgent_' + key); if (!a) { return; }");
                out.println("  params.append('performedByAgent', a.value);");
                out.println("  ['HandoffMode','HandoffTemplateId','HandoffProjectId','WorkloadKey'].forEach(function (f) {");
                out.println("    var el = document.getElementById('tmplAgent' + f + '_' + key);");
                out.println("    params.append('agent' + f, el ? el.value : '');");
                out.println("  });");
                out.println("}");
                // Add-form save
                out.println("function tmplSubmitForm() {");
                out.println("  var params = new URLSearchParams();");
                out.println("  params.append('action', 'saveTemplate');");
                out.println("  params.append('mode', tmplGetField('tmplMode'));");
                out.println("  params.append('actionNextId', tmplGetField('tmplActionNextId'));");
                out.println("  params.append('templateType', tmplGetField('tmplTemplateType'));");
                out.println("  params.append('nextDescription', tmplGetField('tmplNextDescription'));");
                out.println("  params.append('missedActionBehavior', tmplGetField('tmplMissedActionBehavior'));");
                out.println("  var autoGen = document.getElementById('tmplAutoGenerate');");
                out.println("  params.append('autoGenerate', (autoGen && autoGen.checked) ? 'Y' : 'N');");
                out.println("  params.append('scheduleDaysOfWeek', tmplGetField('tmplScheduleDaysOfWeek'));");
                out.println("  params.append('scheduleDaysOfMonth', tmplGetField('tmplScheduleDaysOfMonth'));");
                out.println("  params.append('scheduleDaysOfQuarter', tmplGetField('tmplScheduleDaysOfQuarter'));");
                out.println("  params.append('scheduleDaysOfYear', tmplGetField('tmplScheduleDaysOfYear'));");
                out.println("  params.append('linkUrl', tmplGetField('tmplLinkUrl'));");
                out.println("  tmplAppendAgent(params, 'New');");
                out.println("  params.append('nextNote', tmplGetField('tmplNextNote'));");
                out.println("  params.append('nextActionType', tmplGetField('tmplNextActionType'));");
                out.println("  params.append('projectId', tmplGetField('tmplProjectId'));");
                if (personalMode) {
                        out.println("  params.append('timeSlot', tmplGetField('tmplTimeSlot'));");
                        out.println("  params.append('nextTimeEstimate', '0');");
                } else {
                        out.println("  params.append('nextTimeEstimate', tmplGetField('tmplNextTimeEstimate'));");
                }
                out.println("  fetch('TemplateManagementServlet', {method:'POST', body:params})");
                out.println("    .then(r => r.json()).then(data => {");
                out.println("      tmplShowMsg(data.message, data.success);");
                out.println("      if (data.success) { setTimeout(function(){ location.reload(); }, 1000); }");
                out.println("    }).catch(e => tmplShowMsg('Request failed: ' + e, false));");
                out.println("}");

                // Add-form delete
                out.println("function tmplDeleteCurrent() {");
                out.println("  var id = tmplGetField('tmplActionNextId');");
                out.println("  if (!id) return;");
                out.println("  if (!confirm('Delete this template? Its open actions from today on will be cancelled.')) return;");
                out.println("  var params = new URLSearchParams();");
                out.println("  params.append('action', 'deleteTemplate');");
                out.println("  params.append('actionNextId', id);");
                out.println("  fetch('TemplateManagementServlet', {method:'POST', body:params})");
                out.println("    .then(r => r.json()).then(data => {");
                out.println("      tmplShowMsg(data.message, data.success);");
                out.println("      if (data.success) { setTimeout(function(){ location.reload(); }, 1000); }");
                out.println("    }).catch(e => tmplShowMsg('Request failed: ' + e, false));");
                out.println("}");

                // Show/reset the Add form
                out.println("function tmplShowAddForm() {");
                out.println(
                                "  document.querySelectorAll('.tmpl-edit-row').forEach(function(r) { r.style.display = 'none'; });");
                out.println(
                                "  document.querySelectorAll('.tmpl-row').forEach(function(r) { r.classList.remove('tmpl-row-active'); });");
                out.println("  document.getElementById('tmplMode').value = 'add';");
                out.println("  document.getElementById('tmplActionNextId').value = '';");
                out.println("  document.getElementById('tmplFormTitle').textContent = 'Add Template';");
                out.println("  document.getElementById('tmplNextDescription').value = '';");
                out.println("  document.getElementById('tmplLinkUrl').value = '';");
                out.println("  document.getElementById('tmplNextNote').value = '';");
                out.println("  document.getElementById('tmplAutoGenerate').checked = true;");
                out.println("  document.getElementById('tmplTemplateType').value = 'D';");
                out.println("  document.getElementById('tmplMissedActionBehavior').value = 'AUTO_CANCEL';");
                out.println("  document.getElementById('tmplNextActionType').value = '" + ProjectNextActionType.WILL
                                + "';");
                out.println("  document.getElementById('tmplDeleteBtn').style.display = 'none';");
                out.println("  var projSel = document.getElementById('tmplProjectId');");
                out.println("  if (projSel) projSel.value = '';");
                out.println("  tmplUpdateScheduleFields();");
                out.println("  document.getElementById('tmplForm').scrollIntoView({behavior:'smooth'});");
                out.println("  document.getElementById('tmplNextDescription').focus();");
                out.println("}");

                // ---- Inline row-edit functions ----

                out.println("function tmplToggleEdit(id) {");
                out.println("  var row = document.getElementById('tmplEditRow_' + id);");
                out.println("  if (!row) return;");
                out.println("  var isOpen = row.style.display !== 'none';");
                out.println(
                                "  document.querySelectorAll('.tmpl-edit-row').forEach(function(r) { r.style.display = 'none'; });");
                out.println(
                                "  document.querySelectorAll('.tmpl-row').forEach(function(r) { r.classList.remove('tmpl-row-active'); });");
                out.println("  if (!isOpen) {");
                out.println("    row.style.display = '';");
                out.println("    var mainRow = document.getElementById('tmplRow_' + id);");
                out.println("    if (mainRow) mainRow.classList.add('tmpl-row-active');");
                out.println("    row.scrollIntoView({behavior:'smooth', block:'nearest'});");
                out.println("  }");
                out.println("}");

                out.println("function tmplUpdateSched(id) {");
                out.println("  var t = document.getElementById('tmplTmplType_' + id).value;");
                out.println("  ['W','M','Q','Y'].forEach(function(s) {");
                out.println("    var el = document.getElementById('tmplSched' + s + '_' + id);");
                out.println("    if (el) el.style.display = (t === s) ? '' : 'none';");
                out.println("  });");
                out.println("}");

                out.println("function tmplSave(id) {");
                out.println("  var params = new URLSearchParams();");
                out.println("  params.append('action', 'saveTemplate');");
                out.println("  params.append('mode', 'edit');");
                out.println("  params.append('actionNextId', id);");
                out.println("  params.append('nextDescription', document.getElementById('tmplDesc_' + id).value);");
                out.println("  params.append('nextActionType', document.getElementById('tmplNAT_' + id).value);");
                out.println("  params.append('templateType', document.getElementById('tmplTmplType_' + id).value);");
                out.println("  params.append('missedActionBehavior', document.getElementById('tmplMissed_' + id).value);");
                out.println("  var ag = document.getElementById('tmplAutoGen_' + id);");
                out.println("  params.append('autoGenerate', (ag && ag.checked) ? 'Y' : 'N');");
                out.println(
                                "  var dw = document.getElementById('tmplDow_' + id); params.append('scheduleDaysOfWeek', dw ? dw.value : '');");
                out.println(
                                "  var dm = document.getElementById('tmplDom_' + id); params.append('scheduleDaysOfMonth', dm ? dm.value : '');");
                out.println(
                                "  var dq = document.getElementById('tmplDoq_' + id); params.append('scheduleDaysOfQuarter', dq ? dq.value : '');");
                out.println(
                                "  var dy = document.getElementById('tmplDoy_' + id); params.append('scheduleDaysOfYear', dy ? dy.value : '');");
                out.println("  params.append('linkUrl', document.getElementById('tmplLink_' + id).value);");
                out.println("  tmplAppendAgent(params, id);");
                out.println("  params.append('nextNote', document.getElementById('tmplNote_' + id).value);");
                out.println(
                                "  var pid = document.getElementById('tmplPid_' + id); params.append('projectId', pid ? pid.value : '');");
                if (personalMode) {
                        out.println(
                                        "  var ts = document.getElementById('tmplTS_' + id); params.append('timeSlot', ts ? ts.value : '');");
                        out.println("  params.append('nextTimeEstimate', '0');");
                } else {
                        out.println(
                                        "  var est = document.getElementById('tmplEst_' + id); params.append('nextTimeEstimate', est ? est.value : '0');");
                }
                out.println("  fetch('TemplateManagementServlet', {method:'POST', body:params})");
                out.println("    .then(r => r.json()).then(data => {");
                out.println("      if (data.success) { setTimeout(function(){ location.reload(); }, 800); }");
                out.println("      else { tmplShowMsg(data.message, false); }");
                out.println("    }).catch(e => tmplShowMsg('Request failed: ' + e, false));");
                out.println("}");

                out.println("function tmplDelete(id) {");
                out.println("  if (!confirm('Delete this template? Its open actions from today on will be cancelled.')) return;");
                out.println("  var params = new URLSearchParams();");
                out.println("  params.append('action', 'deleteTemplate');");
                out.println("  params.append('actionNextId', id);");
                out.println("  fetch('TemplateManagementServlet', {method:'POST', body:params})");
                out.println("    .then(r => r.json()).then(data => {");
                out.println("      if (data.success) { setTimeout(function(){ location.reload(); }, 800); }");
                out.println("      else { tmplShowMsg(data.message, false); }");
                out.println("    }).catch(e => tmplShowMsg('Request failed: ' + e, false));");
                out.println("}");

                out.println("function tmplReactivate(id) {");
                out.println("  var params = new URLSearchParams();");
                out.println("  params.append('action', 'reactivateTemplate');");
                out.println("  params.append('actionNextId', id);");
                out.println("  fetch('TemplateManagementServlet', {method:'POST', body:params})");
                out.println("    .then(r => r.json()).then(data => {");
                out.println("      if (data.success) { setTimeout(function(){ location.reload(); }, 800); }");
                out.println("      else { tmplShowMsg(data.message, false); }");
                out.println("    }).catch(e => tmplShowMsg('Request failed: ' + e, false));");
                out.println("}");

                out.println("</script>");

        }

        // =========================================================================
        // Mutation handlers
        // =========================================================================

        private void handleMutationResult(AppReq appReq, PlanAheadMutationResult result) throws IOException {
                Map<String, Object> data = new LinkedHashMap<String, Object>();
                data.put("success", result.isSuccess());
                data.put("message", result.getMessage());
                sendJson(appReq, data);
        }

        private void sendJson(AppReq appReq, Map<String, Object> data) throws IOException {
                HttpServletResponse response = appReq.getResponse();
                response.setContentType("application/json;charset=UTF-8");
                PrintWriter out = response.getWriter();
                out.println("{");
                out.println("  \"success\": " + data.get("success") + ",");
                out.println("  \"message\": \"" + jsonEsc(String.valueOf(data.get("message"))) + "\"");
                out.println("}");
        }

        // =========================================================================
        // Helpers
        // =========================================================================

        @SuppressWarnings("unchecked")
        private List<Project> loadFilteredProjects(Session session, int workspaceId, boolean personalMode) {
                Query pq = session.createQuery(
                                "from Project p where p.workspaceId = :workspaceId "
                                                + "and (p.projectStatus is null or p.projectStatus = :status) "
                                                + "order by p.projectName");
                pq.setParameter("workspaceId", workspaceId);
                pq.setParameter("status", ProjectStatus.ACTIVE.getDatabaseValue());
                List<Project> all = pq.list();

                Query bcq = session.createQuery("from BillCode where workspaceId = :workspaceId");
                bcq.setParameter("workspaceId", workspaceId);
                List<BillCode> billCodes = bcq.list();
                Map<String, BillCode> billCodeMap = new HashMap<String, BillCode>();
                for (BillCode bc : billCodes) {
                        if (bc.getBillCode() != null) {
                                billCodeMap.put(bc.getBillCode(), bc);
                        }
                }

                List<Project> filtered = new java.util.ArrayList<Project>();
                for (Project p : all) {
                        String code = p.getBillCode();
                        BillCode bc = (code != null && !code.trim().isEmpty()) ? billCodeMap.get(code.trim()) : null;
                        boolean billable = bc != null && "Y".equals(bc.getBillable());
                        if (personalMode ? !billable : billable) {
                                filtered.add(p);
                        }
                }
                return filtered;
        }

        private String summarizeSchedule(TemplateType tt, ActionNextTemplateConfig config) {
                if (tt == null) {
                        return "";
                }
                switch (tt) {
                        case WEEKLY:
                                return config.getScheduleDaysOfWeek() != null ? config.getScheduleDaysOfWeek() : "any";
                        case MONTHLY:
                                return config.getScheduleDaysOfMonth() != null ? config.getScheduleDaysOfMonth()
                                                : "any";
                        case QUARTERLY:
                                return config.getScheduleDaysOfQuarter() != null ? config.getScheduleDaysOfQuarter()
                                                : "any";
                        case YEARLY:
                                return config.getScheduleDaysOfYear() != null ? config.getScheduleDaysOfYear() : "any";
                        default:
                                return "daily";
                }
        }

        private String formatMissed(String value) {
                if ("CARRY_FORWARD".equals(value))
                        return "Carry Forward";
                if ("IGNORE".equals(value))
                        return "Ignore";
                return "Auto Cancel";
        }

        private String sel(String current, String value) {
                if (current == null || value == null)
                        return "";
                return value.equals(current) ? " selected" : "";
        }

        /**
         * "Performed by" (a person, or an agent such as Claude Code) and, for an agent, how its work
         * reaches a person: a same-project review (default), the open instance of another template
         * on the same date, or one prep per occurrence of another template.
         */
        private String agentFields(AppReq appReq, ActionNext template, String key) {
                org.dandeliondaily.sessions.service.AgentCatalogService catalog =
                                new org.dandeliondaily.sessions.service.AgentCatalogService();
                String current = template == null || template.getPerformedByAgent() == null ? ""
                                : template.getPerformedByAgent();
                org.openimmunizationsoftware.pt.model.ActionAgentConfig config = template == null ? null
                                : (org.openimmunizationsoftware.pt.model.ActionAgentConfig) appReq.getDataSession().get(
                                                org.openimmunizationsoftware.pt.model.ActionAgentConfig.class,
                                                Integer.valueOf(template.getActionNextId()));
                String mode = config == null || config.getHandoffMode() == null ? "SAME_PROJECT_REVIEW"
                                : config.getHandoffMode();
                StringBuilder sb = new StringBuilder();
                sb.append("<label>Performed by</label><select id='tmplAgent_").append(key).append("'>");
                sb.append("<option value=''").append(current.length() == 0 ? " selected" : "").append(">a person</option>");
                for (org.openimmunizationsoftware.pt.model.Agent agent : catalog.listActive(appReq.getDataSession())) {
                        sb.append("<option value='").append(esc(agent.getAgentKey())).append("'")
                                        .append(agent.getAgentKey().equals(current) ? " selected" : "").append(">")
                                        .append(esc(agent.getDisplayName())).append(" (agent)</option>");
                }
                sb.append("</select>");
                sb.append("<details class='tmpl-agent-handoff'").append(current.length() > 0 ? " open" : "")
                                .append("><summary>Agent handoff</summary>");
                sb.append("<label>Hand the result to</label><select id='tmplAgentHandoffMode_").append(key).append("'>");
                String[][] modes = { { "SAME_PROJECT_REVIEW", "a new review action in this project" },
                                { "ATTACH_TEMPLATE_INSTANCE", "the open action of another template, same date" },
                                { "PER_TARGET_INSTANCE", "one prep per occurrence of another template" } };
                for (String[] m : modes) {
                        sb.append("<option value='").append(m[0]).append("'").append(m[0].equals(mode) ? " selected" : "")
                                        .append(">").append(esc(m[1])).append("</option>");
                }
                sb.append("</select>");
                sb.append("<label>Other template #</label><input type='number' min='1' id='tmplAgentHandoffTemplateId_")
                                .append(key).append("' value='")
                                .append(config == null || config.getHandoffTemplateId() == null ? ""
                                                : String.valueOf(config.getHandoffTemplateId()))
                                .append("'>");
                sb.append("<label>Its project #</label><input type='number' min='1' id='tmplAgentHandoffProjectId_")
                                .append(key).append("' value='")
                                .append(config == null || config.getHandoffProjectId() == null ? ""
                                                : String.valueOf(config.getHandoffProjectId()))
                                .append("'>");
                sb.append("<label>Workload (PC permission profile)</label><input type='text' maxlength='40' id='tmplAgentWorkloadKey_")
                                .append(key).append("' value='")
                                .append(esc(config == null || config.getWorkloadKey() == null ? "" : config.getWorkloadKey()))
                                .append("' placeholder='e.g. work-hygiene-prep'>");
                sb.append("</details>");
                return sb.toString();
        }

        private String esc(String value) {
                if (value == null)
                        return "";
                return value
                                .replace("&", "&amp;")
                                .replace("<", "&lt;")
                                .replace(">", "&gt;")
                                .replace("\"", "&quot;")
                                .replace("'", "&#39;");
        }

        private String jsonEsc(String value) {
                if (value == null)
                        return "";
                return value
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n")
                                .replace("\r", "");
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
}
