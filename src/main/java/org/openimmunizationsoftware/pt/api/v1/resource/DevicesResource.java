package org.openimmunizationsoftware.pt.api.v1.resource;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.dandeliondaily.personal.service.DeviceService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.common.ApiRequestContext;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.api.v1.resource.dto.ApiErrorResponse;
import org.openimmunizationsoftware.pt.model.WebApiClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Phones (Dandelion Sundial) sign in here. A new device is PENDING until its owner approves
 * it on the Devices page (DevicesServlet). See docs/plans/Android App/Sundial Plan.md.
 */
@Path("/v1/devices")
@Produces(MediaType.APPLICATION_JSON)
public class DevicesResource extends BaseApiResource {

    private final DeviceService deviceService = new DeviceService();

    public static class SignInRequest {
        public String username;
        public String password;
        public String deviceName;
    }

    @POST
    @Path("/sign-in")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Sign in a phone; returns its device key once. The device starts pending.")
    public Response signIn(SignInRequest request) {
        if (request == null) {
            throw error(Response.Status.BAD_REQUEST, "bad_request", "A JSON body is required.");
        }
        Session session = HibernateRequestContext.getCurrentSession();
        DeviceService.SignInResult result = deviceService.signIn(session, request.username, request.password,
                request.deviceName);
        if (!result.isOk()) {
            int status;
            if ("invalid_credentials".equals(result.errorCode)) {
                status = 401;
            } else if ("too_many_attempts".equals(result.errorCode)) {
                status = 429;
            } else {
                status = 400;
            }
            return Response.status(status).entity(new ApiErrorResponse(result.errorCode, result.errorMessage, null))
                    .build();
        }
        Map<String, Object> json = deviceJson(result.client);
        json.put("deviceKey", result.deviceKey);
        return Response.status(Response.Status.CREATED).entity(json).build();
    }

    @GET
    @Path("/me")
    @SecurityRequirement(name = "ApiKeyAuth")
    @Operation(summary = "This device's status: PENDING until approved, then APPROVED")
    public Map<String, Object> me() {
        return deviceJson(requireDevice());
    }

    @DELETE
    @Path("/me")
    @SecurityRequirement(name = "ApiKeyAuth")
    @Operation(summary = "Sign this phone out (revokes its key)")
    public Response signOut() {
        WebApiClient client = requireDevice();
        deviceService.revoke(HibernateRequestContext.getCurrentSession(), client);
        return Response.noContent().build();
    }

    private WebApiClient requireDevice() {
        ApiRequestContext.ApiClientInfo info = requireClient();
        WebApiClient client = (WebApiClient) HibernateRequestContext.getCurrentSession().get(WebApiClient.class,
                Integer.valueOf(info.getClientId()));
        if (client == null || !client.isDevice()) {
            throw error(Response.Status.FORBIDDEN, "not_a_device", "This key is not a device key.");
        }
        return client;
    }

    private static Map<String, Object> deviceJson(WebApiClient client) {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("clientId", client.getClientId());
        json.put("deviceName", client.getDeviceName());
        json.put("status", client.getDeviceStatus());
        json.put("username", client.getUsername());
        return json;
    }

    private static WebApplicationException error(Response.Status status, String code, String message) {
        return new WebApplicationException(
                Response.status(status).entity(new ApiErrorResponse(code, message, null)).build());
    }
}
