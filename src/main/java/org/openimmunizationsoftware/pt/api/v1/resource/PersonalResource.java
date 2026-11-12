package org.openimmunizationsoftware.pt.api.v1.resource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.dandeliondaily.personal.service.ActionAccessService;
import org.dandeliondaily.personal.service.ActionQuickChangeService;
import org.dandeliondaily.personal.service.ActionUndoService;
import org.dandeliondaily.personal.service.IdempotencyService;
import org.dandeliondaily.personal.service.PersonalActionJson;
import org.dandeliondaily.personal.service.PersonalDayService;
import org.dandeliondaily.personal.service.PersonalUserSupport;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.common.ApiRequestContext;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.api.v1.resource.dto.ApiErrorResponse;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.ObjectMapper;

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
    public static final int API_LEVEL = 2;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PersonalDayService dayService = new PersonalDayService();
    private final ActionAccessService accessService = new ActionAccessService();
    private final ActionQuickChangeService quickChangeService = new ActionQuickChangeService();
    private final ActionUndoService undoService = new ActionUndoService();
    private final IdempotencyService idempotencyService = new IdempotencyService();
    private final ActionNotesService notesService = new ActionNotesService();

    public static class MoveRequest {
        public String date;
        public String part;
    }

    public static class NoteRequest {
        public String text;
    }

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

    // ---- Writes (step 3). Each takes an Idempotency-Key header; a repeat gets the first answer. ----

    @POST
    @Path("/personal/actions/{id}/complete")
    @Operation(summary = "Complete a personal action; returns it and an undo token")
    public Response complete(@PathParam("id") final int actionNextId,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        return idempotent(idempotencyKey, "complete/" + actionNextId, new Write() {
            public Response run(Ctx c) {
                ActionNext action = requireOpenAction(c, actionNextId);
                return changed(c, quickChangeService.complete(c.session, c.webUser, action, actor(c)));
            }
        });
    }

    @POST
    @Path("/personal/actions/{id}/cancel")
    @Operation(summary = "Cancel a personal action; returns it and an undo token")
    public Response cancel(@PathParam("id") final int actionNextId,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        return idempotent(idempotencyKey, "cancel/" + actionNextId, new Write() {
            public Response run(Ctx c) {
                ActionNext action = requireOpenAction(c, actionNextId);
                return changed(c, quickChangeService.cancel(c.session, c.webUser, action, actor(c)));
            }
        });
    }

    @POST
    @Path("/personal/actions/{id}/move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Move a personal action to a date and part of the day")
    public Response move(@PathParam("id") final int actionNextId, final MoveRequest request,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        return idempotent(idempotencyKey, "move/" + actionNextId, new Write() {
            public Response run(Ctx c) {
                if (request == null || request.date == null) {
                    throw error(Response.Status.BAD_REQUEST, "bad_request", "date is required.");
                }
                LocalDate date;
                try {
                    date = LocalDate.parse(request.date.trim());
                } catch (Exception e) {
                    throw error(Response.Status.BAD_REQUEST, "bad_request", "date must be yyyy-MM-dd.");
                }
                TimeSlot slot = null;
                if (request.part != null && !request.part.trim().isEmpty()) {
                    slot = TimeSlot.getTimeSlot(request.part.trim());
                    if (slot == null) {
                        throw error(Response.Status.BAD_REQUEST, "bad_request",
                                "part must be WAKE, MORNING, AFTERNOON or EVENING.");
                    }
                }
                if (date.isBefore(c.today)) {
                    throw error(Response.Status.BAD_REQUEST, "bad_request", "Can't move an action into the past.");
                }
                ActionNext action = requireOpenAction(c, actionNextId);
                LocalDate current = ActionQuickChangeService.localDate(action.getNextActionDate());
                if (new PersonalActionJson(c.session, c.webUser).expires(action) && !date.equals(current)) {
                    throw error(Response.Status.CONFLICT, "routine_expires",
                            "This routine expires if it isn't done; the next day has its own.");
                }
                return changed(c, quickChangeService.move(c.session, action, date, slot, actor(c)));
            }
        });
    }

    @POST
    @Path("/personal/actions/{id}/notes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add a line to a personal action's notes")
    public Response addNote(@PathParam("id") final int actionNextId, final NoteRequest request,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        return idempotent(idempotencyKey, "notes/" + actionNextId, new Write() {
            public Response run(Ctx c) {
                ActionNext action = c.findAction(actionNextId);
                ActionNotesService.Result result = notesService.append(c.session, action,
                        request == null ? null : request.text, new Date());
                if (!result.isOk()) {
                    throw error(Response.Status.BAD_REQUEST, "bad_request", result.getErrorMessage());
                }
                Map<String, Object> json = new LinkedHashMap<String, Object>();
                json.put("action", new PersonalActionJson(c.session, c.webUser).detail(action, c.todayKey));
                return Response.ok(json).build();
            }
        });
    }

    @POST
    @Path("/personal/undo/{token}")
    @Operation(summary = "Undo a change this device made in the last 5 minutes")
    public Response undo(@PathParam("token") final String token,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        return idempotent(idempotencyKey, "undo/" + token, new Write() {
            public Response run(Ctx c) {
                ActionUndoService.Result result = undoService.undo(c.session, c.client.getClientId(), token,
                        new Date());
                switch (result.outcome) {
                case NOT_FOUND:
                    throw error(Response.Status.NOT_FOUND, "not_found", "No such change to undo.");
                case EXPIRED:
                    throw error(Response.Status.GONE, "expired", "Too late to undo that.");
                case ALREADY_UNDONE:
                    throw error(Response.Status.GONE, "already_undone", "That was already undone.");
                case CHANGED_ELSEWHERE:
                    throw error(Response.Status.CONFLICT, "changed_elsewhere",
                            "Couldn't undo: it was changed elsewhere.");
                default:
                    break;
                }
                Map<String, Object> json = new LinkedHashMap<String, Object>();
                json.put("undone", Boolean.TRUE);
                json.put("summary", result.summary);
                ActionNext action = accessService.findPersonalAction(c.session, c.webUser, c.workspaceId,
                        result.actionNextId);
                json.put("action", action == null ? null
                        : new PersonalActionJson(c.session, c.webUser).detail(action, c.todayKey));
                return Response.ok(json).build();
            }
        });
    }

    /** Everything a write needs about the caller. */
    private final class Ctx {
        final ApiRequestContext.ApiClientInfo client;
        final int workspaceId;
        final Session session;
        final WebUser webUser;
        final String todayKey;
        final LocalDate today;

        Ctx() {
            client = requireClient();
            workspaceId = requireWorkspaceId(client);
            session = HibernateRequestContext.getCurrentSession();
            webUser = requireWebUser(session);
            todayKey = todayKey(webUser);
            today = LocalDate.parse(todayKey);
        }

        ActionNext findAction(int id) {
            ActionNext action = accessService.findPersonalAction(session, webUser, workspaceId, id);
            if (action == null) {
                throw error(Response.Status.NOT_FOUND, "not_found", "No such personal action.");
            }
            return action;
        }
    }

    private interface Write {
        Response run(Ctx c);
    }

    private ActionNext requireOpenAction(Ctx c, int actionNextId) {
        ActionNext action = c.findAction(actionNextId);
        if (action.getNextActionStatus() != ProjectNextActionStatus.READY) {
            String status = action.getNextActionStatus() == null ? "closed"
                    : action.getNextActionStatus().getLabel().toLowerCase();
            throw error(Response.Status.CONFLICT, "not_open", "This action is already " + status + ".");
        }
        return action;
    }

    private Response changed(Ctx c, ActionQuickChangeService.Change change) {
        String token = undoService.save(c.session, c.client.getClientId(), change, new Date());
        PersonalActionJson json = new PersonalActionJson(c.session, c.webUser);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("action", json.detail(change.action, c.todayKey));
        result.put("undoToken", token);
        result.put("summary", change.summary);
        result.put("unblocked", change.unblocked == null || change.unblocked.isBillable() ? null
                : json.summary(change.unblocked, false));
        return Response.ok(result).build();
    }

    private static ActionQuickChangeService.Actor actor(Ctx c) {
        String name = c.client.getAgentName();
        return new ActionQuickChangeService.Actor(
                name == null || name.trim().isEmpty() ? "device " + c.client.getClientId() : name, "sundial");
    }

    /**
     * Runs a write once per Idempotency-Key. The response, success or refusal, is stored with the key,
     * so a retry gets the same answer without changing anything again.
     */
    private Response idempotent(String key, String path, Write write) {
        if (!IdempotencyService.isValidKey(key)) {
            throw error(Response.Status.BAD_REQUEST, "idempotency_key_required",
                    "Send an Idempotency-Key header (8-80 letters, digits, - _ . :).");
        }
        Ctx c = new Ctx();
        IdempotencyService.Stored stored = idempotencyService.find(c.session, c.client.getClientId(), key, path);
        if (stored != null) {
            if (stored.differentRequest) {
                throw error(Response.Status.CONFLICT, "idempotency_key_reused",
                        "This Idempotency-Key was already used for a different request.");
            }
            return Response.status(stored.status).entity(stored.body).type(MediaType.APPLICATION_JSON)
                    .header("Idempotent-Replay", "true").build();
        }
        Response response;
        try {
            response = write.run(c);
        } catch (WebApplicationException e) {
            // Refusals happen before anything is written; remember them so a retry is refused the same way.
            response = e.getResponse();
        }
        String body;
        try {
            body = MAPPER.writeValueAsString(response.getEntity());
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the response.", e);
        }
        idempotencyService.remember(c.session, c.client.getClientId(), key, path, response.getStatus(), body,
                new Date());
        return Response.status(response.getStatus()).entity(body).type(MediaType.APPLICATION_JSON).build();
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
