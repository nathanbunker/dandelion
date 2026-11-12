package org.dandeliondaily.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.dandeliondaily.personal.service.DeviceService;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.WebApiClient;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

/**
 * Devices: the phones signed in to this account (Dandelion Sundial). A new phone waits
 * here until it is approved; any phone can be revoked, which stops it on its next request.
 */
public class DevicesServlet extends ClientServlet {

    private static final long serialVersionUID = 1L;

    private final DeviceService deviceService = new DeviceService();

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            if (appReq.isLoggedOut()) {
                forwardToHome(request, response);
                return;
            }
            WebUser webUser = appReq.getWebUser();
            Session dataSession = appReq.getDataSession();

            if ("POST".equals(request.getMethod())) {
                String action = request.getParameter("action");
                int clientId = parseInt(request.getParameter("clientId"));
                boolean changed = false;
                Transaction tx = dataSession.beginTransaction();
                try {
                    if ("approve".equals(action)) {
                        changed = deviceService.approve(dataSession, webUser, clientId);
                    } else if ("revoke".equals(action)) {
                        changed = deviceService.revoke(dataSession, webUser, clientId);
                    }
                    tx.commit();
                } catch (RuntimeException e) {
                    tx.rollback();
                    throw e;
                }
                response.sendRedirect("DevicesServlet" + (changed ? "?done=" + action : ""));
                return;
            }

            appReq.setTitle("Devices");
            printHtmlHead(appReq);
            PrintWriter out = appReq.getOut();
            out.println("<div class=\"main\">");
            out.println("<h1>Devices</h1>");
            String done = request.getParameter("done");
            if ("approve".equals(done)) {
                out.println("<p class=\"pass\">Approved. The phone will continue on its own.</p>");
            } else if ("revoke".equals(done)) {
                out.println("<p class=\"pass\">Revoked. The phone loses access on its next request.</p>");
            }
            out.println("<p>Phones signed in to your account with Dandelion Sundial. A phone only sees"
                    + " personal actions, never work ones. A new phone waits here until you approve it.</p>");

            List<WebApiClient> devices = deviceService.listDevices(dataSession, webUser);
            if (devices.isEmpty()) {
                out.println("<p><em>No phones yet. Sign in from the Sundial app and it will appear here.</em></p>");
            } else {
                SimpleDateFormat fmt = webUser.getTimeFormat();
                out.println("<table class=\"boxed\">");
                out.println("  <tr class=\"boxed\"><th class=\"boxed\">Device</th><th class=\"boxed\">Status</th>"
                        + "<th class=\"boxed\">Signed in</th><th class=\"boxed\">Last used</th>"
                        + "<th class=\"boxed\">Action</th></tr>");
                for (WebApiClient device : devices) {
                    String status = device.getDeviceStatus();
                    out.println("  <tr class=\"boxed\">");
                    out.println("    <td class=\"boxed\">" + esc(device.getDeviceName()) + "</td>");
                    out.println("    <td class=\"boxed\">" + statusLabel(status) + "</td>");
                    out.println("    <td class=\"boxed\">" + date(fmt, device.getCreateDate()) + "</td>");
                    out.println("    <td class=\"boxed\">" + date(fmt, device.getLastUsedDate()) + "</td>");
                    out.println("    <td class=\"boxed\">");
                    if (WebApiClient.STATUS_PENDING.equals(status)) {
                        out.println(button(device, "approve", "Approve"));
                    }
                    if (!WebApiClient.STATUS_REVOKED.equals(status)) {
                        out.println(button(device, "revoke", "Revoke"));
                    }
                    out.println("    </td>");
                    out.println("  </tr>");
                }
                out.println("</table>");
            }
            out.println("</div>");
            printHtmlFoot(appReq);
        } finally {
            appReq.close();
        }
    }

    private static String button(WebApiClient device, String action, String label) {
        return "      <form action=\"DevicesServlet\" method=\"POST\" style=\"display:inline;\">"
                + "<input type=\"hidden\" name=\"clientId\" value=\"" + device.getClientId() + "\">"
                + "<input type=\"hidden\" name=\"action\" value=\"" + action + "\">"
                + "<input type=\"submit\" value=\"" + label + "\"></form>";
    }

    private static String statusLabel(String status) {
        if (WebApiClient.STATUS_PENDING.equals(status)) {
            return "<strong>Waiting for approval</strong>";
        }
        if (WebApiClient.STATUS_APPROVED.equals(status)) {
            return "Approved";
        }
        if (WebApiClient.STATUS_REVOKED.equals(status)) {
            return "Revoked";
        }
        return esc(status);
    }

    private static String date(SimpleDateFormat fmt, Date date) {
        return date == null ? "" : esc(fmt.format(date));
    }

    private static int parseInt(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
