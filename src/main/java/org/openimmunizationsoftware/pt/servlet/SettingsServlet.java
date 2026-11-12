/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.WebApiClient;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.model.Workspace;

/**
 * 
 * @author nathan
 */
public class SettingsServlet extends ClientServlet {

  private static final String ACTION_SAVE = "Save";
  private static final String ACTION_SAVE_REPORT_SETTINGS = "Save Report Settings";
  private static final String ACTION_CREATE_API_KEY = "Create API Key";
  private static final String ACTION_DELETE_API_KEY = "Delete API Key";

  private static final String PARAM_ACTION = "action";
  private static final String PARAM_DISPLAY_COLOR = "displayColor";
  private static final String PARAM_DISPLAY_SIZE = "displaySize";
  private static final String PARAM_TIME_ZONE_USER = "timeZoneUser";
  private static final String PARAM_TIME_ZONE_APPLICATION = "timeZoneApplication";
  private static final String PARAM_DATE_DISPLAY_FORMAT = "dateDisplayFormat";
  private static final String PARAM_DATE_ENTRY_FORMAT = "dateEntryFormat";
  private static final String PARAM_TIME_DISPLAY_FORMAT = "timeDisplayFormat";
  private static final String PARAM_TIME_ENTRY_FORMAT = "timeEntryFormat";
  private static final String PARAM_TRACK_TIME = "trackTime";
  private static final String PARAM_REPORT_DAILY_ENABLED = "reportDailyEnabled";
  private static final String PARAM_API_KEY_AGENT_NAME = "apiKeyAgentName";
  private static final String PARAM_API_KEY_WORKSPACE_ID = "apiKeyWorkspaceId";
  private static final String PARAM_API_KEY_CLIENT_ID = "apiKeyClientId";
  private static final String PARAM_FIRST_NAME = "firstName";
  private static final String PARAM_LAST_NAME = "lastName";
  private static final String PARAM_WORKFLOW_TYPE = "workflowType";

  private static final String WORKFLOW_TYPE_STANDARD = "STANDARD";
  private static final String WORKFLOW_TYPE_STUDENT = "STUDENT";

  /**
   * Processes requests for both HTTP <code>GET</code> and <code>POST</code>
   * methods.
   * 
   * @param request
   *                 servlet request
   * @param response
   *                 servlet response
   * @throws ServletException
   *                          if a servlet-specific error occurs
   * @throws IOException
   *                          if an I/O error occurs
   */
  protected void processRequest(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    AppReq appReq = new AppReq(request, response);
    try {
      WebUser webUser = appReq.getWebUser();
      if (appReq.isLoggedOut()) {
        forwardToHome(request, response);
        return;
      }
      PrintWriter out = appReq.getOut();
      Session dataSession = appReq.getDataSession();
      String action = appReq.getAction();
      Integer privateWorkspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession,
          webUser.getWebUserId());
      List<Workspace> patchWorkspaces = WorkspaceRegistry.getPatchWorkspacesForWebUser(dataSession,
          webUser.getWebUserId());

      if (action != null) {
        if (action.equals(ACTION_SAVE)) {
          String firstName = request.getParameter(PARAM_FIRST_NAME);
          String lastName = request.getParameter(PARAM_LAST_NAME);
          String workflowType = request.getParameter(PARAM_WORKFLOW_TYPE);

          firstName = firstName == null ? "" : firstName.trim();
          lastName = lastName == null ? "" : lastName.trim();
          workflowType = WORKFLOW_TYPE_STUDENT.equals(workflowType) ? WORKFLOW_TYPE_STUDENT
              : WORKFLOW_TYPE_STANDARD;

          if (firstName.length() < 1 || lastName.length() < 1) {
            appReq.setMessageProblem("First name and last name must each have at least one character.");
          } else {
            WebUser webUserDb = (WebUser) dataSession.get(WebUser.class, webUser.getWebUserId());
            Transaction trans = dataSession.beginTransaction();
            webUserDb.setFirstName(firstName);
            webUserDb.setLastName(lastName);
            webUserDb.setWorkflowType(workflowType);
            dataSession.update(webUserDb);
            trans.commit();

            webUser.setFirstName(firstName);
            webUser.setLastName(lastName);
            webUser.setWorkflowType(workflowType);
          }

          if (request.getParameter(PARAM_DISPLAY_SIZE) != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_DISPLAY_SIZE, webUser,
                request.getParameter(PARAM_DISPLAY_SIZE), dataSession);
          }
          if (request.getParameter(PARAM_DISPLAY_COLOR) != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_DISPLAY_COLOR, webUser,
                request.getParameter(PARAM_DISPLAY_COLOR), dataSession);
          }
          String timeZoneUser = request.getParameter(PARAM_TIME_ZONE_USER);
          String timeZoneApplication = request.getParameter(PARAM_TIME_ZONE_APPLICATION);
          String dateDisplayFormat = request.getParameter(PARAM_DATE_DISPLAY_FORMAT);
          String dateEntryFormat = request.getParameter(PARAM_DATE_ENTRY_FORMAT);
          String timeDisplayFormat = request.getParameter(PARAM_TIME_DISPLAY_FORMAT);
          String timeEntryFormat = request.getParameter(PARAM_TIME_ENTRY_FORMAT);
          if (timeZoneUser != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_TIME_ZONE, webUser, timeZoneUser,
                dataSession);
            webUser.setTimeZone(TimeZone.getTimeZone(timeZoneUser));
          }
          if (dateDisplayFormat != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_DATE_DISPLAY_FORMAT, webUser,
                dateDisplayFormat, dataSession);
            webUser.setDateDisplayPattern(dateDisplayFormat);
          }
          if (dateEntryFormat != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_DATE_ENTRY_FORMAT, webUser,
                dateEntryFormat, dataSession);
            webUser.setDateEntryPattern(dateEntryFormat);
          }
          if (timeDisplayFormat != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_TIME_DISPLAY_FORMAT, webUser,
                timeDisplayFormat, dataSession);
            webUser.setTimeDisplayPattern(timeDisplayFormat);
          }
          if (timeEntryFormat != null) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_TIME_ENTRY_FORMAT, webUser,
                timeEntryFormat, dataSession);
            webUser.setTimeEntryPattern(timeEntryFormat);
          }
          if (timeZoneApplication != null) {
            TrackerKeysManager.saveApplicationKeyValue(TrackerKeysManager.KEY_TIME_ZONE,
                timeZoneApplication, dataSession);
          }

          if (webUser.isUserTypeAdmin()) {
            TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_TRACK_TIME, webUser,
                request.getParameter(PARAM_TRACK_TIME) != null ? "Y" : "N", dataSession);
          }
        } else if (action.equals(ACTION_SAVE_REPORT_SETTINGS)) {
          TrackerKeysManager.saveKeyValue(TrackerKeysManager.KEY_REPORT_DAILY_ENABLED, webUser,
              request.getParameter(PARAM_REPORT_DAILY_ENABLED) != null ? "Y" : "N", dataSession);
        }
        if (action.equals(ACTION_CREATE_API_KEY)) {
          String agentName = request.getParameter(PARAM_API_KEY_AGENT_NAME);
          if (agentName == null || agentName.trim().equals("")) {
            appReq.setMessageProblem("Agent name is required to create an API key. ");
          } else {
            agentName = agentName.trim();
            Integer workspaceId = resolveRequestedWorkspaceId(
                request.getParameter(PARAM_API_KEY_WORKSPACE_ID), privateWorkspaceId);
            if (workspaceId == null) {
              appReq.setMessageProblem(
                  "Private workspace is not available. Please choose a valid workspace.");
            } else if (!isAllowedWorkspaceSelection(dataSession, webUser, workspaceId,
                privateWorkspaceId)) {
              appReq.setMessageProblem("Workspace selection is not valid for this account.");
            } else {
              Query query = dataSession.createQuery(
                  "from WebApiClient where webUser = :webUser and enabled = true and lower(agentName) = :agentNameLower and workspaceId = :workspaceId");
              query.setParameter("webUser", webUser);
              query.setParameter("agentNameLower", agentName.toLowerCase());
              query.setParameter("workspaceId", workspaceId);
              @SuppressWarnings("unchecked")
              List<WebApiClient> existingClients = query.list();
              if (existingClients.size() > 0) {
                appReq.setMessageProblem(
                    "Agent name already exists in selected workspace, so new API key not created. ");
              } else {
                WebApiClient client = new WebApiClient();
                client.setWebUser(webUser);
                client.setWorkspaceId(workspaceId);
                client.setAgentName(agentName);
                client.setEnabled(true);
                client.setCreateDate(new Date());
                client.setApiKey(generateUniqueApiKey(dataSession));
                Transaction trans = dataSession.beginTransaction();
                dataSession.save(client);
                trans.commit();
              }
            }
          }
        } else if (action.equals(ACTION_DELETE_API_KEY)) {
          String clientIdString = request.getParameter(PARAM_API_KEY_CLIENT_ID);
          if (clientIdString != null && !clientIdString.trim().equals("")) {
            try {
              int clientId = Integer.parseInt(clientIdString);
              WebApiClient client = (WebApiClient) dataSession.get(WebApiClient.class, clientId);
              if (client != null && client.isEnabled()
                  && webUser.equals(client.getWebUser())) {
                client.setEnabled(false);
                Transaction trans = dataSession.beginTransaction();
                dataSession.update(client);
                trans.commit();
              }
            } catch (NumberFormatException nfe) {
              // do nothing
            }
          }
        }
      }
      appReq.setTitle("Settings");
      printHtmlHead(appReq);
      printDandelionLocation(out, "Setup / Settings");

      if (webUser.isUserTypeAdmin()) {
        out.println("<br/>");
        out.println("<table class=\"boxed\">");
        out.println("  <tr class=\"boxed\">");
        out.println(
            "    <td class=\"boxed\">Need system-wide controls? <a href=\"AdminSettingsServlet\">Open Admin Settings</a></td>");
        out.println("  </tr>");
        out.println("</table>");
      }

      if (!WORKFLOW_TYPE_STUDENT.equals(webUser.getWorkflowType())) {
        out.println("<br/>");
        out.println("<table class=\"boxed\">");
        out.println("  <tr class=\"boxed\">");
        out.println(
            "    <td class=\"boxed\">Manage parent reward store templates: <a href=\"StudentOfferSetupServlet\">Student Offer Setup</a></td>");
        out.println("  </tr>");
        out.println("</table>");
      }

      boolean userDailyReportsEnabled = TrackerKeysManager.getUserKeyValueBooleanNoFallback(
          TrackerKeysManager.KEY_REPORT_DAILY_ENABLED, false, webUser, dataSession);
      String selectedDisplayColor = valueOrEmpty(appReq.getDisplayColor());
      if (selectedDisplayColor.length() == 0) {
        selectedDisplayColor = CssServlet.DisplayColor.DEFAULT.getLabel();
      }

      out.println("<br/>");
      out.println("<form action=\"SettingsServlet\" method=\"POST\">");
      out.println("<table class=\"boxed\">");
      out.println("  <tr class=\"boxed\">");
      out.println("     <th class=\"title\" colspan=\"2\">Profile Settings</td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">First Name</th>");
      out.println("    <td class=\"boxed\"><input type=\"text\" name=\"" + PARAM_FIRST_NAME
          + "\" size=\"30\" value=\"" + h(webUser.getFirstName()) + "\"></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Last Name</th>");
      out.println("    <td class=\"boxed\"><input type=\"text\" name=\"" + PARAM_LAST_NAME
          + "\" size=\"30\" value=\"" + h(webUser.getLastName()) + "\"></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Workflow Type</th>");
      out.println("    <td class=\"boxed\"><select name=\"" + PARAM_WORKFLOW_TYPE + "\">");
      String selectedWorkflowType = webUser.getWorkflowType() == null ? WORKFLOW_TYPE_STANDARD
          : webUser.getWorkflowType();
      out.println("      <option value=\"" + WORKFLOW_TYPE_STANDARD + "\""
          + (WORKFLOW_TYPE_STANDARD.equals(selectedWorkflowType) ? " selected" : "")
          + ">Standard</option>");
      out.println("      <option value=\"" + WORKFLOW_TYPE_STUDENT + "\""
          + (WORKFLOW_TYPE_STUDENT.equals(selectedWorkflowType) ? " selected" : "")
          + ">School</option>");
      out.println("    </select></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Display Color</th>");
      out.println("    <td class=\"boxed\"><select name=\"" + PARAM_DISPLAY_COLOR + "\">");
      for (CssServlet.DisplayColor displayColor : CssServlet.DisplayColor.values()) {
        out.println("      <option value=\"" + h(displayColor.getLabel()) + "\""
            + (displayColor.getLabel().equals(selectedDisplayColor) ? " selected" : "")
            + ">" + h(displayColor.getLabel()) + "</option>");
      }
      if (selectedDisplayColor.startsWith("#")) {
        out.println("      <option value=\"" + h(selectedDisplayColor) + "\" selected>Custom ("
            + h(selectedDisplayColor) + ")</option>");
      }
      out.println("    </select></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <td class=\"boxed-submit\" colspan=\"2\"><input type=\"submit\" name=\""
          + PARAM_ACTION + "\" value=\"" + ACTION_SAVE + "\"></td>");
      out.println("  </tr>");
      out.println("</table>");
      out.println("</form>");

      out.println("<br/>");
      out.println("<form action=\"SettingsServlet\" method=\"POST\">");
      out.println("<table class=\"boxed\">");
      out.println("  <tr class=\"boxed\">");
      out.println("     <th class=\"title\" colspan=\"2\">Automatic Report Emails</td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Receive automatic report emails</th>");
      out.println("    <td class=\"boxed\">");
      out.println("      <input type=\"checkbox\" name=\"" + PARAM_REPORT_DAILY_ENABLED
          + "\" value=\"Y\"" + (userDailyReportsEnabled ? " checked" : "") + "> ");
      out.println(
          "      <span class=\"small\">Both this setting and the admin daily report switch must be enabled before automatic reports send.</span>");
      out.println("    </td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <td class=\"boxed-submit\" colspan=\"2\"><input type=\"submit\" name=\""
          + PARAM_ACTION + "\" value=\"" + ACTION_SAVE_REPORT_SETTINGS + "\"></td>");
      out.println("  </tr>");
      out.println("</table>");
      out.println("</form>");

      Query apiKeyQuery = dataSession.createQuery(
          "from WebApiClient where webUser = :webUser and enabled = true and clientType = 'AGENT'"
              + " order by createDate desc");
      apiKeyQuery.setParameter("webUser", webUser);
      @SuppressWarnings("unchecked")
      List<WebApiClient> apiClients = apiKeyQuery.list();
      if (apiClients.size() > 0) {
        out.println("<br/>");
        out.println("<table class=\"boxed\">");
        out.println("  <tr class=\"boxed\">");
        out.println("     <th class=\"title\" colspan=\"6\">Web API Client Keys</td>");
        out.println("  </tr>");
        out.println("  <tr class=\"boxed\">");
        out.println("     <th>Agent Name</th>");
        out.println("     <th>Scope</th>");
        out.println("     <th>Created Date</th>");
        out.println("     <th>Last Used Date</th>");
        out.println("     <th>API Key</th>");
        out.println("     <th>Action</th>");
        out.println("  </tr>");
        SimpleDateFormat apiKeyDateFormat = webUser.getTimeFormat();
        for (WebApiClient client : apiClients) {
          out.println("  <tr class=\"boxed\">");
          out.println("     <td>" + n(client.getAgentName()) + "</td>");
          out.println("     <td>" + n(resolveWorkspaceLabel(dataSession, client.getWorkspaceId(),
              privateWorkspaceId)) + "</td>");
          out.println("     <td>"
              + (client.getCreateDate() == null ? "" : apiKeyDateFormat.format(client.getCreateDate()))
              + "</td>");
          out.println("     <td>"
              + (client.getLastUsedDate() == null ? "" : apiKeyDateFormat.format(client.getLastUsedDate()))
              + "</td>");
          out.println("     <td>" + n(client.getApiKey()) + "</td>");
          out.println("     <td>");
          out.println("       <form action=\"SettingsServlet\" method=\"POST\">");
          out.println("         <input type=\"hidden\" name=\"" + PARAM_API_KEY_CLIENT_ID
              + "\" value=\"" + client.getClientId() + "\">");
          out.println("         <input type=\"submit\" name=\"" + PARAM_ACTION + "\" value=\""
              + ACTION_DELETE_API_KEY + "\">");
          out.println("       </form>");
          out.println("     </td>");
          out.println("  </tr>");
        }
        out.println("</table>");
      }
      out.println("<br/>");
      out.println("<form action=\"SettingsServlet\" method=\"POST\">");
      out.println("<table class=\"boxed\">");
      out.println("  <tr class=\"boxed\">");
      out.println("     <th class=\"title\" colspan=\"2\">Create Web API Client Key</td>");
      out.println("  </tr>");
      Integer selectedWorkspaceId = resolveWorkspaceSelectionForForm(
          request.getParameter(PARAM_API_KEY_WORKSPACE_ID), privateWorkspaceId);
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Scope</th>");
      out.println("    <td class=\"boxed\">");
      out.println("      <select name=\"" + PARAM_API_KEY_WORKSPACE_ID + "\">");
      if (privateWorkspaceId != null) {
        out.println("        <option value=\"" + privateWorkspaceId + "\""
            + (selectedWorkspaceId != null && selectedWorkspaceId.equals(privateWorkspaceId)
                ? " selected"
                : "")
            + ">Private (default)</option>");
      }
      for (Workspace patchWorkspace : patchWorkspaces) {
        Integer patchWorkspaceId = Integer.valueOf(patchWorkspace.getWorkspaceId());
        out.println("        <option value=\"" + patchWorkspaceId + "\""
            + (selectedWorkspaceId != null && selectedWorkspaceId.equals(patchWorkspaceId)
                ? " selected"
                : "")
            + ">" + h(patchWorkspace.getWorkspaceName()) + "</option>");
      }
      out.println("      </select>");
      out.println("    </td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Agent Name</th>");
      out.println("    <td class=\"boxed\">");
      out.println("      <input type=\"text\" name=\"" + PARAM_API_KEY_AGENT_NAME
          + "\" size=\"30\" value=\"" + h(valueOrEmpty(request.getParameter(
              PARAM_API_KEY_AGENT_NAME)))
          + "\">");
      out.println("    </td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <td class=\"boxed-submit\" colspan=\"2\"><input type=\"submit\" name=\""
          + PARAM_ACTION + "\" value=\"" + ACTION_CREATE_API_KEY + "\"></td>");
      out.println("  </tr>");
      out.println("</table>");
      out.println("</form>");
      out.println("<p>Phones (Dandelion Sundial) are approved and revoked on the "
          + "<a href=\"DevicesServlet\">Devices</a> page.</p>");

      printHtmlFoot(appReq);

    } catch (

    Exception e) {
      e.printStackTrace();
    } finally {
      appReq.close();
    }

  }

  // <editor-fold defaultstate="collapsed"
  // desc="HttpServlet methods. Click on the + sign on the left to edit the
  // code.">

  /**
   * Handles the HTTP <code>GET</code> method.
   * 
   * @param request
   *                 servlet request
   * @param response
   *                 servlet response
   * @throws ServletException
   *                          if a servlet-specific error occurs
   * @throws IOException
   *                          if an I/O error occurs
   */
  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    processRequest(request, response);
  }

  /**
   * Handles the HTTP <code>POST</code> method.
   * 
   * @param request
   *                 servlet request
   * @param response
   *                 servlet response
   * @throws ServletException
   *                          if a servlet-specific error occurs
   * @throws IOException
   *                          if an I/O error occurs
   */
  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    processRequest(request, response);
  }

  /**
   * Returns a short description of the servlet.
   * 
   * @return a String containing servlet description
   */
  @Override
  public String getServletInfo() {
    return "DQA Tester Home Page";
  }// </editor-fold>

  private static Integer resolveRequestedWorkspaceId(String requestedWorkspaceId,
      Integer privateWorkspaceId) {
    Integer workspaceId = parseWorkspaceId(requestedWorkspaceId);
    if (workspaceId != null) {
      return workspaceId;
    }
    return privateWorkspaceId;
  }

  private static Integer resolveWorkspaceSelectionForForm(String requestedWorkspaceId,
      Integer privateWorkspaceId) {
    Integer workspaceId = parseWorkspaceId(requestedWorkspaceId);
    if (workspaceId != null) {
      return workspaceId;
    }
    return privateWorkspaceId;
  }

  private static Integer parseWorkspaceId(String workspaceIdString) {
    if (workspaceIdString == null) {
      return null;
    }
    String trimmed = workspaceIdString.trim();
    if (trimmed.length() == 0) {
      return null;
    }
    try {
      int workspaceId = Integer.parseInt(trimmed);
      return workspaceId > 0 ? Integer.valueOf(workspaceId) : null;
    } catch (NumberFormatException nfe) {
      return null;
    }
  }

  private static boolean isAllowedWorkspaceSelection(Session dataSession, WebUser webUser,
      Integer workspaceId, Integer privateWorkspaceId) {
    if (workspaceId == null) {
      return false;
    }
    if (privateWorkspaceId != null && privateWorkspaceId.equals(workspaceId)) {
      return true;
    }
    Workspace workspace = (Workspace) dataSession.get(Workspace.class, workspaceId);
    if (workspace == null) {
      return false;
    }
    if (!Workspace.STATUS_ACTIVE.equals(workspace.getWorkspaceStatus())) {
      return false;
    }
    if (!Workspace.TYPE_PATCH.equals(workspace.getWorkspaceType())) {
      return false;
    }
    return WorkspaceRegistry.hasActiveMembership(dataSession, workspaceId.intValue(),
        webUser.getWebUserId());
  }

  private static String resolveWorkspaceLabel(Session dataSession, Integer workspaceId,
      Integer privateWorkspaceId) {
    if (workspaceId == null) {
      return "Unscoped";
    }
    if (privateWorkspaceId != null && privateWorkspaceId.equals(workspaceId)) {
      return "Private";
    }
    Workspace workspace = (Workspace) dataSession.get(Workspace.class, workspaceId);
    if (workspace == null) {
      return "Workspace #" + workspaceId;
    }
    String workspaceName = workspace.getWorkspaceName() == null ? "" : workspace.getWorkspaceName();
    return workspaceName;
  }

  private static String generateUniqueApiKey(Session dataSession) {
    String apiKey = "";
    while (true) {
      apiKey = UUID.randomUUID().toString().replace("-", "");
      Query query = dataSession
          .createQuery("select clientId from WebApiClient where apiKey = :apiKey");
      query.setParameter("apiKey", apiKey);
      @SuppressWarnings("unchecked")
      List<Integer> matches = query.list();
      if (matches.isEmpty()) {
        return apiKey;
      }
    }
  }

  private static String h(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;");
  }

  private static String valueOrEmpty(String value) {
    return value == null ? "" : value;
  }

}
