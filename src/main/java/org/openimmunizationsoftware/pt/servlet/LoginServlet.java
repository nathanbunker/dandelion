/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.openimmunizationsoftware.pt.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.List;
import java.util.TimeZone;
import java.util.Date;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.manager.MailManager;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.RememberMeManager;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * 
 * @author nathan
 */
public class LoginServlet extends ClientServlet {

  private static final String ACTION_LOGIN = "Login";
  private static final String ACTION_LOGOUT = "Logout";
  private static final String ACTION_SEND_MAGIC_LINK = "Send Magic Link";
  private static final String ACTION_MAGIC_LOGIN = "MagicLogin";
  private static final String ACTION_MAGIC_LOGIN_CONFIRM = "MagicLoginConfirm";

  private static final String PARAM_MAGIC_EMAIL = "magicEmail";
  private static final String PARAM_MAGIC_USER_ID = "magicUserId";
  private static final String PARAM_MAGIC_TOKEN = "magicToken";
  private static final String PARAM_MAGIC_CONFIRM_NONCE = "magicConfirmNonce";

  private static final String SESSION_MAGIC_CONFIRM_NONCE = "LOGIN_MAGIC_CONFIRM_NONCE";

  private static final int MAGIC_LINK_MINUTES_VALID = 20;

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
      Session dataSession = appReq.getDataSession();
      String action = appReq.getAction();
      PrintWriter out = appReq.getOut();
      boolean loginSuccess = false;
      if (action != null) {
        if (action.equals(ACTION_LOGIN)) {
          loginSuccess = login(request, appReq, dataSession, loginSuccess);
        } else if (action.equals(ACTION_SEND_MAGIC_LINK)) {
          sendMagicLink(request, appReq, dataSession);
        } else if (action.equals(ACTION_MAGIC_LOGIN)) {
          if ("GET".equalsIgnoreCase(request.getMethod())) {
            renderMagicLinkConfirmationPage(request, appReq, dataSession);
            return;
          }
          loginSuccess = loginWithMagicLink(request, appReq, dataSession, false);
        } else if (action.equals(ACTION_MAGIC_LOGIN_CONFIRM)) {
          loginSuccess = loginWithMagicLink(request, appReq, dataSession, true);
        } else if (action.equals(ACTION_LOGOUT)) {
          appReq.logout();
          response.sendRedirect("LoginServlet");
          return;
        }
      }
      if (!loginSuccess) {
        String username = request.getParameter("username");
        if (username == null) {
          username = "";
        }
        String password = request.getParameter("password");
        if (password == null) {
          password = "";
        }
        String magicEmail = request.getParameter(PARAM_MAGIC_EMAIL);
        if (magicEmail == null) {
          magicEmail = "";
        }
        String uiMode = request.getParameter("uiMode");
        if (uiMode == null || uiMode.equals("")) {
          uiMode = "desktop";
        }
        try {
          appReq.setTitle("Login");
          boolean emailEnabled = TrackerKeysManager.getApplicationKeyValueBoolean(
              TrackerKeysManager.KEY_SYSTEM_EMAIL_ENABLE, false, dataSession);
          printHtmlHead(appReq);
          printLoginForm(out, username, password, magicEmail, uiMode, emailEnabled);
          printHtmlFoot(appReq);
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          appReq.close();
        }
      } else {
        WebUser webUser = appReq.getWebUser();
        if (webUser != null && !WebUser.REGISTRATION_STATUS_ACTIVE.equals(webUser.getRegistrationStatus())) {
          response.sendRedirect("RegistrationServlet?status=setup");
          return;
        }
        if (webUser != null && !hasWorkspace(dataSession, webUser)) {
          appReq.logout();
          appReq.setMessageProblem("Account setup is incomplete. Please complete registration or contact support.");
          appReq.setTitle("Login");
          boolean emailEnabled = TrackerKeysManager.getApplicationKeyValueBoolean(
              TrackerKeysManager.KEY_SYSTEM_EMAIL_ENABLE, false, dataSession);
          printHtmlHead(appReq);
          printLoginForm(out, "", "", "", "desktop", emailEnabled);
          printHtmlFoot(appReq);
          return;
        }
        String uiMode = request.getParameter("uiMode");
        String target = Authenticate.APP_DEFAULT_HOME;
        if ("STUDENT".equals(webUser.getWorkflowType())) {
          target = "student/school";
        } else if (uiMode != null && uiMode.equals("mobile")) {
          target = "m/todo";
        }
        response.sendRedirect(target);
        return;
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      appReq.close();
    }
  }

  private boolean login(HttpServletRequest request, AppReq appReq, Session dataSession,
      boolean loginSuccess) {
    String username = request.getParameter("username");
    String password = request.getParameter("password");
    String message = null;
    if (username != null && username.length() > 0 && password != null && password.length() > 0) {

      Query query = dataSession.createQuery("from WebUser where username = ? and password = ?");
      query.setParameter(0, username);
      query.setParameter(1, password);
      @SuppressWarnings("unchecked")
      List<WebUser> webUserList = query.list();

      if (webUserList.size() > 0) {
        WebUser webUser = hydrateWebUserForSession(dataSession, webUserList.get(0).getWebUserId());
        webUser.setLastLoginDate(new Date());
        Transaction trans = dataSession.beginTransaction();
        dataSession.update(webUser);
        trans.commit();

        String welcomeMessage = initializeUserSession(appReq, dataSession, webUser);
        RememberMeManager.issueRememberMeCookie(appReq.getResponse(), webUser, dataSession);
        loginSuccess = true;
        message = welcomeMessage;

      } else {
        message = "Invalid username or password";
      }
    } else {
      message = "Username and password required";
    }
    if (message != null) {
      appReq.setMessageProblem(message);
    }
    return loginSuccess;
  }

  private boolean loginWithMagicLink(HttpServletRequest request, AppReq appReq, Session dataSession,
      boolean requireConfirmNonce) {
    MagicLinkValidationResult validationResult = validateMagicLinkRequest(request, dataSession);
    if (!validationResult.isValid()) {
      appReq.setMessageProblem(validationResult.getMessage());
      clearMagicConfirmNonce(request);
      return false;
    }

    if (requireConfirmNonce && !isMagicConfirmNonceValid(request)) {
      appReq.setMessageProblem("Magic link confirmation is invalid. Open the link again and confirm sign in.");
      clearMagicConfirmNonce(request);
      return false;
    }

    WebUser webUser = validationResult.getWebUser();
    Date now = new Date();

    webUser.setMagicLinkTokenHash(null);
    webUser.setMagicLinkExpiry(null);
    webUser.setLastLoginDate(now);
    webUser.setEmailVerified(true);
    if (webUser.getVerifiedDate() == null) {
      webUser.setVerifiedDate(now);
    }

    Transaction trans = dataSession.beginTransaction();
    dataSession.update(webUser);
    trans.commit();

    clearMagicConfirmNonce(request);
    initializeUserSession(appReq, dataSession, webUser);
    RememberMeManager.issueRememberMeCookie(appReq.getResponse(), webUser, dataSession);
    appReq.setMessageConfirmation("Signed in using magic link");
    return true;
  }

  private void renderMagicLinkConfirmationPage(HttpServletRequest request, AppReq appReq, Session dataSession)
      throws IOException {
    MagicLinkValidationResult validationResult = validateMagicLinkRequest(request, dataSession);
    PrintWriter out = appReq.getOut();

    setNoCacheHeaders(appReq.getResponse());
    appReq.setTitle("Magic Link Confirmation");
    printHtmlHead(appReq);

    out.println("<h2>Magic Link Sign-In</h2>");
    if (validationResult.isValid()) {
      String nonce = issueMagicConfirmNonce(request);
      out.println("<p>Your sign-in link is valid. Continue to sign in.</p>");
      out.println("<form action=\"LoginServlet\" method=\"POST\"> ");
      out.println("<input type=\"hidden\" name=\"action\" value=\"" + ACTION_MAGIC_LOGIN_CONFIRM + "\">");
      out.println("<input type=\"hidden\" name=\"" + PARAM_MAGIC_USER_ID + "\" value=\""
          + escapeHtml(request.getParameter(PARAM_MAGIC_USER_ID)) + "\">");
      out.println("<input type=\"hidden\" name=\"" + PARAM_MAGIC_TOKEN + "\" value=\""
          + escapeHtml(request.getParameter(PARAM_MAGIC_TOKEN)) + "\">");
      out.println("<input type=\"hidden\" name=\"" + PARAM_MAGIC_CONFIRM_NONCE + "\" value=\""
          + escapeHtml(nonce) + "\">");
      out.println("<p><input type=\"submit\" value=\"Sign In\"></p>");
      out.println("</form>");
    } else {
      clearMagicConfirmNonce(request);
      out.println("<p>" + escapeHtml(validationResult.getMessage()) + "</p>");
      out.println("<p><a href=\"LoginServlet\">Back to Login</a></p>");
    }

    printHtmlFoot(appReq);
  }

  private MagicLinkValidationResult validateMagicLinkRequest(HttpServletRequest request, Session dataSession) {
    String magicUserId = request.getParameter(PARAM_MAGIC_USER_ID);
    String magicToken = request.getParameter(PARAM_MAGIC_TOKEN);
    if (magicUserId == null || magicToken == null || magicUserId.equals("") || magicToken.equals("")) {
      return MagicLinkValidationResult.invalid("Magic link is invalid or missing");
    }

    int webUserId = 0;
    try {
      webUserId = Integer.parseInt(magicUserId);
    } catch (NumberFormatException nfe) {
      return MagicLinkValidationResult.invalid("Magic link is invalid or missing");
    }

    WebUser webUser = hydrateWebUserForSession(dataSession, webUserId);
    if (webUser == null || webUser.getMagicLinkTokenHash() == null || webUser.getMagicLinkExpiry() == null) {
      return MagicLinkValidationResult.invalid("Magic link is invalid or expired");
    }

    String tokenHash = hashToken(magicToken);
    Date now = new Date();
    if (!webUser.getMagicLinkTokenHash().equals(tokenHash) || webUser.getMagicLinkExpiry().before(now)) {
      return MagicLinkValidationResult.invalid("Magic link is invalid or expired");
    }

    return MagicLinkValidationResult.valid(webUser);
  }

  private String issueMagicConfirmNonce(HttpServletRequest request) {
    String nonce = UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
    HttpSession session = request.getSession(true);
    session.setAttribute(SESSION_MAGIC_CONFIRM_NONCE, nonce);
    return nonce;
  }

  private boolean isMagicConfirmNonceValid(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session == null) {
      return false;
    }
    Object expectedObj = session.getAttribute(SESSION_MAGIC_CONFIRM_NONCE);
    String expected = expectedObj == null ? "" : expectedObj.toString();
    String supplied = request.getParameter(PARAM_MAGIC_CONFIRM_NONCE);
    return supplied != null && !supplied.equals("") && supplied.equals(expected);
  }

  private void clearMagicConfirmNonce(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.removeAttribute(SESSION_MAGIC_CONFIRM_NONCE);
    }
  }

  private void setNoCacheHeaders(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setDateHeader("Expires", 0);
  }

  private static class MagicLinkValidationResult {
    private final boolean valid;
    private final String message;
    private final WebUser webUser;

    private MagicLinkValidationResult(boolean valid, String message, WebUser webUser) {
      this.valid = valid;
      this.message = message;
      this.webUser = webUser;
    }

    private static MagicLinkValidationResult valid(WebUser webUser) {
      return new MagicLinkValidationResult(true, null, webUser);
    }

    private static MagicLinkValidationResult invalid(String message) {
      return new MagicLinkValidationResult(false, message, null);
    }

    private boolean isValid() {
      return valid;
    }

    private String getMessage() {
      return message;
    }

    private WebUser getWebUser() {
      return webUser;
    }
  }

  private void sendMagicLink(HttpServletRequest request, AppReq appReq, Session dataSession) {
    String emailAddress = request.getParameter(PARAM_MAGIC_EMAIL);
    if (emailAddress == null) {
      emailAddress = "";
    }
    emailAddress = emailAddress.trim();
    if (emailAddress.equals("")) {
      appReq.setMessageProblem("Email address is required to send a magic link");
      return;
    }

    Query query = dataSession.createQuery(
        "from WebUser where lower(emailAddress) = ? and registrationStatus in (:activeStatus, :pendingStatus) order by webUserId desc");
    query.setParameter(0, emailAddress.toLowerCase());
    query.setParameter("activeStatus", WebUser.REGISTRATION_STATUS_ACTIVE);
    query.setParameter("pendingStatus", WebUser.REGISTRATION_STATUS_PENDING);
    @SuppressWarnings("unchecked")
    List<WebUser> webUserList = query.list();

    if (webUserList.size() > 0) {
      WebUser webUser = selectMagicLinkCandidate(webUserList);
      if (webUser == null) {
        appReq.setMessageConfirmation("If that email is registered, a sign-in link has been sent.");
        return;
      }
      webUser = hydrateWebUserForSession(dataSession, webUser.getWebUserId());
      String rawToken = UUID.randomUUID().toString().replace("-", "")
          + UUID.randomUUID().toString().replace("-", "");
      String tokenHash = hashToken(rawToken);
      Date expiry = new Date(System.currentTimeMillis() + (MAGIC_LINK_MINUTES_VALID * 60L * 1000L));

      Transaction trans = dataSession.beginTransaction();
      webUser.setMagicLinkTokenHash(tokenHash);
      webUser.setMagicLinkExpiry(expiry);
      dataSession.update(webUser);
      trans.commit();

      String magicLink = buildMagicLinkUrl(request, dataSession, webUser.getWebUserId(), rawToken);
      String body = "<p>A sign-in link was requested for your Dandelion account.</p>"
          + "<p><a href=\"" + magicLink + "\">Sign in to Dandelion</a></p>"
          + "<p>This link expires in " + MAGIC_LINK_MINUTES_VALID + " minutes.</p>";
      try {
        MailManager mailManager = new MailManager(dataSession);
        mailManager.sendEmail("Dandelion Sign-In Link", body, webUser.getEmailAddress());
      } catch (Exception e) {
        appReq.setMessageProblem("Unable to send magic link email: " + e.getMessage());
        return;
      }
    }

    appReq.setMessageConfirmation("If that email is registered, a sign-in link has been sent.");
  }

  private WebUser selectMagicLinkCandidate(List<WebUser> webUserList) {
    WebUser preferred = null;
    for (WebUser candidate : webUserList) {
      if (candidate.getUsername() != null && !candidate.getUsername().trim().equals("")) {
        preferred = candidate;
        break;
      }
    }
    if (preferred != null) {
      return preferred;
    }
    return webUserList.size() > 0 ? webUserList.get(0) : null;
  }

  private WebUser hydrateWebUserForSession(Session dataSession, int webUserId) {
    return (WebUser) dataSession.get(WebUser.class, webUserId);
  }

  private String initializeUserSession(AppReq appReq, Session dataSession, WebUser webUser) {
    appReq.setWebUser(webUser);
    appReq.setActiveWorkspaceId(WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId()));
    ProjectContact projectContact = (ProjectContact) dataSession.get(ProjectContact.class,
        webUser.getContactId());
    webUser.setProjectContact(projectContact);

    webUser.setTrackTime(TrackerKeysManager
        .getKeyValue(TrackerKeysManager.KEY_TRACK_TIME, "N", webUser, dataSession)
        .equalsIgnoreCase("Y"));

    webUser.setTimeZone(TimeZone.getTimeZone(TrackerKeysManager.getKeyValue(
        TrackerKeysManager.KEY_TIME_ZONE, WebUser.AMERICA_DENVER, webUser, dataSession)));
    webUser.setDateDisplayPattern(TrackerKeysManager.getKeyValue(
        TrackerKeysManager.KEY_DATE_DISPLAY_FORMAT,
        webUser.getDateDisplayPattern(), webUser, dataSession));
    webUser.setDateEntryPattern(TrackerKeysManager.getKeyValue(
        TrackerKeysManager.KEY_DATE_ENTRY_FORMAT,
        webUser.getDateEntryPattern(), webUser, dataSession));
    webUser.setTimeDisplayPattern(TrackerKeysManager.getKeyValue(
        TrackerKeysManager.KEY_TIME_DISPLAY_FORMAT,
        webUser.getTimeDisplayPattern(), webUser, dataSession));
    webUser.setTimeEntryPattern(TrackerKeysManager.getKeyValue(
        TrackerKeysManager.KEY_TIME_ENTRY_FORMAT,
        webUser.getTimeEntryPattern(), webUser, dataSession));

    if (webUser.isTrackTime()) {
      TimeTracker timeTracker = new TimeTracker(webUser, dataSession);
      appReq.setTimeTracker(timeTracker);
    }

    return "Welcome " + projectContact.getNameFirst() + " " + projectContact.getNameLast();
  }

  private String buildMagicLinkUrl(HttpServletRequest request, Session dataSession, int webUserId,
      String rawToken) {
    String externalUrl = TrackerKeysManager.getApplicationKeyValue(
        TrackerKeysManager.KEY_SYSTEM_EXTERNAL_URL, "", dataSession);
    String baseLoginUrl;
    if (externalUrl == null || externalUrl.trim().equals("")) {
      String requestUrl = request.getRequestURL().toString();
      String servletPath = request.getServletPath();
      int servletPathStart = requestUrl.indexOf(servletPath);
      String rootUrl = servletPathStart > 0 ? requestUrl.substring(0, servletPathStart + 1)
          : requestUrl;
      baseLoginUrl = rootUrl + "LoginServlet";
    } else {
      String normalized = externalUrl.trim();
      if (normalized.endsWith("LoginServlet")) {
        baseLoginUrl = normalized;
      } else {
        if (!normalized.endsWith("/")) {
          normalized += "/";
        }
        baseLoginUrl = normalized + "LoginServlet";
      }
    }

    try {
      return baseLoginUrl
          + "?action=" + URLEncoder.encode(ACTION_MAGIC_LOGIN, "UTF-8")
          + "&" + PARAM_MAGIC_USER_ID + "=" + webUserId
          + "&" + PARAM_MAGIC_TOKEN + "=" + URLEncoder.encode(rawToken, "UTF-8");
    } catch (UnsupportedEncodingException uee) {
      return baseLoginUrl
          + "?action=" + ACTION_MAGIC_LOGIN
          + "&" + PARAM_MAGIC_USER_ID + "=" + webUserId
          + "&" + PARAM_MAGIC_TOKEN + "=" + rawToken;
    }
  }

  private String hashToken(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.getBytes("UTF-8"));
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new RuntimeException("Unable to hash token", e);
    }
  }

  private void printLoginForm(PrintWriter out, String username, String password, String magicEmail,
      String uiMode, boolean emailEnabled) {
    String escapedUsername = escapeHtml(username);
    String escapedPassword = escapeHtml(password);
    String escapedMagicEmail = escapeHtml(magicEmail);

    if (!emailEnabled) {
      out.println("<form action=\"LoginServlet\" method=\"POST\">");
      out.println("<table>");
      out.println("  <tr>");
      out.println("    <td>Username</td>");
      out.println("    <td><input type=\"text\" name=\"username\" value=\"" + escapedUsername + "\"></td>");
      out.println("  </tr>");
      out.println("  <tr>");
      out.println("    <td>Password</td>");
      out.println(
          "    <td><input type=\"password\" name=\"password\" value=\"" + escapedPassword + "\"></td>");
      out.println("  </tr>");
      out.println("  <tr>");
      out.println("    <td>Mode</td>");
      out.println("    <td>");
      out.println("      <input type=\"radio\" name=\"uiMode\" value=\"desktop\""
          + (uiMode.equals("desktop") ? " checked" : "") + "> Desktop");
      out.println("      <input type=\"radio\" name=\"uiMode\" value=\"mobile\""
          + (uiMode.equals("mobile") ? " checked" : "") + "> Mobile");
      out.println("    </td>");
      out.println("  </tr>");
      out.println("  <tr>");
      out.println(
          "    <td colspan=\"2\" align=\"right\"><input type=\"submit\" name=\"action\" value=\"Login\"></td>");
      out.println("  </tr>");
      out.println("</table>");
      out.println("</form>");
    } else {
      out.println("<form action=\"LoginServlet\" method=\"POST\">");
      out.println("<table>");
      out.println("  <tr>");
      out.println("    <td colspan=\"2\"><b>Email Magic Link</b></td>");
      out.println("  </tr>");
      out.println("  <tr>");
      out.println("    <td>Email</td>");
      out.println("    <td><input type=\"text\" name=\"" + PARAM_MAGIC_EMAIL
          + "\" value=\"" + escapedMagicEmail + "\" size=\"40\"></td>");
      out.println("  </tr>");
      out.println("  <tr>");
      out.println("    <td colspan=\"2\" align=\"right\"><input type=\"submit\" name=\"action\" value=\""
          + ACTION_SEND_MAGIC_LINK + "\"></td>");
      out.println("  </tr>");
      out.println("</table>");
      out.println("</form>");
    }
    out.println("<p>&nbsp;</p>");
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

  private boolean hasWorkspace(Session dataSession, WebUser webUser) {
    return webUser != null
        && WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId()) != null;
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

}
