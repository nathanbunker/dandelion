package org.openimmunizationsoftware.pt.api.v1.resource;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.dandeliondaily.personal.service.PersonalUserSupport;
import org.dandeliondaily.sessions.service.AgentRunService;
import org.dandeliondaily.sessions.service.SessionException;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.SoftwareVersion;
import org.openimmunizationsoftware.pt.api.common.ApiRequestContext;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.api.v1.resource.dto.ApiErrorResponse;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Shared plumbing for the runner and launcher endpoints. The whole request is one transaction
 * (HibernateSessionFilter). A {@link SessionException} means nothing should be kept, so it is
 * rolled back here before the error response is built (Jersey turns exceptions into responses,
 * so the filter would otherwise commit). An {@link AgentRunService.Outcome} with a non-200 status
 * recorded something on purpose (a failure) and is committed.
 */
abstract class SessionApiSupport extends BaseApiResource {

    interface Work {
        Object run(Session session, WebUser webUser, int workspaceId, int clientId);
    }

    protected Response handle(Work work) {
        ApiRequestContext.ApiClientInfo client = requireClient();
        int workspaceId = requireWorkspaceId(client);
        Session session = HibernateRequestContext.getCurrentSession();
        WebUser webUser = PersonalUserSupport.loadWebUser(session, client.getUsername());
        if (webUser == null) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ApiErrorResponse("unauthorized", "No user for this key.", null)).build());
        }
        try {
            Object result = work.run(session, webUser, workspaceId, client.getClientId());
            if (result instanceof AgentRunService.Outcome) {
                AgentRunService.Outcome outcome = (AgentRunService.Outcome) result;
                return Response.status(outcome.status).entity(outcome.body).build();
            }
            return Response.ok(result).build();
        } catch (SessionException refusal) {
            rollback(session);
            Map<String, Object> body = new LinkedHashMap<String, Object>();
            body.put("code", refusal.getCode());
            body.put("message", refusal.getMessage());
            if (refusal.getDetails() != null) {
                body.put("details", refusal.getDetails());
            }
            return Response.status(refusal.getStatus()).entity(body).build();
        } catch (RuntimeException unexpected) {
            rollback(session);
            throw unexpected;
        }
    }

    static void rollback(Session session) {
        try {
            if (session.getTransaction() != null && session.getTransaction().isActive()) {
                session.getTransaction().rollback();
            }
        } catch (RuntimeException ignored) {
            ignored.printStackTrace(System.err);
        }
    }

    static Map<String, Object> identity(String envKey) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("app", "dandelion");
        map.put("envKey", envKey);
        map.put("version", SoftwareVersion.VERSION);
        return map;
    }
}
