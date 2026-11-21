package org.dandeliondaily.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.personal.service.DeviceService;
import org.dandeliondaily.sessions.service.AgentCatalogService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.Agent;
import org.openimmunizationsoftware.pt.model.WebApiClient;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

/**
 * Agent preferences and the PC's keys:
 * <ul>
 * <li>the default agent for Start and New, and an optional model per agent (unset means no model
 * flag at all);</li>
 * <li>whether this browser shows the launcher buttons (kept in this browser only: they appear on a
 * Windows desktop browser that opted in here);</li>
 * <li>the PC's LAUNCHER key (link handler and session commands) and RUNNER key (unattended runs),
 * each shown once, stored hashed and revocable on its own.</li>
 * </ul>
 */
public class AgentSettingsServlet extends ClientServlet {

    private static final long serialVersionUID = 1L;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AgentCatalogService catalog = new AgentCatalogService();

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
            String action = n(request.getParameter("action"));
            String message = null;
            String problem = null;
            String newKey = null;
            String newKeyType = null;
            boolean post = "POST".equalsIgnoreCase(request.getMethod());
            if (post && "savePreferences".equals(action)) {
                Map<String, String> models = new LinkedHashMap<String, String>();
                for (Agent agent : catalog.listActive(session)) {
                    String value = request.getParameter("model_" + agent.getAgentKey());
                    if (value != null) {
                        models.put(agent.getAgentKey(), value);
                    }
                }
                try {
                    catalog.savePreferences(session, webUser, request.getParameter("defaultAgent"), models);
                    message = "Agent preferences saved.";
                } catch (IllegalArgumentException invalid) {
                    problem = invalid.getMessage();
                }
            } else if (post && "saveGmailProjects".equals(action)) {
                StringBuilder ids = new StringBuilder();
                for (String part : n(request.getParameter("gmailTaskProjects")).split("[,\\s]+")) {
                    if (part.matches("[1-9][0-9]{0,9}")) {
                        org.openimmunizationsoftware.pt.model.Project project = (org.openimmunizationsoftware.pt.model.Project)
                                session.get(org.openimmunizationsoftware.pt.model.Project.class, Integer.valueOf(part));
                        if (project != null && project.getWebUser() != null
                                && project.getWebUser().getWebUserId() == webUser.getWebUserId()) {
                            ids.append(ids.length() == 0 ? "" : ",").append(part);
                        } else {
                            problem = "Project #" + part + " isn't one of your projects.";
                        }
                    }
                }
                if (problem == null) {
                    org.openimmunizationsoftware.pt.manager.TrackerKeysManager.saveKeyValue(
                            org.dandeliondaily.sessions.service.RunScopedReadsService.KEY_GMAIL_TASK_PROJECTS, webUser,
                            ids.toString(), session);
                    message = "Gmail workload projects saved.";
                }
            } else if (post && "createKey".equals(action)) {
                String type = request.getParameter("clientType");
                if (WebApiClient.TYPE_LAUNCHER.equals(type) || WebApiClient.TYPE_RUNNER.equals(type)) {
                    newKeyType = type;
                    newKey = createKey(session, appReq, webUser, type);
                } else {
                    problem = "Unknown key type.";
                }
            } else if (post && "revokeKey".equals(action)) {
                try {
                    int clientId = Integer.parseInt(n(request.getParameter("clientId")));
                    WebApiClient client = (WebApiClient) session.get(WebApiClient.class, Integer.valueOf(clientId));
                    if (client != null && client.getWebUser() != null
                            && client.getWebUser().getWebUserId() == webUser.getWebUserId()
                            && (WebApiClient.TYPE_LAUNCHER.equals(client.getClientType())
                                    || WebApiClient.TYPE_RUNNER.equals(client.getClientType()))) {
                        Transaction tx = session.beginTransaction();
                        client.setEnabled(false);
                        client.setRevokedDate(new Date());
                        session.update(client);
                        tx.commit();
                        message = "Key revoked. That PC client stops working on its next request.";
                    }
                } catch (NumberFormatException ignored) {
                    problem = "Unknown key.";
                }
            }

            appReq.setTitle("Agent settings");
            printHtmlHead(appReq);
            PrintWriter out = appReq.getOut();
            printDandelionLocation(out, "Setup / Agent settings");
            out.println("<div class=\"main\">");
            if (message != null) {
                out.println("<p class=\"pass\">" + escapeHtmlAttribute(message) + "</p>");
            }
            if (problem != null) {
                out.println("<p class=\"fail\">" + escapeHtmlAttribute(problem) + "</p>");
            }
            if (newKey != null) {
                out.println("<div class=\"boxed\" style=\"padding:10px;margin:8px 0;\"><strong>New "
                        + (WebApiClient.TYPE_RUNNER.equals(newKeyType) ? "runner" : "launcher")
                        + " key</strong> (shown once; store it with the PC install, never in a project folder):<br/>"
                        + "<code style=\"user-select:all\">" + escapeHtmlAttribute(newKey) + "</code></div>");
            }

            String defaultAgent = catalog.defaultAgent(session, webUser);
            out.println("<h2>Agents</h2>");
            out.println("<form method=\"POST\" action=\"AgentSettingsServlet\">");
            out.println("<input type=\"hidden\" name=\"action\" value=\"savePreferences\">");
            out.println("<table class=\"boxed\">");
            out.println("<tr class=\"boxed\"><th class=\"boxed\">Default agent for Start and New</th><td class=\"boxed\">"
                    + "<select name=\"defaultAgent\">");
            List<Agent> agents = catalog.listActive(session);
            for (Agent agent : agents) {
                out.println("<option value=\"" + escapeHtmlAttribute(agent.getAgentKey()) + "\""
                        + (agent.getAgentKey().equals(defaultAgent) ? " selected" : "") + ">"
                        + escapeHtmlAttribute(agent.getDisplayName()) + "</option>");
            }
            out.println("</select></td></tr>");
            for (Agent agent : agents) {
                String model = catalog.model(session, webUser, agent.getAgentKey());
                out.println("<tr class=\"boxed\"><th class=\"boxed\">" + escapeHtmlAttribute(agent.getDisplayName())
                        + " model</th><td class=\"boxed\"><input type=\"text\" name=\"model_"
                        + escapeHtmlAttribute(agent.getAgentKey()) + "\" value=\"" + escapeHtmlAttribute(n(model))
                        + "\" placeholder=\"(the agent's own default)\"></td></tr>");
            }
            out.println("<tr class=\"boxed\"><td class=\"boxed-submit\" colspan=\"2\"><input type=\"submit\" value=\"Save\"></td></tr>");
            out.println("</table></form>");

            out.println("<h2>Launcher buttons on this computer</h2>");
            out.println("<p>Folder, Terminal, Start, New and Resume open things on your Windows PC through the "
                    + "<code>dandelion:</code> link handler. They show only in a Windows desktop browser that has "
                    + "opted in here. The setting stays in this browser.</p>");
            out.println("<label><input type=\"checkbox\" id=\"launcherOptIn\"> Show launcher buttons in this browser</label>"
                    + " <span id=\"launcherPlatform\"></span>");
            out.println("<script>(function () {");
            out.println("  var box = document.getElementById('launcherOptIn');");
            out.println("  var ua = navigator.userAgent || '';");
            out.println("  var windows = /Windows/.test(ua) && !/Mobi|Android|iPhone|iPad/.test(ua);");
            out.println("  document.getElementById('launcherPlatform').textContent = windows ? '' : '(not available: this is not a Windows desktop browser)';");
            out.println("  try { box.checked = localStorage.getItem('dandelion.launcher.optIn') === '1'; } catch (e) { box.disabled = true; }");
            out.println("  box.disabled = box.disabled || !windows;");
            out.println("  box.addEventListener('change', function () { try { if (box.checked) { localStorage.setItem('dandelion.launcher.optIn', '1'); } else { localStorage.removeItem('dandelion.launcher.optIn'); } } catch (e) {} });");
            out.println("})();</script>");

            out.println("<h2>Gmail workload</h2>");
            out.println("<p>The unattended Gmail triage may create dated tasks only in these projects (ids, comma "
                    + "separated). Anything else is refused by Dandelion, whatever the PC asks.</p>");
            StringBuilder current = new StringBuilder();
            for (Integer id : org.dandeliondaily.sessions.service.RunScopedReadsService.allowedTaskProjects(session, webUser)) {
                current.append(current.length() == 0 ? "" : ",").append(id);
            }
            out.println("<form method=\"POST\" action=\"AgentSettingsServlet\"><input type=\"hidden\" name=\"action\" "
                    + "value=\"saveGmailProjects\"><input type=\"text\" name=\"gmailTaskProjects\" size=\"40\" value=\""
                    + escapeHtmlAttribute(current.toString()) + "\"> <input type=\"submit\" value=\"Save\"></form>");

            out.println("<h2>PC keys</h2>");
            out.println("<p>The launcher key lets the PC's link handler and session commands (/bail, /park, "
                    + "/close-out) act on the action a session was started for. The runner key lets the scheduled "
                    + "runner claim and report agent actions. Neither can use the MCP or any other endpoint.</p>");
            out.println("<table class=\"boxed\"><tr class=\"boxed\"><th class=\"boxed\">Key</th><th class=\"boxed\">Created</th>"
                    + "<th class=\"boxed\">Last used</th><th class=\"boxed\"></th></tr>");
            for (WebApiClient client : listKeys(session, webUser)) {
                out.println("<tr class=\"boxed\"><td class=\"boxed\">" + escapeHtmlAttribute(client.getAgentName())
                        + "</td><td class=\"boxed\">" + escapeHtmlAttribute(String.valueOf(client.getCreateDate()))
                        + "</td><td class=\"boxed\">" + escapeHtmlAttribute(n(client.getLastUsedDate()))
                        + "</td><td class=\"boxed\"><form method=\"POST\" action=\"AgentSettingsServlet\" style=\"margin:0\">"
                        + "<input type=\"hidden\" name=\"action\" value=\"revokeKey\"><input type=\"hidden\" name=\"clientId\" value=\""
                        + client.getClientId() + "\"><input type=\"submit\" value=\"Revoke\"></form></td></tr>");
            }
            out.println("</table>");
            out.println("<form method=\"POST\" action=\"AgentSettingsServlet\" style=\"display:inline\">"
                    + "<input type=\"hidden\" name=\"action\" value=\"createKey\"><input type=\"hidden\" name=\"clientType\" value=\""
                    + WebApiClient.TYPE_LAUNCHER + "\"><input type=\"submit\" value=\"Create launcher key\"></form> ");
            out.println("<form method=\"POST\" action=\"AgentSettingsServlet\" style=\"display:inline\">"
                    + "<input type=\"hidden\" name=\"action\" value=\"createKey\"><input type=\"hidden\" name=\"clientType\" value=\""
                    + WebApiClient.TYPE_RUNNER + "\"><input type=\"submit\" value=\"Create runner key\"></form>");
            out.println("</div>");
            printHtmlFoot(appReq);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            appReq.close();
        }
    }

    @SuppressWarnings("unchecked")
    private List<WebApiClient> listKeys(Session session, WebUser webUser) {
        Query query = session.createQuery("from WebApiClient where webUser.webUserId = :userId and enabled = true "
                + "and clientType in ('LAUNCHER', 'RUNNER') order by clientId");
        query.setParameter("userId", Integer.valueOf(webUser.getWebUserId()));
        return query.list();
    }

    private String createKey(Session session, AppReq appReq, WebUser webUser, String type) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        WebApiClient client = new WebApiClient();
        client.setWebUser(webUser);
        client.setWorkspaceId(appReq.getActiveWorkspaceId());
        client.setAgentName((WebApiClient.TYPE_RUNNER.equals(type) ? "PC runner " : "PC launcher ")
                + new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        client.setEnabled(true);
        client.setCreateDate(new Date());
        client.setClientType(type);
        client.setApiKey(DeviceService.hashKey(raw));
        Transaction tx = session.beginTransaction();
        session.save(client);
        tx.commit();
        return raw;
    }
}
