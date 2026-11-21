package org.dandeliondaily.focus.render;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The working-session controls on the Focused Action title card (every layout): Folder (primary,
 * plus related folders), Terminal, Start with the default agent and a chooser for the other agent
 * and blank New sessions, Resume with agent and age, and Close out. The launcher controls are
 * rendered hidden and shown by script only on a Windows desktop browser that opted in (Agent
 * settings); they ask the server for a one-time {@code dandelion:} link, which carries only ids.
 * Close out is ordinary Dandelion and always shown. Nothing here appears on the phone (Sundial).
 */
public class FocusLauncherRenderer {

    /** What the controls need for one action. */
    public static class LauncherData {
        public int actionNextId;
        public boolean hasPrimaryFolder;
        public String primaryFolderLabel = "";
        /** {folderId, label} in stored order. */
        public final List<String[]> relatedFolders = new ArrayList<String[]>();
        public String defaultAgentKey;
        /** {agentKey, displayName, canStart ("Y"/"N"), canNew} for active agents. */
        public final List<String[]> agents = new ArrayList<String[]>();
        /** The action's session link (ActionSessionService.toMap), or null. */
        public Map<String, Object> session;
        public String performedByName;
        /** AgentRunService.describeLastRun, or null. */
        public Map<String, Object> lastRun;
    }

    public void renderButtons(PrintWriter out, LauncherData data) {
        if (data == null) {
            return;
        }
        String noFolder = "Set a primary folder on the project first.";
        out.println("            <span class=\"fa-launch\" hidden>");
        // Folder, with related folders in a menu.
        if (data.relatedFolders.isEmpty()) {
            out.println("              " + button("Folder", "faLaunch('folder')", data.hasPrimaryFolder,
                    data.hasPrimaryFolder ? "Open " + data.primaryFolderLabel : noFolder, false));
        } else {
            out.println("              <span class=\"fa-launch-menu\">");
            out.println("                " + button("Folder", "faLaunch('folder')", data.hasPrimaryFolder,
                    data.hasPrimaryFolder ? "Open " + data.primaryFolderLabel : noFolder, false)
                    + "<button type=\"button\" class=\"fa-small-btn fa-launch-caret\" title=\"Related folders\""
                    + " onclick=\"faLaunchMenu(this)\">&#9662;</button>");
            out.println("                <span class=\"fa-launch-list\" hidden>");
            for (String[] folder : data.relatedFolders) {
                out.println("                  <button type=\"button\" class=\"fa-launch-item\" onclick=\"faLaunch('folder', null, "
                        + Integer.parseInt(folder[0]) + ")\">" + esc(folder[1]) + "</button>");
            }
            out.println("                </span>");
            out.println("              </span>");
        }
        out.println("              " + button("Terminal", "faLaunch('terminal')", data.hasPrimaryFolder,
                data.hasPrimaryFolder ? "Windows Terminal in the project folder" : noFolder, false));

        // Start with the default agent; the chooser holds the other agent and blank New sessions.
        String defaultName = name(data, data.defaultAgentKey);
        out.println("              <span class=\"fa-launch-menu\">");
        out.println("                " + button("Start " + defaultName,
                "faLaunch('start', '" + jsKey(data.defaultAgentKey) + "')", data.hasPrimaryFolder,
                data.hasPrimaryFolder ? "New " + defaultName + " session that reads this action, its notes and earlier steps"
                        : noFolder, false)
                + "<button type=\"button\" class=\"fa-small-btn fa-launch-caret\" title=\"Other agents and blank sessions\""
                + " onclick=\"faLaunchMenu(this)\"" + (data.hasPrimaryFolder ? "" : " disabled") + ">&#9662;</button>");
        out.println("                <span class=\"fa-launch-list\" hidden>");
        for (String[] agent : data.agents) {
            if (!agent[0].equals(data.defaultAgentKey) && "Y".equals(agent[2])) {
                out.println("                  <button type=\"button\" class=\"fa-launch-item\" onclick=\"faLaunch('start', '"
                        + jsKey(agent[0]) + "')\">Start " + esc(name(data, agent[0])) + " on this action</button>");
            }
        }
        for (String[] agent : data.agents) {
            if ("Y".equals(agent[3])) {
                out.println("                  <button type=\"button\" class=\"fa-launch-item\" onclick=\"faLaunch('new', '"
                        + jsKey(agent[0]) + "')\">New " + esc(name(data, agent[0])) + " (blank)</button>");
            }
        }
        out.println("                </span>");
        out.println("              </span>");

        // Resume: under 30 days; prominent on a review action an agent handed over.
        if (data.session != null && Boolean.TRUE.equals(data.session.get("resumable"))) {
            String agentKey = String.valueOf(data.session.get("agentKey"));
            String source = String.valueOf(data.session.get("source"));
            boolean review = "HANDOFF".equals(source) || "RUNNER".equals(source);
            Object age = data.session.get("ageDays");
            String state = String.valueOf(data.session.get("state"));
            String label = "Resume " + name(data, agentKey) + " &middot; " + age + "d";
            String title = "Reopen the " + name(data, agentKey) + " session in the folder it ran in"
                    + ("BAILED".equals(state) ? " (bailed)" : "PARKED".equals(state) ? " (parked)" : "");
            out.println("              <button type=\"button\" class=\"" + (review ? "fa-primary-btn fa-resume-main" : "fa-small-btn")
                    + "\" title=\"" + esc(title) + "\" onclick=\"faLaunch('resume')\">" + label + "</button>");
        }
        out.println("            </span>");
        out.println("            <a class=\"fa-small-btn fa-closeout-btn\" href=\"CloseOutServlet?actionNextId="
                + data.actionNextId + "\" title=\"Complete this action, set up what comes next, and tidy the project's other actions\">Close out</a>");
    }

    /** "Performed by Claude Code" and the last run's state, under the title. */
    public void renderAgentState(PrintWriter out, LauncherData data) {
        if (data == null || (data.performedByName == null && data.lastRun == null)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (data.performedByName != null) {
            sb.append("Performed by ").append(esc(data.performedByName));
        }
        if (data.lastRun != null) {
            String status = String.valueOf(data.lastRun.get("status"));
            if (sb.length() > 0) {
                sb.append(" &middot; ");
            }
            Object finished = data.lastRun.get("finishedAt");
            if ("FAILED".equals(status) || "EXPIRED".equals(status)) {
                sb.append("<span class=\"fa-agent-failed\">Agent run ")
                        .append("EXPIRED".equals(status) ? "expired" : "failed")
                        .append(finished == null ? "" : " " + esc(String.valueOf(finished))).append("</span>");
                Object error = data.lastRun.get("error");
                if (error != null) {
                    sb.append(": ").append(esc(String.valueOf(error)));
                }
                if (Boolean.TRUE.equals(data.lastRun.get("retryable"))) {
                    sb.append(" <button type=\"button\" class=\"fa-small-btn\" onclick=\"faRetryAgentRun(")
                            .append(data.actionNextId).append(")\">Retry</button>");
                } else if (Boolean.FALSE.equals(data.lastRun.get("sourceOpen"))) {
                    sb.append(" (the agent action is closed, so there is nothing to retry)");
                } else if (Boolean.TRUE.equals(data.lastRun.get("retryRequested"))) {
                    sb.append(" (retry requested; the runner picks it up on its next poll)");
                }
            } else if ("SUCCEEDED".equals(status)) {
                sb.append("Agent run done").append(finished == null ? "" : " " + esc(String.valueOf(finished)));
            } else {
                sb.append("Agent run in progress");
            }
        }
        out.println("          <div class=\"fa-agent-state\">" + sb + "</div>");
    }

    public void renderScript(PrintWriter out, LauncherData data) {
        if (data == null) {
            return;
        }
        out.println("<script>");
        out.println("(function () {");
        out.println("  var ua = navigator.userAgent || '';");
        out.println("  var desktopWindows = /Windows/.test(ua) && !/Mobi|Android|iPhone|iPad/.test(ua);");
        out.println("  var optedIn = false;");
        out.println("  try { optedIn = localStorage.getItem('dandelion.launcher.optIn') === '1'; } catch (e) { optedIn = false; }");
        out.println("  if (desktopWindows && optedIn) {");
        out.println("    var els = document.querySelectorAll('.fa-launch'); for (var i = 0; i < els.length; i++) { els[i].hidden = false; }");
        out.println("  }");
        out.println("})();");
        out.println("function faLaunchMenu(btn) {");
        out.println("  var list = btn.parentNode.querySelector('.fa-launch-list'); if (list) { list.hidden = !list.hidden; }");
        out.println("}");
        out.println("function faPostJson(params) {");
        out.println("  var body = new URLSearchParams(); Object.keys(params).forEach(function (k) { if (params[k] != null) { body.append(k, String(params[k])); } });");
        out.println("  return fetch('FocusedActionServlet', { method: 'POST', credentials: 'same-origin',");
        out.println("    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Dandelion-Launch': '1' }, body: body.toString() })");
        out.println("    .then(function (r) { return r.json(); });");
        out.println("}");
        out.println("function faLaunch(verb, agentKey, folderId) {");
        out.println("  faPostJson({ action: 'issueLaunch', actionNextId: " + data.actionNextId
                + ", verb: verb, agentKey: agentKey || null, folderId: folderId || null })");
        out.println("    .then(function (res) {");
        out.println("      if (res && res.success && res.uri) { window.location.href = res.uri; }");
        out.println("      else { alert((res && res.message) || 'Could not open that.'); }");
        out.println("    }).catch(function () { alert('Could not reach Dandelion.'); });");
        out.println("}");
        out.println("function faRetryAgentRun(id) {");
        out.println("  faPostJson({ action: 'retryAgentRun', actionNextId: id }).then(function (res) {");
        out.println("    if (res && res.success) { window.location.reload(); } else { alert((res && res.message) || 'Could not retry.'); }");
        out.println("  });");
        out.println("}");
        out.println("</script>");
    }

    public void printStyles(PrintWriter out) {
        out.println("  .fa-launch { display: inline; }");
        out.println("  .fa-launch[hidden] { display: none; }");
        out.println("  .fa-launch-menu { position: relative; display: inline-block; white-space: nowrap; }");
        out.println("  .fa-launch-caret { padding: 3px 5px; margin-left: -2px; }");
        out.println("  .fa-launch-list { position: absolute; z-index: 30; top: 100%; left: 0; min-width: 190px; background: #fff;"
                + " border: 1px solid #a9bba6; border-radius: 6px; box-shadow: 0 4px 12px rgba(0,0,0,.12); padding: 4px; }");
        out.println("  .fa-launch-list[hidden] { display: none; }");
        out.println("  .fa-launch-item { display: block; width: 100%; text-align: left; border: 0; background: none; padding: 5px 8px;"
                + " font-size: 12.5px; cursor: pointer; border-radius: 4px; }");
        out.println("  .fa-launch-item:hover { background: #e3eee0; }");
        out.println("  .fa-small-btn[disabled] { opacity: .5; cursor: not-allowed; }");
        out.println("  .fa-resume-main { padding: 3px 12px; font-size: 12.5px; }");
        out.println("  a.fa-closeout-btn { text-decoration: none; display: inline-block; white-space: nowrap; }");
        out.println("  .fa-agent-state { margin-top: 6px; font-size: 12.5px; color: #4a5d47; }");
        out.println("  .fa-agent-failed { color: #9b2c1f; font-weight: 600; }");
    }

    private static String button(String label, String onclick, boolean enabled, String title, boolean primary) {
        return "<button type=\"button\" class=\"" + (primary ? "fa-primary-btn" : "fa-small-btn") + "\" title=\""
                + esc(title) + "\"" + (enabled ? " onclick=\"" + onclick + "\"" : " disabled") + ">" + esc(label)
                + "</button>";
    }

    private static String name(LauncherData data, String agentKey) {
        for (String[] agent : data.agents) {
            if (agent[0].equals(agentKey)) {
                return "claude-code".equals(agentKey) ? "Claude" : agent[1];
            }
        }
        return agentKey == null ? "" : agentKey;
    }

    /** Agent keys are [a-z0-9-]; anything else is dropped before it reaches script. */
    private static String jsKey(String key) {
        return key == null ? "" : key.replaceAll("[^a-z0-9-]", "");
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
