package org.openimmunizationsoftware.pt.api.v1.resource;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.dandeliondaily.personal.service.ActionAccessService;
import org.dandeliondaily.personal.service.PersonalActionJson;
import org.dandeliondaily.personal.service.PersonalDayService;
import org.dandeliondaily.personal.service.PersonalUserSupport;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.common.ApiRequestContext;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.api.v1.resource.dto.ApiErrorResponse;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Read endpoints for Dandelion Sundial: personal (non-billable) actions only. Work actions
 * are never returned (PersonalDayService and ActionAccessService leave them out).
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "ApiKeyAuth")
public class PersonalResource extends BaseApiResource {

    /** Bumped when the phone API changes, so the app can say "Dandelion needs an update". */
    public static final int API_LEVEL = 1;

    private final PersonalDayService dayService = new PersonalDayService();
    private final ActionAccessService accessService = new ActionAccessService();

    @GET
    @Path("/me")
    @Operation(summary = "Who is signed in, their time zone and today's date")
    public Map<String, Object> me() {
        Session session = HibernateRequestContext.getCurrentSession();
        WebUser webUser = requireWebUser(session);
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("username", webUser.getUsername());
        ProjectContact contact = webUser.getProjectContact();
        json.put("firstName", contact == null ? null : contact.getNameFirst());
        json.put("timeZone", webUser.getTimeZone().getID());
        json.put("today", todayKey(webUser));
        json.put("apiLevel", API_LEVEL);
        return json;
    }

    @GET
    @Path("/personal/day")
    @Operation(summary = "A day's personal actions by part of the day; overdue ones when the day is today")
    public Map<String, Object> day(@QueryParam("date") String dateParam) {
        ApiRequestContext.ApiClientInfo client = requireClient();
        int workspaceId = requireWorkspaceId(client);
        Session session = HibernateRequestContext.getCurrentSession();
        WebUser webUser = requireWebUser(session);

        String todayKey = todayKey(webUser);
        String dateKey = dateParam == null || dateParam.trim().isEmpty() ? todayKey : dateParam.trim();
        Date selected;
        try {
            if (!dateKey.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new IllegalArgumentException();
            }
            selected = webUser.getDateFormatService().parseTransportDate(dateKey, webUser.getTimeZone());
        } catch (Exception e) {
            throw error(Response.Status.BAD_REQUEST, "bad_request", "date must be yyyy-MM-dd.");
        }
        // The same keys TodoServlet uses: the database (UTC) key and the user's key.
        String selectedKeyDb = PersonalDayService.databaseDateKey(selected);
        String selectedKeyUser = webUser.getDateFormatService().formatTransportDate(selected, webUser.getTimeZone());
        boolean isToday = selectedKeyUser.equals(todayKey);

        List<ActionNext> actions = dayService.fetchReadyActions(session, webUser, Integer.valueOf(workspaceId),
                selectedKeyDb);
        PersonalDayService.Day day = PersonalDayService.organize(actions, selectedKeyDb, selectedKeyUser, isToday);

        PersonalActionJson json = new PersonalActionJson(session, webUser);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("date", selectedKeyUser);
        result.put("today", todayKey);
        result.put("isToday", Boolean.valueOf(isToday));
        List<Map<String, Object>> overdue = new ArrayList<Map<String, Object>>();
        for (ActionNext action : day.getOverdue()) {
            overdue.add(json.summary(action, true));
        }
        result.put("overdue", overdue);
        Map<String, Object> parts = new LinkedHashMap<String, Object>();
        for (TimeSlot part : PersonalDayService.PARTS) {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            for (ActionNext action : day.getPart(part)) {
                list.add(json.summary(action, false));
            }
            parts.put(part.getId(), list);
        }
        result.put("parts", parts);
        return result;
    }

    @GET
    @Path("/personal/actions/{id}")
    @Operation(summary = "One personal action with its notes")
    public Map<String, Object> action(@PathParam("id") int actionNextId) {
        ApiRequestContext.ApiClientInfo client = requireClient();
        int workspaceId = requireWorkspaceId(client);
        Session session = HibernateRequestContext.getCurrentSession();
        WebUser webUser = requireWebUser(session);
        ActionNext action = accessService.findPersonalAction(session, webUser, workspaceId, actionNextId);
        if (action == null) {
            throw error(Response.Status.NOT_FOUND, "not_found", "No such personal action.");
        }
        return new PersonalActionJson(session, webUser).detail(action, todayKey(webUser));
    }

    private WebUser requireWebUser(Session session) {
        WebUser webUser = PersonalUserSupport.loadWebUser(session, requireClient().getUsername());
        if (webUser == null) {
            throw error(Response.Status.UNAUTHORIZED, "unauthorized", "The user for this key no longer exists.");
        }
        return webUser;
    }

    private static String todayKey(WebUser webUser) {
        Date today = TimeTracker.createToday(webUser).getTime();
        return webUser.getDateFormatService().formatTransportDate(today, webUser.getTimeZone());
    }

    private static WebApplicationException error(Response.Status status, String code, String message) {
        return new WebApplicationException(
                Response.status(status).entity(new ApiErrorResponse(code, message, null)).build());
    }
}
