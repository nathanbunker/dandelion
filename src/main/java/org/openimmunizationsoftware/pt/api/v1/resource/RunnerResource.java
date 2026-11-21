package org.openimmunizationsoftware.pt.api.v1.resource;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.dandeliondaily.sessions.service.AgentRunService;
import org.dandeliondaily.sessions.service.LaunchService;
import org.dandeliondaily.sessions.service.SessionException;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.common.ApiRequestContext;
import org.openimmunizationsoftware.pt.model.WebApiClient;
import org.openimmunizationsoftware.pt.model.WebUser;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * For the PC's unattended agent runner (RUNNER key only, see SessionClientScope). The runner
 * polls for eligible work, claims one action at a time, renews its lease while the agent works,
 * and reports the agent's structured result or a failure. The server owns every decision and
 * every write.
 */
@Path("/v1/runner")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Hidden
public class RunnerResource extends SessionApiSupport {

    private final AgentRunService runService = new AgentRunService();
    private final org.dandeliondaily.sessions.service.RunScopedReadsService scopedReads =
            new org.dandeliondaily.sessions.service.RunScopedReadsService();

    @Context
    private HttpServletRequest request;

    public static class ClaimRequest {
        public int actionNextId;
    }

    public static class FailRequest {
        public String error;
        public String partialOutcome;
    }

    private void requireRunner() {
        if (!WebApiClient.TYPE_RUNNER.equals(ApiRequestContext.getCurrentClient().getClientType())) {
            throw new javax.ws.rs.WebApplicationException(Response.status(403).build());
        }
    }

    @GET
    @Path("/identity")
    public Response identity() {
        requireRunner();
        return Response.ok(identity(LaunchService.environmentFor(LauncherResource.forwardedHost(request),
                request.getServletContext().getInitParameter("dandelion.envKey")))).build();
    }

    @GET
    @Path("/eligible")
    public Response eligible(@QueryParam("agentKey") final String agentKey) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return runService.listEligible(session, webUser, workspaceId,
                        agentKey == null || agentKey.trim().length() == 0 ? null : agentKey.trim(),
                        LauncherResource.now());
            }
        });
    }

    @POST
    @Path("/claims")
    public Response claim(final ClaimRequest body) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                if (body == null || body.actionNextId <= 0) {
                    throw SessionException.bad("\"actionNextId\" is required.");
                }
                return runService.claim(session, webUser, workspaceId, clientId, body.actionNextId,
                        LauncherResource.now());
            }
        });
    }

    @POST
    @Path("/runs/{token}/heartbeat")
    public Response heartbeat(@PathParam("token") final String token) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return runService.heartbeat(session, webUser, workspaceId, token, LauncherResource.now());
            }
        });
    }

    @POST
    @Path("/runs/{token}/check-target")
    public Response checkTarget(@PathParam("token") final String token) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return runService.checkTarget(session, webUser, workspaceId, token, LauncherResource.now());
            }
        });
    }

    @GET
    @Path("/runs/{token}/context")
    public Response context(@PathParam("token") final String token) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return scopedReads.context(session, webUser, workspaceId, token, LauncherResource.now());
            }
        });
    }

    @POST
    @Path("/runs/{token}/tasks")
    public Response createTask(@PathParam("token") final String token,
            final org.dandeliondaily.sessions.service.RunScopedReadsService.TaskRequest body) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                try {
                    return scopedReads.createTask(session, webUser, workspaceId, clientId, token, body,
                            LauncherResource.now());
                } catch (SessionException refusal) {
                    throw refusal;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    @POST
    @Path("/runs/{token}/complete")
    public Response complete(@PathParam("token") final String token, final AgentRunService.Completion body) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return runService.complete(session, webUser, workspaceId, token, body, LauncherResource.now());
            }
        });
    }

    @POST
    @Path("/runs/{token}/fail")
    public Response fail(@PathParam("token") final String token, final FailRequest body) {
        requireRunner();
        return handle(new Work() {
            public Object run(Session session, WebUser webUser, int workspaceId, int clientId) {
                return runService.fail(session, webUser, workspaceId, token, body == null ? null : body.error,
                        body == null ? null : body.partialOutcome, LauncherResource.now());
            }
        });
    }
}
