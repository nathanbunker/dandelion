package org.dandeliondaily.focus.render;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

import org.openimmunizationsoftware.pt.util.WebEscaper;

/**
 * Drawer holding a filterable list of actions, shared by the Focused Action context strip rows
 * (Up next, and later Meetings and This project). Action rows switch through switchAction; meeting
 * rows go through faSelectMeeting so the pre-meeting action is remembered.
 */
public class FocusActionPickerRenderer {

        private static final String SWITCH_URL_PREFIX = "FocusedActionServlet?action=switchAction&completingActionNextId=";

        public static class Row {
                private final int actionNextId;
                private final String projectName;
                private final String description;
                private final Integer estimateMinutes;
                private final int minutesToday;
                private final String whenLabel;
                private final boolean current;
                private final boolean meeting;
                private Long startEpochMs;
                private Long endEpochMs;
                private String startLabel;
                private String endLabel;
                private String joinUrl;
                private boolean recurring;

                public Row(int actionNextId, String projectName, String description, Integer estimateMinutes,
                                int minutesToday, String whenLabel, boolean current, boolean meeting) {
                        this.actionNextId = actionNextId;
                        this.projectName = projectName;
                        this.description = description;
                        this.estimateMinutes = estimateMinutes;
                        this.minutesToday = minutesToday;
                        this.whenLabel = whenLabel;
                        this.current = current;
                        this.meeting = meeting;
                }

                public int getActionNextId() {
                        return actionNextId;
                }

                public String getProjectName() {
                        return projectName;
                }

                public String getDescription() {
                        return description;
                }

                public Integer getEstimateMinutes() {
                        return estimateMinutes;
                }

                public int getMinutesToday() {
                        return minutesToday;
                }

                public String getWhenLabel() {
                        return whenLabel;
                }

                public boolean isCurrent() {
                        return current;
                }

                public boolean isMeeting() {
                        return meeting;
                }

                /**
                 * Marks the row with its meeting times so the page script can label it "in 25m",
                 * "now" or "ended 10:30" as time passes.
                 */
                public Row withMeetingTimes(Long startEpochMs, Long endEpochMs, String startLabel, String endLabel) {
                        this.startEpochMs = startEpochMs;
                        this.endEpochMs = endEpochMs;
                        this.startLabel = startLabel;
                        this.endLabel = endLabel;
                        return this;
                }

                public Row withRecurring(boolean recurring) {
                        this.recurring = recurring;
                        return this;
                }

                public boolean isRecurring() {
                        return recurring;
                }

                public Row withJoinUrl(String joinUrl) {
                        this.joinUrl = joinUrl;
                        return this;
                }

                public Long getStartEpochMs() {
                        return startEpochMs;
                }

                public Long getEndEpochMs() {
                        return endEpochMs;
                }

                public String getStartLabel() {
                        return startLabel;
                }

                public String getEndLabel() {
                        return endLabel;
                }

                public String getJoinUrl() {
                        return joinUrl;
                }

                /** "Project - Description (15m)", for one-line previews. */
                public String getLabel() {
                        String text = projectName == null || projectName.length() == 0 ? "" : projectName + " - ";
                        text += description == null ? "" : description;
                        if (estimateMinutes != null) {
                                text += " (" + estimateMinutes + "m)";
                        }
                        return text;
                }
        }

        public static class Group {
                private final String title;
                private final List<Row> rows;
                private final boolean readOnly;

                public Group(String title, List<Row> rows) {
                        this(title, rows, false);
                }

                /** A read-only group lists its rows for reference; they cannot be selected. */
                public Group(String title, List<Row> rows, boolean readOnly) {
                        this.title = title;
                        this.rows = rows == null ? Collections.<Row>emptyList() : rows;
                        this.readOnly = readOnly;
                }

                public boolean isReadOnly() {
                        return readOnly;
                }

                public String getTitle() {
                        return title;
                }

                public List<Row> getRows() {
                        return rows;
                }
        }

        public void render(PrintWriter out, String drawerId, String title, List<Group> groups, String emptyText) {
                render(out, drawerId, title, groups, emptyText, null);
        }

        /** {@code footerHtml}, when given, is trusted markup placed at the foot of the drawer. */
        public void render(PrintWriter out, String drawerId, String title, List<Group> groups, String emptyText,
                        String footerHtml) {
                boolean empty = true;
                for (Group group : groups) {
                        if (!group.getRows().isEmpty()) {
                                empty = false;
                        }
                }
                out.println("      <aside id=\"" + drawerId + "\" class=\"fa-drawer fa-picker\" aria-label=\""
                                + escapeHtml(title) + "\" aria-hidden=\"true\">");
                out.println("        <div class=\"fa-drawer-head\">");
                out.println("          <h2 class=\"fa-section-title\">" + escapeHtml(title) + "</h2>");
                out.println("          <button type=\"button\" class=\"fa-modal-close\" title=\"Close (Esc)\" onclick=\"faCloseDrawer()\">&times;</button>");
                out.println("        </div>");
                out.println("        <div class=\"fa-picker-filter\">");
                out.println("          <input type=\"text\" class=\"fa-picker-filter-input\" placeholder=\"Filter...\" autocomplete=\"off\""
                                + " oninput=\"faPickerFilter(this)\" onkeydown=\"faPickerKeydown(event, this)\" />");
                out.println("        </div>");
                out.println("        <div class=\"fa-drawer-body\">");
                if (empty) {
                        out.println("          <div class=\"fa-drawer-empty\">" + escapeHtml(emptyText) + "</div>");
                }
                for (Group group : groups) {
                        if (group.getRows().isEmpty()) {
                                continue;
                        }
                        out.println("          <div class=\"fa-picker-group\">");
                        out.println("            <div class=\"fa-picker-group-title\">" + escapeHtml(group.getTitle())
                                        + "</div>");
                        for (Row row : group.getRows()) {
                                if (group.isReadOnly()) {
                                        renderStaticRow(out, row);
                                } else {
                                        renderRow(out, row);
                                }
                        }
                        out.println("          </div>");
                }
                out.println("          <div class=\"fa-drawer-empty fa-picker-nomatch\" style=\"display:none\">No matches.</div>");
                out.println("        </div>");
                if (footerHtml != null) {
                        out.println(footerHtml);
                }
                out.println("      </aside>");
        }

        private void renderRow(PrintWriter out, Row row) {
                String classes = "fa-picker-row" + (row.isCurrent() ? " fa-picker-current" : "")
                                + (row.isMeeting() ? " fa-picker-meeting" : "");
                String filterText = (n(row.getProjectName()) + " " + n(row.getDescription())).toLowerCase();
                String target = row.isMeeting()
                                ? " href=\"#\" onclick=\"event.preventDefault(); faSelectMeeting(" + row.getActionNextId()
                                                + ");\""
                                : " href=\"" + escapeHtml(SWITCH_URL_PREFIX) + row.getActionNextId() + "\"";
                StringBuilder meta = new StringBuilder();
                if (row.getEstimateMinutes() != null) {
                        meta.append("est ").append(formatMinutes(row.getEstimateMinutes().intValue()));
                }
                if (row.getMinutesToday() > 0) {
                        appendSeparator(meta).append(formatMinutes(row.getMinutesToday())).append(" today");
                }
                String times = "";
                if (row.getStartEpochMs() != null && row.getEndEpochMs() != null) {
                        times = " data-start=\"" + row.getStartEpochMs() + "\" data-end=\"" + row.getEndEpochMs()
                                        + "\" data-end-label=\"" + escapeHtml(row.getEndLabel()) + "\"";
                }
                boolean hasJoin = n(row.getJoinUrl()).length() > 0;
                if (hasJoin) {
                        out.println("            <div class=\"fa-picker-item\">");
                }
                out.println("            <a class=\"" + classes + "\"" + target + times + " data-filter=\""
                                + escapeHtml(filterText) + "\">");
                out.println("              <span class=\"fa-picker-main\">");
                out.println("                <span class=\"fa-picker-project\">"
                                + (row.isMeeting() ? "<span class=\"fa-picker-tag\">Meeting</span> " : "")
                                + (row.isRecurring() ? "<span class=\"fa-picker-tag fa-picker-tag-recurring\" title=\"Recurring\">&#8635; Recurring</span> " : "")
                                + escapeHtml(row.getProjectName())
                                + (row.isCurrent() ? " <span class=\"fa-picker-tag fa-picker-tag-current\">Current</span>" : "")
                                + "</span>");
                out.println("                <span class=\"fa-picker-desc\">" + escapeHtml(row.getDescription()) + "</span>");
                out.println("              </span>");
                out.println("              <span class=\"fa-picker-side\">");
                if (n(row.getWhenLabel()).length() > 0) {
                        out.println("                <span class=\"fa-picker-when\">" + escapeHtml(row.getWhenLabel())
                                        + "</span>");
                }
                if (times.length() > 0) {
                        out.println("                <span class=\"fa-picker-rel\"></span>");
                }
                if (meta.length() > 0) {
                        out.println("                <span class=\"fa-picker-meta\">" + meta + "</span>");
                }
                out.println("              </span>");
                out.println("            </a>");
                if (hasJoin) {
                        // The link opens the meeting itself (the browser only allows a new tab during the click);
                        // faJoinMeeting switches the clock to the meeting in this tab at the same time.
                        out.println("              <a class=\"fa-picker-join\" href=\"" + escapeHtml(row.getJoinUrl())
                                        + "\" target=\"_blank\" rel=\"noopener noreferrer\" title=\"Open the meeting and start its clock\""
                                        + " onclick=\"faJoinMeeting(" + row.getActionNextId() + ")\">Join</a>");
                        out.println("            </div>");
                }
        }

        private void renderStaticRow(PrintWriter out, Row row) {
                out.println("            <div class=\"fa-picker-static\">");
                out.println("              <span class=\"fa-picker-project\">" + escapeHtml(row.getProjectName()) + "</span>");
                out.println("              <span class=\"fa-picker-desc\">&#10003; " + escapeHtml(row.getDescription()) + "</span>");
                out.println("            </div>");
        }

        public void printStyles(PrintWriter out) {
                out.println("  .fa-picker-filter { flex: 0 0 auto; padding: 8px 12px; border-bottom: 1px solid #e3e9e1; }");
                out.println("  .fa-picker-filter-input { width: 100%; box-sizing: border-box; padding: 7px 9px; border: 1px solid #bfcdbb; border-radius: 6px; font: inherit; font-size: 14px; background: #fcfffb; }");
                out.println("  .fa-picker-group { margin-bottom: 10px; }");
                out.println("  .fa-picker-group-title { font-size: 11px; font-weight: 700; letter-spacing: .05em; text-transform: uppercase; color: #6a7a68; margin: 4px 2px 4px; }");
                out.println("  .fa-picker-row { display: flex; align-items: center; gap: 10px; padding: 7px 9px; margin-bottom: 3px; border: 1px solid transparent; border-radius: 7px; color: #233423; text-decoration: none; }");
                out.println("  .fa-picker-row:hover { background: #edf5ea; }");
                out.println("  .fa-picker-row.fa-picker-active { background: #e3eee0; border-color: #7f9b7f; }");
                out.println("  .fa-picker-current { border-color: #2a6f2a; background: #f1f8ee; }");
                out.println("  .fa-picker-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }");
                out.println("  .fa-picker-project { font-size: 11px; letter-spacing: .03em; text-transform: uppercase; color: #4a6350; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }");
                out.println("  .fa-picker-desc { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }");
                out.println("  .fa-picker-side { flex: 0 0 auto; display: flex; flex-direction: column; align-items: flex-end; font-size: 12px; color: #5b6b58; }");
                out.println("  .fa-picker-when { font-weight: 600; }");
                out.println("  .fa-picker-tag { display: inline-block; padding: 0 5px; border-radius: 4px; background: #e6ece4; color: #3d553d; font-size: 10px; font-weight: 700; letter-spacing: .04em; }");
                out.println("  .fa-picker-tag-current { background: #2f8c2f; color: #fff; }");
                out.println("  .fa-picker-tag-recurring { background: #efe9f6; color: #5a4380; }");
                out.println("  .fa-picker-meeting .fa-picker-tag { background: #e4e9f3; color: #34466b; }");
                out.println("  .fa-picker-rel { color: #34466b; }");
                out.println("  .fa-picker-dim { opacity: 0.55; }");
                out.println("  .fa-picker-dim .fa-picker-rel { color: #5b6b58; }");
                out.println("  .fa-picker-live { background: #e8eef9; border-color: #5b7bc4; }");
                out.println("  .fa-picker-live .fa-picker-rel { font-weight: 700; color: #2a4f9e; }");
                out.println("  .fa-picker-item { display: flex; align-items: center; gap: 6px; }");
                out.println("  .fa-picker-item .fa-picker-row { flex: 1; min-width: 0; }");
                out.println("  .fa-picker-join { flex: 0 0 auto; margin-bottom: 3px; padding: 5px 9px; border: 1px solid #5b7bc4; border-radius: 6px; background: #eef2fa; color: #2a4f9e; font-size: 12px; font-weight: 700; text-decoration: none; }");
                out.println("  .fa-picker-join:hover { background: #dde6f6; }");
                out.println("  .fa-picker-static { display: flex; flex-direction: column; padding: 4px 9px; color: #6a7a68; }");
                out.println("  .fa-picker-static .fa-picker-desc { font-weight: 400; }");
        }

        /** Filter, arrow-key and Enter handling for every picker drawer on the page. */
        public void printScript(PrintWriter out) {
                out.println("  function faPickerRows(drawer) { return Array.prototype.slice.call(drawer.querySelectorAll('.fa-picker-row')).filter(function(r) { return r.style.display !== 'none'; }); }");
                out.println("  function faPickerSetActive(drawer, row) {");
                out.println("    var old = drawer.querySelector('.fa-picker-active'); if (old) { old.classList.remove('fa-picker-active'); }");
                out.println("    if (row) { row.classList.add('fa-picker-active'); row.scrollIntoView({ block: 'nearest' }); }");
                out.println("  }");
                out.println("  function faPickerOpen(drawerId) {");
                out.println("    faOpenDrawer(drawerId);");
                out.println("    var drawer = document.getElementById(drawerId); if (!drawer) { return; }");
                out.println("    var input = drawer.querySelector('.fa-picker-filter-input');");
                out.println("    if (input && input.value) { input.value = ''; faPickerFilter(input); }");
                out.println("    var body = drawer.querySelector('.fa-drawer-body'); if (body) { body.scrollTop = 0; }");
                out.println("    var start = drawer.querySelector('.fa-picker-current') || drawer.querySelector('.fa-picker-row:not(.fa-picker-dim)') || faPickerRows(drawer)[0];");
                out.println("    faPickerSetActive(drawer, start);");
                out.println("    if (start) { start.scrollIntoView({ block: 'center' }); }");
                out.println("    if (input) { setTimeout(function() { input.focus(); }, 0); }");
                out.println("  }");
                out.println("  function faPickerFilter(input) {");
                out.println("    var drawer = input.closest('.fa-picker'); var text = (input.value || '').trim().toLowerCase();");
                out.println("    var rows = drawer.querySelectorAll('.fa-picker-row'); var shown = 0;");
                out.println("    for (var i = 0; i < rows.length; i++) { var match = !text || (rows[i].getAttribute('data-filter') || '').indexOf(text) >= 0; rows[i].style.display = match ? '' : 'none'; var item = rows[i].closest('.fa-picker-item'); if (item) { item.style.display = rows[i].style.display; } if (match) { shown++; } }");
                out.println("    var groups = drawer.querySelectorAll('.fa-picker-group');");
                out.println("    for (var g = 0; g < groups.length; g++) { groups[g].style.display = (faPickerRows(groups[g]).length || (!text && groups[g].querySelector('.fa-picker-static'))) ? '' : 'none'; }");
                out.println("    var nomatch = drawer.querySelector('.fa-picker-nomatch'); if (nomatch) { nomatch.style.display = (rows.length && !shown) ? '' : 'none'; }");
                out.println("    var active = drawer.querySelector('.fa-picker-active');");
                out.println("    if (!active || active.style.display === 'none') { faPickerSetActive(drawer, faPickerRows(drawer)[0]); }");
                out.println("  }");
                out.println("  function faPickerKeydown(e, input) {");
                out.println("    var drawer = input.closest('.fa-picker'); var rows = faPickerRows(drawer); if (!rows.length) { return; }");
                out.println("    var index = rows.indexOf(drawer.querySelector('.fa-picker-active'));");
                out.println("    if (e.key === 'ArrowDown') { e.preventDefault(); faPickerSetActive(drawer, rows[Math.min(rows.length - 1, index + 1)]); }");
                out.println("    else if (e.key === 'ArrowUp') { e.preventDefault(); faPickerSetActive(drawer, rows[Math.max(0, index - 1)]); }");
                out.println("    else if (e.key === 'Enter') { e.preventDefault(); if (index >= 0) { rows[index].click(); } }");
                out.println("  }");
        }

        private StringBuilder appendSeparator(StringBuilder sb) {
                if (sb.length() > 0) {
                        sb.append(" &middot; ");
                }
                return sb;
        }

        private String formatMinutes(int minutes) {
                int safe = Math.max(0, minutes);
                int h = safe / 60;
                int m = safe % 60;
                if (h <= 0) {
                        return m + "m";
                }
                if (m == 0) {
                        return h + "h";
                }
                return h + "h " + m + "m";
        }

        private String escapeHtml(String value) {
                return WebEscaper.escapeHtml(value);
        }

        private String n(String value) {
                return value == null ? "" : value;
        }
}
