package org.dandeliondaily.focus.render;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import org.dandeliondaily.dashboard.service.ProjectDisplayLabelService;
import org.dandeliondaily.shared.render.ActionEditorRenderer;
import org.dandeliondaily.shared.render.ActionRecoveryRenderer;
import org.dandeliondaily.shared.render.QuickCaptureRenderer;
import org.dandeliondaily.shared.render.ActionThreadView;
import org.dandeliondaily.shared.service.ActionThreadService;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.WebUser;

public class FocusedActionPageRenderer {

        private static final String SWITCH_URL_PREFIX = "FocusedActionServlet?action=switchAction&completingActionNextId=";

        private final ActionEditorRenderer actionEditorRenderer = new ActionEditorRenderer();
        private final QuickCaptureRenderer quickCaptureRenderer = new QuickCaptureRenderer();
        private final FocusActionPickerRenderer pickerRenderer = new FocusActionPickerRenderer();
        private final ActionRecoveryRenderer actionRecoveryRenderer = new ActionRecoveryRenderer();
        private final ProjectDisplayLabelService projectDisplayLabelService = new ProjectDisplayLabelService();

        /**
         * Today's queue (buildOrderedCycleList) for the Up next row and drawer. nextItem is the
         * queue item after the current action, or the first item when the current action is not in
         * the queue; nextPosition is its 1-based place in the queue.
         */
        public static class UpNextData {
                private final List<FocusActionPickerRenderer.Group> groups;
                private final FocusActionPickerRenderer.Row nextItem;
                private final int nextPosition;
                private final int total;
                private final boolean currentInQueue;
                private final List<FocusActionPickerRenderer.Row> upcoming;

                public UpNextData(List<FocusActionPickerRenderer.Group> groups, FocusActionPickerRenderer.Row nextItem,
                                int nextPosition, int total, boolean currentInQueue) {
                        this(groups, nextItem, nextPosition, total, currentInQueue, null);
                }

                /** {@code upcoming}: queue items from nextItem on, for the Up next panel. */
                public UpNextData(List<FocusActionPickerRenderer.Group> groups, FocusActionPickerRenderer.Row nextItem,
                                int nextPosition, int total, boolean currentInQueue,
                                List<FocusActionPickerRenderer.Row> upcoming) {
                        this.upcoming = upcoming != null ? upcoming
                                        : nextItem == null ? Collections.<FocusActionPickerRenderer.Row>emptyList()
                                                        : Collections.singletonList(nextItem);
                        this.groups = groups == null ? Collections.<FocusActionPickerRenderer.Group>emptyList() : groups;
                        this.nextItem = nextItem;
                        this.nextPosition = nextPosition;
                        this.total = total;
                        this.currentInQueue = currentInQueue;
                }

                public List<FocusActionPickerRenderer.Group> getGroups() {
                        return groups;
                }

                public FocusActionPickerRenderer.Row getNextItem() {
                        return nextItem;
                }

                public int getNextPosition() {
                        return nextPosition;
                }

                public int getTotal() {
                        return total;
                }

                public boolean isCurrentInQueue() {
                        return currentInQueue;
                }

                public List<FocusActionPickerRenderer.Row> getUpcoming() {
                        return upcoming;
                }
        }


        /**
         * Today's meetings (FocusMeetingService) as picker rows, in time order with untimed meetings
         * last, plus the server time the page was built at. The strip row, drawer labels and clock
         * arcs are all computed from these rows against serverNowEpochMs.
         */
        public static class MeetingsData {
                private final List<FocusActionPickerRenderer.Row> rows;
                private final long serverNowEpochMs;
                private final String chime;

                public MeetingsData(List<FocusActionPickerRenderer.Row> rows, long serverNowEpochMs) {
                        this(rows, serverNowEpochMs, "off");
                }

                public MeetingsData(List<FocusActionPickerRenderer.Row> rows, long serverNowEpochMs, String chime) {
                        this.rows = rows == null ? Collections.<FocusActionPickerRenderer.Row>emptyList() : rows;
                        this.serverNowEpochMs = serverNowEpochMs;
                        this.chime = chime == null ? "off" : chime;
                }

                /** The meeting-start sound: off, chime, bell or rise. */
                public String getChime() {
                        return chime;
                }

                public List<FocusActionPickerRenderer.Row> getRows() {
                        return rows;
                }

                public long getServerNowEpochMs() {
                        return serverNowEpochMs;
                }
        }

        /**
         * The current action's project for the This project row and drawer. Counts are open READY
         * actions by group; the groups hold the picker rows, ending with a read-only Done today.
         */
        public static class ProjectData {
                private final String projectName;
                private final List<FocusActionPickerRenderer.Group> groups;
                private final int overdueCount;
                private final int todayCount;
                private final int tomorrowCount;
                private final int laterCount;

                public ProjectData(String projectName, List<FocusActionPickerRenderer.Group> groups, int overdueCount,
                                int todayCount, int tomorrowCount, int laterCount) {
                        this.projectName = projectName;
                        this.groups = groups == null ? Collections.<FocusActionPickerRenderer.Group>emptyList() : groups;
                        this.overdueCount = overdueCount;
                        this.todayCount = todayCount;
                        this.tomorrowCount = tomorrowCount;
                        this.laterCount = laterCount;
                }

                public String getProjectName() {
                        return projectName;
                }

                public List<FocusActionPickerRenderer.Group> getGroups() {
                        return groups;
                }

                public int getOpenCount() {
                        return overdueCount + todayCount + tomorrowCount + laterCount;
                }

                /** "1 overdue · 2 today · 3 later", leaving out empty groups. */
                public String getSummary() {
                        StringBuilder sb = new StringBuilder();
                        appendCount(sb, overdueCount, "overdue");
                        appendCount(sb, todayCount, "today");
                        appendCount(sb, tomorrowCount, "tomorrow");
                        appendCount(sb, laterCount, "later");
                        return sb.toString();
                }

                private void appendCount(StringBuilder sb, int count, String label) {
                        if (count <= 0) {
                                return;
                        }
                        if (sb.length() > 0) {
                                sb.append(" \u00B7 ");
                        }
                        sb.append(count).append(' ').append(label);
                }
        }

        /** One fixed position in the switch bar. An empty slot has actionNextId 0. */
        public static class SwitchSlot {
                private final int actionNextId;
                private final String projectName;
                private final String description;
                private final int minutesToday;
                private final boolean completed;
                private final boolean current;
                private String projectIcon = "";

                public SwitchSlot(int actionNextId, String projectName, String description, int minutesToday,
                                boolean completed, boolean current) {
                        this.actionNextId = actionNextId;
                        this.projectName = projectName;
                        this.description = description;
                        this.minutesToday = minutesToday;
                        this.completed = completed;
                        this.current = current;
                }

                public static SwitchSlot empty() {
                        return new SwitchSlot(0, "", "", 0, false, false);
                }

                public boolean isEmpty() {
                        return actionNextId <= 0;
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

                public int getMinutesToday() {
                        return minutesToday;
                }

                public boolean isCompleted() {
                        return completed;
                }

                public boolean isCurrent() {
                        return current;
                }

                public SwitchSlot withProjectIcon(String projectIcon) {
                        this.projectIcon = projectIcon == null ? "" : projectIcon.trim();
                        return this;
                }

                public String getProjectIcon() {
                        return projectIcon;
                }
        }

        public void render(AppReq appReq, ActionNext action, String notes,
                        MeetingsData meetings,
                        List<SwitchSlot> switchSlots,
                        int spentMinutes, int estimateMinutes, boolean runningClock, int nowMinute,
                        int spentMinutesThisWeek, int todayBillableMinutes,
                        int todayTargetMinutes, int weekTargetMinutes,
                        int dayRemainingMinutes, int dayUnestimatedCount,
                        UpNextData upNext, ProjectData projectData,
                        String quickCaptureSentenceValue, List<String> quickCaptureProjectNames,
                        boolean quickCaptureFocusRequested, int editorOpenActionNextId,
                        ActionThreadService.Thread history) {
                PrintWriter out = appReq.getOut();
                printStyles(out);
                actionRecoveryRenderer.render(appReq);
                String closeTarget = action == null
                                ? "DandelionDashboardServlet"
                                : "DandelionDashboardServlet?action=SelectAction&completingActionNextId="
                                                + action.getActionNextId();

                out.println("<div class=\"fa-page\">");
                out.println("  <div class=\"fa-topbar\">");
                renderSwitchBar(out, switchSlots, runningClock);
                quickCaptureRenderer.render(out, new QuickCaptureRenderer.Config("FocusedActionServlet")
                                .sentenceValue(quickCaptureSentenceValue)
                                .projectNames(quickCaptureProjectNames)
                                .focusRequested(quickCaptureFocusRequested)
                                .boxClass("fa-quick-capture-box"));
                out.println("  </div>");
                out.println(
                                "  <button class=\"fa-close\" title=\"Back to Dandelion Dashboard\" onclick=\"window.location.href='"
                                                + closeTarget + "';\">&times;</button>");
                out.println("  <div class=\"fa-shell\">");
                out.println("    <section class=\"fa-left\">");
                out.println("      <div class=\"fa-left-scroll\">");

                if (action == null) {
                        out.println("        <h1 class=\"fa-title\">No current action selected</h1>");
                        out.println("        <p class=\"fa-subtitle\">Return to dashboard to choose an action.</p>");
                        out.println("      </div>");
                        out.println("    </section>");
                        out.println("    <section class=\"fa-right\">"
                                        + "<div class=\"fa-empty-clock\">No timer context</div>"
                                        + "</section>");
                        out.println("  </div>");
                        out.println("</div>");
                        actionEditorRenderer.render(out, appReq, editorOpenActionNextId, "FocusedActionServlet");
                        return;
                }

                String projectName = action.getProject() == null ? ""
                                : n(projectDisplayLabelService.buildDisplayName(appReq.getDataSession(),
                                                action.getProject()));
                String descriptionForDisplay = action.getNextDescriptionForDisplay(null);
                String projectIcon = action.getProject() == null ? "" : n(action.getProject().getProjectIcon()).trim();

                out.println("        <div class=\"fa-card fa-title-card" + (projectIcon.length() > 0 ? " fa-has-icon" : "")
                                + "\">");
                if (projectIcon.length() > 0) {
                        out.println("          <span class=\"fa-title-icon\" aria-hidden=\"true\">" + escapeHtml(projectIcon)
                                        + "</span>");
                }
                out.println("          <div class=\"fa-main-title\">" + descriptionForDisplay
                                + " <span class=\"fa-project-subtitle\">" + escapeHtml(projectName) + "</span></div>");
                out.println("          <div class=\"fa-title-actions\">");
                out.println(
                                "            <button type=\"button\" class=\"fa-small-btn\" onclick=\"faOpenRescheduleModal(event)\">Reschedule</button>");
                out.println(
                                "            <button type=\"button\" class=\"fa-small-btn\" onclick=\"faOpenEditor(event)\">Edit Action</button>");
                out.println("          </div>");
                out.println("        </div>");

                out.println("        <div id=\"faRescheduleModal\" class=\"fa-modal-overlay\" onclick=\"faCloseModalOnOverlay(event, this)\">");
                out.println("          <div class=\"fa-modal fa-reschedule-modal\" onclick=\"event.stopPropagation()\">");
                out.println("            <div class=\"fa-modal-head\">");
                out.println("              <h3 class=\"fa-modal-title\">Reschedule Action</h3>");
                out.println("              <button type=\"button\" class=\"fa-modal-close\" onclick=\"faCloseModal('faRescheduleModal')\">&times;</button>");
                out.println("            </div>");
                out.println("            <div class=\"fa-modal-body\">");
                out.println("              <div class=\"fa-modal-field\">");
                out.println("                <label for=\"faRescheduleDate\">New Date (today or future)</label>");
                out.println("                <input type=\"date\" id=\"faRescheduleDate\" />");
                out.println("              </div>");
                out.println("              <div class=\"fa-modal-actions\">");
                out.println("                <button type=\"button\" class=\"fa-primary-btn\" onclick=\"faSubmitReschedule()\">Apply</button>");
                out.println("                <button type=\"button\" class=\"fa-secondary-btn\" onclick=\"faCloseModal('faRescheduleModal')\">Cancel</button>");
                out.println("              </div>");
                out.println("            </div>");
                out.println("          </div>");
                out.println("        </div>");

                actionEditorRenderer.render(out, appReq, editorOpenActionNextId, "FocusedActionServlet");

                out.println("        <div class=\"fa-card\">");
                out.println("          <form method=\"POST\" action=\"FocusedActionServlet\" class=\"fa-work-form\">");
                out.println("            <div class=\"fa-work-progress-label\">What action was taken:</div>");
                out.println("            <input type=\"text\" class=\"fa-work-progress-input\" name=\"nextSummary\" value=\"\" autofocus />");
                out.println("            <div class=\"fa-status-row fa-work-status-toggle\">");
                out.println(
                                "              <input type=\"radio\" id=\"faWorkStatusInProgress\" name=\"workStatus\" value=\"IN_PROGRESS\" checked/><label for=\"faWorkStatusInProgress\">In Progress</label>");
                out.println(
                                "              <input type=\"radio\" id=\"faWorkStatusComplete\" name=\"workStatus\" value=\"COMPLETE\"/><label for=\"faWorkStatusComplete\">Complete</label>");
                out.println("              <input type=\"radio\" id=\"faWorkStatusDelete\" name=\"workStatus\" value=\"DELETE\"/><label for=\"faWorkStatusDelete\">Delete</label>");
                out.println(
                                "              <input type=\"radio\" id=\"faWorkStatusBlocked\" name=\"workStatus\" value=\"BLOCKED\"/><label for=\"faWorkStatusBlocked\">Blocked</label>");
                out.println("            </div>");
                out.println("            <div class=\"fa-next-row\">");
                out.println("              <span>Next</span>");
                out.println("              <span class=\"fa-capture-input-container\" style=\"flex:1;\">");
                out.println("                <input type=\"text\" id=\"workFollowUpInput\" name=\"workFollowUp\" style=\"width:100%;\" autocomplete=\"off\" />");
                out.println("                <div id=\"workFollowUpSuggestions\" class=\"fa-capture-suggestions\"></div>");
                out.println("              </span>");
                out.println(
                                "              <button type=\"submit\" name=\"action\" value=\"WorkNext\" class=\"fa-primary-btn\">Next</button>");
                out.println("            </div>");
                out.println(
                                "            <input type=\"hidden\" name=\"completingActionNextId\" value=\""
                                                + action.getActionNextId()
                                                + "\" />");
                out.println("          </form>");
                out.println("        </div>");

                renderContextStrip(out, appReq.getWebUser(), notes, history, meetings, upNext, projectData);


                out.println("      </div>");
                renderNotesDrawer(out, notes, action.getActionNextId());
                renderHistoryDrawer(out, appReq.getWebUser(), history);
                pickerRenderer.render(out, "fa-meetings-drawer", "Meetings today",
                                Collections.singletonList(new FocusActionPickerRenderer.Group("Today", meetings.getRows())),
                                "No meetings today.", meetingChimeFooter(meetings.getChime()));
                pickerRenderer.render(out, "fa-upnext-drawer", "Up next", upNext.getGroups(),
                                "Nothing left in today's list.", null, true);
                // Every row here is the same project, so the icon goes once in the title, not on each row.
                pickerRenderer.render(out, "fa-project-drawer",
                                projectData.getProjectName().length() == 0 ? "This project"
                                                : "This project: " + (projectIcon.length() > 0 ? projectIcon + " " : "")
                                                                + projectData.getProjectName(),
                                projectData.getGroups(), "No open actions in this project.");
                out.println("    </section>");

                out.println("    <section class=\"fa-right\">");
                out.println("      <div class=\"fa-clock-wrap\">");
                out.println("        <div class=\"fa-status-left-layer\" id=\"fa-status-left\">Actual<br>"
                                + formatMinutes(spentMinutes) + "</div>");
                out.println("        <div class=\"fa-status-right-layer\" id=\"fa-status-right\">Estimated<br>"
                                + formatMinutes(estimateMinutes) + "</div>");
                out.println(
                                "        <svg id=\"fa-clock\" class=\"fa-clock\" viewBox=\"0 0 760 760\" role=\"img\" aria-label=\"One-hour focus clock\"></svg>");
                out.println("        <div id=\"fa-dandelion-btn\" class=\"fa-dandelion-btn\"><img src=\"dandelion-button.png\" alt=\"Dandelion\" /></div>");
                out.println("        <div id=\"fa-meeting-card\" class=\"fa-meeting-card\" role=\"status\" hidden></div>");
                out.println("        <div class=\"fa-controls\">");
                out.println(
                                "          <button id=\"fa-toggle-timer\" type=\"button\" class=\"fa-primary-btn fa-timer-btn\" onclick=\"faToggleTimer()\">"
                                                + (runningClock ? "Stop" : "Start") + "</button>");
                out.println("        </div>");
                out.println("        <div id=\"fa-time-widget\" class=\"fa-time-widget\">");
                out.println("        <div class=\"fa-time-label\">Today:</div>");
                out.println("        <div id=\"fa-today-time\" class=\"fa-time-value\">"
                                + formatMinutes(todayBillableMinutes)
                                + "</div>");
                out.println("        <div class=\"fa-time-bar\"><div id=\"fa-today-bar\" class=\"fa-time-bar-fill\"></div></div>");
                out.println("        <div class=\"fa-time-label\">Week:</div>");
                out.println("        <div id=\"fa-week-time\" class=\"fa-time-value\">"
                                + formatMinutes(spentMinutesThisWeek) + "</div>");
                out.println("        <div class=\"fa-time-bar\"><div id=\"fa-week-bar\" class=\"fa-time-bar-fill\"></div></div>");
                out.println("        </div>");
                out.println("      </div>");
                renderDayBar(out);
                out.println("    </section>");
                out.println("  </div>");
                out.println("</div>");

                out.println("<script>");
                out.println("  var faState = {");
                out.println("    actionNextId: " + action.getActionNextId() + ",");
                out.println("    spentMinutes: " + Math.max(0, spentMinutes) + ",");
                out.println("    spentMinutesThisWeek: " + Math.max(0, spentMinutesThisWeek) + ",");
                out.println("    todayBillableMinutes: " + Math.max(0, todayBillableMinutes) + ",");
                out.println("    todayTargetMinutes: " + Math.max(1, todayTargetMinutes) + ",");
                out.println("    weekTargetMinutes: " + Math.max(1, weekTargetMinutes) + ",");
                out.println("    estimateMinutes: " + Math.max(0, estimateMinutes) + ",");
                out.println("    dayRemainingMinutes: " + Math.max(0, dayRemainingMinutes) + ",");
                out.println("    dayUnestimatedCount: " + Math.max(0, dayUnestimatedCount) + ",");
                out.println("    runningClock: " + (runningClock ? "true" : "false") + ",");
                out.println("    nowMinute: " + Math.max(0, Math.min(59, nowMinute)) + ",");
                out.println("    serverNowEpochMs: " + meetings.getServerNowEpochMs() + ",");
                out.println("    meetingChime: \"" + escapeJs(meetings.getChime()) + "\"");
                out.println("  };");
                printMeetingState(out, meetings);
                out.println("  faState.clockSyncedAt = Date.now();");
                out.println("  function faOpenModal(modalId) { var modal = document.getElementById(modalId); if (modal) { modal.classList.add('fa-modal-open'); } }");
                out.println("  function faCloseModal(modalId) { var modal = document.getElementById(modalId); if (modal) { modal.classList.remove('fa-modal-open'); } }");
                out.println("  function faCloseModalOnOverlay(evt, overlay) { if (evt && evt.target === overlay) { overlay.classList.remove('fa-modal-open'); } }");
                out.println("  function faCreateDashboardParams() { return new URLSearchParams(); }");
                out.println("  function faFetchDashboardJson(formData, contextLabel) {");
                out.println("    return fetch('DandelionDashboardServlet', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body: formData.toString() })");
                out.println("      .then(function(response) {");
                out.println("        return response.text().then(function(text) {");
                out.println("          if (!response.ok) { throw new Error(contextLabel + ' HTTP ' + response.status + ': ' + text.substring(0, 400)); }");
                out.println("          try { return JSON.parse(text); } catch (parseError) { throw new Error(contextLabel + ' invalid JSON: ' + text.substring(0, 400)); }");
                out.println("        });");
                out.println("      });");
                out.println("  }");
                out.println("  function faGetTodayISO() { var d = new Date(); var y = d.getFullYear(); var m = String(d.getMonth() + 1).padStart(2, '0'); var day = String(d.getDate()).padStart(2, '0'); return y + '-' + m + '-' + day; }");
                out.println("  function faIsoToUserDate(isoDate) { var parts = (isoDate || '').split('-'); if (parts.length !== 3) { return ''; } return parts[1] + '/' + parts[2] + '/' + parts[0]; }");
                out.println("  function faUserDateToIso(userDate) { var parts = (userDate || '').split('/'); if (parts.length !== 3) { return ''; } var m = parts[0].trim().padStart(2, '0'); var d = parts[1].trim().padStart(2, '0'); var y = parts[2].trim(); if (y.length !== 4) { return ''; } return y + '-' + m + '-' + d; }");
                out.println("  function faHandleActionMutationSuccess(data) {");
                out.println("    if (data && data.requiresActionRefresh) { window.location.href = 'FocusedActionServlet'; return; }");
                out.println("    window.location.href = 'FocusedActionServlet?completingActionNextId=' + encodeURIComponent(String(faState.actionNextId));");
                out.println("  }");
                out.println("  function faOpenRescheduleModal(evt) {");
                out.println("    if (evt) { evt.preventDefault(); evt.stopPropagation(); }");
                out.println("    var dateInput = document.getElementById('faRescheduleDate');");
                out.println("    if (dateInput) { dateInput.setAttribute('min', faGetTodayISO()); }");
                out.println("    var formData = faCreateDashboardParams();");
                out.println("    formData.append('action', 'loadRescheduleData');");
                out.println("    formData.append('actionNextId', String(faState.actionNextId));");
                out.println("    faFetchDashboardJson(formData, 'Reschedule preload').then(function(data) {");
                out.println("      if (!data || !data.success) { alert('Unable to load reschedule form.'); return; }");
                out.println("      var loadedIso = faUserDateToIso(data.nextActionDate || '') || faGetTodayISO();");
                out.println("      if (dateInput) { dateInput.value = loadedIso; }");
                out.println("      faOpenModal('faRescheduleModal');");
                out.println("    }).catch(function() { alert('Unable to load reschedule form.'); });");
                out.println("  }");
                out.println("  function faSubmitReschedule() {");
                out.println("    var dateInput = document.getElementById('faRescheduleDate');");
                out.println("    var isoDate = dateInput ? dateInput.value : ''; ");
                out.println("    if (!isoDate) { alert('Please select a date.'); return; }");
                out.println("    var formData = faCreateDashboardParams();");
                out.println("    formData.append('action', 'rescheduleAction');");
                out.println("    formData.append('actionNextId', String(faState.actionNextId));");
                out.println("    formData.append('nextActionDate', faIsoToUserDate(isoDate));");
                out.println("    faFetchDashboardJson(formData, 'Reschedule submit').then(function(data) {");
                out.println("      if (!data || !data.success) { alert('Error: ' + (data && data.message ? data.message : 'Could not reschedule action')); return; }");
                out.println("      faCloseModal('faRescheduleModal');");
                out.println("      faHandleActionMutationSuccess(data);");
                out.println("    }).catch(function() { alert('Error rescheduling action. Please try again.'); });");
                out.println("  }");
                out.println("  ActionEditor.configure({");
                out.println("    onSaved: function(data, mode) {");
                out.println("      if (mode === 'saveAndStart') { window.location.href = 'FocusedActionServlet'; return; }");
                out.println("      faHandleActionMutationSuccess(data);");
                out.println("    },");
                out.println("    onCompleted: function(data, mode, actionNextId) { faAfterActionRemoved(data, actionNextId); },");
                out.println("    onDeleted: function(data, mode, actionNextId) { faAfterActionRemoved(data, actionNextId); }");
                out.println("  });");
                out.println("  function faAfterActionRemoved(data, actionNextId) {");
                out.println("    if (actionNextId === faState.actionNextId) { window.location.href = 'FocusedActionServlet'; return; }");
                out.println("    faHandleActionMutationSuccess(data);");
                out.println("  }");
                out.println("  function faOpenEditor(evt) {");
                out.println("    if (evt) { evt.preventDefault(); evt.stopPropagation(); }");
                out.println("    ActionEditor.open(faState.actionNextId);");
                out.println("  }");
                out.println("  function faNormalizeMinute(value) { var v = value % 60; if (v < 0) { v += 60; } return v; }");
                out.println(
                                "  function faMinuteToAngle(minute) { return (faNormalizeMinute(minute) / 60) * Math.PI * 2 - (Math.PI / 2); }");
                out.println("  function faPolar(cx, cy, r, minute) {");
                out.println("    var angle = faMinuteToAngle(minute);");
                out.println("    return { x: cx + (Math.cos(angle) * r), y: cy + (Math.sin(angle) * r) }; }");
                out.println(
                                "  function faSvgEl(name) { return document.createElementNS('http://www.w3.org/2000/svg', name); }");
                out.println("  function faAddFullCircle(svg, cx, cy, radius, color) {");
                out.println(
                                "    var c = faSvgEl('circle'); c.setAttribute('cx', cx); c.setAttribute('cy', cy); c.setAttribute('r', radius); c.setAttribute('fill', color); svg.appendChild(c); }");
                out.println("  function faAddSector(svg, cx, cy, radius, startMinute, length, color) {");
                out.println("    if (length <= 0) { return; }");
                out.println("    if (length >= 60) { faAddFullCircle(svg, cx, cy, radius, color); return; }");
                out.println("    var remaining = length;");
                out.println("    var cursor = startMinute;");
                out.println("    while (remaining > 0) {");
                out.println("      var startN = faNormalizeMinute(cursor);");
                out.println("      var untilWrap = 60 - startN;");
                out.println("      var segmentLen = Math.min(remaining, untilWrap);");
                out.println("      var endN = faNormalizeMinute(startN + segmentLen);");
                out.println("      var p0 = faPolar(cx, cy, radius, startN);");
                out.println("      var p1 = faPolar(cx, cy, radius, endN);");
                out.println("      var largeArc = segmentLen > 30 ? 1 : 0;");
                out.println("      var path = faSvgEl('path');");
                out.println(
                                "      path.setAttribute('d', 'M ' + cx + ' ' + cy + ' L ' + p0.x + ' ' + p0.y + ' A ' + radius + ' ' + radius + ' 0 ' + largeArc + ' 1 ' + p1.x + ' ' + p1.y + ' Z');");
                out.println("      path.setAttribute('fill', color);");
                out.println("      svg.appendChild(path);");
                out.println("      remaining -= segmentLen;");
                out.println("      cursor += segmentLen;");
                out.println("    }");
                out.println("  }");
                out.println("  function faDrawFace(svg, cx, cy, radius, palette) {");
                out.println(
                                "    var ring = faSvgEl('circle'); ring.setAttribute('cx', cx); ring.setAttribute('cy', cy); ring.setAttribute('r', radius); ring.setAttribute('fill', 'none'); ring.setAttribute('stroke', palette.ring); ring.setAttribute('stroke-width', '5'); svg.appendChild(ring);");
                out.println("    for (var minute = 0; minute < 60; minute++) {");
                out.println("      var isMajor = minute % 5 === 0;");
                out.println("      var outer = faPolar(cx, cy, radius - 2, minute);");
                out.println("      var inner = faPolar(cx, cy, radius - (isMajor ? 22 : 13), minute);");
                out.println("      var tick = faSvgEl('line');");
                out.println(
                                "      tick.setAttribute('x1', outer.x); tick.setAttribute('y1', outer.y); tick.setAttribute('x2', inner.x); tick.setAttribute('y2', inner.y);");
                out.println("      tick.setAttribute('stroke', isMajor ? palette.majorTick : palette.minorTick);");
                out.println("      tick.setAttribute('stroke-width', isMajor ? 2.6 : 1.2);");
                out.println("      svg.appendChild(tick);");
                out.println("    }");
                out.println("    for (var marker = 0; marker < 60; marker += 5) {");
                out.println("      var labelP = faPolar(cx, cy, radius - 44, marker);");
                out.println("      var t = faSvgEl('text');");
                out.println(
                                "      t.setAttribute('x', labelP.x); t.setAttribute('y', labelP.y + 5); t.setAttribute('text-anchor', 'middle'); t.setAttribute('font-size', '17'); t.setAttribute('fill', palette.label); t.textContent = String(marker);");
                out.println("      svg.appendChild(t);");
                out.println("    }");
                out.println("  }");
                out.println("  function faDrawMinuteHand(svg, cx, cy, radius, minute, palette) {");
                out.println("    var handEnd = faPolar(cx, cy, radius * 0.70, minute);");
                out.println("    var handAngle = faMinuteToAngle(minute);");
                out.println("    var hand = faSvgEl('line');");
                out.println("    hand.setAttribute('x1', cx); hand.setAttribute('y1', cy);");
                out.println("    hand.setAttribute('x2', handEnd.x); hand.setAttribute('y2', handEnd.y);");
                out.println("    hand.setAttribute('stroke', palette.hand);");
                out.println("    hand.setAttribute('stroke-width', '9');");
                out.println("    hand.setAttribute('stroke-linecap', 'round');");
                out.println("    svg.appendChild(hand);");
                out.println("    var wingLength = 26;");
                out.println("    var wingSpread = Math.PI / 6;");
                out.println("    var wing1 = { x: handEnd.x + Math.cos(handAngle + Math.PI - wingSpread) * wingLength, y: handEnd.y + Math.sin(handAngle + Math.PI - wingSpread) * wingLength };");
                out.println("    var wing2 = { x: handEnd.x + Math.cos(handAngle + Math.PI + wingSpread) * wingLength, y: handEnd.y + Math.sin(handAngle + Math.PI + wingSpread) * wingLength };");
                out.println("    var arrow1 = faSvgEl('line');");
                out.println("    arrow1.setAttribute('x1', handEnd.x); arrow1.setAttribute('y1', handEnd.y);");
                out.println("    arrow1.setAttribute('x2', wing1.x); arrow1.setAttribute('y2', wing1.y);");
                out.println("    arrow1.setAttribute('stroke', palette.hand);");
                out.println("    arrow1.setAttribute('stroke-width', '9');");
                out.println("    arrow1.setAttribute('stroke-linecap', 'round');");
                out.println("    svg.appendChild(arrow1);");
                out.println("    var arrow2 = faSvgEl('line');");
                out.println("    arrow2.setAttribute('x1', handEnd.x); arrow2.setAttribute('y1', handEnd.y);");
                out.println("    arrow2.setAttribute('x2', wing2.x); arrow2.setAttribute('y2', wing2.y);");
                out.println("    arrow2.setAttribute('stroke', palette.hand);");
                out.println("    arrow2.setAttribute('stroke-width', '9');");
                out.println("    arrow2.setAttribute('stroke-linecap', 'round');");
                out.println("    svg.appendChild(arrow2);");
                out.println("  }");
                out.println("  function faDrawClock() {");
                out.println("    var svg = document.getElementById('fa-clock'); if (!svg) { return; }");
                out.println("    while (svg.firstChild) { svg.removeChild(svg.firstChild); }");
                out.println("    var cx = 380; var cy = 380; var radius = 300;");
                out.println("    var running = !!faState.runningClock;");
                out.println("    var palette = running ? {");
                out.println(
                                "      background: '#ffffff', spent: '#d9f0d2', remaining: '#2f8c2f', overrun: '#cb2c1f', ring: '#2e342e', minorTick: '#9ba59a', majorTick: '#4d5a4d', label: '#2d3a2d', hand: '#2e342e' }");
                out.println(
                                "      : { background: '#f3f3f3', spent: '#dbdbdb', remaining: '#c7c7c7', overrun: '#bdbdbd', ring: '#8a8a8a', minorTick: '#b5b5b5', majorTick: '#979797', label: '#7a7a7a', hand: '#949494' };");
                out.println("    faAddFullCircle(svg, cx, cy, radius, palette.background);");
                out.println("    var nowMinute = faNormalizeMinute(faState.nowMinute || 0);");
                out.println("    var spent = Math.max(0, Math.min(60, faState.spentMinutes || 0));");
                out.println("    var estimate = Math.max(0, faState.estimateMinutes || 0);");
                out.println("    var remaining = Math.max(0, estimate - (faState.spentMinutes || 0));");
                out.println("    var overrun = Math.max(0, (faState.spentMinutes || 0) - estimate);");
                out.println("    faAddSector(svg, cx, cy, radius - 6, nowMinute - spent, spent, palette.spent);");
                out.println("    faAddSector(svg, cx, cy, radius - 6, nowMinute, Math.min(60, remaining), palette.remaining);");
                out.println(
                                "    faAddSector(svg, cx, cy, radius - 6, nowMinute - overrun, Math.min(60, overrun), palette.overrun);");
                out.println("    faDrawFace(svg, cx, cy, radius, palette);");
                out.println("    faDrawMeetingArcs(svg, cx, cy, radius, nowMinute);");
                out.println("    faDrawMinuteHand(svg, cx, cy, radius, nowMinute, palette);");
                out.println("  }");
                printMeetingScript(out);
                out.println("  function faSelectMeeting(meetingActionNextId) {");
                out.println("    var body = new URLSearchParams();");
                out.println("    body.append('action', 'selectMeetingAction');");
                out.println("    body.append('meetingActionNextId', String(meetingActionNextId));");
                out.println(
                                "    fetch('FocusedActionServlet', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body: body.toString() })");
                out.println("      .then(function(response) { return response.json(); })");
                out.println(
                                "      .then(function(data) { if (data && data.success) { window.location.href='FocusedActionServlet?completingActionNextId=' + encodeURIComponent(String(meetingActionNextId)); } else { alert('Unable to select meeting.'); } })");
                out.println("      .catch(function() { alert('Unable to select meeting.'); });");
                out.println("  }");
                out.println("  // Meeting starting card: shown over the clock from two minutes before a timed meeting until five");
                out.println("  // minutes after it starts, unless that meeting is already the current action. Dismissals live in");
                out.println("  // this tab's sessionStorage only. faNow() counts forward from the last server time, so the");
                out.println("  // browser clock and time zone never matter.");
                out.println("  var FA_MEETING_LEAD_MS = 2 * 60000; var FA_MEETING_AFTER_MS = 5 * 60000;");
                out.println("  var faMeetingCard = { key: null, baseTitle: document.title, flash: false, dismissed: {}, upcoming: {}, chimed: {} };");
                out.println("  function faNow() { return (faState.serverNowEpochMs || 0) + (Date.now() - (faState.clockSyncedAt || Date.now())); }");
                out.println("  function faJoinMeeting(meetingActionNextId) {");
                out.println("    if (faState.actionNextId === meetingActionNextId && faState.runningClock) { return; }");
                out.println("    faSelectMeeting(meetingActionNextId);");
                out.println("  }");
                out.println("  function faMeetingDismissKey(m) { return 'faMeetingDismissed:' + m.actionNextId + ':' + m.startEpochMs; }");
                out.println("  function faMeetingDismissed(m) {");
                out.println("    var key = faMeetingDismissKey(m); if (faMeetingCard.dismissed[key]) { return true; }");
                out.println("    try { return window.sessionStorage.getItem(key) === '1'; } catch (e) { return false; }");
                out.println("  }");
                out.println("  function faDismissMeetingCard() {");
                out.println("    if (!faMeetingCard.key) { return; }");
                out.println("    faMeetingCard.dismissed[faMeetingCard.key] = true;");
                out.println("    try { window.sessionStorage.setItem(faMeetingCard.key, '1'); } catch (e) { }");
                out.println("    faUpdateMeetingCard();");
                out.println("  }");
                out.println("  function faFindStartingMeeting(now) {");
                out.println("    var list = faState.meetingArcs || []; var best = null;");
                out.println("    for (var i = 0; i < list.length; i++) {");
                out.println("      var m = list[i];");
                out.println("      if (!m.actionNextId || !m.startEpochMs || m.actionNextId === faState.actionNextId) { continue; }");
                out.println("      if (now < m.startEpochMs - FA_MEETING_LEAD_MS || now >= m.startEpochMs + FA_MEETING_AFTER_MS) { continue; }");
                out.println("      if (m.endEpochMs && now >= m.endEpochMs) { continue; }");
                out.println("      if (faMeetingDismissed(m)) { continue; }");
                out.println("      if (!best || m.startEpochMs < best.startEpochMs) { best = m; }");
                out.println("    }");
                out.println("    return best;");
                out.println("  }");
                out.println("  function faMeetingCountdown(msToStart) {");
                out.println("    if (msToStart > 0) {");
                out.println("      var s = Math.ceil(msToStart / 1000);");
                out.println("      return s >= 60 ? 'in ' + Math.floor(s / 60) + 'm ' + (s % 60 < 10 ? '0' : '') + (s % 60) + 's' : 'in ' + s + 's';");
                out.println("    }");
                out.println("    var ago = Math.floor(-msToStart / 60000);");
                out.println("    return ago < 1 ? 'starting now' : 'started ' + ago + 'm ago';");
                out.println("  }");
                out.println("  function faBuildMeetingCard(card, m) {");
                out.println("    card.textContent = '';");
                out.println("    var close = document.createElement('button'); close.type = 'button'; close.className = 'fa-meeting-card-close';");
                out.println("    close.title = 'Dismiss'; close.setAttribute('aria-label', 'Dismiss'); close.innerHTML = '&times;';");
                out.println("    close.addEventListener('click', faDismissMeetingCard); card.appendChild(close);");
                out.println("    var label = document.createElement('div'); label.className = 'fa-meeting-card-label';");
                out.println("    label.appendChild(document.createTextNode('Meeting ' + (m.startLabel || '') + ' \\u00b7 '));");
                out.println("    var countdown = document.createElement('span'); countdown.className = 'fa-meeting-card-countdown'; label.appendChild(countdown);");
                out.println("    card.appendChild(label);");
                out.println("    var title = document.createElement('div'); title.className = 'fa-meeting-card-title'; title.textContent = m.title || 'Meeting';");
                out.println("    title.title = m.title || ''; card.appendChild(title);");
                out.println("    var id = m.actionNextId; var url = m.joinUrl && /^https?:\\/\\//i.test(m.joinUrl) ? m.joinUrl : null;");
                out.println("    var go = document.createElement(url ? 'a' : 'button'); go.className = 'fa-meeting-card-join';");
                out.println("    if (url) {");
                out.println("      go.href = url; go.target = '_blank'; go.rel = 'noopener noreferrer'; go.textContent = 'Join';");
                out.println("      go.title = 'Open the meeting and start its clock (j)';");
                out.println("      go.addEventListener('click', function() { faJoinMeeting(id); });");
                out.println("    } else {");
                out.println("      go.type = 'button'; go.textContent = 'Start meeting'; go.title = 'Start the clock on this meeting (j)';");
                out.println("      go.addEventListener('click', function() { faJoinMeeting(id); });");
                out.println("    }");
                out.println("    card.appendChild(go);");
                out.println("  }");
                out.println("  function faSetMeetingTitleFlash(title) {");
                out.println("    if (!title) { if (document.title !== faMeetingCard.baseTitle) { document.title = faMeetingCard.baseTitle; } faMeetingCard.flash = false; return; }");
                out.println("    faMeetingCard.flash = !faMeetingCard.flash;");
                out.println("    document.title = faMeetingCard.flash ? 'Meeting starting: ' + title : faMeetingCard.baseTitle;");
                out.println("  }");
                out.println("  // Meeting-start sound, picked in the Meetings drawer and saved per user. The sounds are");
                out.println("  // synthesized; browsers only play audio once the page has been clicked or typed on.");
                out.println("  var faAudio = null;");
                out.println("  function faAudioCtx() {");
                out.println("    if (!faAudio) { var C = window.AudioContext || window.webkitAudioContext; if (!C) { return null; } faAudio = new C(); }");
                out.println("    if (faAudio.state === 'suspended') { faAudio.resume().catch(function() { }); }");
                out.println("    return faAudio;");
                out.println("  }");
                out.println("  function faTone(ctx, freq, start, dur, peak) {");
                out.println("    var o = ctx.createOscillator(); var g = ctx.createGain();");
                out.println("    o.type = 'sine'; o.frequency.value = freq;");
                out.println("    g.gain.setValueAtTime(0.0001, start);");
                out.println("    g.gain.exponentialRampToValueAtTime(peak, start + 0.015);");
                out.println("    g.gain.exponentialRampToValueAtTime(0.0001, start + dur);");
                out.println("    o.connect(g); g.connect(ctx.destination); o.start(start); o.stop(start + dur + 0.05);");
                out.println("  }");
                out.println("  function faChimePlay(kind) {");
                out.println("    if (!kind || kind === 'off') { return; }");
                out.println("    var ctx = faAudioCtx(); if (!ctx) { return; }");
                out.println("    var t = ctx.currentTime + 0.05;");
                out.println("    if (kind === 'chime') { faTone(ctx, 659.25, t, 1.3, 0.2); faTone(ctx, 523.25, t + 0.38, 1.8, 0.2); }");
                out.println("    else if (kind === 'bell') { faTone(ctx, 784, t, 2.8, 0.18); faTone(ctx, 784 * 2.76, t, 0.7, 0.035); faTone(ctx, 784 * 5.4, t, 0.3, 0.015); }");
                out.println("    else if (kind === 'rise') { faTone(ctx, 523.25, t, 1.0, 0.15); faTone(ctx, 659.25, t + 0.2, 1.0, 0.15); faTone(ctx, 783.99, t + 0.4, 1.4, 0.15); }");
                out.println("  }");
                out.println("  function faChimeChange(select) {");
                out.println("    var previous = faState.meetingChime; var value = select.value;");
                out.println("    faState.meetingChime = value; faChimePlay(value);");
                out.println("    var status = document.getElementById('fa-chime-status'); if (status) { status.textContent = ''; }");
                out.println("    var body = new URLSearchParams(); body.append('action', 'saveMeetingChime'); body.append('chime', value);");
                out.println("    fetch('FocusedActionServlet', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body: body.toString() })");
                out.println("      .then(function(response) { return response.json(); })");
                out.println("      .then(function(data) { if (!data || !data.success) { throw new Error(); } if (status) { status.textContent = 'Saved'; } })");
                out.println("      .catch(function() { faState.meetingChime = previous; select.value = previous; if (status) { status.textContent = 'Not saved'; } });");
                out.println("  }");
                out.println("  function faChimePreview() { var select = document.getElementById('fa-chime-select'); if (select) { faChimePlay(select.value); } }");
                out.println("  function faUpdateMeetingCard() {");
                out.println("    var card = document.getElementById('fa-meeting-card'); if (!card) { return; }");
                out.println("    var now = faNow(); var m = faFindStartingMeeting(now);");
                out.println("    if (!m) {");
                out.println("      card.hidden = true; card.classList.remove('fa-meeting-card-due'); faMeetingCard.key = null;");
                out.println("      faSetMeetingTitleFlash(null); return;");
                out.println("    }");
                out.println("    var key = faMeetingDismissKey(m);");
                out.println("    if (faMeetingCard.key !== key) { faBuildMeetingCard(card, m); faMeetingCard.key = key; }");
                out.println("    card.hidden = false;");
                out.println("    var due = now >= m.startEpochMs;");
                out.println("    card.classList.toggle('fa-meeting-card-due', due);");
                out.println("    if (!due) { faMeetingCard.upcoming[key] = true; }");
                out.println("    else if (faMeetingCard.upcoming[key] && !faMeetingCard.chimed[key]) { faMeetingCard.chimed[key] = true; faChimePlay(faState.meetingChime); }");
                out.println("    var countdown = card.querySelector('.fa-meeting-card-countdown');");
                out.println("    if (countdown) { countdown.textContent = faMeetingCountdown(m.startEpochMs - now); }");
                out.println("    faSetMeetingTitleFlash(due ? (m.title || 'Meeting') : null);");
                out.println("  }");
                out.println("  // Notes drawer: a notepad holding the action's whole notes text, plus a one-line quick add.");
                out.println("  // faNotes.base is the text last loaded or saved; the server uses it to keep text appended");
                out.println("  // elsewhere (MCP, another tab) and to refuse a save over any other change.");
                out.println("  var faNotes = { base: null, saving: null };");
                out.println("  function faNotesBox() { return document.getElementById('fa-notes-text'); }");
                out.println("  function faNotesInit() {");
                out.println("    var box = faNotesBox(); if (!box) { return; }");
                out.println("    faNotes.base = box.value;");
                out.println("    box.addEventListener('keydown', function(e) {");
                out.println("      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); faNotesSave(); }");
                out.println("    });");
                out.println("    box.addEventListener('input', function() { faNotesStatus(''); });");
                out.println("    box.addEventListener('blur', function() { faNotesSave(); });");
                out.println("    window.addEventListener('pagehide', function() {");
                out.println("      var b = faNotesBox();");
                out.println("      if (!b || b.value === faNotes.base || faNotes.saving || !navigator.sendBeacon) { return; }");
                out.println("      navigator.sendBeacon('FocusedActionServlet', faNotesParams('saveCurrentActionNotes', { nextNote: b.value, baseNote: faNotes.base }));");
                out.println("    });");
                out.println("  }");
                out.println("  function faNotesParams(action, fields) {");
                out.println("    var body = new URLSearchParams(); body.append('action', action);");
                out.println("    body.append('actionNextId', faNotesBox().getAttribute('data-action-next-id'));");
                out.println("    for (var k in fields) { body.append(k, fields[k]); }");
                out.println("    return body;");
                out.println("  }");
                out.println("  function faNotesPost(action, fields) {");
                out.println("    return fetch('FocusedActionServlet', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body: faNotesParams(action, fields).toString() })");
                out.println("      .then(function(response) { return response.json(); });");
                out.println("  }");
                out.println("  function faNotesStatus(text, isError) {");
                out.println("    var el = document.getElementById('fa-notes-status'); if (!el) { return; }");
                out.println("    el.textContent = text; el.classList.toggle('fa-notes-status-error', !!isError);");
                out.println("  }");
                out.println("  // Saves the notepad if it differs from faNotes.base. Resolves true when nothing is left unsaved.");
                out.println("  function faNotesSave() {");
                out.println("    var box = faNotesBox(); if (!box) { return Promise.resolve(true); }");
                out.println("    if (faNotes.saving) { return faNotes.saving.then(function() { return faNotesSave(); }); }");
                out.println("    var sent = box.value;");
                out.println("    if (sent === faNotes.base) { return Promise.resolve(true); }");
                out.println("    faNotesStatus('Saving...');");
                out.println("    faNotes.saving = faNotesPost('saveCurrentActionNotes', { nextNote: sent, baseNote: faNotes.base })");
                out.println("      .then(function(data) {");
                out.println("        if (!data || !data.success) { faNotesStatus((data && data.message) || 'Unable to save notes.', true); return false; }");
                out.println("        faNotesApply(data.notes, sent);");
                out.println("        faNotesStatus(data.tailReattached ? 'Saved, keeping text added elsewhere' : 'Saved');");
                out.println("        return true;");
                out.println("      })");
                out.println("      .catch(function() { faNotesStatus('Unable to save notes.', true); return false; })");
                out.println("      .then(function(ok) { faNotes.saving = null; return ok; });");
                out.println("    return faNotes.saving;");
                out.println("  }");
                out.println("  // Takes the server's text as the new base; the box shows it unless it was edited after `sent`.");
                out.println("  function faNotesApply(text, sent) {");
                out.println("    var box = faNotesBox();");
                out.println("    faNotes.base = text;");
                out.println("    if (box.value === sent) { box.value = text; }");
                out.println("    faUpdateNotesRow(text);");
                out.println("  }");
                out.println("  function faHandleNoteKeydown(event) {");
                out.println("    if (event.key === 'Enter') { event.preventDefault(); faNotesAppend(); }");
                out.println("  }");
                out.println("  // Quick add: saves any notepad edits first, then appends the line to the end of the notes.");
                out.println("  function faNotesAppend() {");
                out.println("    var input = document.getElementById('fa-note-input');");
                out.println("    if (!input || input.value.trim().length === 0) { return; }");
                out.println("    var line = input.value;");
                out.println("    var box = faNotesBox();");
                out.println("    faNotesSave().then(function(ok) {");
                out.println("      if (!ok) { return; }");
                out.println("      box.readOnly = true;");
                out.println("      var sent = box.value;");
                out.println("      return faNotesPost('addCurrentActionNote', { nextNote: line }).then(function(data) {");
                out.println("        if (!data || !data.success) { faNotesStatus((data && data.message) || 'Unable to add note.', true); return; }");
                out.println("        faNotesApply(data.notes, sent);");
                out.println("        faNotesStatus('Saved');");
                out.println("        input.value = '';");
                out.println("        box.scrollTop = box.scrollHeight;");
                out.println("      });");
                out.println("    }).catch(function() { faNotesStatus('Unable to add note.', true); })");
                out.println("      .then(function() { box.readOnly = false; });");
                out.println("  }");
                out.println("  // The context-strip row previews the first non-blank line.");
                out.println("  function faUpdateNotesRow(text) {");
                out.println("    var peek = document.getElementById('fa-notes-peek'); if (!peek) { return; }");
                out.println("    var has = String(text || '').trim().length > 0;");
                out.println("    peek.textContent = has ? text : 'No notes yet \\u00b7 press n';");
                out.println("    peek.classList.toggle('fa-notes-peek-empty', !has);");
                out.println("    faFitPanels();");
                out.println("  }");
                pickerRenderer.printScript(out);
                out.println("  var faOpenDrawerId = null;");
                out.println("  function faOpenDrawer(drawerId, focusId) {");
                out.println("    if (faOpenDrawerId && faOpenDrawerId !== drawerId) { faCloseDrawer(); }");
                out.println("    var drawer = document.getElementById(drawerId); if (!drawer) { return; }");
                out.println("    drawer.classList.add('fa-drawer-open'); drawer.setAttribute('aria-hidden', 'false');");
                out.println("    faOpenDrawerId = drawerId;");
                out.println("    var body = drawer.querySelector('.fa-drawer-body'); if (body) { body.scrollTop = drawerId === 'fa-history-drawer' ? 0 : body.scrollHeight; }");
                out.println("    var focusEl = focusId ? document.getElementById(focusId) : null;");
                out.println("    if (focusEl) { setTimeout(function() {");
                out.println("      focusEl.focus();");
                out.println("      if (focusEl.tagName === 'TEXTAREA') { focusEl.setSelectionRange(focusEl.value.length, focusEl.value.length); focusEl.scrollTop = focusEl.scrollHeight; }");
                out.println("    }, 0); }");
                out.println("  }");
                out.println("  function faCloseDrawer() {");
                out.println("    if (!faOpenDrawerId) { return; }");
                out.println("    if (faOpenDrawerId === 'fa-notes-drawer') { faNotesSave(); }");
                out.println("    var drawer = document.getElementById(faOpenDrawerId);");
                out.println("    if (drawer) { drawer.classList.remove('fa-drawer-open'); drawer.setAttribute('aria-hidden', 'true'); }");
                out.println("    var trigger = document.querySelector('[data-drawer=\"' + faOpenDrawerId + '\"]');");
                out.println("    faOpenDrawerId = null;");
                out.println("    if (trigger) { trigger.focus(); }");
                out.println("  }");
                out.println("  document.addEventListener('keydown', function(e) {");
                out.println("    if (document.querySelector('.fa-modal-open')) { return; }");
                out.println("    if (e.key === 'Escape' && faOpenDrawerId) { e.preventDefault(); faCloseDrawer(); return; }");
                out.println("    if ((e.key !== 'n' && e.key !== 'j') || e.altKey || e.ctrlKey || e.metaKey) { return; }");
                out.println("    var el = document.activeElement;");
                out.println("    if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable)) { return; }");
                out.println("    if (e.key === 'j') {");
                out.println("      var join = document.querySelector('#fa-meeting-card:not([hidden]) .fa-meeting-card-join');");
                out.println("      if (join) { e.preventDefault(); join.click(); }");
                out.println("      return;");
                out.println("    }");
                out.println("    e.preventDefault();");
                out.println("    faOpenDrawer('fa-notes-drawer', 'fa-notes-text');");
                out.println("  });");
                out.println("  function faInitWorkFollowUpSuggestions() {");
                out.println(
                                "    var actionVerbs = ['I will', 'I have committed', 'I might', 'I would like to', 'I will meet', 'I have set goal to', 'I am waiting'];");
                out.println("    var input = document.getElementById('workFollowUpInput');");
                out.println("    var suggestionsBox = document.getElementById('workFollowUpSuggestions');");
                out.println("    if (!input || !suggestionsBox) { return; }");
                out.println("    var currentSuggestions = []; var selectedIndex = -1;");
                out.println(
                                "    function showSuggestions(suggestions) { suggestionsBox.innerHTML = ''; suggestionsBox.style.display = suggestions.length ? 'block' : 'none'; for (var i=0;i<suggestions.length;i++) { var suggestion = suggestions[i]; var div = document.createElement('div'); div.textContent = suggestion; if (i === selectedIndex) { div.style.backgroundColor = '#e0e0e0'; } div.addEventListener('click', function(evt){ var text = evt.target.textContent || ''; input.value = text + ' '; suggestionsBox.style.display = 'none'; selectedIndex = -1; input.focus(); }); suggestionsBox.appendChild(div); } }");
                out.println(
                                "    input.addEventListener('input', function(){ var text = (input.value || '').trim(); if (text.length === 0) { currentSuggestions = actionVerbs.slice(0); selectedIndex = -1; showSuggestions(currentSuggestions); return; } var suggestions = actionVerbs.filter(function(verb){ return verb.toLowerCase().startsWith(text.toLowerCase()); }); currentSuggestions = suggestions; selectedIndex = -1; showSuggestions(suggestions); });");
                out.println(
                                "    input.addEventListener('focus', function(){ var text = (input.value || '').trim(); if (text.length === 0) { currentSuggestions = actionVerbs.slice(0); selectedIndex = -1; showSuggestions(currentSuggestions); } });");
                out.println(
                                "    input.addEventListener('keydown', function(e){ var visible = suggestionsBox.style.display === 'block'; if (visible && (e.key === 'ArrowDown' || e.key === 'ArrowUp')) { e.preventDefault(); var count = currentSuggestions.length; if (count === 0) { return; } if (e.key === 'ArrowDown') { selectedIndex = (selectedIndex + 1) % count; } else { selectedIndex = (selectedIndex - 1 + count) % count; } showSuggestions(currentSuggestions); } if (visible && (e.key === 'Enter' || e.key === 'Tab')) { if (selectedIndex < 0) { selectedIndex = 0; } if (selectedIndex < currentSuggestions.length) { e.preventDefault(); input.value = currentSuggestions[selectedIndex] + ' '; suggestionsBox.style.display = 'none'; selectedIndex = -1; } } if (e.key === 'Escape') { suggestionsBox.style.display = 'none'; selectedIndex = -1; } });");
                out.println(
                                "    input.addEventListener('blur', function(){ setTimeout(function(){ suggestionsBox.style.display = 'none'; }, 150); });");
                out.println("  }");
                out.println("  function faToggleTimer() {");
                out.println(
                                "    var body = new URLSearchParams(); body.append('action', 'toggleTimer'); body.append('completingActionNextId', String(faState.actionNextId || 0));");
                out.println(
                                "    fetch('FocusedActionServlet', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body: body.toString() })");
                out.println("      .then(function(response) { return response.json(); })");
                out.println(
                                "      .then(function(data) { if (!data || !data.success) { alert('Unable to toggle timer.'); return; } faState.runningClock = !!data.runningClock; faRefreshClock(); })");
                out.println("      .catch(function() { alert('Unable to toggle timer.'); });");
                out.println("  }");
                out.println("  function faRefreshClock() {");
                out.println(
                                "    var body = new URLSearchParams(); body.append('action', 'refreshFocusClock'); body.append('completingActionNextId', String(faState.actionNextId || 0));");
                out.println(
                                "    fetch('FocusedActionServlet', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' }, body: body.toString() })");
                out.println("      .then(function(response) { return response.json(); })");
                out.println("      .then(function(data) {");
                out.println("        if (!data || !data.success) { return; }");
                out.println("        faState.spentMinutes = parseInt(data.spentMinutes || 0, 10) || 0;");
                out.println("        faState.spentMinutesThisWeek = parseInt(data.spentMinutesThisWeek || 0, 10) || 0;");
                out.println("        faState.todayBillableMinutes = parseInt(data.todayBillableMinutes || 0, 10) || 0;");
                out.println("        faState.todayTargetMinutes = parseInt(data.todayTargetMinutes || 0, 10) || 0;");
                out.println("        faState.weekTargetMinutes = parseInt(data.weekTargetMinutes || 0, 10) || 0;");
                out.println("        faState.estimateMinutes = parseInt(data.estimateMinutes || 0, 10) || 0;");
                out.println("        faState.dayRemainingMinutes = parseInt(data.dayRemainingMinutes || 0, 10) || 0;");
                out.println("        faState.dayUnestimatedCount = parseInt(data.dayUnestimatedCount || 0, 10) || 0;");
                out.println("        faState.nowMinute = parseInt(data.nowMinute || 0, 10) || 0;");
                out.println("        faState.runningClock = !!data.runningClock;");
                out.println("        if (data.serverNowEpochMs) { faState.serverNowEpochMs = data.serverNowEpochMs; faState.clockSyncedAt = Date.now(); }");
                out.println("        if (data.meetingArcs) { faState.meetingArcs = data.meetingArcs; }");
                out.println("        faUpdateMeetingViews();");
                out.println("        faFitPanels();");
                out.println("        faUpdateMeetingCard();");
                out.println("        var toggle = document.getElementById('fa-toggle-timer');");
                out.println("        if (toggle) { toggle.textContent = faState.runningClock ? 'Stop' : 'Start'; }");
                out.println("        var todayEl = document.getElementById('fa-today-time');");
                out.println("        if (todayEl) { todayEl.textContent = faFormatMinutes(faState.todayBillableMinutes); }");
                out.println("        var weekEl = document.getElementById('fa-week-time');");
                out.println("        if (weekEl) { weekEl.textContent = faFormatMinutes(faState.spentMinutesThisWeek); }");
                out.println("        var switchTimeEl = document.getElementById('fa-switch-current-time');");
                out.println("        if (switchTimeEl) { switchTimeEl.textContent = faFormatMinutes(faState.spentMinutes); }");
                out.println("        var switchTile = document.getElementById('fa-switch-current');");
                out.println("        if (switchTile) { switchTile.classList.toggle('fa-switch-running', faState.runningClock); }");
                out.println("        faUpdateStatusAndGauge();");
                out.println("        faDrawClock();");
                out.println("      });");
                out.println("  }");
                out.println("  function faClampPct(v) { return Math.max(0, Math.min(100, v)); }");
                out.println("  function faUpdateStatusAndGauge() {");
                out.println("    var statusLeftEl = document.getElementById('fa-status-left');");
                out.println("    var statusRightEl = document.getElementById('fa-status-right');");
                out.println("    var spent = Math.max(0, faState.spentMinutes || 0);");
                out.println("    var estimate = Math.max(0, faState.estimateMinutes || 0);");
                out.println("    var overrun = Math.max(0, spent - estimate);");
                out.println("    if (statusLeftEl && statusRightEl) {");
                out.println("      if (overrun > 0) {");
                out.println("        statusLeftEl.innerHTML = 'Over<br>' + faFormatMinutes(overrun);");
                out.println("        statusLeftEl.style.color = '#cb2c1f';");
                out.println("      } else {");
                out.println("        statusLeftEl.innerHTML = 'Actual<br>' + faFormatMinutes(spent);");
                out.println("        statusLeftEl.style.color = '#263226';");
                out.println("      }");
                out.println("      statusRightEl.innerHTML = 'Estimated<br>' + faFormatMinutes(estimate);");
                out.println("    }");
                out.println("    var todayBase = Math.max(1, parseInt(faState.todayTargetMinutes || 0, 10) || (8 * 60));");
                out.println("    var weekBase = Math.max(1, parseInt(faState.weekTargetMinutes || 0, 10) || Math.round(37.5 * 60));");
                out.println("    var todayPct = faClampPct(((Math.max(0, faState.todayBillableMinutes || 0)) / todayBase) * 100);");
                out.println("    var weekPct = faClampPct(((Math.max(0, faState.spentMinutesThisWeek || 0)) / weekBase) * 100);");
                out.println("    var todayBar = document.getElementById('fa-today-bar');");
                out.println("    var weekBar = document.getElementById('fa-week-bar');");
                out.println("    if (todayBar) { todayBar.style.width = todayPct.toFixed(1) + '%'; }");
                out.println("    if (weekBar) { weekBar.style.width = weekPct.toFixed(1) + '%'; }");
                out.println("    faUpdateDayBar();");
                out.println("  }");
                printDayBarScript(out);
                out.println("  function faFormatMinutes(minutes) {");
                out.println("    var safe = Math.max(0, minutes);");
                out.println("    var h = Math.floor(safe / 60);");
                out.println("    var m = safe % 60;");
                out.println("    if (h <= 0) { return m + 'm'; }");
                out.println("    if (m == 0) { return h + 'h'; }");
                out.println("    return h + 'h ' + m + 'm';");
                out.println("  }");
                out.println("  faUpdateStatusAndGauge();");
                out.println("  faUpdateMeetingViews();");
                out.println("  faFitPanels();");
                out.println("  faDrawClock();");
                out.println("  faInitWorkFollowUpSuggestions();");
                out.println("  faNotesInit();");
                out.println("  window.setInterval(function(){ faRefreshClock(); }, 60000);");
                out.println("  faUpdateMeetingCard();");
                out.println("  window.setInterval(faUpdateMeetingCard, 1000);");
                out.println("</script>");
        }

        /**
         * The day bar: a vertical gauge of today's pile of work, measured in hours of work rather than
         * clock time. Top is 0h. Green is time logged today, blue is the estimated work still on today's
         * list, the line is today's target and anything past it is red. The triangle marks where logged
         * work ends. faUpdateDayBar draws it from faState.
         */
        private void renderDayBar(PrintWriter out) {
                out.println("      <div id=\"fa-daybar\" class=\"fa-daybar\" role=\"img\" aria-label=\"Today's work\">");
                out.println("        <div class=\"fa-daybar-track\">");
                out.println("          <div id=\"fa-daybar-ticks\" class=\"fa-daybar-ticks\"></div>");
                out.println("          <div id=\"fa-daybar-logged\" class=\"fa-daybar-seg fa-daybar-logged\"></div>");
                out.println("          <div id=\"fa-daybar-logged-over\" class=\"fa-daybar-seg fa-daybar-logged-over\"></div>");
                out.println("          <div id=\"fa-daybar-left\" class=\"fa-daybar-seg fa-daybar-left\"></div>");
                out.println("          <div id=\"fa-daybar-left-over\" class=\"fa-daybar-seg fa-daybar-left-over\"></div>");
                out.println("          <div id=\"fa-daybar-target\" class=\"fa-daybar-target\"></div>");
                out.println("        </div>");
                out.println("        <div id=\"fa-daybar-target-label\" class=\"fa-daybar-target-label\"></div>");
                out.println("        <div id=\"fa-daybar-marker\" class=\"fa-daybar-marker\"><span id=\"fa-daybar-marker-label\"></span></div>");
                out.println("      </div>");
        }

        private void printDayBarScript(PrintWriter out) {
                out.println("  function faUpdateDayBar() {");
                out.println("    var bar = document.getElementById('fa-daybar'); if (!bar) { return; }");
                out.println("    var logged = Math.max(0, faState.todayBillableMinutes || 0);");
                out.println("    var left = Math.max(0, faState.dayRemainingMinutes || 0);");
                out.println("    var target = Math.max(0, parseInt(faState.todayTargetMinutes || 0, 10) || 0);");
                // A day with no target (vacation, weekend) has no red; the bar just shows the pile.
                out.println("    var hasTarget = target > 1;");
                out.println("    var scale = Math.max(hasTarget ? target : 60, logged + left);");
                out.println("    function pct(m) { return (Math.max(0, m) / scale * 100).toFixed(2) + '%'; }");
                out.println("    function place(id, from, to) { var el = document.getElementById(id); if (!el) { return; } el.style.top = pct(from); el.style.height = pct(Math.max(0, to - from)); el.style.display = to > from ? '' : 'none'; }");
                out.println("    var limit = hasTarget ? target : Infinity;");
                out.println("    place('fa-daybar-logged', 0, Math.min(logged, limit));");
                out.println("    place('fa-daybar-logged-over', Math.min(logged, limit), logged);");
                out.println("    place('fa-daybar-left', logged, Math.max(logged, Math.min(logged + left, limit)));");
                out.println("    place('fa-daybar-left-over', Math.max(logged, Math.min(logged + left, limit)), logged + left);");
                out.println("    var targetEl = document.getElementById('fa-daybar-target');");
                out.println("    var targetLabel = document.getElementById('fa-daybar-target-label');");
                out.println("    if (targetEl) { targetEl.style.display = hasTarget ? '' : 'none'; targetEl.style.top = pct(target); }");
                out.println("    if (targetLabel) { targetLabel.style.display = hasTarget ? '' : 'none'; targetLabel.style.top = pct(target); targetLabel.textContent = faFormatMinutes(target); }");
                out.println("    var ticks = document.getElementById('fa-daybar-ticks');");
                out.println("    if (ticks) { var html = ''; for (var h = 60; h < scale; h += 60) { if (!hasTarget || h !== target) { html += '<div class=\"fa-daybar-tick\" style=\"top:' + pct(h) + '\"></div>'; } } ticks.innerHTML = html; }");
                out.println("    var marker = document.getElementById('fa-daybar-marker');");
                out.println("    if (marker) { marker.style.top = pct(logged); marker.classList.toggle('fa-daybar-marker-over', hasTarget && logged > target); }");
                out.println("    var markerLabel = document.getElementById('fa-daybar-marker-label');");
                out.println("    if (markerLabel) { markerLabel.textContent = faFormatMinutes(logged); }");
                out.println("    var tip = 'Logged ' + faFormatMinutes(logged) + ' \u00b7 Left ' + faFormatMinutes(left)");
                out.println("        + (hasTarget ? ' \u00b7 ' + faFormatMinutes(logged + left) + ' of ' + faFormatMinutes(target) : '');");
                out.println("    if (hasTarget && logged + left > target) { tip += ' \u00b7 ' + faFormatMinutes(logged + left - target) + ' over'; }");
                out.println("    var unestimated = Math.max(0, faState.dayUnestimatedCount || 0);");
                out.println("    if (unestimated > 0) { tip += ' \u00b7 ' + unestimated + (unestimated === 1 ? ' item has' : ' items have') + ' no estimate'; }");
                out.println("    bar.title = tip; bar.setAttribute('aria-label', tip);");
                out.println("  }");
        }

        /**
         * Fixed-position tiles for the actions being juggled. Clicking a tile, or pressing Alt+1..4,
         * switches to it and moves a running clock along with it.
         */
        private void renderSwitchBar(PrintWriter out, List<SwitchSlot> switchSlots, boolean runningClock) {
                out.println("  <nav class=\"fa-switch-bar\" aria-label=\"Switch action\">");
                int position = 0;
                for (SwitchSlot slot : switchSlots) {
                        position++;
                        String key = "<span class=\"fa-switch-key\">" + position + "</span>";
                        if (slot.isEmpty()) {
                                out.println("    <div class=\"fa-switch-tile fa-switch-empty\" data-switch-slot=\"" + position
                                                + "\">" + key + "<span class=\"fa-switch-desc\">Open slot</span></div>");
                                continue;
                        }
                        String classes = "fa-switch-tile"
                                        + (slot.isCurrent() ? " fa-switch-current" : "")
                                        + (slot.isCurrent() && runningClock ? " fa-switch-running" : "")
                                        + (slot.isCompleted() ? " fa-switch-done" : "")
                                        + (slot.getProjectIcon().length() > 0 ? " fa-switch-has-icon" : "");
                        String title = (slot.isCurrent() ? "Current action" : "Alt+" + position + " to switch")
                                        + ": " + slot.getProjectName() + " - " + slot.getDescription();
                        String meta = "<span" + (slot.isCurrent() ? " id=\"fa-switch-current-time\"" : "") + ">"
                                        + formatMinutes(slot.getMinutesToday()) + "</span> today"
                                        + (slot.isCompleted() ? " &middot; &#10003; done" : "");
                        String body = key
                                        + (slot.getProjectIcon().length() > 0
                                                        ? "<span class=\"fa-switch-icon\" aria-hidden=\"true\">"
                                                                        + escapeHtml(slot.getProjectIcon()) + "</span>"
                                                        : "")
                                        + "<span class=\"fa-switch-project\">" + escapeHtml(slot.getProjectName()) + "</span>"
                                        + "<span class=\"fa-switch-desc\">" + escapeHtml(slot.getDescription()) + "</span>"
                                        + "<span class=\"fa-switch-meta\">" + meta + "</span>";
                        if (slot.isCurrent()) {
                                out.println("    <div id=\"fa-switch-current\" class=\"" + classes + "\" data-switch-slot=\""
                                                + position + "\" title=\"" + escapeHtml(title) + "\">" + body + "</div>");
                        } else {
                                out.println("    <a class=\"" + classes + "\" data-switch-slot=\"" + position + "\" href=\""
                                                + escapeHtml(SWITCH_URL_PREFIX) + slot.getActionNextId() + "\" title=\""
                                                + escapeHtml(title) + "\">" + body + "</a>");
                        }
                }
                out.println("  </nav>");
                out.println("<script>");
                out.println("  document.addEventListener('keydown', function(e) {");
                out.println("    if (!e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) { return; }");
                out.println("    var match = /^(?:Digit|Numpad)([1-9])$/.exec(e.code || '');");
                out.println("    if (!match) { return; }");
                out.println("    var tile = document.querySelector('[data-switch-slot=\"' + match[1] + '\"]');");
                out.println("    if (!tile) { return; }");
                out.println("    e.preventDefault();");
                out.println("    if (!tile.href) { return; }");
                out.println("    var el = document.activeElement;");
                out.println("    var typing = el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && el.type === 'text')) && (el.value || '').trim().length > 0;");
                out.println("    if (typing || document.querySelector('.fa-modal-open')) {");
                out.println("      tile.classList.add('fa-switch-blocked'); setTimeout(function() { tile.classList.remove('fa-switch-blocked'); }, 700);");
                out.println("      return;");
                out.println("    }");
                out.println("    window.location.href = tile.href;");
                out.println("  });");
                out.println("</script>");
        }

        /**
         * One-line rows below the work form. Each shows a count and a preview, and opens its drawer
         * over the left column when clicked.
         */
        private void renderContextStrip(PrintWriter out, WebUser webUser, String notes,
                        ActionThreadService.Thread history, MeetingsData meetings, UpNextData upNext,
                        ProjectData projectData) {
                boolean hasNotes = n(notes).trim().length() > 0;
                out.println("        <div class=\"fa-card fa-context-strip\">");
                out.println("          <section class=\"fa-panel fa-panel-notes\">");
                out.println("            <button type=\"button\" class=\"fa-strip-row fa-panel-head\" data-drawer=\"fa-notes-drawer\""
                                + " title=\"Notes (press n)\" onclick=\"faOpenDrawer('fa-notes-drawer', 'fa-notes-text')\">");
                out.println("              <span class=\"fa-strip-label\">Notes</span>");
                out.println("              <span class=\"fa-strip-chevron\">&rsaquo;</span>");
                out.println("            </button>");
                out.println("            <div id=\"fa-notes-peek\" class=\"fa-panel-body fa-notes-peek" + (hasNotes ? "" : " fa-notes-peek-empty")
                                + "\" title=\"Open notes (n)\" onclick=\"faOpenDrawer('fa-notes-drawer', 'fa-notes-text')\">"
                                + (hasNotes ? escapeHtml(n(notes)) : "No notes yet \u00b7 press n") + "</div>");
                out.println("          </section>");
                renderHistoryPanel(out, webUser, history);
                out.println("          <section class=\"fa-panel fa-panel-meetings\">");
                out.println("            <button type=\"button\" class=\"fa-strip-row fa-panel-head\" data-drawer=\"fa-meetings-drawer\""
                                + " onclick=\"faPickerOpen('fa-meetings-drawer')\">");
                out.println("              <span class=\"fa-strip-label\">Meetings <span>"
                                + (meetings.getRows().isEmpty() ? "" : "(" + meetings.getRows().size() + ")") + "</span></span>");
                out.println("              <span class=\"fa-strip-chevron\">&rsaquo;</span>");
                out.println("            </button>");
                out.println("            <div id=\"fa-meetings-list\" class=\"fa-panel-body fa-panel-list\"></div>");
                out.println("          </section>");
                renderUpNextPanel(out, upNext);
                out.println("          <button type=\"button\" class=\"fa-strip-row\" data-drawer=\"fa-project-drawer\""
                                + " onclick=\"faPickerOpen('fa-project-drawer')\">");
                out.println("            <span class=\"fa-strip-label\">This project <span>"
                                + (projectData.getOpenCount() == 0 ? "" : "(" + projectData.getOpenCount() + ")")
                                + "</span></span>");
                out.println("            <span class=\"fa-strip-preview\">"
                                + (projectData.getOpenCount() == 0 ? "<span class=\"fa-strip-faint\">No open actions</span>"
                                                : escapeHtml(projectData.getSummary()))
                                + "</span>");
                out.println("            <span class=\"fa-strip-chevron\">&rsaquo;</span>");
                out.println("          </button>");
                out.println("        </div>");
        }

        /**
         * Up next panel: the queue from the next item on, each one a switch link like the drawer's
         * rows (a running clock moves with it). The page shows as many as fit.
         */
        private void renderUpNextPanel(PrintWriter out, UpNextData upNext) {
                String count;
                String note = "";
                String empty = null;
                if (upNext.getTotal() == 0) {
                        count = "";
                        empty = "Nothing left in today's list";
                } else if (!upNext.isCurrentInQueue()) {
                        count = "(" + upNext.getTotal() + ")";
                        note = " <span class=\"fa-strip-note\">current action is not in today's list</span>";
                } else if (upNext.getNextItem() == null) {
                        count = "(" + upNext.getTotal() + ")";
                        empty = "Current action is last in today's list";
                } else {
                        count = "(" + upNext.getNextPosition() + " of " + upNext.getTotal() + ")";
                }
                out.println("          <section class=\"fa-panel fa-panel-upnext\">");
                out.println("            <button type=\"button\" class=\"fa-strip-row fa-panel-head\" data-drawer=\"fa-upnext-drawer\""
                                + " onclick=\"faPickerOpen('fa-upnext-drawer')\">");
                out.println("              <span class=\"fa-strip-label\">Up next <span>" + count + "</span>" + note + "</span>");
                out.println("              <span class=\"fa-strip-chevron\">&rsaquo;</span>");
                out.println("            </button>");
                out.println("            <div class=\"fa-panel-body fa-panel-list\">");
                if (empty != null) {
                        out.println("              <div class=\"fa-panel-empty\">" + escapeHtml(empty) + "</div>");
                } else {
                        int position = upNext.getNextPosition();
                        int index = 0;
                        for (FocusActionPickerRenderer.Row row : upNext.getUpcoming()) {
                                StringBuilder meta = new StringBuilder(n(row.getWhenLabel()));
                                if (row.getEstimateMinutes() != null) {
                                        if (meta.length() > 0) {
                                                meta.append(" \u00b7 ");
                                        }
                                        meta.append(formatMinutes(row.getEstimateMinutes().intValue()));
                                }
                                out.println("              <div class=\"fa-panel-item\" data-fit-priority=\"" + index + "\">");
                                out.println("                <a class=\"fa-panel-link\" href=\"" + UP_NEXT_SWITCH_URL_PREFIX
                                                + row.getActionNextId() + "\" title=\"Switch to this action\">"
                                                + "<span class=\"fa-panel-when fa-panel-pos\">" + (position + index) + "</span>"
                                                + "<span class=\"fa-panel-icon\" aria-hidden=\"true\">"
                                                + escapeHtml(n(row.getProjectIcon())) + "</span>"
                                                + "<span class=\"fa-panel-main\">"
                                                + (n(row.getProjectName()).length() > 0
                                                                ? "<span class=\"fa-panel-project\">" + escapeHtml(row.getProjectName())
                                                                                + "</span> "
                                                                : "")
                                                + escapeHtml(row.getDescription()) + "</span>"
                                                + "<span class=\"fa-panel-meta\">" + escapeHtml(meta.toString()) + "</span></a>");
                                out.println("              </div>");
                                index++;
                        }
                }
                out.println("            </div>");
                out.println("          </section>");
        }

        private static final String UP_NEXT_SWITCH_URL_PREFIX = "FocusedActionServlet?action=switchAction&amp;completingActionNextId=";

        /**
         * faState.meetings (for the strip row and drawer labels) and faState.meetingArcs (for the
         * clock). refreshFocusClock sends fresh meetingArcs and serverNowEpochMs each minute.
         */
        private void printMeetingState(PrintWriter out, MeetingsData meetings) {
                StringBuilder list = new StringBuilder();
                StringBuilder arcs = new StringBuilder();
                for (FocusActionPickerRenderer.Row row : meetings.getRows()) {
                        boolean timed = row.getStartEpochMs() != null && row.getEndEpochMs() != null;
                        if (list.length() > 0) {
                                list.append(", ");
                        }
                        list.append("{ id: ").append(row.getActionNextId())
                                        .append(", joinUrl: \"").append(escapeJs(n(row.getJoinUrl())))
                                        .append("\", title: \"").append(escapeJs(row.getDescription()))
                                        .append("\", startLabel: \"").append(escapeJs(row.getStartLabel()))
                                        .append("\", endLabel: \"").append(escapeJs(row.getEndLabel()))
                                        .append("\", start: ").append(timed ? String.valueOf(row.getStartEpochMs()) : "null")
                                        .append(", end: ").append(timed ? String.valueOf(row.getEndEpochMs()) : "null").append(" }");
                        if (timed) {
                                if (arcs.length() > 0) {
                                        arcs.append(", ");
                                }
                                arcs.append("{ startEpochMs: ").append(row.getStartEpochMs()).append(", endEpochMs: ")
                                                .append(row.getEndEpochMs()).append(", actionNextId: ")
                                                .append(row.getActionNextId()).append(", title: \"")
                                                .append(escapeJs(row.getDescription())).append("\", startLabel: \"")
                                                .append(escapeJs(row.getStartLabel())).append("\", joinUrl: \"")
                                                .append(escapeJs(n(row.getJoinUrl()))).append("\" }");
                        }
                }
                out.println("  faState.meetings = [" + list + "];");
                out.println("  faState.meetingArcs = [" + arcs + "];");
        }

        /**
         * Notes drawer: the whole notes text in a notepad that saves on Ctrl+Enter, on blur and when
         * the drawer closes, with a one-line quick add underneath that appends.
         */
        /**
         * History panel: the earlier steps of this action's thread, newest first, one line each
         * (what was done, with the outcome note as its tooltip). Shown only when the action
         * continues an earlier one; the History drawer holds the full text.
         */
        private void renderHistoryPanel(PrintWriter out, WebUser webUser, ActionThreadService.Thread history) {
                if (history == null || history.isEmpty()) {
                        return;
                }
                out.println("          <section class=\"fa-panel fa-panel-history\">");
                out.println("            <button type=\"button\" class=\"fa-strip-row fa-panel-head\" data-drawer=\"fa-history-drawer\""
                                + " title=\"Earlier steps of this work\" onclick=\"faOpenDrawer('fa-history-drawer')\">");
                out.println("              <span class=\"fa-strip-label\">History <span>(" + history.getSteps().size()
                                + (history.isMore() ? "+" : "") + ")</span></span>");
                out.println("              <span class=\"fa-strip-chevron\">&rsaquo;</span>");
                out.println("            </button>");
                out.println("            <div class=\"fa-panel-body fa-panel-list\">");
                int index = 0;
                for (ActionThreadService.Step step : history.getSteps()) {
                        String outcome = ActionThreadView.join(step.getOutcomeNotes(), "\n\n");
                        String status = ActionThreadView.statusLabel(step);
                        String tip = outcome.length() > 0 ? outcome : n(step.getNotes());
                        out.println("              <div class=\"fa-panel-item fa-history-item\" data-fit-priority=\"" + index + "\""
                                        + " title=\"" + escapeHtml(tip) + "\" onclick=\"faOpenDrawer('fa-history-drawer')\">");
                        out.println("                <span class=\"fa-panel-when\">"
                                        + escapeHtml(ActionThreadView.dateLabel(webUser, step)) + "</span>"
                                        + "<span class=\"fa-panel-main\">" + escapeHtml(ActionThreadView.headline(step)) + "</span>"
                                        + "<span class=\"fa-panel-meta\">"
                                        + escapeHtml(status.length() > 0 ? status
                                                        : ActionThreadView.minutesLabel(step.getMinutes()))
                                        + "</span>");
                        out.println("              </div>");
                        index++;
                }
                out.println("            </div>");
                out.println("          </section>");
        }

        /** History drawer: each earlier step in full, newest first. */
        private void renderHistoryDrawer(PrintWriter out, WebUser webUser, ActionThreadService.Thread history) {
                if (history == null || history.isEmpty()) {
                        return;
                }
                out.println("      <aside id=\"fa-history-drawer\" class=\"fa-drawer\" aria-label=\"History\" aria-hidden=\"true\">");
                out.println("        <div class=\"fa-drawer-head\">");
                out.println("          <h2 class=\"fa-section-title\">History</h2>");
                out.println("          <button type=\"button\" class=\"fa-modal-close\" title=\"Close (Esc)\" onclick=\"faCloseDrawer()\">&times;</button>");
                out.println("        </div>");
                out.println("        <div class=\"fa-drawer-body fa-history-body\">");
                for (ActionThreadService.Step step : history.getSteps()) {
                        String minutes = ActionThreadView.minutesLabel(step.getMinutes());
                        String status = ActionThreadView.statusLabel(step);
                        out.println("          <div class=\"fa-history-step\">");
                        out.println("            <div class=\"fa-history-when\">"
                                        + escapeHtml(ActionThreadView.dateLabel(webUser, step))
                                        + (minutes.length() > 0 ? " &middot; " + escapeHtml(minutes) : "")
                                        + (status.length() > 0 ? " <span class=\"fa-history-tag\">" + escapeHtml(status) + "</span>" : "")
                                        + "</div>");
                        out.println("            <div class=\"fa-history-head\">" + escapeHtml(ActionThreadView.headline(step)) + "</div>");
                        if (!step.getDone().isEmpty() && n(step.getDescription()).length() > 0) {
                                out.println("            <div class=\"fa-history-planned\">Planned: "
                                                + escapeHtml(step.getDescription()) + "</div>");
                        }
                        String outcome = ActionThreadView.join(step.getOutcomeNotes(), "\n\n");
                        if (outcome.length() > 0) {
                                out.println("            <div class=\"fa-history-text\">" + escapeHtml(outcome) + "</div>");
                        }
                        if (step.getNotes() != null) {
                                out.println("            <div class=\"fa-history-text fa-history-planned\">Notes: "
                                                + escapeHtml(step.getNotes()) + "</div>");
                        }
                        out.println("          </div>");
                }
                if (history.isMore()) {
                        out.println("          <div class=\"fa-history-planned\">Earlier steps not shown.</div>");
                }
                out.println("        </div>");
                out.println("      </aside>");
        }

        private void renderNotesDrawer(PrintWriter out, String notes, int actionNextId) {
                out.println("      <aside id=\"fa-notes-drawer\" class=\"fa-drawer\" aria-label=\"Notes\" aria-hidden=\"true\">");
                out.println("        <div class=\"fa-drawer-head\">");
                out.println("          <h2 class=\"fa-section-title\">Notes</h2>");
                out.println("          <span id=\"fa-notes-status\" class=\"fa-notes-status\" role=\"status\"></span>");
                out.println("          <button type=\"button\" class=\"fa-modal-close\" title=\"Close (Esc)\" onclick=\"faCloseDrawer()\">&times;</button>");
                out.println("        </div>");
                out.println("        <div id=\"fa-notes-drawer-body\" class=\"fa-drawer-body fa-notes-body\">");
                // The newline after the tag keeps a leading newline in the notes: browsers drop the first one.
                out.println("          <textarea id=\"fa-notes-text\" class=\"fa-notes-text\" data-action-next-id=\""
                                + actionNextId + "\" placeholder=\"Notes\" spellcheck=\"true\">");
                out.println(escapeHtml(n(notes)) + "</textarea>");
                out.println("          <div class=\"fa-notes-hint\">Saves when you leave the box or press Ctrl+Enter.</div>");
                out.println("        </div>");
                out.println("        <div class=\"fa-drawer-foot fa-inline-note\">");
                out.println("          <input type=\"text\" id=\"fa-note-input\" placeholder=\"Add a line... (Enter adds)\""
                                + " onkeydown=\"faHandleNoteKeydown(event)\">");
                out.println("          <button type=\"button\" class=\"fa-primary-btn fa-note-submit-btn\" onclick=\"faNotesAppend()\">Add</button>");
                out.println("        </div>");
                out.println("      </aside>");
        }

        /** Meetings drawer foot: the sound played when a meeting starts. */
        private String meetingChimeFooter(String chime) {
                String[][] options = { { "off", "Off" }, { "chime", "Soft chime" }, { "bell", "Single bell" },
                                { "rise", "Rising notes" } };
                StringBuilder html = new StringBuilder();
                html.append("        <div class=\"fa-drawer-foot fa-chime-setting\">\n");
                html.append("          <label for=\"fa-chime-select\">Sound when a meeting starts</label>\n");
                html.append("          <select id=\"fa-chime-select\" onchange=\"faChimeChange(this)\">");
                for (String[] option : options) {
                        html.append("<option value=\"").append(option[0]).append("\"")
                                        .append(option[0].equals(chime) ? " selected" : "").append(">").append(option[1])
                                        .append("</option>");
                }
                html.append("</select>\n");
                html.append("          <button type=\"button\" class=\"fa-chime-preview\" title=\"Play the sound\""
                                + " onclick=\"faChimePreview()\">&#9654;</button>\n");
                html.append("          <span id=\"fa-chime-status\" class=\"fa-chime-status\" role=\"status\"></span>\n");
                html.append("        </div>");
                return html.toString();
        }

        /**
         * Meeting labels and clock arcs. Times are compared with faState.serverNowEpochMs, never the
         * browser clock, and arcs are placed relative to the minute hand, so the browser's time zone
         * does not matter.
         */
        private void printMeetingScript(PrintWriter out) {
                out.println("  function faMeetingState(start, end, now) {");
                out.println("    if (!start || !end) { return 'untimed'; }");
                out.println("    if (end <= now) { return 'ended'; }");
                out.println("    return start <= now ? 'now' : 'upcoming';");
                out.println("  }");
                out.println("  function faFormatIn(ms) { return 'in ' + faFormatMinutes(Math.max(1, Math.ceil(ms / 60000))); }");
                out.println("  // Context panels (Notes, Meetings, Up next) fill the left column; each list shows as many items");
                out.println("  // as fit, dropping the lowest-priority ones (data-fit-priority, higher drops first).");
                out.println("  function faFitPanels() {");
                out.println("    var bodies = document.querySelectorAll('.fa-panel-list');");
                out.println("    for (var b = 0; b < bodies.length; b++) {");
                out.println("      var body = bodies[b]; var items = Array.prototype.slice.call(body.querySelectorAll('.fa-panel-item'));");
                out.println("      for (var i = 0; i < items.length; i++) { items[i].hidden = false; }");
                out.println("      items.sort(function(x, y) { return (parseFloat(y.getAttribute('data-fit-priority')) || 0) - (parseFloat(x.getAttribute('data-fit-priority')) || 0); });");
                out.println("      var k = 0;");
                out.println("      while (k < items.length - 1 && body.scrollHeight > body.clientHeight + 1) { items[k].hidden = true; k++; }");
                out.println("    }");
                out.println("    var peek = document.getElementById('fa-notes-peek');");
                out.println("    if (peek) { peek.classList.toggle('fa-notes-peek-more', peek.scrollHeight > peek.clientHeight + 1); }");
                out.println("  }");
                out.println("  window.addEventListener('resize', faFitPanels);");
                out.println("  function faRenderMeetingsPanel(now) {");
                out.println("    var listEl = document.getElementById('fa-meetings-list'); if (!listEl) { return; }");
                out.println("    var list = faState.meetings || []; var groups = { ended: [], now: [], upcoming: [], untimed: [] };");
                out.println("    for (var i = 0; i < list.length; i++) { groups[faMeetingState(list[i].start, list[i].end, now)].push(list[i]); }");
                out.println("    var ordered = groups.ended.concat(groups.now, groups.upcoming, groups.untimed);");
                out.println("    listEl.textContent = '';");
                out.println("    if (!groups.ended.length && !groups.now.length && !groups.upcoming.length) {");
                out.println("      var empty = document.createElement('div'); empty.className = 'fa-panel-empty';");
                out.println("      empty.textContent = !list.length ? 'No meetings today' : groups.untimed.length ? 'No more timed meetings today' : 'No more meetings today';");
                out.println("      listEl.appendChild(empty);");
                out.println("      if (!groups.untimed.length) { return; }");
                out.println("      ordered = groups.untimed;");
                out.println("    }");
                out.println("    for (var j = 0; j < ordered.length; j++) {");
                out.println("      var m = ordered[j]; var state = faMeetingState(m.start, m.end, now);");
                out.println("      var priority = state === 'now' ? 0 : state === 'upcoming' ? (groups.upcoming.indexOf(m) === 0 ? 1 : 10 + groups.upcoming.indexOf(m))");
                out.println("        : state === 'ended' ? 2 + (groups.ended.length - 1 - groups.ended.indexOf(m)) / 100 : 50 + j;");
                out.println("      var current = m.id === faState.actionNextId;");
                out.println("      var item = document.createElement('div'); item.className = 'fa-panel-item fa-mtg-' + state + (current ? ' fa-panel-current' : '');");
                out.println("      item.setAttribute('data-fit-priority', String(priority));");
                out.println("      var link = document.createElement('a'); link.className = 'fa-panel-link'; link.href = '#';");
                out.println("      link.title = current ? 'Current action' : 'Switch to this meeting';");
                out.println("      (function(id) { link.addEventListener('click', function(e) { e.preventDefault(); if (id !== faState.actionNextId) { faSelectMeeting(id); } }); })(m.id);");
                out.println("      var when = document.createElement('span'); when.className = 'fa-panel-when';");
                out.println("      if (state === 'ended') { var tag = document.createElement('span'); tag.className = 'fa-panel-tag fa-panel-tag-closeout'; tag.textContent = 'Close out'; when.appendChild(tag); }");
                out.println("      else if (state === 'now') { var nowTag = document.createElement('span'); nowTag.className = 'fa-panel-tag fa-panel-tag-now'; nowTag.textContent = 'Now'; when.appendChild(nowTag); }");
                out.println("      when.appendChild(document.createTextNode(state === 'untimed' ? 'no time' : m.startLabel));");
                out.println("      link.appendChild(when);");
                out.println("      var main = document.createElement('span'); main.className = 'fa-panel-main'; main.textContent = m.title; link.appendChild(main);");
                out.println("      var meta = document.createElement('span'); meta.className = 'fa-panel-meta';");
                out.println("      meta.textContent = current ? 'Current' : state === 'upcoming' ? faFormatIn(m.start - now) : state === 'now' ? 'until ' + m.endLabel : state === 'ended' ? 'ended ' + m.endLabel : '';");
                out.println("      link.appendChild(meta);");
                out.println("      item.appendChild(link);");
                out.println("      if (m.joinUrl && /^https?:\\/\\//i.test(m.joinUrl) && (state === 'now' || state === 'upcoming')) {");
                out.println("        var join = document.createElement('a'); join.className = 'fa-picker-join fa-panel-join'; join.href = m.joinUrl; join.target = '_blank'; join.rel = 'noopener noreferrer';");
                out.println("        join.textContent = 'Join'; join.title = 'Open the meeting and start its clock';");
                out.println("        (function(id) { join.addEventListener('click', function() { faJoinMeeting(id); }); })(m.id);");
                out.println("        item.appendChild(join);");
                out.println("      }");
                out.println("      listEl.appendChild(item);");
                out.println("    }");
                out.println("  }");
                out.println("  function faUpdateMeetingViews() {");
                out.println("    var now = faState.serverNowEpochMs || 0; var list = faState.meetings || [];");
                out.println("    faRenderMeetingsPanel(now);");
                out.println("    var rows = document.querySelectorAll('#fa-meetings-drawer .fa-picker-row[data-start]');");
                out.println("    for (var r = 0; r < rows.length; r++) {");
                out.println("      var start = parseInt(rows[r].getAttribute('data-start'), 10); var end = parseInt(rows[r].getAttribute('data-end'), 10);");
                out.println("      var rowState = faMeetingState(start, end, now);");
                out.println("      rows[r].classList.toggle('fa-picker-dim', rowState === 'ended');");
                out.println("      rows[r].classList.toggle('fa-picker-live', rowState === 'now');");
                out.println("      var rel = rows[r].querySelector('.fa-picker-rel'); if (!rel) { continue; }");
                out.println("      rel.textContent = rowState === 'ended' ? 'ended ' + (rows[r].getAttribute('data-end-label') || '') : rowState === 'now' ? 'now' : faFormatIn(start - now);");
                out.println("    }");
                out.println("  }");
                out.println("  function faDrawMeetingArcs(svg, cx, cy, radius, nowMinute) {");
                out.println("    var now = faState.serverNowEpochMs; if (!now) { return; }");
                out.println("    var handMs = now - (now % 60000);");
                out.println("    var limit = (now - handMs) / 60000 + 58;");
                out.println("    var arcs = [];");
                out.println("    var source = faState.meetingArcs || [];");
                out.println("    for (var i = 0; i < source.length; i++) {");
                out.println("      var s = source[i].startEpochMs; var e = source[i].endEpochMs;");
                out.println("      if (!s || !e || e <= now) { continue; }");
                out.println("      var startOffset = (s - handMs) / 60000; var endOffset = (e - handMs) / 60000;");
                out.println("      if (startOffset >= limit) { continue; }");
                out.println("      var live = s <= now;");
                out.println("      var from = live ? 0 : startOffset + 0.5;");
                out.println("      var to = Math.min(endOffset, limit) - 0.5;");
                out.println("      if (to - from < 0.1) { continue; }");
                out.println("      arcs.push({ startOffset: live ? 0 : startOffset, endOffset: endOffset, from: from, to: to, live: live });");
                out.println("    }");
                out.println("    arcs.sort(function(a, b) { return a.startOffset - b.startOffset; });");
                out.println("    var laneEnds = [];");
                out.println("    for (var a = 0; a < arcs.length; a++) {");
                out.println("      var lane = 0;");
                out.println("      while (lane < laneEnds.length && laneEnds[lane] > arcs[a].startOffset) { lane++; }");
                out.println("      laneEnds[lane] = arcs[a].endOffset;");
                out.println("      var r = radius + 31 - Math.min(lane, 1) * 15;");
                out.println("      var p0 = faPolar(cx, cy, r, nowMinute + arcs[a].from);");
                out.println("      var p1 = faPolar(cx, cy, r, nowMinute + arcs[a].to);");
                out.println("      var path = faSvgEl('path');");
                out.println("      path.setAttribute('d', 'M ' + p0.x + ' ' + p0.y + ' A ' + r + ' ' + r + ' 0 ' + ((arcs[a].to - arcs[a].from) > 30 ? 1 : 0) + ' 1 ' + p1.x + ' ' + p1.y);");
                out.println("      path.setAttribute('fill', 'none');");
                out.println("      path.setAttribute('stroke', arcs[a].live ? '#2a4f9e' : '#7d97d4');");
                out.println("      path.setAttribute('stroke-width', '11');");
                out.println("      path.setAttribute('class', 'fa-meeting-arc');");
                out.println("      svg.appendChild(path);");
                out.println("    }");
                out.println("  }");
        }

        private void printStyles(PrintWriter out) {
                out.println("<style>");
                out.println(
                                "  .fa-page { height: 100vh; overflow: hidden; background: linear-gradient(160deg, #f6f8f5 0%, #edf2ec 48%, #f8fbf7 100%); box-sizing: border-box; padding: 8px 10px 10px 10px; position: relative; display: flex; flex-direction: column; }");
                out.println("  .fa-topbar { flex: 0 0 auto; display: flex; align-items: stretch; gap: 10px; margin-bottom: 8px; padding-right: 50px; }");
                out.println("  .fa-switch-bar { flex: 1; min-width: 0; display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }");
                out.println("  .fa-switch-tile { position: relative; display: flex; flex-direction: column; justify-content: center; gap: 1px; min-width: 0; min-height: 58px; box-sizing: border-box; padding: 6px 10px 6px 36px; border: 1px solid #ced8cb; border-radius: 8px; background: #ffffffd4; color: #274127; text-decoration: none; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }");
                out.println("  a.fa-switch-tile:hover { background: #edf5ea; border-color: #7f9b7f; }");
                out.println("  .fa-switch-key { position: absolute; left: 9px; top: 50%; transform: translateY(-50%); width: 20px; height: 20px; box-sizing: border-box; border: 1px solid #9fb09c; border-radius: 4px; background: #f6faf5; color: #4a6350; font-size: 12px; font-weight: 700; line-height: 18px; text-align: center; }");
                out.println("  .fa-switch-project { font-size: 11px; letter-spacing: .03em; text-transform: uppercase; color: #4a6350; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }");
                out.println("  .fa-switch-desc { font-size: 14px; font-weight: 600; color: #1f2f1f; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }");
                out.println("  .fa-switch-meta { font-size: 12px; color: #5b6b58; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }");
                out.println("  .fa-switch-current { border: 2px solid #2a6f2a; background: #e6f1df; padding: 5px 9px 5px 35px; }");
                out.println("  .fa-switch-current .fa-switch-key { background: #2f8c2f; border-color: #2a6f2a; color: #fff; left: 8px; }");
                out.println("  .fa-switch-running .fa-switch-meta::before { content: '\\25CF  timing \\00B7  '; color: #2f8c2f; font-weight: 700; }");
                out.println("  .fa-switch-done .fa-switch-desc { color: #5e6b5c; }");
                out.println("  .fa-switch-empty { border-style: dashed; background: transparent; box-shadow: none; }");
                out.println("  .fa-switch-empty .fa-switch-desc { color: #9aa79a; font-weight: 400; font-size: 13px; }");
                out.println("  .fa-switch-empty .fa-switch-key { color: #9aa79a; border-color: #c9d2c7; background: transparent; }");
                out.println("  .fa-switch-icon { position: absolute; top: 3px; right: 7px; font-size: 20px; line-height: 1; }");
                out.println("  .fa-switch-has-icon .fa-switch-project { padding-right: 22px; }");
                out.println("  .fa-switch-blocked { outline: 2px solid #cb2c1f; outline-offset: 1px; }");
                out.println(
                                "  .fa-close { position: fixed; right: 14px; top: 10px; border: 1px solid #7f8f7f; background: #fff; width: 38px; height: 38px; border-radius: 8px; font-size: 26px; line-height: 30px; cursor: pointer; z-index: 60; }");
                out.println(
                                "  .fa-shell { display: grid; grid-template-columns: minmax(380px, 39%) minmax(520px, 61%); gap: 12px; flex: 1; min-height: 0; }");
                out.println("  .fa-left { min-height: 0; position: relative; overflow: hidden; }");
                out.println("  .fa-drawer { position: absolute; inset: 0; z-index: 30; display: flex; flex-direction: column; background: #fffdf8; border: 1px solid #c8d4c4; border-radius: 10px; box-shadow: 4px 0 18px rgba(0,0,0,0.14); transform: translateX(-104%); visibility: hidden; transition: transform 0.18s ease, visibility 0s linear 0.18s; }");
                out.println("  .fa-drawer.fa-drawer-open { transform: translateX(0); visibility: visible; transition: transform 0.18s ease, visibility 0s; }");
                out.println("  .fa-drawer-head { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; padding: 10px 12px; border-bottom: 1px solid #d8e0d6; }");
                out.println("  .fa-drawer-head .fa-section-title { margin: 0; }");
                out.println("  .fa-drawer-body { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 12px; }");
                out.println("  .fa-history-item { cursor: pointer; }");
                out.println("  .fa-history-step { margin: 0 0 14px; padding-left: 10px; border-left: 2px solid #c8d4c4; font-size: 13.5px; color: #2f3f2f; }");
                out.println("  .fa-history-when { font-size: 12px; font-weight: 600; color: #5b6b58; }");
                out.println("  .fa-history-tag { margin-left: 6px; padding: 0 6px; border-radius: 8px; background: #eee6d4; color: #6b5420; font-size: 11px; }");
                out.println("  .fa-history-head { font-weight: 600; margin-top: 2px; }");
                out.println("  .fa-history-planned { color: #66765f; }");
                out.println("  .fa-history-text { white-space: pre-wrap; overflow-wrap: anywhere; margin-top: 3px; }");
                out.println("  .fa-drawer-foot { flex: 0 0 auto; padding: 10px 12px; border-top: 1px solid #d8e0d6; }");
                out.println("  .fa-chime-setting { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 8px; font-size: 13px; color: #3d553d; }");
                out.println("  .fa-chime-setting select { padding: 4px 6px; border: 1px solid #bfcdbb; border-radius: 6px; font: inherit; background: #fcfffb; }");
                out.println("  .fa-chime-preview { padding: 3px 9px; border: 1px solid #bfcdbb; border-radius: 6px; background: #f4f8f2; color: #2a4f2a; cursor: pointer; font-size: 12px; }");
                out.println("  .fa-chime-status { font-size: 12px; color: #5b6b58; }");
                out.println("  .fa-drawer-empty { color: #8a968a; font-style: italic; }");
                out.println("  .fa-context-strip { padding: 4px; }");
                out.println("  .fa-left-scroll { display: flex; flex-direction: column; }");
                out.println("  .fa-left-scroll > * { flex: 0 0 auto; }");
                out.println("  .fa-left-scroll > .fa-context-strip { flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; margin-bottom: 4px; }");
                out.println("  .fa-panel { flex: 1 1 0; min-height: 92px; display: flex; flex-direction: column; border-bottom: 1px solid #e3e9e1; padding-bottom: 4px; margin-bottom: 2px; }");
                out.println("  .fa-panel-head { flex: 0 0 auto; padding-top: 6px; padding-bottom: 4px; }");
                out.println("  .fa-panel-head .fa-strip-label { flex: 1; }");
                out.println("  .fa-panel-body { flex: 1 1 auto; min-height: 0; overflow: hidden; padding: 0 10px 2px; }");
                out.println("  .fa-notes-peek { position: relative; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.4; color: #2f3f2f; cursor: pointer; }");
                out.println("  .fa-notes-peek-more::after { content: ''; position: absolute; left: 0; right: 0; bottom: 0; height: 2.2em; background: linear-gradient(rgba(255,255,255,0), #fff); pointer-events: none; }");
                out.println("  .fa-notes-peek-empty, .fa-panel-empty { color: #9aa79a; font-style: italic; font-size: 13px; padding: 2px 0; }");
                out.println("  .fa-panel-list { display: flex; flex-direction: column; gap: 1px; }");
                out.println("  .fa-panel-item { flex: 0 0 auto; display: flex; align-items: center; gap: 6px; }");
                out.println("  .fa-panel-item[hidden] { display: none; }");
                out.println("  .fa-panel-link { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 8px; padding: 4px 6px; border-radius: 6px; color: #233423; text-decoration: none; font-size: 13px; }");
                out.println("  .fa-panel-link:hover { background: #edf5ea; }");
                out.println("  .fa-panel-when { flex: 0 0 auto; min-width: 42px; color: #5b6b58; font-size: 12px; font-weight: 600; white-space: nowrap; }");
                out.println("  .fa-panel-pos { min-width: 22px; text-align: right; color: #7f9b7f; }");
                out.println("  .fa-panel-main { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-weight: 600; }");
                out.println("  .fa-panel-icon { flex: 0 0 18px; text-align: center; font-size: 14px; line-height: 1; }");
                out.println("  .fa-panel-project { font-size: 11px; font-weight: 400; letter-spacing: .03em; text-transform: uppercase; color: #4a6350; }");
                out.println("  .fa-panel-meta { flex: 0 0 auto; color: #5b6b58; font-size: 12px; white-space: nowrap; }");
                out.println("  .fa-panel-tag { display: inline-block; margin-right: 5px; padding: 0 5px; border-radius: 4px; font-size: 10px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; }");
                out.println("  .fa-panel-tag-closeout { background: #fbf0d9; color: #7a5a1a; }");
                out.println("  .fa-panel-tag-now { background: #2a4f9e; color: #fff; }");
                out.println("  .fa-mtg-now .fa-panel-link { background: #e8eef9; }");
                out.println("  .fa-mtg-now .fa-panel-main { color: #2a4f9e; }");
                out.println("  .fa-panel-current .fa-panel-meta { color: #2f8c2f; font-weight: 700; }");
                out.println("  .fa-panel-join { margin-bottom: 0; padding: 3px 8px; }");
                out.println("  .fa-strip-row { display: flex; align-items: center; gap: 10px; width: 100%; box-sizing: border-box; padding: 8px 10px; border: 0; border-radius: 7px; background: transparent; color: #233423; font: inherit; font-size: 14px; text-align: left; cursor: pointer; }");
                out.println("  .fa-strip-row:hover, .fa-strip-row:focus-visible { background: #edf5ea; }");
                out.println("  .fa-strip-label { flex: 0 0 auto; min-width: 110px; font-weight: 700; color: #293829; }");
                out.println("  .fa-strip-preview { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: #3d4f3d; }");
                out.println("  .fa-strip-faint { color: #9aa79a; font-style: italic; }");
                out.println("  .fa-strip-wrap { margin-left: 6px; padding: 0 6px; border-radius: 4px; background: #fbf0d9; color: #7a5a1a; font-size: 12px; font-weight: 600; }");
                out.println("  .fa-strip-now { color: #2a4f9e; font-weight: 700; }");
                out.println("  .fa-strip-note { margin-left: 6px; padding: 0 6px; border-radius: 4px; background: #fbf0d9; color: #7a5a1a; font-size: 11px; }");
                out.println("  .fa-strip-chevron { flex: 0 0 auto; color: #7f9b7f; font-size: 20px; line-height: 1; }");
                out.println("  .fa-title-actions { display: flex; gap: 6px; flex-wrap: wrap; }");
                out.println("  .fa-small-btn { border: 1px solid #a9bba6; background: #f4f9f3; color: #3a553a; padding: 3px 9px; border-radius: 6px; font-size: 12px; cursor: pointer; }");
                out.println("  .fa-small-btn:hover { background: #e3eee0; border-color: #7f9b7f; }");
                out.println("  .fa-left-scroll { height: 100%; overflow-y: auto; padding-right: 6px; }");
                out.println(
                                "  .fa-right { min-height: 0; display: flex; flex-direction: column; justify-content: center; align-items: center; position: relative; }");
                out.println("  .fa-clock-wrap { width: min(82vh, calc(100vh - 110px), 760px); max-width: 100%; position: relative; }");
                out.println("  .fa-clock { width: 100%; height: auto; }");
                out.println("  .fa-status-left-layer, .fa-status-right-layer { position: absolute; z-index: 7; font-size: 30px; font-weight: 700; text-shadow: 0 1px 1px rgba(255,255,255,0.55); width: 180px; line-height: 1.1; text-align: center; pointer-events: none; }");
                out.println("  .fa-status-left-layer { left: 15%; top: 16%; transform: translate(-50%, -50%); color: #263226; text-align: left; }");
                out.println("  .fa-status-right-layer { left: 85%; top: 16%; transform: translate(-50%, -50%); color: #2f8c2f; text-align: right; }");
                out.println("  .fa-dandelion-btn { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); width: 106px; height: 106px; border-radius: 50%; border: 3px solid #1f2f1f; background-color: #f0f0f0; z-index: 8; display: flex; align-items: center; justify-content: center; overflow: hidden; }");
                out.println("  .fa-dandelion-btn img { width: 90%; height: 90%; object-fit: contain; display: block; }");
                out.println("  .fa-meeting-card { position: absolute; left: 50%; top: 71%; transform: translate(-50%, -50%); width: min(62%, 430px); box-sizing: border-box; z-index: 12; display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 12px 40px 14px 16px; background: rgba(255,255,255,0.97); border: 2px solid #7d97d4; border-radius: 12px; box-shadow: 0 6px 18px rgba(30,50,90,0.18); color: #233423; }");
                out.println("  .fa-meeting-card[hidden] { display: none; }");
                out.println("  .fa-meeting-card-label { font-size: 12px; font-weight: 700; letter-spacing: .04em; text-transform: uppercase; color: #34466b; }");
                out.println("  .fa-meeting-card-title { max-width: 100%; font-size: 17px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }");
                out.println("  .fa-meeting-card-join { display: inline-block; padding: 9px 20px; border: none; border-radius: 8px; background: #2a4f9e; color: #fff; font: inherit; font-size: 15px; font-weight: 700; text-decoration: none; cursor: pointer; }");
                out.println("  .fa-meeting-card-join:hover { background: #213f80; }");
                out.println("  .fa-meeting-card-close { position: absolute; top: 4px; right: 8px; border: none; background: none; font-size: 24px; line-height: 1; color: #5b6b58; cursor: pointer; }");
                out.println("  .fa-meeting-card-due { border-color: #2a4f9e; background: #eef3fc; animation: faMeetingPulse 1.6s ease-in-out infinite; }");
                out.println("  @keyframes faMeetingPulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(42,79,158,0.5); } 50% { box-shadow: 0 0 0 12px rgba(42,79,158,0); } }");
                out.println("  @media (prefers-reduced-motion: reduce) { .fa-meeting-card-due { animation: none; } }");
                out.println("  .fa-time-widget { position: absolute; left: calc(95% - 36px); top: 89%; transform: translate(-50%, -50%); background: rgba(255,255,255,0.95); border: 2px solid #2a6f2a; border-radius: 12px; padding: 6px 8px; display: grid; grid-template-columns: auto auto 72px; gap: 4px 6px; align-items: center; font-weight: 600; font-size: 12px; z-index: 10; width: 228px; }");
                out.println("  .fa-daybar { position: absolute; right: 4px; top: 16px; bottom: 16px; width: 18px; z-index: 11; cursor: default; }");
                out.println("  .fa-daybar-track { position: absolute; inset: 0; border: 1px solid #b3beb3; border-radius: 9px; background: #eef2ee; overflow: hidden; }");
                out.println("  .fa-daybar-seg { position: absolute; left: 0; right: 0; transition: top 0.3s ease, height 0.3s ease; }");
                out.println("  .fa-daybar-logged { background: linear-gradient(180deg, #2f8c2f 0%, #4ea24e 100%); }");
                out.println("  .fa-daybar-logged-over { background: #a8241a; }");
                out.println("  .fa-daybar-left { background: #8fb3dd; }");
                out.println("  .fa-daybar-left-over { background: repeating-linear-gradient(135deg, #cb2c1f 0 5px, #e2574c 5px 10px); }");
                out.println("  .fa-daybar-ticks { position: absolute; inset: 0; z-index: 2; pointer-events: none; }");
                out.println("  .fa-daybar-tick { position: absolute; left: 0; right: 0; height: 1px; background: rgba(255,255,255,0.75); }");
                out.println("  .fa-daybar-target { position: absolute; left: -1px; right: -1px; height: 3px; margin-top: -1px; background: #1f2f1f; z-index: 3; }");
                out.println("  .fa-daybar-target-label { position: absolute; right: 22px; transform: translateY(-50%); font-size: 11px; font-weight: 700; color: #1f2f1f; white-space: nowrap; }");
                out.println("  .fa-daybar-marker { position: absolute; right: 18px; transform: translateY(-50%); display: flex; align-items: center; gap: 3px; color: #2a6f2a; font-size: 12px; font-weight: 700; white-space: nowrap; transition: top 0.3s ease; pointer-events: none; }");
                out.println("  .fa-daybar-marker::after { content: ''; border-style: solid; border-width: 7px 0 7px 10px; border-color: transparent transparent transparent currentColor; }");
                out.println("  .fa-daybar-marker span { background: rgba(255,255,255,0.9); border-radius: 4px; padding: 0 3px; }");
                out.println("  .fa-daybar-marker-over { color: #a8241a; }");
                out.println("  .fa-time-label { color: #555; text-align: right; margin: 0; }");
                out.println("  .fa-time-value { color: #2f8c2f; font-size: 16px; }");
                out.println("  .fa-time-bar { height: 10px; border-radius: 999px; background: #d8dfd8; overflow: hidden; border: 1px solid #b3beb3; }");
                out.println("  .fa-time-bar-fill { height: 100%; width: 0; background: linear-gradient(90deg, #2f8c2f 0%, #6fb56f 100%); transition: width 0.3s ease; }");
                out.println("  .fa-controls { position: absolute; left: 7%; top: 84%; transform: translate(-50%, -50%); z-index: 10; text-align: left; }");
                out.println(
                                "  .fa-card { background: #ffffffd4; border: 1px solid #ced8cb; border-radius: 10px; padding: 12px; margin-bottom: 10px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }");
                out.println("  .fa-title-card { position: relative; border: 2px solid #2a6f2a; }");
                out.println("  .fa-title-icon { position: absolute; top: 8px; right: 12px; font-size: 56px; line-height: 1; }");
                out.println("  .fa-has-icon .fa-main-title { padding-right: 72px; }");
                out.println("  .fa-main-title { font-size: 36px; font-weight: 700; color: #1f2f1f; line-height: 1.3; margin-bottom: 8px; }");
                out.println("  .fa-main-title i { font-style: italic; }");
                out.println("  .fa-project-subtitle { font-size: 14px; letter-spacing: .02em; text-transform: uppercase; color: #4a6350; }");
                out.println("  .fa-main-title .fa-project-subtitle { margin-left: 10px; vertical-align: baseline; }");
                out.println(
                                "  .fa-secondary-btn { border: 1px solid #7f9b7f; background: #eef6ec; color: #274127; padding: 8px 12px; border-radius: 7px; cursor: pointer; }");
                out.println(
                                "  .fa-danger-btn { border: 1px solid #c49090; background: #f0ebe0; color: #8b2020; padding: 8px 12px; border-radius: 7px; cursor: pointer; }");
                out.println(
                                "  .fa-danger-btn:hover { background: #f5e8e8; border-color: #a06060; }");
                out.println(
                                "  .fa-primary-btn { border: 1px solid #2a6f2a; background: #2f8c2f; color: #fff; padding: 8px 14px; border-radius: 7px; cursor: pointer; font-size: 14px; font-weight: 500; }");
                out.println(
                                "  .fa-action-type-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }");
                out.println(
                                "  .fa-chip { display: inline-block; padding: 4px 10px; background: #eef6ec; border: 1px solid #7f9b7f; border-radius: 3px; color: #274127; font-size: 12px; cursor: pointer; }");
                out.println(
                                "  .fa-chip:hover { background: #dff0db; }");
                out.println(
                                "  .fa-chip-active { background: #2f8c2f; color: #fff; border-color: #2a6f2a; }");
                out.println("  .fa-timer-btn { padding: 16px 32px; border-radius: 10px; font-size: 18px; font-weight: 700; min-width: 140px; }");
                out.println("  .fa-section-title { margin: 0 0 10px 0; font-size: 18px; color: #293829; }");
                out.println("  .fa-notes-body { display: flex; flex-direction: column; overflow: hidden; }");
                out.println("  .fa-notes-text { flex: 1; min-height: 160px; width: 100%; box-sizing: border-box; resize: none; padding: 8px; border: 1px solid #bbb; border-radius: 4px; font-family: inherit; font-size: 14px; line-height: 1.45; background: #fff; }");
                out.println("  .fa-notes-hint { margin-top: 6px; color: #8a968a; font-size: 12px; }");
                out.println("  .fa-notes-status { flex: 1; margin: 0 10px; text-align: right; color: #58734f; font-size: 12px; }");
                out.println("  .fa-notes-status.fa-notes-status-error { color: #a33; }");
                out.println("  .fa-inline-note { display: flex; gap: 8px; }");
                out.println("  .fa-inline-note input { flex: 1; min-width: 0; box-sizing: border-box; padding: 8px; border: 1px solid #bbb; border-radius: 4px; font-family: inherit; }");
                out.println("  .fa-note-submit-btn { width: auto; }");
                out.println("  .fa-work-progress-label { margin-bottom: 6px; color: #324532; font-size: 16px; font-weight: 700; }");
                out.println("  .fa-work-progress-input { width: 100%; box-sizing: border-box; font-size: 18px; padding: 8px 10px; border: 1px solid #cfbea6; background: #fffdf8; }");
                out.println("  .fa-status-row { display: flex; gap: 10px; margin: 10px 0; flex-wrap: wrap; }");
                out.println("  .fa-work-status-toggle input[type='radio'] { position: absolute; opacity: 0; pointer-events: none; }");
                out.println("  .fa-work-status-toggle label { display: inline-block; padding: 6px 10px; border: 1px solid #d5d5d5; background: #efefef; color: #5e5e5e; border-radius: 4px; cursor: pointer; font-weight: bold; user-select: none; }");
                out.println("  .fa-work-status-toggle input[type='radio']:checked + label { border: 2px solid #58734f; background: #e6f1df; color: #23361e; padding: 5px 9px; }");
                out.println("  .fa-next-row { display: flex; align-items: center; gap: 8px; }");
                out.println("  .fa-capture-input-container { position: relative; flex: 1; }");
                out.println("  .fa-capture-suggestions { position: absolute; left: 0; right: 0; top: calc(100% + 2px); z-index: 40; display: none; background: #fffdf8; border: 1px solid #d9ccb8; box-shadow: 0 4px 10px rgba(0, 0, 0, 0.08); max-height: 220px; overflow-y: auto; }");
                out.println("  .fa-capture-suggestions div { padding: 6px 8px; cursor: pointer; }");
                out.println("  .fa-capture-suggestions div:hover { background: #efe7db; }");
                out.println("  .fa-quick-capture-box { flex: 0 0 440px; }");
                out.println("  .fa-empty-clock { color: #7d7d7d; font-size: 20px; }");
                out.println("  .fa-modal-overlay { position: fixed; inset: 0; background: rgba(30, 40, 30, 0.35); display: none; align-items: center; justify-content: center; z-index: 90; }");
                out.println("  .fa-modal-overlay.fa-modal-open { display: flex; }");
                out.println("  .fa-modal { width: min(92vw, 760px); max-height: 84vh; overflow-y: auto; background: #fffdf8; border: 1px solid #c8d4c4; border-radius: 10px; box-shadow: 0 10px 30px rgba(0,0,0,0.22); }");
                out.println("  .fa-reschedule-modal { width: min(92vw, 420px); }");
                out.println("  .fa-modal-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; border-bottom: 1px solid #d8e0d6; }");
                out.println("  .fa-modal-title { margin: 0; font-size: 18px; color: #243624; }");
                out.println("  .fa-modal-close { border: 1px solid #8ca08c; background: #f5faf4; color: #274127; width: 34px; height: 34px; border-radius: 7px; font-size: 24px; line-height: 28px; cursor: pointer; }");
                out.println("  .fa-modal-body { padding: 14px; }");
                out.println("  .fa-modal-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }");
                out.println("  .fa-modal-field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }");
                out.println("  .fa-modal-field label { font-size: 12px; font-weight: 700; color: #314631; letter-spacing: .02em; text-transform: uppercase; }");
                out.println("  .fa-modal-field input, .fa-modal-field textarea { width: 100%; box-sizing: border-box; border: 1px solid #bfcdbb; border-radius: 6px; padding: 8px 9px; font-family: inherit; font-size: 14px; background: #fcfffb; }");
                out.println("  .fa-modal-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 6px; }");
                out.println("  @media (max-width: 900px) {");
                out.println("    .fa-page { height: auto; min-height: 100vh; overflow: visible; }");
                out.println("    .fa-topbar { flex-direction: column; }");
                out.println("    .fa-switch-bar { grid-template-columns: repeat(2, minmax(0, 1fr)); }");
                out.println("    .fa-shell { grid-template-columns: 1fr; }");
                out.println("    .fa-left-scroll { height: auto; max-height: none; }");
                out.println("    .fa-panel { flex: none; }");
                out.println("    .fa-panel-body { max-height: 9.5em; }");
                out.println("    .fa-drawer { position: fixed; inset: 8px; z-index: 80; }");
                out.println("    .fa-right { min-height: 420px; }");
                out.println("    .fa-clock-wrap { width: min(92vw, 620px); }");
                out.println("    .fa-dandelion-btn { width: 78px; height: 78px; }");
                out.println("    .fa-status-left-layer, .fa-status-right-layer { font-size: 18px; width: 120px; }");
                out.println("    .fa-status-left-layer { left: 16%; top: 18%; }");
                out.println("    .fa-status-right-layer { left: 84%; top: 18%; }");
                out.println("    .fa-controls { left: 11%; top: 82%; }");
                out.println("    .fa-time-widget { left: 90%; top: 82%; width: min(52vw, 236px); grid-template-columns: auto auto 1fr; }");
                out.println("    .fa-timer-btn { min-width: 112px; padding: 12px 20px; font-size: 16px; }");
                out.println("    .fa-quick-capture-box { flex: 0 0 auto; position: static; min-width: 0; max-width: none; width: 100%; margin: 0 0 8px 0; }");
                out.println("    .fa-modal-row { grid-template-columns: 1fr; }");
                out.println("  }");
                pickerRenderer.printStyles(out);
                out.println("</style>");
        }

        private String escapeHtml(String value) {
                if (value == null || value.length() == 0) {
                        return "";
                }
                StringBuilder sb = new StringBuilder(value.length() + 16);
                for (int i = 0; i < value.length(); i++) {
                        char ch = value.charAt(i);
                        if (ch == '&') {
                                sb.append("&amp;");
                        } else if (ch == '<') {
                                sb.append("&lt;");
                        } else if (ch == '>') {
                                sb.append("&gt;");
                        } else if (ch == '\"') {
                                sb.append("&quot;");
                        } else if (ch == '\'') {
                                sb.append("&#39;");
                        } else {
                                sb.append(ch);
                        }
                }
                return sb.toString();
        }

        private String escapeJs(String value) {
                if (value == null || value.length() == 0) {
                        return "";
                }
                return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r")
                                .replace("\n", "\\n").replace("</", "<\\/");
        }

        private String n(String value) {
                return value == null ? "" : value;
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
}
