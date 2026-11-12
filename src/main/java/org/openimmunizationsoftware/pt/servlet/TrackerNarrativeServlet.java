package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.narrative.model.NarrativeReviewQueue;
import org.dandeliondaily.narrative.model.TrackerNarrativeScope;
import org.dandeliondaily.narrative.render.NarrativeReviewRenderer;
import org.dandeliondaily.narrative.service.NarrativeWorkflowException;
import org.dandeliondaily.narrative.service.TrackerNarrativeService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.doa.TrackerNarrativeDao;
import org.openimmunizationsoftware.pt.manager.TrackerNarrativeGenerator;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Narrative review: a queue of drafts to review and periods that need a report, with the selected report
 * read, edited and approved in place. Every change goes through {@link TrackerNarrativeService}, the same
 * service the MCP narrative tools use. The previous list-and-editor page is TrackerNarrativeClassicServlet.
 */
public class TrackerNarrativeServlet extends ClientServlet {

    private static final long serialVersionUID = 1L;

    private static final String PAGE = NarrativeReviewRenderer.PAGE;
    private static final int DEFAULT_LOOKBACK_DAYS = 30;

    private final TrackerNarrativeService narrativeService = new TrackerNarrativeService();
    private final NarrativeReviewRenderer renderer = new NarrativeReviewRenderer();

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
                forwardToHome(request, response);
                return;
            }
            if ("POST".equalsIgnoreCase(request.getMethod())) {
                handlePost(request, response, appReq);
                return;
            }
            showPage(request, appReq);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            appReq.close();
        }
    }

    // ---- actions -------------------------------------------------------------------------------------

    private void handlePost(HttpServletRequest request, HttpServletResponse response, AppReq appReq)
            throws IOException {
        String action = n(request.getParameter("action"));
        if ("preview".equals(action)) {
            response.setContentType("text/html;charset=UTF-8");
            appReq.getOut().print(NarrativeReviewRenderer.renderMarkdown(request.getParameter("markdown")));
            return;
        }

        Session session = appReq.getDataSession();
        WebUser user = appReq.getWebUser();
        long id = readLong(request.getParameter("id"));
        long nextId = readLong(request.getParameter("nextId"));
        String lastUpdated = request.getParameter("lastUpdated");
        String back = id > 0 ? PAGE + "?id=" + id : PAGE;
        try {
            String redirect;
            if ("approve".equals(action)) {
                TrackerNarrative approved = narrativeService.saveAndApprove(session, user, null, id, lastUpdated,
                        request.getParameter("markdownFinal"));
                redirect = afterward(nextId, approved, "Approved " + describe(approved) + ".");
            } else if ("save".equals(action)) {
                TrackerNarrative saved = narrativeService.updateDraft(session, user, null, id, lastUpdated,
                        request.getParameter("markdownFinal"));
                redirect = PAGE + "?id=" + saved.getNarrativeId() + "&notice="
                        + NarrativeReviewRenderer.urlEncode(saved.getNarrativeId() == id ? "Draft saved."
                                : "Saved as a new draft. The approved report stays until you approve this one.");
            } else if ("reject".equals(action) || "delete".equals(action)) {
                boolean delete = "delete".equals(action);
                TrackerNarrative changed = narrativeService.changeStatus(session, user, null, id, lastUpdated,
                        delete ? TrackerNarrativeReviewStatus.DELETED : TrackerNarrativeReviewStatus.REJECTED);
                redirect = afterward(nextId, changed,
                        (delete ? "Deleted " : "Rejected ") + describe(changed) + ".");
            } else if ("regenerate".equals(action)) {
                requireGeneration();
                TrackerNarrative queued = narrativeService.regenerate(session, user, null, id, lastUpdated);
                redirect = PAGE + "?id=" + queued.getNarrativeId();
            } else if ("generate-again".equals(action)) {
                // A stuck generation can't be regenerated in place: clear it and start the period over.
                requireGeneration();
                TrackerNarrative stuck = narrativeService.changeStatus(session, user, null, id, lastUpdated,
                        TrackerNarrativeReviewStatus.DELETED);
                TrackerNarrativeScope scope = narrativeService.resolveScope(stuck.getNarrativeType(),
                        user.toLocalDate(stuck.getPeriodStart()));
                redirect = PAGE + "?id=" + narrativeService.generate(appReq, scope);
            } else if ("generate".equals(action)) {
                requireGeneration();
                LocalDate date = parseDate(request.getParameter("date"));
                if (date == null) {
                    throw new NarrativeWorkflowException("invalid_arguments", "Pick a period to generate.");
                }
                TrackerNarrativeScope scope = narrativeService.resolveScope(request.getParameter("type"), date);
                back = periodLink(scope.getNarrativeType(), scope.getPeriodStart());
                redirect = PAGE + "?id=" + narrativeService.generate(appReq, scope);
            } else {
                redirect = back;
            }
            response.sendRedirect(redirect);
        } catch (NarrativeWorkflowException e) {
            String message = "stale".equals(e.getCode())
                    ? "This report changed somewhere else (another tab or an MCP update). It has been reloaded; "
                            + "make your change again."
                    : e.getMessage();
            response.sendRedirect(back + (back.contains("?") ? "&" : "?") + "error="
                    + NarrativeReviewRenderer.urlEncode(message));
        }
    }

    /** Where to go after a report leaves the queue: the next one waiting, or back to the queue. */
    private static String afterward(long nextId, TrackerNarrative done, String notice) {
        String target = nextId > 0 && nextId != done.getNarrativeId() ? PAGE + "?id=" + nextId : PAGE + "?";
        return target + (target.endsWith("?") ? "" : "&") + "notice=" + NarrativeReviewRenderer.urlEncode(notice);
    }

    private static void requireGeneration() {
        if (!TrackerNarrativeGenerator.isGenerationAvailable()) {
            throw new NarrativeWorkflowException("generation_unavailable",
                    TrackerNarrativeGenerator.getGenerationUnavailableMessage());
        }
    }

    private static String describe(TrackerNarrative narrative) {
        String type = narrative.getNarrativeType();
        String label = TrackerNarrativeScope.TYPE_WEEKLY.equals(type) ? "the weekly report"
                : TrackerNarrativeScope.TYPE_MONTHLY.equals(type) ? "the monthly report" : "the daily report";
        return label + " for " + TrackerNarrativeDao.periodDate(narrative.getPeriodStart());
    }

    // ---- page ----------------------------------------------------------------------------------------

    private void showPage(HttpServletRequest request, AppReq appReq) {
        Session session = appReq.getDataSession();
        WebUser user = appReq.getWebUser();
        TrackerNarrativeDao dao = new TrackerNarrativeDao(session);

        NarrativeReviewRenderer.Page page = new NarrativeReviewRenderer.Page();
        page.today = user.getLocalDateToday();
        page.lookbackDays = clamp((int) readLong(request.getParameter("back")), DEFAULT_LOOKBACK_DAYS, 7, 400);
        page.queue = narrativeService.loadReviewQueue(session, user, page.lookbackDays);
        page.generationAvailable = TrackerNarrativeGenerator.isGenerationAvailable();
        page.generationUnavailableMessage = TrackerNarrativeGenerator.getGenerationUnavailableMessage();
        page.notice = blankToNull(request.getParameter("notice"));
        page.error = blankToNull(request.getParameter("error"));
        page.editing = "1".equals(request.getParameter("edit"));

        long id = readLong(request.getParameter("id"));
        LocalDate date = parseDate(request.getParameter("date"));
        if (id > 0) {
            TrackerNarrative narrative = dao.getByIdForContact(id, user.getContactId());
            if (narrative == null) {
                page.error = "That report was not found.";
            } else {
                page.selected = narrative;
            }
        } else if (date != null) {
            TrackerNarrativeScope scope = narrativeService.resolveScope(request.getParameter("type"), date);
            page.selected = narrativeService.loadViewModel(session, user, scope, 0).getActiveNarrative();
            if (page.selected != null && page.selected.getReviewStatus() == TrackerNarrativeReviewStatus.DELETED) {
                page.selected = null;
            }
            page.scope = scope;
        } else if (request.getParameter("month") == null && !page.queue.getToReview().isEmpty()) {
            page.selected = page.queue.getToReview().get(0).getNarrative();
        }

        if (page.selected != null) {
            page.scope = narrativeService.resolveScope(page.selected.getNarrativeType(),
                    TrackerNarrativeDao.periodDate(page.selected.getPeriodStart()));
        }
        if (page.scope != null) {
            loadPeriodContext(dao, user, page);
        }

        YearMonth month = parseMonth(request.getParameter("month"));
        page.calendarMonth = month != null ? month
                : YearMonth.from(page.scope != null ? page.scope.getPeriodStart() : page.today);
        loadCalendar(dao, user, page);

        appReq.setTitle("Narratives");
        printHtmlHead(appReq);
        PrintWriter out = appReq.getOut();
        printDandelionLocation(out, "Time Management & Reporting / Narratives");
        renderer.render(out, user, page);
        printHtmlFoot(appReq);
    }

    private static void loadPeriodContext(TrackerNarrativeDao dao, WebUser user, NarrativeReviewRenderer.Page page) {
        TrackerNarrativeScope scope = page.scope;
        int contactId = user.getContactId();
        int selectedId = page.selected == null ? 0 : page.selected.getNarrativeId();
        for (TrackerNarrative other : dao.findByContactTypeAndPeriod(contactId, scope.getNarrativeType(),
                scope.getPeriodStart(), scope.getPeriodEnd())) {
            if (other.getNarrativeId() != selectedId
                    && other.getReviewStatus() != TrackerNarrativeReviewStatus.DELETED) {
                page.otherVersions.add(other);
            }
        }
        page.approvedForPeriod = dao.findApprovedByContactTypeAndPeriod(contactId, scope.getNarrativeType(),
                scope.getPeriodStart(), scope.getPeriodEnd());
        page.previousPeriod = dao.findAdjacentByContactAndType(contactId, scope.getNarrativeType(),
                scope.getPeriodStart(), false);
        page.nextPeriod = dao.findAdjacentByContactAndType(contactId, scope.getNarrativeType(),
                scope.getPeriodStart(), true);
        Map<LocalDate, Integer> billable = dao.sumBillableMinutesByDay(Integer.valueOf(user.getWebUserId()),
                scope.getPeriodStart(), scope.getPeriodEnd().plusDays(1));
        for (Integer minutes : billable.values()) {
            page.scopeBillableMinutes += minutes == null ? 0 : minutes.intValue();
        }
    }

    private static void loadCalendar(TrackerNarrativeDao dao, WebUser user, NarrativeReviewRenderer.Page page) {
        LocalDate first = page.calendarMonth.atDay(1);
        LocalDate gridStart = first.minusDays(first.getDayOfWeek().getValue() % 7);
        LocalDate last = page.calendarMonth.atEndOfMonth();
        LocalDate gridEnd = last.plusDays(6 - (last.getDayOfWeek().getValue() % 7));
        page.calendarBillable = dao.sumBillableMinutesByDay(Integer.valueOf(user.getWebUserId()), gridStart,
                gridEnd.plusDays(1));
        page.approvedDays = dao.findApprovedPeriodStarts(user.getContactId(), TrackerNarrativeScope.TYPE_DAILY,
                gridStart, gridEnd);
        page.approvedWeeks = dao.findApprovedPeriodStarts(user.getContactId(), TrackerNarrativeScope.TYPE_WEEKLY,
                gridStart, gridEnd);
        Set<String> pending = new HashSet<String>();
        for (NarrativeReviewQueue.Draft draft : page.queue.getToReview()) {
            pending.add(key(draft.getNarrative()));
        }
        addKeys(pending, page.queue.getGenerating());
        page.pendingKeys = pending;
    }

    private static void addKeys(Set<String> keys, List<TrackerNarrative> narratives) {
        for (TrackerNarrative narrative : narratives) {
            keys.add(key(narrative));
        }
    }

    private static String key(TrackerNarrative narrative) {
        return TrackerNarrativeService.periodKey(narrative.getNarrativeType(),
                TrackerNarrativeDao.periodDate(narrative.getPeriodStart()));
    }

    // ---- parsing -------------------------------------------------------------------------------------

    private static String periodLink(String type, LocalDate start) {
        return PAGE + "?type=" + type + "&date=" + start;
    }

    private static long readLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int clamp(int value, int fallback, int min, int max) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static YearMonth parseMonth(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
