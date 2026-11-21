package org.openimmunizationsoftware.pt.api.v1.resource;

import java.util.Date;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.dandeliondaily.mcp.service.McpActionContextSupport;
import org.dandeliondaily.sessions.service.CloseOutJson;
import org.dandeliondaily.sessions.service.CloseOutService;
import org.dandeliondaily.sessions.service.LaunchService;
import org.dandeliondaily.sessions.service.SessionCommandService;
import org.dandeliondaily.sessions.service.SessionException;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.SoftwareVersion;
import org.openimmunizationsoftware.pt.api.common.ApiRequestContext;
import org.openimmunizationsoftware.pt.model.ActionLaunch;
import org.openimmunizationsoftware.pt.model.WebApiClient;
import org.openimmunizationsoftware.pt.model.WebUser;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * For the PC's {@code dandelion:} link handler and the /bail, /park and /close-out session
 * commands (LAUNCHER key only, see SessionClientScope). Every session call names its launch, so
 * it always acts on the action that launch is bound to.
 */
@Path("/v1/launcher")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Hidden
public class LauncherResource extends SessionApiSupport {

    private final LaunchService launchService = new LaunchService();
    private final SessionCommandService commandService = new SessionCommandService();
    private final CloseOutService closeOutService = new CloseOutService();

    @Context
    private HttpServletRequest request;

    public static class RedeemRequest {
        public String nonce;
        public String verb;
        public int actionNextId;
        public Integer folderId;
    }

    public static class SessionReport {
        public String sessionId;
    }

    public static class BailRequest {
        public String text;
    }

    public static class ParkRequest {
        public String handoff;
        public String date;
        public String requestKey;
    }

    private String hostEnv() {
        return LaunchService.environmentFor(forwardedHost(request),
                request.getServletContext().getInitParameter("dandelion.envKey"));
    }

    static String forwardedHost(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Host");
        if (forwarded != null && forwarded.trim().length() > 0) {
            return forwarded.split(",")[0].trim();
        }
        return request.getHeader("Host");
    }

    private void requireLauncher() {
        if (!WebApiClient.TYPE_LAUNCHER.equals(ApiRequestContext.getCurrentClient().getClientType())) {
            throw new javax.ws.rs.WebApplicationException(Response.status(403).build());
        }
    }

    @GET
    @Path("/identity")
    public Response identity() {
        requireLauncher();
        return Response.ok(identity(hostEnv())).build();
    }

    @POST
    @Path("/redeem")
    public Response redeem(final RedeemRequest body) {
        requireLauncher();
        final String env = hostEnv();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                if (body == null || body.verb == null || body.actionNextId <= 0) {
                    throw SessionException.bad("nonce, verb and actionNextId are required.");
                }
                return launchService.redeem(session, webUser, workspaceId, clientId, body.nonce, body.verb,
                        body.actionNextId, body.folderId, env, SoftwareVersion.VERSION, now());
            }
        });
    }

    @POST
    @Path("/launches/{launchId}/session-start")
    public Response sessionStart(@PathParam("launchId") final int launchId, final SessionReport body) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return launchService.bind(session, webUser, launchId, body == null ? null : body.sessionId, now());
            }
        });
    }

    @POST
    @Path("/launches/{launchId}/session-end")
    public Response sessionEnd(@PathParam("launchId") final int launchId) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return launchService.end(session, webUser, launchId, now());
            }
        });
    }

    @GET
    @Path("/launches/{launchId}/brief")
    public Response brief(@PathParam("launchId") final int launchId) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return commandService.brief(session, webUser, launchId);
            }
        });
    }

    @POST
    @Path("/launches/{launchId}/bail")
    public Response bail(@PathParam("launchId") final int launchId, final BailRequest body) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return commandService.bail(session, webUser, launchId, body == null ? null : body.text, now());
            }
        });
    }

    @POST
    @Path("/launches/{launchId}/park")
    public Response park(@PathParam("launchId") final int launchId, final ParkRequest body) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                if (body == null) {
                    throw SessionException.bad("A JSON body is required.");
                }
                return commandService.park(session, webUser, launchId, body.handoff, body.date, body.requestKey,
                        now());
            }
        });
    }

    @POST
    @Path("/launches/{launchId}/close-out/preview")
    public Response closeOutPreview(@PathParam("launchId") final int launchId, final String body) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                ActionLaunch launch = launchService.requireRedeemed(session, webUser, launchId);
                CloseOutService.Request req = CloseOutJson.parse(body, launch.getActionNextId(), false, false);
                CloseOutService.Plan plan = closeOutService.preview(session, webUser, launch.getWorkspaceId(), req);
                return CloseOutJson.planMap(plan);
            }
        });
    }

    @POST
    @Path("/launches/{launchId}/close-out/apply")
    public Response closeOutApply(@PathParam("launchId") final int launchId, final String body) {
        requireLauncher();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                ActionLaunch launch = launchService.requireRedeemed(session, webUser, launchId);
                CloseOutService.Request req = CloseOutJson.parse(body, launch.getActionNextId(), true, false);
                return closeOutService.apply(session, webUser, launch.getWorkspaceId(), req, now());
            }
        });
    }

    static Date now() {
        return McpActionContextSupport.truncatedNow();
    }
}
