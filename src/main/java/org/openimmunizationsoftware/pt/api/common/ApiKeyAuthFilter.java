package org.openimmunizationsoftware.pt.api.common;

import java.util.Date;
import javax.annotation.Priority;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import org.dandeliondaily.personal.service.DeviceAccessRules;
import org.openimmunizationsoftware.pt.api.v1.resource.dto.ApiErrorResponse;
import org.openimmunizationsoftware.pt.model.WebApiClient;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyAuthFilter implements ContainerRequestFilter {

    private static final String API_KEY_HEADER = "X-Api-Key";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (isPublicPath(requestContext)) {
            return;
        }
        String apiKey = requestContext.getHeaderString(API_KEY_HEADER);
        if (apiKey == null || apiKey.trim().length() == 0) {
            abortUnauthorized(requestContext, "missing_api_key", "Missing API key.");
            return;
        }

        WebApiClientDao dao = new WebApiClientDao();
        WebApiClient client = dao.findByApiKey(apiKey.trim());
        if (client != null && client.isDevice()) {
            // Checked before "enabled": a revoked phone gets device_revoked, so it knows to sign out.
            DeviceAccessRules.Refusal refusal = DeviceAccessRules.check(client.getClientType(),
                    client.getDeviceStatus(), requestContext.getUriInfo().getPath());
            if (refusal != null) {
                requestContext.abortWith(Response.status(refusal.status)
                        .entity(new ApiErrorResponse(refusal.code, refusal.message, null)).build());
                return;
            }
        }
        if (client == null || !client.isEnabled()) {
            abortUnauthorized(requestContext, "invalid_api_key", "Invalid or disabled API key.");
            return;
        }

        ApiRequestContext.set(new ApiRequestContext.ApiClientInfo(
                client.getClientId(),
                client.getUsername(),
                client.getWorkspaceId(),
                client.getAgentName(),
                client.getClientType()));

        dao.touchLastUsedDate(client, new Date());
    }

    private void abortUnauthorized(ContainerRequestContext requestContext, String errorCode,
            String message) {
        ApiErrorResponse error = new ApiErrorResponse(errorCode, message, null);
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity(error).build());
    }

    private boolean isPublicPath(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null) {
            return false;
        }
        return path.equals("openapi.json") || path.startsWith("swagger-ui")
                || DeviceAccessRules.isPublicPath(path);
    }
}
