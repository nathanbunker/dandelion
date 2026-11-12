package org.dandeliondaily.narrative.render;

import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.dandeliondaily.narrative.model.NarrativeReviewQueue;
import org.dandeliondaily.narrative.model.TrackerNarrativeScope;
import org.dandeliondaily.narrative.service.TrackerNarrativeService;
import org.openimmunizationsoftware.pt.doa.TrackerNarrativeDao;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The narrative review page: a queue of what needs attention on the left, the selected report on the
 * right, read and approved in place.
 */
public class NarrativeReviewRenderer {

    public static final String PAGE = "trackerNarrative";

    private static final Parser MARKDOWN_PARSER = Parser.builder().build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().escapeHtml(true).build();
    private static final DateTimeFormatter DAY_LONG = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter DAY_SHORT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    /** Everything the page shows. */
    public static class Page {
        public NarrativeReviewQueue queue;
        /** The report on the right, or null when an empty period (or nothing at all) is selected. */
        public TrackerNarrative selected;
        /** The selected period; set even when it has no report. */
        public TrackerNarrativeScope scope;
        public int scopeBillableMinutes;
        /** Other reports for the selected period, newest first. */
        public List<TrackerNarrative> otherVersions = new ArrayList<TrackerNarrative>();
        public TrackerNarrative approvedForPeriod;
        public TrackerNarrative previousPeriod;
        public TrackerNarrative nextPeriod;
        public boolean editing;
        public boolean generationAvailable;
        public String generationUnavailableMessage;
        public String notice;
        public String error;
        public int lookbackDays;
        public YearMonth calendarMonth;
        public Map<LocalDate, Integer> calendarBillable = Collections.emptyMap();
        public Set<LocalDate> approvedDays = Collections.emptySet();
        public Set<LocalDate> approvedWeeks = Collections.emptySet();
        public Set<String> pendingKeys = Collections.emptySet();
        public LocalDate today;
    }

    public void render(PrintWriter out, WebUser user, Page page) {
        printStyle(out);
        out.println("<div class=\"nr\">");
        out.println("<aside class=\"nr-side\">");
        printQueue(out, user, page);
        printCalendar(out, page);
        out.println("<p class=\"nr-foot\">");
        int otherLookback = page.lookbackDays > 30 ? 30 : 90;
        out.println("<a href=\"" + PAGE + "?back=" + otherLookback + "\">Look back " + otherLookback
                + " days</a> &middot; <a href=\"TrackerNarrativeClassicServlet\">Classic view</a></p>");
        out.println("</aside>");
        out.println("<main class=\"nr-main\">");
        if (page.notice != null) {
            out.println("<div class=\"nr-toast\">" + esc(page.notice) + "</div>");
        }
        if (page.error != null) {
            out.println("<div class=\"nr-toast nr-toast-bad\">" + esc(page.error) + "</div>");
        }
        if (!page.generationAvailable && page.generationUnavailableMessage != null) {
            out.println("<div class=\"nr-muted nr-small\">" + esc(page.generationUnavailableMessage) + "</div>");
        }
        if (page.selected != null) {
            printReport(out, user, page);
        } else if (page.scope != null) {
            printEmptyPeriod(out, page);
        } else {
            printCaughtUp(out, page);
        }
        out.println("</main>");
        out.println("</div>");
        printScript(out, page);
    }

    // ---- queue ---------------------------------------------------------------------------------------

    private void printQueue(PrintWriter out, WebUser user, Page page) {
        NarrativeReviewQueue queue = page.queue;
        int selectedId = page.selected == null ? 0 : page.selected.getNarrativeId();

        out.println("<h3 class=\"nr-h\">To review <span class=\"nr-count\">" + queue.getToReview().size()
                + "</span></h3>");
        if (queue.getToReview().isEmpty()) {
            out.println("<p class=\"nr-muted nr-small\">Nothing waiting. &#127881;</p>");
        }
        out.println("<ul class=\"nr-list\">");
        for (NarrativeReviewQueue.Draft draft : queue.getToReview()) {
            TrackerNarrative n = draft.getNarrative();
            out.println("<li><a class=\"nr-item" + (n.getNarrativeId() == selectedId ? " is-current" : "")
                    + "\" href=\"" + reportLink(n) + "\">" + typeChip(n.getNarrativeType())
                    + "<span class=\"nr-when\">" + esc(shortPeriod(n.getNarrativeType(), start(n), end(n)))
                    + "</span>" + (draft.isRevision() ? "<span class=\"nr-tag\">revision</span>" : "")
                    + "</a></li>");
        }
        out.println("</ul>");

        printNarrativeGroup(out, "Generating", queue.getGenerating(), selectedId, "&#9203;");
        printNarrativeGroup(out, "Failed", queue.getFailed(), selectedId, "&#9888;&#65039;");
        printNarrativeGroup(out, "Stuck generating", queue.getStuck(), selectedId, "&#9888;&#65039;");

        if (!queue.getMissing().isEmpty()) {
            out.println("<h3 class=\"nr-h\">Needs a report <span class=\"nr-count nr-count-soft\">"
                    + queue.getMissing().size() + "</span></h3>");
            out.println("<ul class=\"nr-list\">");
            for (NarrativeReviewQueue.MissingPeriod missing : queue.getMissing()) {
                boolean current = page.selected == null && page.scope != null
                        && page.scope.getNarrativeType().equals(missing.getNarrativeType())
                        && page.scope.getPeriodStart().equals(missing.getPeriodStart());
                out.print("<li class=\"nr-missing\"><a class=\"nr-item" + (current ? " is-current" : "")
                        + "\" href=\"" + periodLink(missing.getNarrativeType(), missing.getPeriodStart()) + "\">"
                        + typeChip(missing.getNarrativeType()) + "<span class=\"nr-when\">"
                        + esc(shortPeriod(missing.getNarrativeType(), missing.getPeriodStart(),
                                missing.getPeriodEnd()))
                        + "</span><span class=\"nr-hours\">" + hours(missing.getBillableMinutes()) + "</span></a>");
                if (page.generationAvailable) {
                    out.print("<form method=\"post\" action=\"" + PAGE + "\">"
                            + hidden("action", "generate") + hidden("type", missing.getNarrativeType())
                            + hidden("date", missing.getPeriodStart().toString())
                            + "<button class=\"nr-mini\" type=\"submit\" title=\"Generate this report\">"
                            + "Generate</button></form>");
                }
                out.println("</li>");
            }
            out.println("</ul>");
        }
        if (queue.getStaleDraftCount() > 0) {
            out.println("<p class=\"nr-muted nr-small\">" + queue.getStaleDraftCount() + " older draft"
                    + (queue.getStaleDraftCount() == 1 ? "" : "s")
                    + " not shown: their periods were approved since.</p>");
        }
    }

    private void printNarrativeGroup(PrintWriter out, String title, List<TrackerNarrative> narratives,
            int selectedId, String icon) {
        if (narratives.isEmpty()) {
            return;
        }
        out.println("<h3 class=\"nr-h\">" + esc(title) + " <span class=\"nr-count nr-count-soft\">"
                + narratives.size() + "</span></h3>");
        out.println("<ul class=\"nr-list\">");
        for (TrackerNarrative n : narratives) {
            out.println("<li><a class=\"nr-item" + (n.getNarrativeId() == selectedId ? " is-current" : "")
                    + "\" href=\"" + reportLink(n) + "\">" + typeChip(n.getNarrativeType())
                    + "<span class=\"nr-when\">" + esc(shortPeriod(n.getNarrativeType(), start(n), end(n)))
                    + "</span><span class=\"nr-hours\">" + icon + "</span></a></li>");
        }
        out.println("</ul>");
    }

    // ---- calendar ------------------------------------------------------------------------------------

    private void printCalendar(PrintWriter out, Page page) {
        YearMonth month = page.calendarMonth;
        LocalDate first = month.atDay(1);
        LocalDate weekStart = first.minusDays(first.getDayOfWeek().getValue() % 7);
        LocalDate selectedStart = page.scope == null ? null : page.scope.getPeriodStart();
        String selectedType = page.scope == null ? null : page.scope.getNarrativeType();

        out.println("<div class=\"nr-cal\">");
        out.println("<div class=\"nr-cal-head\"><a href=\"" + PAGE + "?month=" + month.minusMonths(1)
                + "\" title=\"Previous month\">&lsaquo;</a><a class=\"nr-cal-title"
                + (TrackerNarrativeScope.TYPE_MONTHLY.equals(selectedType)
                        && first.equals(selectedStart) ? " is-current" : "")
                + "\" href=\"" + periodLink(TrackerNarrativeScope.TYPE_MONTHLY, first)
                + "\" title=\"Open the monthly report\">" + esc(first.format(MONTH_YEAR)) + "</a><a href=\""
                + PAGE + "?month=" + month.plusMonths(1) + "\" title=\"Next month\">&rsaquo;</a></div>");
        out.println("<table class=\"nr-cal-grid\"><tr><th title=\"Week\">Wk</th><th>S</th><th>M</th><th>T</th>"
                + "<th>W</th><th>T</th><th>F</th><th>S</th></tr>");
        while (!weekStart.isAfter(month.atEndOfMonth())) {
            int weekMinutes = 0;
            for (int i = 0; i < 7; i++) {
                weekMinutes += minutes(page.calendarBillable, weekStart.plusDays(i));
            }
            String weekState = state(page, TrackerNarrativeScope.TYPE_WEEKLY, weekStart, weekMinutes,
                    page.approvedWeeks.contains(weekStart), !weekStart.plusDays(6).isBefore(page.today));
            boolean weekCurrent = TrackerNarrativeScope.TYPE_WEEKLY.equals(selectedType)
                    && weekStart.equals(selectedStart);
            out.print("<tr><td><a class=\"nr-day nr-wk " + weekState + (weekCurrent ? " is-current" : "")
                    + "\" href=\"" + periodLink(TrackerNarrativeScope.TYPE_WEEKLY, weekStart)
                    + "\" title=\"Week of " + esc(weekStart.format(MONTH_DAY)) + "\">W</a></td>");
            for (int i = 0; i < 7; i++) {
                LocalDate day = weekStart.plusDays(i);
                int dayMinutes = minutes(page.calendarBillable, day);
                String dayState = state(page, TrackerNarrativeScope.TYPE_DAILY, day, dayMinutes,
                        page.approvedDays.contains(day), false);
                boolean dayCurrent = TrackerNarrativeScope.TYPE_DAILY.equals(selectedType)
                        && day.equals(selectedStart);
                String outside = day.getMonthValue() != month.getMonthValue() ? " is-outside" : "";
                String todayClass = day.equals(page.today) ? " is-today" : "";
                out.print("<td><a class=\"nr-day " + dayState + outside + todayClass
                        + (dayCurrent ? " is-current" : "") + "\" href=\""
                        + periodLink(TrackerNarrativeScope.TYPE_DAILY, day) + "\" title=\""
                        + esc(day.format(DAY_SHORT)) + (dayMinutes > 0 ? " &middot; " + hours(dayMinutes) : "")
                        + "\">" + day.getDayOfMonth() + "</a></td>");
            }
            out.println("</tr>");
            weekStart = weekStart.plusWeeks(1);
        }
        out.println("</table>");
        out.println("<div class=\"nr-legend\"><span class=\"nr-dot s-approved\"></span>approved "
                + "<span class=\"nr-dot s-pending\"></span>to review <span class=\"nr-dot s-missing\"></span>"
                + "missing</div>");
        out.println("</div>");
    }

    private static String state(Page page, String type, LocalDate start, int minutes, boolean approved,
            boolean notOverYet) {
        if (approved) {
            return "s-approved";
        }
        if (page.pendingKeys.contains(TrackerNarrativeService.periodKey(type, start))) {
            return "s-pending";
        }
        if (minutes > 0 && !notOverYet) {
            return "s-missing";
        }
        return minutes > 0 ? "s-time" : "s-none";
    }

    // ---- the selected report -------------------------------------------------------------------------

    private void printReport(PrintWriter out, WebUser user, Page page) {
        TrackerNarrative n = page.selected;
        TrackerNarrativeReviewStatus status = n.getReviewStatus();
        boolean generating = status == TrackerNarrativeReviewStatus.GENERATING;
        boolean stuck = generating && TrackerNarrativeService.isStuck(n);
        boolean editable = status == TrackerNarrativeReviewStatus.GENERATED
                || status == TrackerNarrativeReviewStatus.APPROVED || status == TrackerNarrativeReviewStatus.REJECTED;

        printHeader(out, user, page, n.getNarrativeType(), start(n), end(n), status);

        out.println("<form id=\"nr-form\" method=\"post\" action=\"" + PAGE + "\""
                + (generating && !stuck ? " data-generating=\"1\"" : "") + ">");
        out.println(hidden("id", String.valueOf(n.getNarrativeId())));
        out.println(hidden("lastUpdated", TrackerNarrativeService.version(n)));
        out.println(hidden("nextId", String.valueOf(followingQueueId(page))));
        out.println("<input type=\"hidden\" name=\"action\" id=\"nr-action\" value=\"\">");

        if (generating && !stuck) {
            out.println("<div class=\"nr-doc nr-doc-empty\">&#9203; Generating&hellip; this page refreshes on "
                    + "its own.</div>");
        } else if (stuck) {
            out.println("<div class=\"nr-doc nr-doc-empty\">This generation started "
                    + esc(formatTime(user, n.getLastUpdated()))
                    + " and never finished. Delete it or generate the period again.</div>");
        } else if (status == TrackerNarrativeReviewStatus.FAILED) {
            out.println("<div class=\"nr-doc nr-doc-empty\">Generation failed. Regenerate to try again.</div>");
        } else {
            String markdown = n.getMarkdownFinal() == null ? "" : n.getMarkdownFinal();
            out.println("<div class=\"nr-doc\" id=\"nr-view\" title=\"Click to edit (E)\">"
                    + renderMarkdown(markdown) + "</div>");
            out.println("<div class=\"nr-editor\" id=\"nr-editor\" hidden>");
            out.println("<textarea name=\"markdownFinal\" id=\"nr-text\" spellcheck=\"true\">" + esc(markdown)
                    + "</textarea>");
            out.println("<div class=\"nr-doc nr-preview\" id=\"nr-preview\">" + renderMarkdown(markdown)
                    + "</div>");
            out.println("</div>");
        }

        out.println("<div class=\"nr-actions\">");
        if (editable) {
            String approveLabel = status == TrackerNarrativeReviewStatus.APPROVED ? "&#10003; Save &amp; approve"
                    : "&#10003; Approve";
            out.println("<button type=\"button\" class=\"nr-btn nr-primary\" id=\"nr-approve\""
                    + (status == TrackerNarrativeReviewStatus.APPROVED ? " hidden" : "")
                    + " data-action=\"approve\">" + approveLabel + "</button>");
            out.println("<button type=\"button\" class=\"nr-btn\" id=\"nr-edit\">Edit</button>");
            out.println("<button type=\"button\" class=\"nr-btn\" id=\"nr-save\" data-action=\"save\" hidden>"
                    + "Save draft</button>");
            out.println("<button type=\"button\" class=\"nr-btn nr-quiet\" id=\"nr-cancel\" hidden>Cancel</button>");
        }
        if (page.generationAvailable && !(generating && !stuck) && status != TrackerNarrativeReviewStatus.DELETED) {
            out.println("<button type=\"button\" class=\"nr-btn\" data-action=\"regenerate\""
                    + (stuck ? " data-generate=\"1\"" : "") + ">&#8635; Regenerate</button>");
        }
        out.println("<details class=\"nr-more\"><summary class=\"nr-btn nr-quiet\">More</summary><div>");
        if (status == TrackerNarrativeReviewStatus.GENERATED || status == TrackerNarrativeReviewStatus.APPROVED
                || status == TrackerNarrativeReviewStatus.FAILED || stuck) {
            out.println("<button type=\"button\" class=\"nr-link\" data-action=\"reject\">Reject</button>");
        }
        if (status != TrackerNarrativeReviewStatus.DELETED && (!generating || stuck)) {
            out.println("<button type=\"button\" class=\"nr-link nr-danger\" data-action=\"delete\" "
                    + "data-confirm=\"1\">Delete</button>");
        }
        if (n.getMarkdownGenerated() != null) {
            out.println("<button type=\"button\" class=\"nr-link\" data-panel=\"nr-diff\">Generated vs final"
                    + "</button>");
        }
        if (n.getPromptUsedText() != null) {
            out.println("<button type=\"button\" class=\"nr-link\" data-panel=\"nr-prompt\">Prompt used</button>");
        }
        out.println("</div></details>");
        out.println("<span class=\"nr-hint\">" + (editable ? "<kbd>Ctrl</kbd>+<kbd>Enter</kbd> approve &middot; "
                + "<kbd>E</kbd> edit &middot; " : "") + "<kbd>J</kbd>/<kbd>K</kbd> next/previous</span>");
        out.println("</div>");
        out.println("</form>");
        if (status == TrackerNarrativeReviewStatus.APPROVED) {
            out.println("<p class=\"nr-muted nr-small\">This is the approved report. Edits are saved as a new "
                    + "draft unless you Save &amp; approve.</p>");
        }

        if (n.getMarkdownGenerated() != null) {
            out.println("<section class=\"nr-panel\" id=\"nr-diff\" hidden><h4>Generated vs final</h4>");
            out.println(LineDiff.html(n.getMarkdownGenerated(), n.getMarkdownFinal()));
            out.println("</section>");
        }
        if (n.getPromptUsedText() != null) {
            out.println("<section class=\"nr-panel\" id=\"nr-prompt\" hidden><h4>Prompt used</h4><pre>"
                    + esc(n.getPromptUsedText()) + "</pre></section>");
        }
    }

    private void printHeader(PrintWriter out, WebUser user, Page page, String type, LocalDate start,
            LocalDate end, TrackerNarrativeReviewStatus status) {
        TrackerNarrative n = page.selected;
        out.println("<header class=\"nr-head\">");
        out.println("<div class=\"nr-title\">" + typeChip(type) + "<h2>" + esc(longPeriod(type, start, end))
                + "</h2>" + (status == null ? "<span class=\"nr-status s-none\">No report</span>"
                        : statusChip(n, page)) + "</div>");
        StringBuilder meta = new StringBuilder();
        if (n != null) {
            if (n.getDateGenerated() != null) {
                meta.append("Generated ").append(esc(formatTime(user, n.getDateGenerated())));
            }
            if (n.getModelName() != null) {
                meta.append(meta.length() > 0 ? " &middot; " : "").append(esc(n.getModelName()));
            }
            if (n.getDateApproved() != null && status == TrackerNarrativeReviewStatus.APPROVED) {
                meta.append(" &middot; approved ").append(esc(formatTime(user, n.getDateApproved())));
            }
        } else if (page.scopeBillableMinutes > 0) {
            meta.append(hours(page.scopeBillableMinutes)).append(" billable");
        }
        if (page.approvedForPeriod != null && n != null
                && page.approvedForPeriod.getNarrativeId() != n.getNarrativeId()) {
            meta.append(meta.length() > 0 ? " &middot; " : "").append("replaces the <a href=\"")
                    .append(reportLink(page.approvedForPeriod)).append("\">approved report</a>");
        }
        if (meta.length() > 0) {
            out.println("<div class=\"nr-meta\">" + meta + "</div>");
        }
        out.println("<nav class=\"nr-nav\">");
        if (page.previousPeriod != null) {
            out.println("<a href=\"" + reportLink(page.previousPeriod) + "\">&lsaquo; "
                    + esc(shortPeriod(type, start(page.previousPeriod), end(page.previousPeriod))) + "</a>");
        }
        if (page.nextPeriod != null) {
            out.println("<a href=\"" + reportLink(page.nextPeriod) + "\">"
                    + esc(shortPeriod(type, start(page.nextPeriod), end(page.nextPeriod))) + " &rsaquo;</a>");
        }
        if (!page.otherVersions.isEmpty()) {
            out.print("<span class=\"nr-versions\">Other versions:");
            for (TrackerNarrative other : page.otherVersions) {
                out.print(" <a href=\"" + reportLink(other) + "\" title=\"#" + other.getNarrativeId() + "\">"
                        + esc(other.getReviewStatus() == null ? "?" : other.getReviewStatus().getLabel().toLowerCase())
                        + (other.getDateGenerated() == null ? ""
                                : " " + esc(formatTime(user, other.getDateGenerated())))
                        + "</a>");
            }
            out.println("</span>");
        }
        out.println("</nav>");
        out.println("</header>");
    }

    private static String statusChip(TrackerNarrative n, Page page) {
        TrackerNarrativeReviewStatus status = n.getReviewStatus();
        if (status == TrackerNarrativeReviewStatus.GENERATED) {
            boolean revision = page.approvedForPeriod != null
                    && page.approvedForPeriod.getNarrativeId() != n.getNarrativeId();
            return "<span class=\"nr-status s-pending\">" + (revision ? "Revision" : "Draft") + "</span>";
        }
        if (status == TrackerNarrativeReviewStatus.APPROVED) {
            return "<span class=\"nr-status s-approved\">Approved</span>";
        }
        if (status == TrackerNarrativeReviewStatus.GENERATING) {
            return "<span class=\"nr-status s-time\">"
                    + (TrackerNarrativeService.isStuck(n) ? "Stuck" : "Generating") + "</span>";
        }
        if (status == TrackerNarrativeReviewStatus.FAILED) {
            return "<span class=\"nr-status s-missing\">Failed</span>";
        }
        return "<span class=\"nr-status s-none\">" + esc(status == null ? "Unknown" : status.getLabel())
                + "</span>";
    }

    private void printEmptyPeriod(PrintWriter out, Page page) {
        TrackerNarrativeScope scope = page.scope;
        printHeader(out, null, page, scope.getNarrativeType(), scope.getPeriodStart(), scope.getPeriodEnd(), null);
        out.println("<div class=\"nr-doc nr-doc-empty\">");
        out.println("<p>No report for this period yet"
                + (page.scopeBillableMinutes > 0 ? "" : ", and no billable time recorded") + ".</p>");
        if (page.generationAvailable) {
            out.println("<form method=\"post\" action=\"" + PAGE + "\">" + hidden("action", "generate")
                    + hidden("type", scope.getNarrativeType()) + hidden("date", scope.getPeriodStart().toString())
                    + "<button class=\"nr-btn nr-primary\" type=\"submit\">Generate report</button></form>");
        }
        out.println("</div>");
    }

    private void printCaughtUp(PrintWriter out, Page page) {
        out.println("<div class=\"nr-doc nr-doc-empty nr-caught-up\">");
        out.println("<p class=\"nr-big\">&#127881; All caught up</p>");
        out.println("<p>No drafts are waiting for review."
                + (page.queue.getMissing().isEmpty() ? ""
                        : " The periods under <b>Needs a report</b> have billable time but no report yet.")
                + "</p>");
        out.println("</div>");
    }

    /** The queue item to show after acting on the selected one: the next down the list, else the one above. */
    private static int followingQueueId(Page page) {
        List<NarrativeReviewQueue.Draft> drafts = page.queue.getToReview();
        int selectedId = page.selected == null ? 0 : page.selected.getNarrativeId();
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).getNarrative().getNarrativeId() == selectedId) {
                if (i + 1 < drafts.size()) {
                    return drafts.get(i + 1).getNarrative().getNarrativeId();
                }
                return i > 0 ? drafts.get(i - 1).getNarrative().getNarrativeId() : 0;
            }
        }
        return drafts.isEmpty() ? 0 : drafts.get(0).getNarrative().getNarrativeId();
    }

    /** Queue neighbours of the selected report for J/K, as links ("" when there is none). */
    private static String[] queueNeighbours(Page page) {
        List<NarrativeReviewQueue.Draft> drafts = page.queue.getToReview();
        int selectedId = page.selected == null ? 0 : page.selected.getNarrativeId();
        for (int i = 0; i < drafts.size(); i++) {
            if (drafts.get(i).getNarrative().getNarrativeId() == selectedId) {
                return new String[] { i > 0 ? reportLink(drafts.get(i - 1).getNarrative()) : "",
                        i + 1 < drafts.size() ? reportLink(drafts.get(i + 1).getNarrative()) : "" };
            }
        }
        return new String[] { "", drafts.isEmpty() ? "" : reportLink(drafts.get(0).getNarrative()) };
    }

    // ---- script and style ----------------------------------------------------------------------------

    private void printScript(PrintWriter out, Page page) {
        String[] neighbours = queueNeighbours(page);
        out.println("<script>");
        out.println("(function(){");
        out.println("var prevHref=" + jsString(neighbours[0]) + ", nextHref=" + jsString(neighbours[1]) + ";");
        out.println("var form=document.getElementById('nr-form');");
        out.println("var view=document.getElementById('nr-view'), editor=document.getElementById('nr-editor');");
        out.println("var text=document.getElementById('nr-text'), preview=document.getElementById('nr-preview');");
        out.println("var approveBtn=document.getElementById('nr-approve'), editBtn=document.getElementById('nr-edit');");
        out.println("var saveBtn=document.getElementById('nr-save'), cancelBtn=document.getElementById('nr-cancel');");
        out.println("var original=text?text.value:'', editing=false, submitting=false, timer=null;");
        out.println("var wasApproved=approveBtn&&approveBtn.hidden;");
        out.println("function dirty(){return !!text&&text.value!==original;}");
        out.println("function refreshButtons(){");
        out.println("  if(!approveBtn)return;");
        out.println("  if(wasApproved){approveBtn.hidden=!dirty();}");
        out.println("  approveBtn.innerHTML=dirty()?'&#10003; Save &amp; approve':'&#10003; Approve';");
        out.println("  saveBtn.hidden=!dirty(); cancelBtn.hidden=!editing; editBtn.hidden=editing;");
        out.println("}");
        out.println("function startEdit(){");
        out.println("  if(!editor||editing)return; editing=true; view.hidden=true; editor.hidden=false;");
        out.println("  refreshButtons(); text.focus();");
        out.println("}");
        out.println("function stopEdit(){");
        out.println("  if(!editor||!editing)return; editing=false; text.value=original;");
        out.println("  preview.innerHTML=view.innerHTML; editor.hidden=true; view.hidden=false; refreshButtons();");
        out.println("}");
        out.println("function submit(action){");
        out.println("  if(!form||submitting)return; submitting=true;");
        out.println("  if(text&&!dirty()&&action==='approve'){text.disabled=true;}");
        out.println("  document.getElementById('nr-action').value=action; form.submit();");
        out.println("}");
        out.println("function renderPreview(){");
        out.println("  var body=new URLSearchParams(); body.set('action','preview'); body.set('markdown',text.value);");
        out.println("  fetch('" + PAGE + "',{method:'POST',body:body,credentials:'same-origin'})");
        out.println("   .then(function(r){return r.ok?r.text():null;})");
        out.println("   .then(function(html){if(html!==null)preview.innerHTML=html;}).catch(function(){});");
        out.println("}");
        out.println("if(view){view.addEventListener('click',function(e){");
        out.println("  if(e.target.closest('a'))return; var s=window.getSelection(); if(s&&String(s).length)return;");
        out.println("  startEdit();});}");
        out.println("if(text){text.addEventListener('input',function(){refreshButtons();clearTimeout(timer);"
                + "timer=setTimeout(renderPreview,350);});}");
        out.println("if(editBtn)editBtn.addEventListener('click',startEdit);");
        out.println("if(cancelBtn)cancelBtn.addEventListener('click',stopEdit);");
        out.println("document.querySelectorAll('[data-action]').forEach(function(b){b.addEventListener('click',function(){");
        out.println("  var action=b.getAttribute('data-action');");
        out.println("  if(b.getAttribute('data-confirm')&&!b.classList.contains('is-armed')){");
        out.println("    b.classList.add('is-armed'); var label=b.textContent; b.textContent='Click again to '+label.toLowerCase();");
        out.println("    setTimeout(function(){b.classList.remove('is-armed');b.textContent=label;},4000); return;}");
        out.println("  if(b.getAttribute('data-generate'))action='generate-again';");
        out.println("  submit(action);});});");
        out.println("document.querySelectorAll('[data-panel]').forEach(function(b){b.addEventListener('click',function(){");
        out.println("  var p=document.getElementById(b.getAttribute('data-panel')); p.hidden=!p.hidden;");
        out.println("  if(!p.hidden)p.scrollIntoView({behavior:'smooth',block:'start'});});});");
        out.println("window.addEventListener('beforeunload',function(e){if(dirty()&&!submitting){e.preventDefault();e.returnValue='';}});");
        out.println("document.addEventListener('keydown',function(e){");
        out.println("  var inField=/^(TEXTAREA|INPUT|SELECT)$/.test(e.target.tagName);");
        out.println("  if((e.ctrlKey||e.metaKey)&&e.key==='Enter'&&approveBtn){e.preventDefault();"
                + "if(!approveBtn.hidden||dirty())submit('approve');return;}");
        out.println("  if((e.ctrlKey||e.metaKey)&&(e.key==='s'||e.key==='S')&&dirty()){e.preventDefault();submit('save');return;}");
        out.println("  if(e.key==='Escape'&&editing){e.preventDefault();stopEdit();return;}");
        out.println("  if(inField||e.ctrlKey||e.metaKey||e.altKey)return;");
        out.println("  if(e.key==='e'&&editor){e.preventDefault();startEdit();}");
        out.println("  else if(e.key==='j'&&nextHref){location.href=nextHref;}");
        out.println("  else if(e.key==='k'&&prevHref){location.href=prevHref;}");
        out.println("});");
        out.println("if(form&&form.getAttribute('data-generating')){setTimeout(function(){location.reload();},4000);}");
        if (page.editing) {
            out.println("startEdit();");
        }
        out.println("refreshButtons();");
        out.println("})();");
        out.println("</script>");
    }

    private void printStyle(PrintWriter out) {
        out.println("<style>");
        out.println(".nr{--nr-ink:#1f2933;--nr-muted:#6b7280;--nr-line:#d9dee5;--nr-soft:#f4f6f8;--nr-card:#fff;"
                + "--nr-accent:#2f6f4e;--nr-approved:#2f855a;--nr-pending:#d69e2e;--nr-missing:#c53030;"
                + "--nr-time:#90a4b8;display:grid;grid-template-columns:290px minmax(0,1fr);gap:20px;"
                + "align-items:start;color:var(--nr-ink);max-width:1400px;margin:8px 0 24px;font-size:15px}");
        out.println("@media (max-width:900px){.nr{grid-template-columns:1fr}}");
        out.println(".nr-side{background:var(--nr-soft);border:1px solid var(--nr-line);border-radius:10px;"
                + "padding:10px 12px;position:sticky;top:8px;max-height:calc(100vh - 16px);overflow:auto}");
        out.println(".nr-h{font-size:13px;text-transform:uppercase;letter-spacing:.04em;color:var(--nr-muted);"
                + "margin:12px 0 6px;display:flex;align-items:center;gap:6px}");
        out.println(".nr-count{background:var(--nr-pending);color:#fff;border-radius:9px;padding:0 7px;font-size:12px}");
        out.println(".nr-count-soft{background:#c4ccd6}");
        out.println(".nr-list{list-style:none;margin:0;padding:0}");
        out.println(".nr-list li{display:flex;align-items:center;gap:4px}");
        out.println(".nr-item{flex:1;display:flex;align-items:center;gap:8px;padding:5px 8px;border-radius:6px;"
                + "text-decoration:none;color:inherit;min-width:0}");
        out.println(".nr-item:hover{background:#e7ebf0}.nr-item.is-current{background:#dfeee5;"
                + "box-shadow:inset 3px 0 0 var(--nr-accent)}");
        out.println(".nr-when{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
        out.println(".nr-hours{color:var(--nr-muted);font-size:12px}");
        out.println(".nr-tag{font-size:11px;color:#8a5a00;background:#fdf1d6;border-radius:4px;padding:0 5px}");
        out.println(".nr-type{display:inline-block;font-size:11px;font-weight:600;border-radius:4px;padding:1px 6px;"
                + "min-width:44px;text-align:center}");
        out.println(".nr-type-DAILY{background:#e3eefb;color:#1c4f8a}.nr-type-WEEKLY{background:#efe6fb;color:#5b2d8e}"
                + ".nr-type-MONTHLY{background:#e6f5ef;color:#1d6b4a}");
        out.println(".nr-mini{font-size:12px;padding:2px 8px;border:1px solid var(--nr-line);background:var(--nr-card);"
                + "border-radius:5px;cursor:pointer}.nr-mini:hover{border-color:var(--nr-accent);color:var(--nr-accent)}");
        out.println(".nr-muted{color:var(--nr-muted)}.nr-small{font-size:12px}");
        out.println(".nr-foot{font-size:12px;margin:12px 0 2px;color:var(--nr-muted)}");
        out.println(".nr-cal{margin-top:14px}.nr-cal-head{display:flex;justify-content:space-between;"
                + "align-items:center;margin-bottom:4px}.nr-cal-head a{text-decoration:none;color:inherit;"
                + "padding:2px 8px;border-radius:5px}.nr-cal-head a:hover{background:#e7ebf0}");
        out.println(".nr-cal-title{font-weight:600}.nr-cal-title.is-current{background:#dfeee5}");
        out.println(".nr-cal-grid{border-collapse:collapse;width:100%;font-size:12px}");
        out.println(".nr-cal-grid th{color:var(--nr-muted);font-weight:500;padding:2px 0}");
        out.println(".nr-cal-grid td{padding:1px;text-align:center}");
        out.println(".nr-day{display:block;border-radius:5px;padding:3px 0;text-decoration:none;color:inherit;"
                + "border:1px solid transparent}");
        out.println(".nr-day:hover{border-color:var(--nr-ink)}.nr-day.is-outside{opacity:.45}"
                + ".nr-day.is-today{font-weight:700;text-decoration:underline}");
        out.println(".nr-day.is-current{border-color:var(--nr-ink);border-width:2px}");
        out.println(".nr-day.s-approved{background:#d6f0df}.nr-day.s-pending{background:#fbe7b5}"
                + ".nr-day.s-missing{background:#f8d4d4}.nr-day.s-time{background:#e9edf2}.nr-day.s-none{color:#a0a8b2}");
        out.println(".nr-wk{font-weight:600}");
        out.println(".nr-legend{font-size:11px;color:var(--nr-muted);margin-top:6px;display:flex;gap:4px;"
                + "align-items:center;flex-wrap:wrap}");
        out.println(".nr-dot{display:inline-block;width:10px;height:10px;border-radius:3px;margin-left:6px}"
                + ".nr-dot.s-approved{background:#d6f0df}.nr-dot.s-pending{background:#fbe7b5}"
                + ".nr-dot.s-missing{background:#f8d4d4}");
        out.println(".nr-main{min-width:0}");
        out.println(".nr-toast{background:#e6f4ea;border:1px solid #b7dfc3;color:#1e5631;border-radius:8px;"
                + "padding:8px 12px;margin-bottom:10px}.nr-toast-bad{background:#fdecec;border-color:#f3b8b8;color:#8a1f1f}");
        out.println(".nr-head{margin-bottom:10px}.nr-title{display:flex;align-items:center;gap:10px;flex-wrap:wrap}");
        out.println(".nr-title h2{margin:0;font-size:22px}");
        out.println(".nr-status{font-size:12px;font-weight:600;border-radius:10px;padding:2px 10px}");
        out.println(".nr-status.s-pending{background:#fbe7b5;color:#7a5200}.nr-status.s-approved{background:#d6f0df;"
                + "color:#1f6b3d}.nr-status.s-missing{background:#f8d4d4;color:#8a1f1f}.nr-status.s-time,"
                + ".nr-status.s-none{background:#e9edf2;color:#4b5563}");
        out.println(".nr-meta{color:var(--nr-muted);font-size:13px;margin-top:4px}");
        out.println(".nr-nav{display:flex;gap:14px;flex-wrap:wrap;font-size:13px;margin-top:6px}");
        out.println(".nr-nav a{text-decoration:none}.nr-versions{color:var(--nr-muted)}");
        out.println(".nr-doc{background:var(--nr-card);border:1px solid var(--nr-line);border-radius:10px;"
                + "padding:14px 20px;line-height:1.5;overflow-wrap:anywhere}");
        out.println("#nr-view{cursor:text}#nr-view:hover{border-color:#9fb3c8;box-shadow:0 0 0 3px #eef3f8}");
        out.println(".nr-doc h1{font-size:20px}.nr-doc h2{font-size:18px}.nr-doc h3{font-size:16px}");
        out.println(".nr-doc-empty{color:var(--nr-muted);text-align:center;padding:40px 20px}");
        out.println(".nr-big{font-size:22px;color:var(--nr-ink)}");
        out.println(".nr-editor{display:grid;grid-template-columns:1fr 1fr;gap:12px}"
                + "@media (max-width:1100px){.nr-editor{grid-template-columns:1fr}}");
        out.println(".nr-editor[hidden]{display:none}");
        out.println("#nr-text{width:100%;box-sizing:border-box;min-height:60vh;font:14px/1.5 ui-monospace,Consolas,"
                + "monospace;padding:12px;border:1px solid var(--nr-line);border-radius:10px;resize:vertical}");
        out.println("#nr-text:focus{outline:2px solid #9fc2ad;border-color:var(--nr-accent)}");
        out.println(".nr-preview{max-height:70vh;overflow:auto}");
        out.println(".nr-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:12px 0;position:sticky;"
                + "bottom:0;background:linear-gradient(transparent,#fff 30%);padding:10px 0}");
        out.println(".nr-btn{font-size:14px;padding:7px 14px;border-radius:7px;border:1px solid var(--nr-line);"
                + "background:var(--nr-card);color:var(--nr-ink);cursor:pointer;list-style:none}");
        out.println(".nr-btn:hover{border-color:#9aa5b1}.nr-btn[hidden]{display:none}");
        out.println(".nr-primary{background:var(--nr-accent);border-color:var(--nr-accent);color:#fff;font-weight:600}");
        out.println(".nr-primary:hover{background:#255a3f}");
        out.println(".nr-quiet{background:transparent}");
        out.println(".nr-more{position:relative}.nr-more summary::-webkit-details-marker{display:none}");
        out.println(".nr-more>div{position:absolute;bottom:110%;left:0;background:var(--nr-card);border:1px solid "
                + "var(--nr-line);border-radius:8px;box-shadow:0 6px 18px rgba(0,0,0,.12);padding:6px;display:flex;"
                + "flex-direction:column;min-width:170px;z-index:5}");
        out.println(".nr-link{background:none;border:0;text-align:left;padding:6px 10px;border-radius:5px;cursor:pointer;"
                + "font-size:14px;color:var(--nr-ink)}.nr-link:hover{background:var(--nr-soft)}");
        out.println(".nr-danger{color:var(--nr-missing)}.nr-link.is-armed{background:#fdecec;font-weight:600}");
        out.println(".nr-hint{margin-left:auto;color:var(--nr-muted);font-size:12px}");
        out.println(".nr-hint kbd{border:1px solid var(--nr-line);border-bottom-width:2px;border-radius:4px;"
                + "padding:0 4px;font-size:11px;background:var(--nr-card)}");
        out.println(".nr-panel{margin-top:16px}.nr-panel h4{margin:0 0 6px}");
        out.println(".nr-panel pre{white-space:pre-wrap;background:var(--nr-soft);border:1px solid var(--nr-line);"
                + "border-radius:8px;padding:10px;max-height:60vh;overflow:auto;font-size:13px}");
        out.println(".nr-diff{font:13px/1.45 ui-monospace,Consolas,monospace;border:1px solid var(--nr-line);"
                + "border-radius:8px;overflow:auto;max-height:60vh}");
        out.println(".nr-diff div{white-space:pre-wrap;padding:0 10px}.nr-diff .d-add{background:#e6f4ea}"
                + ".nr-diff .d-del{background:#fdecec;text-decoration:line-through;color:#8a1f1f}"
                + ".nr-diff .d-same{color:var(--nr-muted)}.nr-diff .d-gap{color:var(--nr-muted);"
                + "background:var(--nr-soft);font-style:italic;text-align:center}");
        out.println("</style>");
    }

    // ---- helpers -------------------------------------------------------------------------------------

    public static String renderMarkdown(String markdown) {
        Node document = MARKDOWN_PARSER.parse(markdown == null ? "" : markdown);
        return MARKDOWN_RENDERER.render(document);
    }

    public static String reportLink(TrackerNarrative n) {
        return PAGE + "?id=" + n.getNarrativeId();
    }

    public static String periodLink(String type, LocalDate start) {
        return PAGE + "?type=" + type + "&amp;date=" + start;
    }

    private static LocalDate start(TrackerNarrative n) {
        return TrackerNarrativeDao.periodDate(n.getPeriodStart());
    }

    private static LocalDate end(TrackerNarrative n) {
        return TrackerNarrativeDao.periodDate(n.getPeriodEnd());
    }

    private static String typeChip(String type) {
        String label = TrackerNarrativeScope.TYPE_WEEKLY.equals(type) ? "Week"
                : TrackerNarrativeScope.TYPE_MONTHLY.equals(type) ? "Month" : "Day";
        return "<span class=\"nr-type nr-type-" + esc(type) + "\">" + label + "</span>";
    }

    static String shortPeriod(String type, LocalDate start, LocalDate end) {
        if (start == null) {
            return "";
        }
        if (TrackerNarrativeScope.TYPE_WEEKLY.equals(type)) {
            return start.format(MONTH_DAY) + " – "
                    + (end.getMonth() == start.getMonth() ? String.valueOf(end.getDayOfMonth()) : end.format(MONTH_DAY));
        }
        if (TrackerNarrativeScope.TYPE_MONTHLY.equals(type)) {
            return start.format(MONTH_YEAR);
        }
        return start.format(DAY_SHORT);
    }

    static String longPeriod(String type, LocalDate start, LocalDate end) {
        if (TrackerNarrativeScope.TYPE_WEEKLY.equals(type)) {
            return "Week of " + shortPeriod(type, start, end) + ", " + end.getYear();
        }
        if (TrackerNarrativeScope.TYPE_MONTHLY.equals(type)) {
            return start.format(MONTH_YEAR);
        }
        return start.format(DAY_LONG);
    }

    private static int minutes(Map<LocalDate, Integer> byDay, LocalDate day) {
        Integer value = byDay.get(day);
        return value == null ? 0 : value.intValue();
    }

    static String hours(int minutes) {
        if (minutes < 60) {
            return minutes + "m";
        }
        int tenths = Math.round(minutes / 6f);
        return (tenths % 10 == 0 ? String.valueOf(tenths / 10) : (tenths / 10) + "." + (tenths % 10)) + "h";
    }

    private static String formatTime(WebUser user, Date date) {
        if (date == null) {
            return "";
        }
        return user == null ? date.toString() : user.getTimeFormat().format(date);
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + esc(name) + "\" value=\"" + esc(value) + "\">";
    }

    private static String jsString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("&amp;", "&").replace("<", "\\x3c")
                + "'";
    }

    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String esc(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
