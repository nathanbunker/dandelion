package org.dandeliondaily.shared.render;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.SoftwareVersion;
import org.openimmunizationsoftware.pt.model.ProjectContact;

/**
 * The one Edit Action box, used by every page. Prints the markup and loads
 * {@code css/action-editor.css} and {@code js/action-editor.js}, which own the styles and
 * behavior. A page then calls {@code ActionEditor.configure({onSaved, onCompleted, onDeleted})}
 * for its after-save handling and {@code ActionEditor.open(actionNextId)} to edit an action.
 */
public class ActionEditorRenderer {

        /** Verb menu, in order: action type and the words that follow "I". The script reads these. */
        private static final String[][] VERBS = {
                        { "WILL", "will" },
                        { "MIGHT", "might" },
                        { "WOULD_LIKE_TO", "would like to" },
                        { "WILL_CONTACT", "will contact" },
                        { "WILL_MEET", "will meet" },
                        { "REVIEW", "will review" },
                        { "DOCUMENT", "will document" },
                        { "WILL_FOLLOW_UP", "will follow up" },
                        { "COMMITTED_TO", "have committed to" },
                        { "GOAL", "have set a goal to" },
                        { "WAITING", "am waiting for" } };

        private static final int[][] ESTIMATES = { { 5, 0 }, { 15, 0 }, { 30, 0 }, { 60, 1 }, { 120, 2 } };

        public void render(PrintWriter out, AppReq appReq) {
                render(out, appReq, 0, null);
        }

        /**
         * @param openActionNextId when not zero, the box opens on this action as soon as the page
         *                         loads (after a Quick Capture "Edit Action" save)
         * @param reloadUrl        the plain GET address the page switches to when it opens that
         *                         way, so a browser reload can't repeat the capture POST
         */
        public void render(PrintWriter out, AppReq appReq, int openActionNextId, String reloadUrl) {
                renderMarkup(out, appReq.getWebUser().getLocalDateToday(), loadContacts(appReq), openActionNextId,
                                reloadUrl);
        }

        /** Everything but the database reads, so it can be tested directly. */
        void renderMarkup(PrintWriter out, LocalDate today, List<ProjectContact> contacts, int openActionNextId,
                        String reloadUrl) {
                String version = escapeHtml(SoftwareVersion.VERSION);
                out.println("<link rel=\"stylesheet\" type=\"text/css\" href=\"css/action-editor.css?v=" + version + "\" />");
                out.println("<div id=\"aeOverlay\" class=\"ae-overlay\" hidden data-today=\""
                                + today + "\""
                                + (openActionNextId > 0 ? " data-open-action=\"" + openActionNextId + "\"" : "")
                                + (reloadUrl == null ? "" : " data-reload-url=\"" + escapeHtml(reloadUrl) + "\"") + ">");
                out.println("  <div class=\"ae-modal\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"aeTitle\">");
                out.println("    <div class=\"ae-head\">");
                out.println("      <div><h3 id=\"aeTitle\" class=\"ae-title\">Edit Action</h3>"
                                + "<div id=\"aeSubtitle\" class=\"ae-subtitle\"></div></div>");
                out.println("      <button type=\"button\" id=\"aeClose\" class=\"ae-close\" title=\"Close (Esc)\">&times;</button>");
                out.println("    </div>");
                out.println("    <div class=\"ae-body\">");
                out.println("      <div id=\"aeError\" class=\"ae-error\" role=\"alert\" hidden></div>");

                out.println("      <div class=\"ae-sentence\">");
                out.println("        <span class=\"ae-i\">I</span>");
                out.println("        <div id=\"aeVerbWrap\" class=\"ae-verb-wrap\">");
                out.println("          <button type=\"button\" id=\"aeVerbBtn\" class=\"ae-verb\" aria-haspopup=\"true\""
                                + " aria-expanded=\"false\" title=\"Change the verb\">will <span class=\"ae-verb-caret\">&#9662;</span></button>");
                out.println("          <div id=\"aeVerbMenu\" class=\"ae-verb-menu\" role=\"menu\" hidden>");
                for (String[] verb : VERBS) {
                        out.println("            <button type=\"button\" class=\"ae-verb-option\" role=\"menuitem\" data-type=\""
                                        + verb[0] + "\">" + escapeHtml(verb[1]) + "</button>");
                }
                out.println("          </div>");
                out.println("        </div>");
                out.println("        <textarea id=\"aeDescription\" rows=\"2\" aria-label=\"Description\""
                                + " placeholder=\"what needs to happen\"></textarea>");
                out.println("      </div>");

                out.println("      <div id=\"aeSlotField\" class=\"ae-field\" hidden>");
                out.println("        <label class=\"ae-label\" for=\"aeTimeSlot\">Time of day</label>");
                out.println("        <select id=\"aeTimeSlot\">");
                out.println("          <option value=\"WAKE\">Wake</option>");
                out.println("          <option value=\"MORNING\">Morning</option>");
                out.println("          <option value=\"AFTERNOON\">Afternoon</option>");
                out.println("          <option value=\"EVENING\">Evening</option>");
                out.println("        </select>");
                out.println("      </div>");

                out.println("      <div class=\"ae-field\">");
                out.println("        <label class=\"ae-label\" for=\"aeDate\">When</label>");
                out.println("        <div class=\"ae-date-row\"><input type=\"date\" id=\"aeDate\" />"
                                + "<span id=\"aeDateHint\" class=\"ae-date-hint\"></span></div>");
                out.println("        <div id=\"aeDateChips\" class=\"ae-chips\"></div>");
                out.println("      </div>");

                out.println("      <div class=\"ae-grid2\">");
                out.println("        <div id=\"aeEstimateField\" class=\"ae-field\">");
                out.println("          <label class=\"ae-label\" for=\"aeEstimate\">Estimate</label>");
                out.println("          <div class=\"ae-estimate-row\"><input type=\"number\" id=\"aeEstimate\" min=\"0\" step=\"5\" />"
                                + "<span class=\"ae-unit\">min</span></div>");
                out.print("          <div id=\"aeEstimateChips\" class=\"ae-chips\">");
                for (int[] estimate : ESTIMATES) {
                        String label = estimate[1] > 0 ? estimate[1] + "h" : String.valueOf(estimate[0]);
                        out.print("<button type=\"button\" class=\"ae-chip\" data-minutes=\"" + estimate[0] + "\">" + label
                                        + "</button>");
                }
                out.println("</div>");
                out.println("        </div>");
                out.println("        <div id=\"aeWhoField\" class=\"ae-field\">");
                out.println("          <label class=\"ae-label\" for=\"aeContact\">Who</label>");
                out.println("          <select id=\"aeContact\">");
                out.println("            <option value=\"\">none</option>");
                for (ProjectContact contact : contacts) {
                        out.println("            <option value=\"" + contact.getContactId() + "\">"
                                        + escapeHtml(contact.getName()) + "</option>");
                }
                out.println("          </select>");
                out.println("        </div>");
                out.println("      </div>");

                out.println("      <div class=\"ae-field\">");
                out.println("        <label class=\"ae-label\" for=\"aeLink\">Link</label>");
                out.println("        <div class=\"ae-link-row\"><input type=\"url\" id=\"aeLink\" placeholder=\"https://\" />"
                                + "<a id=\"aeLinkOpen\" class=\"ae-link-open\" target=\"_blank\" rel=\"noopener\" hidden>Open &#8599;</a></div>");
                out.println("      </div>");

                out.println("      <div class=\"ae-field\">");
                out.println("        <label class=\"ae-label\" for=\"aeNote\">Note</label>");
                out.println("        <textarea id=\"aeNote\" rows=\"3\" placeholder=\"Notes\"></textarea>");
                out.println("      </div>");

                out.println("      <div class=\"ae-grid2\">");
                out.println("        <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeTarget\">Target date</label>"
                                + "<input type=\"date\" id=\"aeTarget\" /></div>");
                out.println("        <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeDeadline\">Deadline</label>"
                                + "<input type=\"date\" id=\"aeDeadline\" /></div>");
                out.println("      </div>");

                out.println("      <div id=\"aeCompleteSection\" class=\"ae-complete\" hidden>");
                out.println("        <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeCompleteDescription\">What was done (short)</label>"
                                + "<input type=\"text\" id=\"aeCompleteDescription\" maxlength=\"500\""
                                + " placeholder=\"e.g. Finished Small Group support\" /></div>");
                out.println("        <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeCompleteNote\">Outcome note (optional)</label>"
                                + "<textarea id=\"aeCompleteNote\" rows=\"3\""
                                + " placeholder=\"What happened, decisions, where it was left\"></textarea></div>");
                out.println("        <div class=\"ae-complete-row\">");
                out.println("          <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeCompleteDate\">Date</label>"
                                + "<input type=\"date\" id=\"aeCompleteDate\" /></div>");
                out.println("          <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeCompleteTime\">Start time</label>"
                                + "<input type=\"time\" id=\"aeCompleteTime\" /></div>");
                out.println("          <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeCompleteDuration\">Minutes spent</label>"
                                + "<input type=\"number\" id=\"aeCompleteDuration\" min=\"0\" step=\"1\" /></div>");
                out.println("        </div>");
                out.println("        <div class=\"ae-field\"><label class=\"ae-label\" for=\"aeFollowUp\">Next action (optional)</label>"
                                + "<input type=\"text\" id=\"aeFollowUp\" placeholder=\"e.g. send Chris the summary tomorrow\" /></div>");
                out.println("        <div class=\"ae-field\" id=\"aeFollowUpNotesField\" hidden><label class=\"ae-label\" for=\"aeFollowUpNotes\">"
                                + "Notes for the next action</label>"
                                + "<textarea id=\"aeFollowUpNotes\" rows=\"2\" placeholder=\"What to know before starting\"></textarea></div>");
                out.println("        <div class=\"ae-field\"><button type=\"button\" id=\"aeCompleteSubmit\" class=\"ae-btn ae-btn-primary\">"
                                + "Mark complete</button></div>");
                out.println("      </div>");
                out.println("    </div>");

                out.println("    <div class=\"ae-foot\">");
                out.println("      <button type=\"button\" id=\"aeSave\" class=\"ae-btn ae-btn-primary\" title=\"Ctrl+Enter\">Save</button>");
                out.println("      <button type=\"button\" id=\"aeSaveStart\" class=\"ae-btn\">Save and Start</button>");
                out.println("      <button type=\"button\" id=\"aeCompleteToggle\" class=\"ae-btn\">Complete&hellip;</button>");
                out.println("      <button type=\"button\" id=\"aeCancel\" class=\"ae-btn\">Cancel</button>");
                out.println("      <span class=\"ae-spacer\"></span>");
                out.println("      <button type=\"button\" id=\"aeDelete\" class=\"ae-btn ae-btn-danger\">Delete</button>");
                out.println("      <div class=\"ae-keys\">Ctrl+Enter saves &middot; Esc closes &middot; type \"might \" or"
                                + " \"am waiting for \" at the start to change the verb</div>");
                out.println("    </div>");
                out.println("  </div>");
                out.println("</div>");
                out.println("<script src=\"js/action-editor.js?v=" + version + "\"></script>");
        }

        /** The workspace's contacts other than the user, for Who. */
        private List<ProjectContact> loadContacts(AppReq appReq) {
                Session dataSession = appReq.getDataSession();
                int currentContactId = appReq.getWebUser().getProjectContact().getContactId();
                Query contactQuery = dataSession.createQuery(
                                "from ProjectContact where workspaceId = :workspaceId order by nameLast, nameFirst");
                contactQuery.setParameter("workspaceId", appReq.getActiveWorkspaceId());
                @SuppressWarnings("unchecked")
                List<ProjectContact> contacts = (List<ProjectContact>) contactQuery.list();
                List<ProjectContact> others = new ArrayList<ProjectContact>();
                for (ProjectContact contact : contacts) {
                        if (contact.getContactId() != currentContactId) {
                                others.add(contact);
                        }
                }
                return others;
        }

        private String escapeHtml(String value) {
                if (value == null) {
                        return "";
                }
                return value.replace("&", "&amp;")
                                .replace("<", "&lt;")
                                .replace(">", "&gt;")
                                .replace("\"", "&quot;")
                                .replace("'", "&#39;");
        }
}
