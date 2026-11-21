package org.dandeliondaily.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.mcp.service.McpActionContextSupport;
import org.dandeliondaily.sessions.service.CloseOutJson;
import org.dandeliondaily.sessions.service.CloseOutService;
import org.dandeliondaily.sessions.service.SessionAccess;
import org.dandeliondaily.sessions.service.SessionException;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The close-out view, reached from the Focused Action title card. Three steps on one page:
 * <ol>
 * <li>the form: what was done and the outcome, the next action(s) (one proposed by default, for
 * tomorrow, with the notes as its brief; "No next action" must be chosen), and every other open
 * action on the project, including undated and future ones, each with keep, rewrite, reschedule,
 * cancel or merge. Recurring-template actions are listed by series, read-only;</li>
 * <li>the preview: every row that would change, before and after, including linked twins and
 * actions this unblocks. Nothing has been written;</li>
 * <li>confirm: everything is applied in one transaction through CloseOutService (the same
 * service MCP close_out_action uses). If any previewed row changed meanwhile, nothing is applied
 * and the row is named. Time is recorded exactly as Done does (none here; the timer keeps its own).</li>
 * </ol>
 */
public class CloseOutServlet extends ClientServlet {

    private static final long serialVersionUID = 1L;
    private static final int SUCCESSOR_ROWS = 5;

    private final CloseOutService service = new CloseOutService();
    private final ActionNotesService notesService = new ActionNotesService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        process(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        process(request, response);
    }

    private void process(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            if (appReq.isLoggedOut()) {
                forwardToHome(request, response);
                return;
            }
            Session session = appReq.getDataSession();
            WebUser webUser = appReq.getWebUser();
            int workspaceId = appReq.getActiveWorkspaceId() == null ? 0 : appReq.getActiveWorkspaceId().intValue();
            int actionNextId = parseInt(request.getParameter("actionNextId"));
            ActionNext action = SessionAccess.find(session, webUser, workspaceId, actionNextId);
            String mode = n(request.getParameter("mode"));
            boolean post = "POST".equalsIgnoreCase(request.getMethod());
            String problem = null;
            CloseOutService.Plan plan = null;
            String requestJson = null;
            if (action != null && post && "apply".equals(mode)) {
                Transaction tx = session.beginTransaction();
                try {
                    CloseOutService.Request closeOut = CloseOutJson.parse(n(request.getParameter("closeOutJson")),
                            actionNextId, true, false);
                    service.apply(session, webUser, workspaceId, closeOut, McpActionContextSupport.truncatedNow());
                    tx.commit();
                    response.sendRedirect("FocusedActionServlet");
                    return;
                } catch (SessionException refused) {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                    problem = refused.getMessage();
                } catch (RuntimeException failed) {
                    if (tx.isActive()) {
                        tx.rollback();
                    }
                    failed.printStackTrace();
                    problem = "Nothing was changed: " + failed.getMessage();
                }
                action = SessionAccess.find(session, webUser, workspaceId, actionNextId);
            } else if (action != null && post && "preview".equals(mode)) {
                try {
                    Map<String, Object> json = fromForm(request, action);
                    requestJson = mapper.writeValueAsString(json);
                    CloseOutService.Request closeOut = CloseOutJson.parse(requestJson, actionNextId, false, false);
                    plan = service.preview(session, webUser, workspaceId, closeOut);
                } catch (SessionException invalid) {
                    problem = invalid.getMessage();
                }
            }

            appReq.setTitle("Close out");
            printHtmlHead(appReq);
            PrintWriter out = appReq.getOut();
            printStyles(out);
            out.println("<div class=\"main co-page\">");
            if (action == null) {
                out.println("<p class=\"fail\">That action wasn't found.</p>");
            } else {
                Project project = SessionAccess.project(session, action);
                out.println("<h1 class=\"co-title\">Close out: " + esc(action.getNextDescription()) + "</h1>");
                out.println("<p class=\"co-sub\">" + esc(project == null ? "" : project.getProjectName())
                        + " &middot; <a href=\"FocusedActionServlet\">back to Focused Action</a></p>");
                if (problem != null) {
                    out.println("<p class=\"fail\">" + esc(problem) + "</p>");
                }
                if (plan != null && plan.isOk()) {
                    renderPreview(out, action, plan, requestJson);
                } else {
                    if (plan != null) {
                        out.println("<ul class=\"fail\">");
                        for (String error : plan.errors) {
                            out.println("<li>" + esc(error) + "</li>");
                        }
                        out.println("</ul>");
                    }
                    renderForm(out, session, webUser, workspaceId, action, request);
                }
            }
            out.println("</div>");
            printHtmlFoot(appReq);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            appReq.close();
        }
    }

    /** The form's fields as the shared close-out JSON (see CloseOutJson). */
    Map<String, Object> fromForm(HttpServletRequest request, ActionNext action) {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("asOf", n(request.getParameter("asOf")));
        json.put("completionDescription", n(request.getParameter("completionDescription")));
        json.put("outcomeNote", n(request.getParameter("outcomeNote")));
        boolean noSuccessor = request.getParameter("noSuccessor") != null;
        json.put("noSuccessor", Boolean.valueOf(noSuccessor));
        List<Map<String, Object>> successors = new ArrayList<Map<String, Object>>();
        if (!noSuccessor) {
            for (int i = 0; i < SUCCESSOR_ROWS; i++) {
                String description = n(request.getParameter("s_desc_" + i)).trim();
                if (description.length() == 0) {
                    continue;
                }
                Map<String, Object> successor = new LinkedHashMap<String, Object>();
                successor.put("description", description);
                String date = n(request.getParameter("s_date_" + i)).trim();
                if (date.length() > 0) {
                    successor.put("scheduledDate", date);
                }
                String notes = n(request.getParameter("s_notes_" + i));
                if (notes.trim().length() > 0) {
                    successor.put("notes", notes);
                }
                successors.add(successor);
            }
        }
        json.put("successors", successors);
        List<Map<String, Object>> changes = new ArrayList<Map<String, Object>>();
        String[] ids = request.getParameterValues("otherId");
        if (ids != null) {
            for (String idText : ids) {
                int id = parseInt(idText);
                String op = n(request.getParameter("op_" + id));
                if (id <= 0 || op.length() == 0 || "keep".equals(op)) {
                    continue;
                }
                Map<String, Object> change = new LinkedHashMap<String, Object>();
                change.put("actionNextId", Integer.valueOf(id));
                change.put("asOf", n(request.getParameter("asof_" + id)));
                change.put("op", op);
                if (CloseOutService.OP_REWRITE.equals(op)) {
                    change.put("description", n(request.getParameter("text_" + id)));
                } else if (CloseOutService.OP_RESCHEDULE.equals(op)) {
                    String date = n(request.getParameter("date_" + id)).trim();
                    if (date.length() > 0) {
                        try {
                            LocalDate.parse(date);
                            change.put("scheduledDate", date);
                        } catch (DateTimeParseException e) {
                            throw SessionException.bad("Pick a date for #" + id + ".");
                        }
                    }
                } else if (CloseOutService.OP_CANCEL.equals(op)) {
                    change.put("reason", n(request.getParameter("text_" + id)));
                } else if (CloseOutService.OP_MERGE.equals(op)) {
                    int survivor = parseInt(request.getParameter("survivor_" + id));
                    if (survivor > 0) {
                        change.put("survivorActionNextId", Integer.valueOf(survivor));
                        change.put("survivorAsOf", n(request.getParameter("asof_" + survivor)));
                    }
                    change.put("combinedDescription", n(request.getParameter("text_" + id)));
                }
                changes.add(change);
            }
        }
        json.put("changes", changes);
        return json;
    }

    private void renderForm(PrintWriter out, Session session, WebUser webUser, int workspaceId, ActionNext action,
            HttpServletRequest request) {
        boolean refill = request.getParameter("completionDescription") != null;
        String scratch = notesService.read(action);
        out.println("<form method=\"POST\" action=\"CloseOutServlet\" class=\"co-form\">");
        out.println("<input type=\"hidden\" name=\"mode\" value=\"preview\">");
        out.println("<input type=\"hidden\" name=\"actionNextId\" value=\"" + action.getActionNextId() + "\">");
        out.println("<input type=\"hidden\" name=\"asOf\" value=\"" + esc(CloseOutService.asOf(action)) + "\">");

        out.println("<h2>What was done</h2>");
        out.println("<label class=\"co-label\">Completion (what reports read)</label>");
        out.println("<input type=\"text\" class=\"co-wide\" name=\"completionDescription\" required value=\""
                + esc(refill ? request.getParameter("completionDescription") : "") + "\" placeholder=\"What you did\">");
        out.println("<label class=\"co-label\">Outcome note (optional)</label>");
        out.println("<textarea class=\"co-wide\" name=\"outcomeNote\" id=\"coOutcome\" rows=\"3\" placeholder=\"What happened, "
                + "decisions, where it was left\">" + esc(refill ? request.getParameter("outcomeNote") : "") + "</textarea>");

        out.println("<h2>What comes next</h2>");
        LocalDate tomorrow = webUser.getLocalDateToday().plusDays(1);
        for (int i = 0; i < SUCCESSOR_ROWS; i++) {
            boolean shown = i == 0 || (refill && n(request.getParameter("s_desc_" + i)).trim().length() > 0);
            String description = refill ? n(request.getParameter("s_desc_" + i)) : (i == 0 ? action.getNextDescription() : "");
            String date = refill ? n(request.getParameter("s_date_" + i)) : (i == 0 ? tomorrow.toString() : "");
            String notes = refill ? n(request.getParameter("s_notes_" + i)) : (i == 0 ? scratch : "");
            out.println("<div class=\"co-successor\" id=\"coSuccessor" + i + "\"" + (shown ? "" : " hidden") + ">");
            out.println("  <label class=\"co-label\">Next action" + (i == 0 ? "" : " " + (i + 1)) + "</label>");
            out.println("  <input type=\"text\" class=\"co-wide\" name=\"s_desc_" + i + "\" value=\"" + esc(description) + "\">");
            out.println("  <label class=\"co-label\">Date</label> <input type=\"date\" name=\"s_date_" + i + "\" value=\""
                    + esc(date) + "\">");
            out.println("  <label class=\"co-label\">Brief (its notes)</label>");
            out.println("  <textarea class=\"co-wide co-brief\" name=\"s_notes_" + i + "\"" + (i == 0 ? " id=\"coBrief\"" : "")
                    + " rows=\"4\">" + esc(notes) + "</textarea>");
            out.println("</div>");
        }
        out.println("<button type=\"button\" onclick=\"coAddSuccessor()\">Add another next action</button>");
        out.println("<label class=\"co-none\"><input type=\"checkbox\" name=\"noSuccessor\" id=\"coNone\""
                + (refill && request.getParameter("noSuccessor") != null ? " checked" : "")
                + "> No next action: this work is finished</label>");

        List<CloseOutService.ProjectRow> rows = service.listOtherOpen(session, webUser, workspaceId, action);
        out.println("<h2>The project's other open actions</h2>");
        if (rows.isEmpty()) {
            out.println("<p>No other open actions on this project.</p>");
        } else {
            out.println("<table class=\"boxed co-table\"><tr class=\"boxed\"><th class=\"boxed\">Action</th>"
                    + "<th class=\"boxed\">Date</th><th class=\"boxed\">Change</th></tr>");
            Integer series = null;
            for (CloseOutService.ProjectRow row : rows) {
                ActionNext other = row.action;
                int id = other.getActionNextId();
                String date = other.getNextActionDate() == null ? "undated"
                        : webUser.toLocalDate(other.getNextActionDate()).toString();
                String twins = row.twinProjects.isEmpty() ? ""
                        : "<div class=\"co-twins\">Linked with " + esc(join(row.twinProjects)) + "; changes apply to both</div>";
                if (row.generated) {
                    if (series == null || !series.equals(row.seriesTemplateId)) {
                        series = row.seriesTemplateId;
                        out.println("<tr class=\"boxed co-series\"><td class=\"boxed\" colspan=\"3\">Recurring: "
                                + esc(row.seriesName) + " (edit via the template)</td></tr>");
                    }
                    out.println("<tr class=\"boxed co-readonly\"><td class=\"boxed\">" + esc(other.getNextDescription()) + twins
                            + "</td><td class=\"boxed\">" + esc(date) + "</td><td class=\"boxed\">edit via template</td></tr>");
                    continue;
                }
                String op = refill ? n(request.getParameter("op_" + id)) : "keep";
                out.println("<tr class=\"boxed\"><td class=\"boxed\">" + esc(other.getNextDescription()) + twins
                        + "<input type=\"hidden\" name=\"otherId\" value=\"" + id + "\">"
                        + "<input type=\"hidden\" name=\"asof_" + id + "\" value=\"" + esc(CloseOutService.asOf(other))
                        + "\"></td><td class=\"boxed\">" + esc(date) + "</td><td class=\"boxed\">");
                out.println("<select name=\"op_" + id + "\" onchange=\"coOp(" + id + ", this.value)\">");
                for (String[] option : new String[][] { { "keep", "keep" }, { "rewrite", "rewrite" },
                        { "reschedule", "reschedule" }, { "cancel", "cancel" }, { "merge", "merge into..." } }) {
                    out.println("<option value=\"" + option[0] + "\"" + (option[0].equals(op) ? " selected" : "") + ">"
                            + option[1] + "</option>");
                }
                out.println("</select>");
                out.println("<span id=\"coText" + id + "\"" + (op.equals("rewrite") || op.equals("cancel") || op.equals("merge") ? "" : " hidden")
                        + "><input type=\"text\" name=\"text_" + id + "\" value=\""
                        + esc(refill ? request.getParameter("text_" + id) : other.getNextDescription())
                        + "\" placeholder=\"new text / reason / combined text\"></span>");
                out.println("<span id=\"coDate" + id + "\"" + (op.equals("reschedule") ? "" : " hidden")
                        + "><input type=\"date\" name=\"date_" + id + "\" value=\""
                        + esc(refill ? request.getParameter("date_" + id) : "") + "\"></span>");
                out.println("<span id=\"coSurvivor" + id + "\"" + (op.equals("merge") ? "" : " hidden")
                        + "><select name=\"survivor_" + id + "\"><option value=\"\">into which action?</option>");
                for (CloseOutService.ProjectRow candidate : rows) {
                    if (!candidate.generated && candidate.action.getActionNextId() != id) {
                        out.println("<option value=\"" + candidate.action.getActionNextId() + "\">"
                                + esc(candidate.action.getNextDescription()) + "</option>");
                    }
                }
                out.println("</select></span></td></tr>");
            }
            out.println("</table>");
        }
        out.println("<p><button type=\"submit\" class=\"co-primary\">Preview changes</button> "
                + "<span class=\"co-hint\">Nothing is changed until you confirm the preview.</span></p>");
        out.println("</form>");
        out.println("<script>");
        out.println("function coAddSuccessor() { for (var i = 1; i < " + SUCCESSOR_ROWS + "; i++) { var d = document.getElementById('coSuccessor' + i);"
                + " if (d && d.hidden) { d.hidden = false; return; } } }");
        out.println("function coOp(id, op) {");
        out.println("  document.getElementById('coText' + id).hidden = !(op === 'rewrite' || op === 'cancel' || op === 'merge');");
        out.println("  document.getElementById('coDate' + id).hidden = op !== 'reschedule';");
        out.println("  document.getElementById('coSurvivor' + id).hidden = op !== 'merge';");
        out.println("}");
        // Ruling 19: the default brief is the outcome plus the remaining scratch notes, until edited.
        out.println("(function () { var outcome = document.getElementById('coOutcome'); var brief = document.getElementById('coBrief');");
        out.println("  if (!outcome || !brief) { return; } var scratch = brief.value; var touched = false;");
        out.println("  brief.addEventListener('input', function () { touched = true; });");
        out.println("  outcome.addEventListener('input', function () { if (!touched) { brief.value = outcome.value.trim()"
                + " ? outcome.value.trim() + (scratch ? '\\n\\n' + scratch : '') : scratch; } });");
        out.println("})();");
        out.println("</script>");
    }

    private void renderPreview(PrintWriter out, ActionNext action, CloseOutService.Plan plan, String requestJson)
            throws IOException {
        out.println("<h2>Preview</h2>");
        out.println("<p>Nothing has been changed yet. These are all the rows this close-out changes:</p>");
        out.println("<table class=\"boxed co-table\"><tr class=\"boxed\"><th class=\"boxed\">Change</th>"
                + "<th class=\"boxed\">Action</th><th class=\"boxed\">Before</th><th class=\"boxed\">After</th></tr>");
        List<Map<String, Object>> confirm = new ArrayList<Map<String, Object>>();
        for (CloseOutService.Row row : plan.rows) {
            String before = row.actionNextId == 0 ? "(new)" : describe(row.beforeDescription, row.beforeDate, row.beforeStatus);
            String after = describe(row.afterDescription, row.afterDate, row.afterStatus);
            out.println("<tr class=\"boxed\"><td class=\"boxed\">" + esc(row.role) + "</td><td class=\"boxed\">"
                    + (row.actionNextId == 0 ? "" : "#" + row.actionNextId + " ") + esc(row.projectName)
                    + "</td><td class=\"boxed\">" + esc(before) + "</td><td class=\"boxed\">" + esc(after) + "</td></tr>");
            if (row.actionNextId > 0) {
                Map<String, Object> ref = new LinkedHashMap<String, Object>();
                ref.put("actionNextId", Integer.valueOf(row.actionNextId));
                ref.put("asOf", row.asOf);
                confirm.add(ref);
            }
        }
        out.println("</table>");
        @SuppressWarnings("unchecked")
        Map<String, Object> json = mapper.readValue(requestJson, LinkedHashMap.class);
        json.put("confirmRows", confirm);
        out.println("<form method=\"POST\" action=\"CloseOutServlet\">");
        out.println("<input type=\"hidden\" name=\"mode\" value=\"apply\">");
        out.println("<input type=\"hidden\" name=\"actionNextId\" value=\"" + action.getActionNextId() + "\">");
        out.println("<input type=\"hidden\" name=\"closeOutJson\" value=\"" + esc(mapper.writeValueAsString(json)) + "\">");
        out.println("<p><button type=\"submit\" class=\"co-primary\">Confirm and close out</button> "
                + "<button type=\"button\" onclick=\"history.back()\">Back to edit</button></p>");
        out.println("</form>");
    }

    private static String describe(String description, String date, String status) {
        String statusText = "C".equals(status) ? "completed" : "X".equals(status) ? "cancelled" : "open";
        return n(description) + " | " + (date == null ? "undated" : date) + " | " + statusText;
    }

    private void printStyles(PrintWriter out) {
        out.println("<style>");
        out.println(".co-page { max-width: 1000px; } .co-title { font-size: 20px; margin-bottom: 2px; } .co-sub { color: #5b6b58; margin-top: 0; }");
        out.println(".co-label { display: block; font-weight: 600; margin: 8px 0 2px; font-size: 13px; }");
        out.println(".co-wide { width: 100%; box-sizing: border-box; } .co-successor { border-left: 3px solid #c8d4c4; padding: 4px 10px; margin: 8px 0; }");
        out.println(".co-none { display: block; margin: 10px 0; } .co-twins { font-size: 12px; color: #6b5420; }");
        out.println(".co-series td { background: #f4f1e8; font-style: italic; } .co-readonly { color: #777; }");
        out.println(".co-primary { font-weight: 600; } .co-hint { color: #66765f; font-size: 12px; }");
        out.println("</style>");
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            sb.append(sb.length() == 0 ? "" : ", ").append(value);
        }
        return sb.toString();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(n(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
