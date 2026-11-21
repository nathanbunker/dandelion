package org.dandeliondaily.sessions.service;

import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.dandeliondaily.mcp.service.McpDayAvailabilityService;
import org.dandeliondaily.mcp.service.McpPlanningContextService;
import org.dandeliondaily.personal.service.IdempotencyService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.AgentRun;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a live agent attempt may read or add besides its own result, scoped to that attempt:
 * <ul>
 * <li>{@link #context}: a read-only planning snapshot (scheduled and overdue actions, meetings
 * with their linked occurrences, day capacity) for the run's planning date and the next few days,
 * so the work prep has the same Dandelion picture the planning tools give a person;</li>
 * <li>{@link #createTask}: for the personal Gmail workload only, one dated action in one of the
 * user's projects, linked to its source; idempotent by request key.</li>
 * </ul>
 * Nothing else: no updates, no completions, no other workspaces.
 */
public class RunScopedReadsService {

    public static final int CONTEXT_DAYS = 4;
    public static final String GMAIL_WORKLOAD = "gmail-triage";

    private final McpPlanningContextService planningService = new McpPlanningContextService();
    private final McpDayAvailabilityService availabilityService = new McpDayAvailabilityService();
    private final EligibleAgentWorkService eligibleService = new EligibleAgentWorkService();
    private final SessionActionFactory actionFactory = new SessionActionFactory();
    private final IdempotencyService idempotency = new IdempotencyService();
    private final ObjectMapper mapper = new ObjectMapper();

    /** A CLAIMED or RUNNING attempt of this user whose lease is still good, else a refusal. */
    AgentRun requireLive(Session session, WebUser webUser, String token, Date now) {
        if (token == null || !AgentRunService.TOKEN.matcher(token).matches()) {
            throw SessionException.bad("Malformed attempt token.");
        }
        AgentRun run = LockingReads.runByToken(session, token);
        if (run == null || run.getWebUserId() != webUser.getWebUserId()) {
            throw SessionException.notFound("Attempt");
        }
        boolean live = AgentRunService.STATUS_CLAIMED.equals(run.getRunStatus())
                || AgentRunService.STATUS_RUNNING.equals(run.getRunStatus());
        if (!live || now.after(run.getLeaseExpiresDate())) {
            throw SessionException.conflict("stale_attempt", "This attempt is no longer live.");
        }
        return run;
    }

    public Map<String, Object> context(Session session, WebUser webUser, int workspaceId, String token, Date now) {
        AgentRun run = requireLive(session, webUser, token, now);
        LocalDate start = webUser.toLocalDate(run.getPlanningDate());
        LocalDate end = start.plusDays(CONTEXT_DAYS);
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("planningDate", start.toString());
        map.put("today", webUser.getLocalDateToday().toString());
        map.put("timeZone", webUser.getZoneId().getId());
        map.put("planning", planningService.getPlanningContext(session, workspaceId, start, end, null, null, webUser));
        map.put("dayAvailability", availabilityService.getAvailability(session, webUser.getWebUserId(), start, end));
        return map;
    }

    /** The body of a task the Gmail workload asks for. */
    public static class TaskRequest {
        public String requestKey;
        public int projectId;
        public String description;
        public String scheduledDate;
        public String deadlineDate;
        public String linkUrl;
        public String notes;
    }

    public Map<String, Object> createTask(Session session, WebUser webUser, int workspaceId, int clientId,
            String token, TaskRequest task, Date now) throws Exception {
        AgentRun run = requireLive(session, webUser, token, now);
        ActionNext source = SessionAccess.find(session, webUser, workspaceId, run.getActionNextId());
        ActionAgentConfig config = source == null ? null : eligibleService.configFor(session, source);
        if (config == null || !GMAIL_WORKLOAD.equals(config.getWorkloadKey())) {
            throw new SessionException(403, "not_allowed", "Only the personal Gmail workload may create tasks.");
        }
        if (task == null || task.requestKey == null || !IdempotencyService.isValidKey(task.requestKey)) {
            throw SessionException.bad("\"requestKey\" is required.");
        }
        String path = "runner/tasks";
        IdempotencyService.Stored earlier = idempotency.find(session, clientId, task.requestKey, path);
        if (earlier != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(earlier.body, LinkedHashMap.class);
            body.put("replayed", Boolean.TRUE);
            return body;
        }
        Project project = (Project) session.get(Project.class, Integer.valueOf(task.projectId));
        if (!SessionAccess.canUseProject(project, webUser, workspaceId)
                || !"Active".equalsIgnoreCase(project.getProjectStatus())) {
            throw SessionException.notFound("Project");
        }
        if (task.description == null || task.description.trim().length() == 0) {
            throw SessionException.bad("\"description\" is required.");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(task.scheduledDate);
        } catch (Exception e) {
            throw SessionException.bad("\"scheduledDate\" must be yyyy-MM-dd.");
        }
        if (date.isBefore(webUser.getLocalDateToday())) {
            throw SessionException.bad("A task can't be scheduled in the past.");
        }
        String link = task.linkUrl == null ? "" : task.linkUrl.trim();
        if (link.length() > 0 && org.dandeliondaily.mcp.service.McpApplyChangesService.linkUrlError(link) != null) {
            throw SessionException.bad("\"linkUrl\" must be an http(s) URL.");
        }
        ActionNext action = actionFactory.create(session, webUser, project, task.description.trim(), null, date,
                task.notes, null, now);
        action.setLinkUrl(link);
        if (task.deadlineDate != null && task.deadlineDate.trim().length() > 0) {
            action.setNextDeadlineDate(java.sql.Date.valueOf(LocalDate.parse(task.deadlineDate.trim())));
        }
        session.update(action);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("actionNextId", Integer.valueOf(action.getActionNextId()));
        body.put("projectId", Integer.valueOf(project.getProjectId()));
        body.put("scheduledDate", date.toString());
        idempotency.remember(session, clientId, task.requestKey, path, 200, mapper.writeValueAsString(body), now);
        body.put("replayed", Boolean.FALSE);
        return body;
    }
}
