/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.narrative.model.TrackerNarrativeScope;
import org.dandeliondaily.narrative.service.TrackerNarrativeService;
import org.dandeliondaily.narrative.service.NarrativeWorkflowException;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.doa.TrackerNarrativeDao;
import org.openimmunizationsoftware.pt.manager.TrackerNarrativeGenerator;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Classic tracker narrative list + editor, kept as a fallback for the review page
 * (TrackerNarrativeServlet).
 *
 * Legacy/alternate narrative view. New editor-first workflow is surfaced in
 * ReviewDashboard,
 * and shared narrative behavior should be implemented in
 * TrackerNarrativeService.
 *
 * @author nathan
 */
public class TrackerNarrativeClassicServlet extends ClientServlet {

    private static final long serialVersionUID = 1952130555696282854L;

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().escapeHtml(true).build();

    private static final String PARAM_ID = "id";
    private static final String PARAM_DATE = "date";
    private static final String PARAM_TYPE = "type";
    private static final String PARAM_VIEW = "view";
    private static final String PARAM_MARKDOWN_FINAL = "markdownFinal";

    private static final String GENERATION_UNAVAILABLE_MESSAGE = TrackerNarrativeGenerator
            .getGenerationUnavailableMessage();

    private static final String ACTION_SAVE = "Save";
    private static final String ACTION_APPROVE = "Approve";
    private static final String ACTION_REGENERATE = "Regenerate";
    private static final String ACTION_REJECT = "Reject";
    private static final String ACTION_DELETE = "Delete";
    private static final String ACTION_GENERATE = "Generate";

    private static final String TYPE_DAILY = "DAILY";
    private static final String TYPE_WEEKLY = "WEEKLY";
    private static final String TYPE_MONTHLY = "MONTHLY";

    private static final String VIEW_EDIT = "edit";

    private final TrackerNarrativeService narrativeService = new TrackerNarrativeService();

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            WebUser webUser = appReq.getWebUser();
            if (appReq.isLoggedOut()) {
                forwardToHome(request, response);
                return;
            }

            Session dataSession = appReq.getDataSession();
            TrackerNarrativeDao narrativeDao = new TrackerNarrativeDao(dataSession);

            if ("POST".equalsIgnoreCase(request.getMethod())) {
                try {
                    handlePost(request, response, appReq, narrativeDao);
                } catch (NarrativeWorkflowException e) {
                    response.sendError("stale".equals(e.getCode()) ? HttpServletResponse.SC_CONFLICT
                            : "not_found".equals(e.getCode()) ? HttpServletResponse.SC_NOT_FOUND
                                    : HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
                }
                return;
            }

            String type = narrativeService.normalizeType(request.getParameter(PARAM_TYPE));
            LocalDate selectedDate = narrativeService.resolveDate(request.getParameter(PARAM_DATE), appReq, webUser);
            TrackerNarrativeScope scope = narrativeService.resolveScope(type, selectedDate);
            long narrativeId = readLong(request.getParameter(PARAM_ID));
            boolean editMode = VIEW_EDIT.equalsIgnoreCase(n(request.getParameter(PARAM_VIEW)));

            appReq.setTitle("Narrative");
            if (!TrackerNarrativeGenerator.isGenerationAvailable()) {
                appReq.addWarningMessage(GENERATION_UNAVAILABLE_MESSAGE);
            }
            printHtmlHead(appReq);

            PrintWriter out = appReq.getOut();
            printDandelionLocation(out, "Time Management & Reporting / Narrative");
            if (narrativeId > 0) {
                TrackerNarrative narrative = (TrackerNarrative) dataSession.get(TrackerNarrative.class,
                        (int) narrativeId);
                if (narrative == null || narrative.getContactId() != webUser.getContactId()) {
                    out.println("<p class=\"fail\">Narrative not found.</p>");
                } else {
                    printEditor(out, webUser, narrative, type, selectedDate, editMode, narrativeDao);
                }
            } else {
                printList(out, webUser, narrativeDao, scope);
            }

            printHtmlFoot(appReq);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            appReq.close();
        }
    }

    private void handlePost(HttpServletRequest request, HttpServletResponse response, AppReq appReq,
            TrackerNarrativeDao narrativeDao) throws IOException {
        String action = appReq.getAction();
        long narrativeId = readLong(request.getParameter(PARAM_ID));
        String type = narrativeService.normalizeType(request.getParameter(PARAM_TYPE));
        LocalDate selectedDate = narrativeService.resolveDate(request.getParameter(PARAM_DATE), appReq,
                appReq.getWebUser());
        TrackerNarrativeScope scope = narrativeService.resolveScope(type, selectedDate);

        if (action == null || action.trim().length() == 0) {
            response.sendRedirect(buildListLink(type, selectedDate));
            return;
        }

        if (ACTION_APPROVE.equals(action)) {
            if (narrativeId > 0) {
                narrativeService.approve(appReq.getDataSession(), appReq.getWebUser(), null, narrativeId,
                        request.getParameter("lastUpdated"));
                appReq.setMessageConfirmation("Narrative approved.");
            }
            response.sendRedirect(buildEditorLink(narrativeId, type, selectedDate));
            return;
        }

        if ((ACTION_GENERATE.equals(action) || ACTION_REGENERATE.equals(action))
                && !TrackerNarrativeGenerator.isGenerationAvailable()) {
            String unavailableRedirect = ACTION_REGENERATE.equals(action)
                    ? buildEditorLink(narrativeId, type, selectedDate)
                    : buildListLink(type, selectedDate);
            response.sendRedirect(unavailableRedirect);
            return;
        }

        String redirect = buildListLink(type, selectedDate);
        if (ACTION_SAVE.equals(action)) {
            String markdownFinal = n(request.getParameter(PARAM_MARKDOWN_FINAL));
            long originalId = narrativeId;
            narrativeId = narrativeService.updateDraft(appReq.getDataSession(), appReq.getWebUser(), null, narrativeId,
                    request.getParameter("lastUpdated"), markdownFinal).getNarrativeId();
            appReq.setMessageConfirmation(narrativeId == originalId ? "Narrative updated."
                    : "Revision saved as a new draft. The approved report is unchanged until you approve this draft.");
            redirect = buildEditorLink(narrativeId, type, selectedDate);
        } else if (ACTION_REJECT.equals(action)) {
            narrativeService.changeStatus(appReq.getDataSession(), appReq.getWebUser(), null, narrativeId,
                    request.getParameter("lastUpdated"), TrackerNarrativeReviewStatus.REJECTED);
            appReq.setMessageConfirmation("Narrative rejected.");
            redirect = buildEditorLink(narrativeId, type, selectedDate);
        } else if (ACTION_DELETE.equals(action)) {
            narrativeService.changeStatus(appReq.getDataSession(), appReq.getWebUser(), null, narrativeId,
                    request.getParameter("lastUpdated"), TrackerNarrativeReviewStatus.DELETED);
            appReq.setMessageConfirmation("Narrative deleted.");
            redirect = buildEditorLink(narrativeId, type, selectedDate);
        } else if (ACTION_GENERATE.equals(action)) {
            long newId = narrativeService.generate(appReq, scope);
            if (newId > 0) {
                appReq.setMessageConfirmation("Generation requested.");
                redirect = buildEditorLink(newId, type, selectedDate);
            }
        } else if (ACTION_REGENERATE.equals(action)) {
            long newId = narrativeService.regenerate(appReq.getDataSession(), appReq.getWebUser(), null, narrativeId,
                    request.getParameter("lastUpdated")).getNarrativeId();
            if (newId > 0) {
                appReq.setMessageConfirmation("Generation requested.");
                redirect = buildEditorLink(newId, type, selectedDate);
            }
        }

        response.sendRedirect(redirect);
    }

    private void printList(PrintWriter out, WebUser webUser, TrackerNarrativeDao narrativeDao,
            TrackerNarrativeScope scope) {
        boolean generationAvailable = TrackerNarrativeGenerator.isGenerationAvailable();
        String type = scope.getNarrativeType();
        LocalDate selectedDate = scope.getSelectedDate();
        out.println("<h2>Tracker Narratives</h2>");

        out.println("<form method=\"GET\" action=\"TrackerNarrativeClassicServlet\">");
        out.println("<table class=\"boxed\">");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"title\" colspan=\"2\">Filter</th>");
        out.println("  </tr>");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">Narrative Type</th>");
        out.println("    <td class=\"boxed\">" + buildTypeSelect(type) + "</td>");
        out.println("  </tr>");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">Date</th>");
        out.println("    <td class=\"boxed\"><input type=\"date\" name=\"" + PARAM_DATE
                + "\" value=\"" + selectedDate + "\"></td>");
        out.println("  </tr>");
        out.println("  <tr class=\"boxed\">");
        out.println("    <td class=\"boxed\" colspan=\"2\"><a class=\"button\" href=\""
                + buildListLink(type, selectedDate.minusDays(1)) + "\">Previous Day</a></td>");
        out.println("  </tr>");
        out.println("  <tr class=\"boxed\">");
        out.println("    <td class=\"boxed\" colspan=\"2\">"
                + "<input type=\"submit\" value=\"Apply\"></td>");
        out.println("  </tr>");
        out.println("</table>");
        out.println("</form><br/>");

        printRapidSelectCalendar(out, narrativeDao, webUser, selectedDate);
        out.println("<br/>");

        if (generationAvailable) {
            out.println("<form method=\"POST\" action=\"TrackerNarrativeClassicServlet\">\n");
            out.println("<input type=\"hidden\" name=\"" + PARAM_TYPE + "\" value=\"" + type + "\">\n");
            out.println("<input type=\"hidden\" name=\"" + PARAM_DATE + "\" value=\"" + selectedDate + "\">\n");
            out.println("<input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_GENERATE
                    + "\">\n");
            out.println("</form><br/>\n");
        } else {
            out.println("<p class=\"fail\">" + escapeHtml(GENERATION_UNAVAILABLE_MESSAGE) + "</p><br/>\n");
        }

        String periodLabel = formatPeriod(webUser, scope.getPeriodStart(), scope.getPeriodEnd());
        out.println("<table class=\"boxed\">");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"title\" colspan=\"4\">Period: " + periodLabel + "</th>");
        out.println("  </tr>");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">Status</th>");
        out.println("    <th class=\"boxed\">Title</th>");
        out.println("    <th class=\"boxed\">Generated</th>");
        out.println("    <th class=\"boxed\">View</th>");
        out.println("  </tr>");

        List<TrackerNarrative> narratives = narrativeDao.findByContactTypeAndPeriod(webUser.getContactId(), type,
                scope.getPeriodStart(), scope.getPeriodEnd());
        TrackerNarrative approved = narrativeDao.findApprovedByContactTypeAndPeriod(webUser.getContactId(), type,
                scope.getPeriodStart(), scope.getPeriodEnd());
        int approvedId = approved == null ? 0 : approved.getNarrativeId();

        if (narratives.isEmpty()) {
            out.println("  <tr class=\"boxed\"><td class=\"boxed\" colspan=\"4\">No narratives found.</td></tr>");
        } else {
            for (TrackerNarrative narrative : narratives) {
                boolean isApproved = narrative.getNarrativeId() == approvedId;
                String rowClass = isApproved ? "boxed-highlight" : "boxed";
                out.println("  <tr class=\"boxed\">");
                out.println("    <td class=\"" + rowClass + "\">"
                        + escapeHtml(n(narrative.getReviewStatusString())) + (isApproved ? " (approved)" : "")
                        + "</td>");
                out.println("    <td class=\"" + rowClass + "\">" + escapeHtml(n(narrative.getDisplayTitle()))
                        + "</td>");
                out.println(
                        "    <td class=\"" + rowClass + "\">" + formatDateTime(webUser, narrative.getDateGenerated())
                                + "</td>");
                out.println("    <td class=\"" + rowClass + "\"><a class=\"button\" href=\""
                        + buildEditorLink(narrative.getNarrativeId(), type, selectedDate) + "\">View</a></td>");
                out.println("  </tr>");
            }
        }
        out.println("</table>");
    }

    private void printRapidSelectCalendar(PrintWriter out, TrackerNarrativeDao narrativeDao, WebUser webUser,
            LocalDate selectedDate) {
        YearMonth month = YearMonth.from(selectedDate);
        LocalDate firstDayOfMonth = month.atDay(1);
        LocalDate lastDayOfMonth = month.atEndOfMonth();
        YearMonth previousMonth = month.minusMonths(1);
        YearMonth nextMonth = month.plusMonths(1);
        LocalDate previousMonthStart = previousMonth.atDay(1);
        LocalDate nextMonthStart = nextMonth.atDay(1);

        int offset = firstDayOfMonth.getDayOfWeek().getValue() % 7;
        LocalDate weekStart = firstDayOfMonth.minusDays(offset);
        LocalDate firstWeekStart = weekStart;

        String monthLabel = firstDayOfMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.US)
                + " " + firstDayOfMonth.getYear();
        String previousMonthShort = previousMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
        String currentMonthShort = month.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
        String nextMonthShort = nextMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.US);

        LocalDate lastWeekStart = lastDayOfMonth
                .minusDays(lastDayOfMonth.getDayOfWeek().getValue() % 7);
        LocalDate endExclusive = lastWeekStart.plusWeeks(1);
        Integer webUserId = webUser == null ? null : Integer.valueOf(webUser.getWebUserId());
        Map<LocalDate, Integer> billableMinutesByDay = narrativeDao.sumBillableMinutesByDay(webUserId,
                firstWeekStart, endExclusive);
        Set<LocalDate> approvedDailyStarts = narrativeDao.findApprovedPeriodStarts(webUser.getContactId(), TYPE_DAILY,
                firstWeekStart, endExclusive.minusDays(1));
        Set<LocalDate> approvedWeeklyStarts = narrativeDao.findApprovedPeriodStarts(webUser.getContactId(),
                TYPE_WEEKLY, firstWeekStart, lastWeekStart);

        out.println("<table class=\"boxed\">");
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"title\" colspan=\"8\">" + escapeHtml(monthLabel) + "</th>");
        out.println("  </tr>");

        while (!weekStart.isAfter(lastDayOfMonth)) {
            boolean weekApproved = approvedWeeklyStarts.contains(weekStart);
            String weekCellClass = weekApproved ? "boxed" : "boxed fail";
            String weekLinkClass = weekApproved ? " class=\"button\"" : "";

            out.println("  <tr class=\"boxed\">");
            out.println("    <td class=\"" + weekCellClass + "\"><a" + weekLinkClass + " href=\""
                    + buildListLink(TYPE_WEEKLY, weekStart)
                    + "\">Week</a></td>");

            for (int dayOffset = 0; dayOffset < 7; dayOffset++) {
                LocalDate day = weekStart.plusDays(dayOffset);
                Integer billableMinutes = billableMinutesByDay.get(day);
                boolean hasBillableTime = billableMinutes != null && billableMinutes.intValue() > 0;
                boolean dailyApproved = approvedDailyStarts.contains(day);
                String dayCellClass;
                if (!hasBillableTime) {
                    dayCellClass = "boxed";
                } else if (dailyApproved) {
                    dayCellClass = "boxed";
                } else {
                    dayCellClass = "boxed fail";
                }

                out.print("    <td class=\"" + dayCellClass + "\">");
                if (hasBillableTime) {
                    String dayLinkClass = dailyApproved ? " class=\"button\"" : "";
                    out.print("<a" + dayLinkClass + " href=\"" + buildListLink(TYPE_DAILY, day)
                            + "\">" + day.getDayOfMonth() + "</a>");
                } else {
                    out.print("<span style=\"color:#6C757D;\">" + day.getDayOfMonth() + "</span>");
                }
                out.println("</td>");
            }

            out.println("  </tr>");
            weekStart = weekStart.plusWeeks(1);
        }

        out.println("  <tr class=\"boxed\">");
        out.println("    <td class=\"boxed\"><a class=\"button\" href=\"" + buildListLink(TYPE_MONTHLY, firstDayOfMonth)
                + "\">Month</a></td>");
        out.println("    <td class=\"boxed\" colspan=\"7\">"
                + "<a class=\"button\" href=\"" + buildListLink(TYPE_MONTHLY, previousMonthStart) + "\">"
                + escapeHtml(previousMonthShort) + "</a> "
                + "<a class=\"button\" href=\"" + buildListLink(TYPE_MONTHLY, firstDayOfMonth) + "\">"
                + escapeHtml(currentMonthShort) + "</a> "
                + "<a class=\"button\" href=\"" + buildListLink(TYPE_MONTHLY, nextMonthStart) + "\">"
                + escapeHtml(nextMonthShort) + "</a></td>");
        out.println("  </tr>");
        out.println("</table>");
    }

    private void printEditor(PrintWriter out, WebUser webUser, TrackerNarrative narrative, String type,
            LocalDate selectedDate, boolean editMode, TrackerNarrativeDao narrativeDao) {
        boolean generationAvailable = TrackerNarrativeGenerator.isGenerationAvailable();
        out.println("<h2>Tracker Narrative</h2>");
        out.println("<p><a class=\"button\" href=\"" + buildListLink(type, selectedDate)
                + "\">Back to list</a>");
        LocalDate periodStart = webUser.toLocalDate(narrative.getPeriodStart());
        for (boolean next : new boolean[] { false, true }) {
            TrackerNarrative adjacent = narrativeDao.findAdjacentByContactAndType(webUser.getContactId(),
                    narrative.getNarrativeType(), periodStart, next);
            if (adjacent != null) {
                out.println(" <a class=\"button\" href=\""
                        + buildEditorLink(adjacent.getNarrativeId(), adjacent.getNarrativeType(),
                                webUser.toLocalDate(adjacent.getPeriodStart()))
                        + "\">" + (next ? "Next" : "Previous") + "</a>");
            }
        }
        out.println("</p>");

        if (!generationAvailable) {
            out.println("<p class=\"fail\">" + escapeHtml(GENERATION_UNAVAILABLE_MESSAGE) + "</p>");
        }

        out.println("<form method=\"POST\" action=\"TrackerNarrativeClassicServlet\">\n");
        out.println("<input type=\"hidden\" name=\"" + PARAM_ID + "\" value=\""
                + narrative.getNarrativeId() + "\">\n");
        out.println("<input type=\"hidden\" name=\"lastUpdated\" value=\""
                + escapeHtml(n(TrackerNarrativeService.version(narrative))) + "\">");
        out.println("<input type=\"hidden\" name=\"" + PARAM_TYPE + "\" value=\"" + escapeHtml(type)
                + "\">\n");
        out.println("<input type=\"hidden\" name=\"" + PARAM_DATE + "\" value=\"" + selectedDate
                + "\">\n");

        out.println("<table class=\"boxed\">\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"title\" colspan=\"2\">Details</th>\n");
        out.println("  </tr>\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"boxed\">Title</th>\n");
        out.println("    <td class=\"boxed\">" + escapeHtml(n(narrative.getDisplayTitle())) + "</td>\n");
        out.println("  </tr>\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"boxed\">Type</th>\n");
        out.println("    <td class=\"boxed\">" + escapeHtml(n(narrative.getNarrativeType())) + "</td>\n");
        out.println("  </tr>\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"boxed\">Status</th>\n");
        out.println("    <td class=\"boxed\">" + escapeHtml(n(narrative.getReviewStatusString())) + "</td>\n");
        out.println("  </tr>\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"boxed\">Generated</th>\n");
        out.println("    <td class=\"boxed\">" + formatDateTime(webUser, narrative.getDateGenerated()) + "</td>\n");
        out.println("  </tr>\n");
        out.println("</table><br/>\n");

        if (TrackerNarrativeReviewStatus.GENERATING.equals(narrative.getReviewStatus())) {
            out.println("<p class=\"fail\">Generating... "
                    + "<a class=\"button\" href=\"" + buildEditorLink(narrative.getNarrativeId(), type,
                            selectedDate, editMode ? VIEW_EDIT : null)
                    + "\">Refresh</a></p>\n");
        } else if (TrackerNarrativeReviewStatus.FAILED.equals(narrative.getReviewStatus())) {
            out.println("<p class=\"fail\">Narrative generation failed. Use Regenerate to try again.</p>\n");
        }

        if (editMode) {
            out.println("<p>Save your edits before approving. Editing an approved report creates a "
                    + "separate unapproved draft.</p>");
            out.println("<p><a class=\"button\" href=\""
                    + buildEditorLink(narrative.getNarrativeId(), type, selectedDate)
                    + "\">Preview</a></p>\n");
            out.println("<h3>Final Markdown</h3>\n");
            out.println("<textarea name=\"" + PARAM_MARKDOWN_FINAL
                    + "\" rows=\"20\" cols=\"100\">"
                    + escapeHtml(n(narrative.getMarkdownFinal())) + "</textarea><br/><br/>\n");
        } else {
            out.println("<p><a class=\"button\" href=\""
                    + buildEditorLink(narrative.getNarrativeId(), type, selectedDate, VIEW_EDIT)
                    + "\">Edit</a></p>\n");
            out.println("<h3>Final Markdown</h3>\n");
            out.println("<div class=\"scrollbox\">"
                    + renderMarkdown(n(narrative.getMarkdownFinal())) + "</div><br/><br/>\n");
        }

        String generatedId = "generatedMarkdown" + narrative.getNarrativeId();
        out.println("<p><a class=\"button\" href=\"javascript:toggleLayer('" + generatedId
                + "')\">Toggle Generated Markdown</a></p>\n");
        out.println("<div id=\"" + generatedId + "\" style=\"display:none;\">\n");
        out.println("<table class=\"boxed-fill\">\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"title\">Generated Markdown (read-only)</th>\n");
        out.println("  </tr>\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <td class=\"boxed\"><div class=\"scrollbox\"><pre>"
                + escapeHtml(n(narrative.getMarkdownGenerated())) + "</pre></div></td>\n");
        out.println("  </tr>\n");
        out.println("</table></div><br/>\n");

        String promptUsedId = "promptUsed" + narrative.getNarrativeId();
        out.println("<p><a class=\"button\" href=\"javascript:toggleLayer('" + promptUsedId
                + "')\">Toggle Prompt Used</a></p>\n");
        out.println("<div id=\"" + promptUsedId + "\" style=\"display:none;\">\n");
        out.println("<table class=\"boxed-fill\">\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <th class=\"title\">Prompt Used (read-only)</th>\n");
        out.println("  </tr>\n");
        out.println("  <tr class=\"boxed\">\n");
        out.println("    <td class=\"boxed\"><div class=\"scrollbox\"><pre>"
                + escapeHtml(n(narrative.getPromptUsedText())) + "</pre></div></td>\n");
        out.println("  </tr>\n");
        out.println("</table></div><br/>\n");

        out.println("<p>");
        if (editMode) {
            out.println("  <input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_SAVE
                    + "\">\n");
        }
        if (!editMode) {
            out.println("  <input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_APPROVE
                    + "\">\n");
        }
        if (generationAvailable) {
            out.println("  <input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_REGENERATE
                    + "\">\n");
        }
        out.println("  <input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_REJECT + "\">\n");
        out.println("  <input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\"" + ACTION_DELETE + "\">\n");
        out.println("</p>\n");
        out.println("</form>\n");
    }

    private static String buildTypeSelect(String selected) {
        StringBuilder sb = new StringBuilder();
        sb.append("<select name=\"").append(PARAM_TYPE).append("\">");
        sb.append(option(TYPE_DAILY, selected));
        sb.append(option(TYPE_WEEKLY, selected));
        sb.append(option(TYPE_MONTHLY, selected));
        sb.append("</select>");
        return sb.toString();
    }

    private static String option(String value, String selected) {
        String mark = value.equals(selected) ? " selected" : "";
        return "<option value=\"" + value + "\"" + mark + ">" + value + "</option>";
    }

    private static String formatPeriod(WebUser webUser, LocalDate periodStart, LocalDate periodEnd) {
        return formatDate(webUser, periodStart) + " - " + formatDate(webUser, periodEnd);
    }

    private static String formatDate(WebUser webUser, LocalDate date) {
        if (date == null) {
            return "";
        }
        Date asDate = webUser.toDate(date);
        return webUser.getDateFormat().format(asDate);
    }

    private static String formatDateTime(WebUser webUser, Date date) {
        if (date == null) {
            return "";
        }
        return webUser.getTimeFormat().format(date);
    }

    private static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String renderMarkdown(String markdown) {
        String value = markdown == null ? "" : markdown;
        Node document = MARKDOWN_PARSER.parse(value);
        return MARKDOWN_RENDERER.render(document);
    }

    private static long readLong(String value) {
        if (value == null || value.trim().length() == 0) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String buildListLink(String type, LocalDate date) {
        return "TrackerNarrativeClassicServlet?" + PARAM_TYPE + "=" + type + "&" + PARAM_DATE + "=" + date;
    }

    private static String buildEditorLink(long id, String type, LocalDate date) {
        return buildEditorLink(id, type, date, null);
    }

    private static String buildEditorLink(long id, String type, LocalDate date, String view) {
        String link = "TrackerNarrativeClassicServlet?" + PARAM_ID + "=" + id + "&" + PARAM_TYPE + "=" + type
                + "&" + PARAM_DATE + "=" + date;
        if (view != null && view.trim().length() > 0) {
            link += "&" + PARAM_VIEW + "=" + view;
        }
        return link;
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
