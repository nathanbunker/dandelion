/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.BillBudget;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContactAssigned;
import org.openimmunizationsoftware.pt.model.ProjectContactAssignedId;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.ReviewInterval;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.dandeliondaily.shared.service.ProjectIconService;

/**
 * 
 * @author nathan
 */
public class ProjectEditServlet extends ClientServlet {

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
    request.setCharacterEncoding("UTF-8");
    response.setCharacterEncoding("UTF-8");
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

      appReq.setTitle("Projects");
      printHtmlHead(appReq);

      Project project = null;
      ProjectContactAssigned projectContactAssignedForThisUser = null;
      Integer activeWorkspaceId = appReq.getActiveWorkspaceId();
      if (request.getParameter("projectId") != null
          && !request.getParameter("projectId").equals("0")) {
        int projectId = Integer.parseInt(request.getParameter("projectId"));
        project = (Project) dataSession.get(Project.class, projectId);
        if (project == null || project.getWorkspaceId() == null
            || activeWorkspaceId == null
            || !activeWorkspaceId.equals(project.getWorkspaceId())) {
          forwardToHome(request, response);
          return;
        }
        projectContactAssignedForThisUser = ProjectServlet.getProjectContactAssigned(webUser, dataSession, project);
      } else {
        project = new Project();
        project.setWorkspaceId(activeWorkspaceId);
        project.setCreatedByWebUserId(webUser.getWebUserId());
      }

      if (action != null) {
        String message = null;
        if (action.equals("Save")) {
          String requestProjectName = trim(request.getParameter("projectName"), 100);
          String requestDescription = trim(request.getParameter("description"), 1200);
          String requestStatus = trim(request.getParameter("projectStatus"), 20);
          String requestHandle = HandleValidationSupport.resolveHandle(
              request.getParameter("projectHandle"), requestProjectName, 60);
          if (project.isExternalManaged()) {
            if (!n(project.getProjectName()).equals(requestProjectName)
                || !n(project.getDescription()).equals(requestDescription)
                || !ProjectStatus.fromDatabaseValue(project.getProjectStatus()).getDatabaseValue()
                    .equalsIgnoreCase(ProjectStatus.fromDatabaseValue(requestStatus).getDatabaseValue())
                || !n(project.getProjectHandle()).equals(requestHandle)) {
              message = "Project name, handle, description, and status are externally managed and cannot be edited here.";
            }
          }

          project.setDescription(trim(request.getParameter("description"), 1200));
          String projectStatus = trim(request.getParameter("projectStatus"), 20);
          project.setProjectStatus(ProjectStatus.fromDatabaseValue(projectStatus).getDatabaseValue());
          project.setProjectId(Integer.parseInt(request.getParameter("projectId")));
          project.setPriorityLevel(Integer.parseInt(request.getParameter("priorityLevel")));
          project.setWebUser(webUser);
          project.setWorkspaceId(activeWorkspaceId);
          project.setLastModifiedByWebUserId(webUser.getWebUserId());
          project.setProjectName(trim(request.getParameter("projectName"), 100));
          String projectHandle = HandleValidationSupport.resolveHandle(
              request.getParameter("projectHandle"), project.getProjectName(), 60);
          project.setProjectHandle(projectHandle);
          project.setProjectIcon(ProjectIconService.normalizeLenient(request.getParameter("projectIcon")));
          if (webUser.isTrackTime()) {
            project.setBillCode(request.getParameter("billCode"));
            String billBudgetId = trim(request.getParameter("billBudgetId"), 12);
            if (billBudgetId.length() == 0) {
              project.setBillBudgetId(null);
            } else {
              BillBudget selectedBudget = (BillBudget) dataSession.get(BillBudget.class,
                  Integer.parseInt(billBudgetId));
              if (selectedBudget == null || selectedBudget.getWorkspaceId() == null
                  || !activeWorkspaceId.equals(selectedBudget.getWorkspaceId())) {
                message = "Selected contract budget was not found in this workspace";
              } else if (selectedBudget.getBillCode() == null
                  || selectedBudget.getBillCode().getBillCode() == null
                  || !selectedBudget.getBillCode().getBillCode().equals(project.getBillCode())) {
                message = "Selected contract budget must use the same bill code as the project";
              } else {
                project.setBillBudgetId(Integer.valueOf(selectedBudget.getBillBudgetId()));
              }
            }
          }
          if (project.getProjectName().equals("")) {
            message = "Project name is required";
          } else if (!ProjectStatus.CLOSED.getDatabaseValue().equalsIgnoreCase(project.getProjectStatus())
              && projectHandle.length() == 0) {
            message = "Project handle is required for active projects";
          } else {
            message = HandleValidationSupport.validateHandleCharacters("Project handle", projectHandle);
            if (message == null
                && !ProjectStatus.CLOSED.getDatabaseValue().equalsIgnoreCase(project.getProjectStatus())) {
              Query uniqueQuery = dataSession.createQuery(
                  "select count(*) from Project where workspaceId = :workspaceId and lower(projectHandle) = :projectHandle and projectId <> :projectId and projectStatus <> :closedStatus");
              uniqueQuery.setParameter("workspaceId", activeWorkspaceId);
              uniqueQuery.setParameter("projectHandle", projectHandle.toLowerCase());
              uniqueQuery.setParameter("projectId", project.getProjectId());
              uniqueQuery.setParameter("closedStatus", ProjectStatus.CLOSED.getDatabaseValue());
              Number duplicateCount = (Number) uniqueQuery.uniqueResult();
              if (duplicateCount != null && duplicateCount.intValue() > 0) {
                message = "Project handle must be unique among active projects in this workspace";
              }
            }
          }
        }
        if (message != null) {
          appReq.setMessageProblem(message);
        } else {
          Transaction trans = dataSession.beginTransaction();
          try {
            dataSession.saveOrUpdate(project);
            if (projectContactAssignedForThisUser == null) {
              ProjectContactAssigned projectContactAssigned = new ProjectContactAssigned();
              projectContactAssigned.setId(new ProjectContactAssignedId());
              projectContactAssigned.getId().setContactId(webUser.getContactId());
              projectContactAssigned.getId().setProjectId(project.getProjectId());
              projectContactAssigned.setEmailAlert("Y");
              projectContactAssigned
                  .setUpdateDue(Integer.parseInt(request.getParameter("updateEvery")));
              dataSession.save(projectContactAssigned);
            } else {
              projectContactAssignedForThisUser
                  .setUpdateDue(Integer.parseInt(request.getParameter("updateEvery")));
              dataSession.update(projectContactAssignedForThisUser);
            }
          } finally {
            trans.commit();
          }
          response.sendRedirect("ProjectServlet?projectId=" + project.getProjectId());
          return;
        }
      }

      out.println("<div class=\"main\">");
      out.println("<form action=\"ProjectEditServlet\" method=\"POST\" accept-charset=\"UTF-8\">");
      out.println(
          "<input type=\"hidden\" name=\"projectId\" value=\"" + project.getProjectId() + "\">");
      boolean externalManaged = project.isExternalManaged();
      String externalReadonly = externalManaged ? " readonly" : "";
      out.println("<table class=\"boxed-full\">");
      out.println("  <tr>");
      out.println("    <th class=\"title\" colspan=\"2\">Edit Project Information</th>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Project Name</th>");
      out.println("    <td class=\"boxed\"><input type=\"text\" name=\"projectName\" value=\""
          + n(project.getProjectName()) + "\" size=\"30\"" + externalReadonly + "></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Project Handle</th>");
      out.println("    <td class=\"boxed\"><input type=\"text\" name=\"projectHandle\" value=\""
          + n(project.getProjectHandle()) + "\" size=\"30\"" + externalReadonly + "></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Priority Level</th>");
      out.println("    <td class=\"boxed\"><input type=\"text\" name=\"priorityLevel\" value=\""
          + project.getPriorityLevel() + "\" size=\"3\"></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Project Icon</th>");
      out.println("    <td class=\"boxed\"><input type=\"text\" name=\"projectIcon\" value=\""
          + n(project.getProjectIcon()) + "\" size=\"8\"></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Description</th>");
      out.println("    <td class=\"boxed\"><textarea type=\"text\" name=\"description\" rows=\"5\""
          + externalReadonly + ">" + n(project.getDescription()) + "</textarea></td>");
      out.println("  </tr>");
      out.println("  <tr class=\"boxed\">");
      out.println("    <th class=\"boxed\">Status</th>");
      out.println("    <td class=\"boxed\">");
      if (externalManaged) {
        out.println("<input type=\"hidden\" name=\"projectStatus\" value=\""
            + n(ProjectStatus.fromDatabaseValue(project.getProjectStatus()).getDatabaseValue())
            + "\">");
      }
      out.println("<select name=\"projectStatus\"" + (externalManaged ? " disabled" : "") + ">");
      {
        String selectedStatus = ProjectStatus.fromDatabaseValue(project.getProjectStatus()).getDatabaseValue();
        for (ProjectStatus status : ProjectStatus.values()) {
          if (status.getDatabaseValue().equalsIgnoreCase(selectedStatus)) {
            out.println("      <option value=\"" + status.getDatabaseValue() + "\" selected>"
                + status.getDatabaseValue() + "</option>");
          } else {
            out.println("      <option value=\"" + status.getDatabaseValue() + "\">"
                + status.getDatabaseValue() + "</option>");
          }
        }
        out.println("      </select>");
      }
      out.println("    </td>");
      out.println("  </tr>");
      if (project.isExternalManaged()) {
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">External Sync</th>");
        out.println("    <td class=\"boxed\">Externally managed"
            + (project.getExternalLastSyncedAt() == null
                ? ""
                : " (Last Synced: " + webUser.getTimeFormat().format(project.getExternalLastSyncedAt()) + ")")
            + "</td>");
        out.println("  </tr>");
      }
      if (webUser.isTrackTime()) {
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">Bill Code</th>");
        out.println("    <td class=\"boxed\"><select name=\"billCode\">");
        Query query = dataSession.createQuery(
            "from BillCode where workspaceId = :workspaceId and visible = 'Y' order by billLabel");
        query.setParameter("workspaceId", activeWorkspaceId);
        @SuppressWarnings("unchecked")
        List<BillCode> billCodeList = query.list();
        for (BillCode billCode : billCodeList) {
          if (billCode.getBillCode().equals(project.getBillCode())) {
            out.println("      <option value=\"" + billCode.getBillCode() + "\" selected>"
                + billCode.getBillLabel() + "</option>");
          } else {
            out.println("      <option value=\"" + billCode.getBillCode() + "\">"
                + billCode.getBillLabel() + "</option>");
          }
        }
        out.println("      </select>");
        out.println("    </td>");
        out.println("  </tr>");

        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">Contract Budget</th>");
        out.println("    <td class=\"boxed\"><select name=\"billBudgetId\">");
        out.println("      <option value=\"\"></option>");
        Query budgetQuery = dataSession.createQuery(
            "from BillBudget where workspaceId = :workspaceId order by billBudgetCode");
        budgetQuery.setParameter("workspaceId", activeWorkspaceId);
        @SuppressWarnings("unchecked")
        List<BillBudget> billBudgetList = budgetQuery.list();
        for (BillBudget billBudget : billBudgetList) {
          String budgetBillCode = billBudget.getBillCode() == null ? "" : n(billBudget.getBillCode().getBillCode());
          boolean selected = project.getBillBudgetId() != null
              && project.getBillBudgetId().intValue() == billBudget.getBillBudgetId();
          out.println("      <option value=\"" + billBudget.getBillBudgetId() + "\""
              + (selected ? " selected" : "") + ">"
              + n(billBudget.getBillBudgetCode()) + " (" + budgetBillCode + ")</option>");
        }
        out.println("      </select>");
        out.println(
            "      <br/><small>Historical time entries are unchanged when project billing settings are updated.</small>");
        out.println("    </td>");
        out.println("  </tr>");
      }
      {
        out.println("  <tr class=\"boxed\">");
        out.println("    <th class=\"boxed\">Update Every</th>");
        out.println("    <td class=\"boxed\"><select name=\"updateEvery\">");
        out.println("      <option value=\"0\">none</option>");
        boolean found = false;
        if (projectContactAssignedForThisUser != null
            && projectContactAssignedForThisUser.getUpdateDue() == 0) {
          found = true;
        }
        for (ReviewInterval interval : ReviewInterval.values()) {
          if (!found && projectContactAssignedForThisUser != null
              && projectContactAssignedForThisUser.getUpdateDue() <= interval.getDays()) {
            out.println("      <option value=\"" + interval.getDays() + "\" selected>"
                + interval.getDescription() + "</option>");
            found = true;
          } else {
            out.println("      <option value=\"" + interval.getDays() + "\">"
                + interval.getDescription() + "</option>");
          }
        }
        out.println("      </select>");
        out.println("    </td>");
        out.println("  </tr>");
      }

      out.println("  <tr class=\"boxed\">");
      out.println(
          "    <td class=\"boxed-submit\" colspan=\"2\"><input type=\"submit\" name=\"action\" value=\"Save\"></td>");
      out.println("  </tr>");
      out.println("</table>");
      out.println("</form>");
      out.println("</div>");

      printHtmlFoot(appReq);
    } catch (Exception e) {
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
}
