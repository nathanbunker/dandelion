package org.dandeliondaily.sessions.service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.dandeliondaily.shared.service.ActionCompletionService;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.SharedActionSiblings;
import org.hibernate.LockMode;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSession;
import org.openimmunizationsoftware.pt.model.AgentPrepTarget;
import org.openimmunizationsoftware.pt.model.AgentRun;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectFolder;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The server side of an unattended agent run, one transaction per call:
 * <ul>
 * <li>{@link #claim}: rechecks eligibility, enforces at most {@value #MAX_ACTIVE} active runs per
 * user (across overlapping polls, under a lock on the user row) and one per action (unique key),
 * resolves and binds the one human target, and returns a unique attempt token with a renewable
 * lease.</li>
 * <li>{@link #heartbeat}: renews the lease.</li>
 * <li>{@link #complete}: completes the agent action (no time) and hands off to exactly one human
 * action: a new same-project review, or the configured existing instance (notes and session only).
 * A retry after a lost response returns the committed result.</li>
 * <li>{@link #fail}: leaves the action open with a redacted error in its notes, releases the claim,
 * and creates one same-project failure action for the attempt. No re-claim until Retry.</li>
 * </ul>
 * An attempt whose lease ran out is marked EXPIRED (visible, with a failure action) and any later
 * write from it is refused. Results that changed nothing come back as a {@link SessionException};
 * results that recorded something (a failure) come back as a {@link Outcome} with a non-200 status,
 * which the caller must commit.
 */
public class AgentRunService {

    public static final int MAX_ACTIVE = 3;
    public static final long LEASE_MS = 15L * 60L * 1000L;

    public static final String STATUS_CLAIMED = "CLAIMED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    public static final int MAX_ERROR_LENGTH = 2000;
    public static final String HANDOFF_MARKER = "Prepared by agent (prep #";

    static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final EligibleAgentWorkService eligibleService = new EligibleAgentWorkService();
    private final ProjectFolderService folderService = new ProjectFolderService();
    private final ActionSessionService sessionService = new ActionSessionService();
    private final ActionNotesService notesService = new ActionNotesService();
    private final ActionCompletionService completionService = new ActionCompletionService();
    private final SessionActionFactory actionFactory = new SessionActionFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    /** A result to commit: an HTTP status and a body. */
    public static class Outcome {
        public final int status;
        public final Map<String, Object> body;

        Outcome(int status, Map<String, Object> body) {
            this.status = status;
            this.body = body;
        }
    }

    /** Test hook: lets a test fail a write partway through to prove the transaction is atomic. */
    public interface FaultInjector {
        void at(String step);
    }

    private FaultInjector faults = new FaultInjector() {
        public void at(String step) {
        }
    };

    void setFaultInjector(FaultInjector faults) {
        this.faults = faults;
    }

    /** Serializes this user's claims, completions and prep creation across overlapping polls. */
    public static void lockUser(Session session, WebUser webUser) {
        session.createSQLQuery("select web_user_id from web_user where web_user_id = :id for update")
                .setParameter("id", Integer.valueOf(webUser.getWebUserId())).list();
    }

    // ---------------------------------------------------------------- poll

    public Map<String, Object> listEligible(Session session, WebUser webUser, int workspaceId, String agentKey,
            Date now) {
        lockUser(session, webUser);
        expireStale(session, webUser, workspaceId, now);
        LocalDate today = webUser.getLocalDateToday();
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (ActionNext action : eligibleService.eligible(session, webUser, workspaceId, agentKey, today, now)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("actionNextId", Integer.valueOf(action.getActionNextId()));
            item.put("agentKey", action.getPerformedByAgent());
            item.put("scheduledDate", webUser.toLocalDate(action.getNextActionDate()).toString());
            item.put("projectId", Integer.valueOf(action.getProjectId()));
            ActionAgentConfig config = eligibleService.configFor(session, action);
            item.put("workloadKey", config == null ? null : config.getWorkloadKey());
            items.add(item);
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("today", today.toString());
        map.put("activeRuns", Integer.valueOf(countActive(session, webUser)));
        map.put("maxActive", Integer.valueOf(MAX_ACTIVE));
        map.put("eligible", items);
        return map;
    }

    // ---------------------------------------------------------------- claim

    public Outcome claim(Session session, WebUser webUser, int workspaceId, int clientId, int actionNextId,
            Date now) {
        lockUser(session, webUser);
        expireStale(session, webUser, workspaceId, now);
        ActionNext action = SessionAccess.find(session, webUser, workspaceId, actionNextId);
        if (action == null) {
            throw SessionException.notFound("Action");
        }
        LockingReads.refresh(session, action);
        AgentRun latest = eligibleService.latestRun(session, actionNextId);
        if (latest != null && (STATUS_CLAIMED.equals(latest.getRunStatus())
                || STATUS_RUNNING.equals(latest.getRunStatus()))) {
            throw SessionException.conflict("already_claimed", "Another runner already holds this action.");
        }
        if (countActive(session, webUser) >= MAX_ACTIVE) {
            throw new SessionException(429, "limit_reached",
                    "At most " + MAX_ACTIVE + " agent runs at once; try again when one finishes.");
        }
        LocalDate today = webUser.getLocalDateToday();
        String reason = eligibleService.whyNotEligible(session, webUser, workspaceId, action, today);
        if (reason != null) {
            throw SessionException.conflict("not_eligible", "This action can't be run now (" + reason + ").");
        }
        AgentRun run = newRun(action, webUser, clientId, now);
        try {
            session.save(run);
            session.flush();
        } catch (org.hibernate.exception.ConstraintViolationException duplicate) {
            // The unique active-attempt key: another runner won this action a moment ago.
            throw SessionException.conflict("already_claimed", "Another runner already holds this action.");
        }

        Project project = SessionAccess.project(session, action);
        String refusal = null;
        if (SharedActionSiblings.resolve(session, action).size() > 1) {
            refusal = "This agent action is linked to other projects (shared). Agent runs only work on a single action.";
        } else if (blocksOthers(session, action)) {
            refusal = "Other actions wait on this one. Agent runs can't complete an action that blocks others.";
        }
        ProjectFolder folder = project == null ? null : folderService.primary(session, project.getProjectId());
        if (refusal == null && folder == null) {
            refusal = "The project has no primary folder, so the agent has nowhere to work. Set one on the project.";
        }
        ActionAgentConfig config = eligibleService.configFor(session, action);
        Target target = null;
        if (refusal == null) {
            target = resolveTarget(session, webUser, workspaceId, action, run, config);
            refusal = target.refusal;
        }
        if (refusal != null) {
            return withStatus(422, failInTx(session, webUser, run, action, refusal, null, STATUS_FAILED, now));
        }
        run.setRootKey(folder.getRootKey());
        run.setRelativePath(folder.getRelativePath());
        run.setTargetActionNextId(target.action == null ? null : Integer.valueOf(target.action.getActionNextId()));
        session.update(run);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("attemptToken", run.getAttemptToken());
        body.put("runId", Integer.valueOf(run.getRunId()));
        body.put("leaseExpiresAt", iso(run.getLeaseExpiresDate()));
        body.put("leaseSeconds", Long.valueOf(LEASE_MS / 1000L));
        body.put("sessionId", run.getSessionId());
        body.put("planningDate", webUser.toLocalDate(run.getPlanningDate()).toString());
        body.put("agentKey", action.getPerformedByAgent());
        body.put("workloadKey", config == null ? null : config.getWorkloadKey());
        Map<String, Object> folderMap = new LinkedHashMap<String, Object>();
        folderMap.put("root", folder.getRootKey());
        folderMap.put("path", folder.getRelativePath());
        body.put("folder", folderMap);
        Map<String, Object> actionMap = new LinkedHashMap<String, Object>();
        actionMap.put("actionNextId", Integer.valueOf(action.getActionNextId()));
        actionMap.put("description", action.getNextDescription());
        actionMap.put("notes", notesService.read(action));
        actionMap.put("projectId", Integer.valueOf(action.getProjectId()));
        actionMap.put("projectName", project == null ? null : project.getProjectName());
        body.put("action", actionMap);
        Map<String, Object> handoff = new LinkedHashMap<String, Object>();
        handoff.put("mode", EligibleAgentWorkService.modeOf(config));
        if (target.action != null) {
            handoff.put("targetActionNextId", Integer.valueOf(target.action.getActionNextId()));
            handoff.put("targetDescription", target.action.getNextDescription());
            handoff.put("targetProjectId", Integer.valueOf(target.action.getProjectId()));
        }
        body.put("handoff", handoff);
        return new Outcome(200, body);
    }

    private AgentRun newRun(ActionNext action, WebUser webUser, int clientId, Date now) {
        AgentRun run = new AgentRun();
        run.setAttemptToken(UUID.randomUUID().toString());
        run.setActionNextId(action.getActionNextId());
        run.setWebUserId(webUser.getWebUserId());
        run.setClientId(clientId);
        run.setRunStatus(STATUS_CLAIMED);
        run.setPlanningDate(java.sql.Date.valueOf(webUser.toLocalDate(action.getNextActionDate())));
        run.setSessionId(UUID.randomUUID().toString());
        run.setClaimedDate(now);
        run.setLeaseExpiresDate(new Date(now.getTime() + LEASE_MS));
        return run;
    }

    /** The human action a run hands off to, or why there can't be one. */
    static class Target {
        ActionNext action;
        String refusal;
        boolean alreadyHandedOff;
    }

    /**
     * Resolves the handoff target for the configured mode. SAME_PROJECT_REVIEW has none until
     * completion. ATTACH_TEMPLATE_INSTANCE finds the one open instance of the configured human
     * template, in the configured project, for this user, on the run's own planning date (so an
     * old run can never take today's instance). PER_TARGET_INSTANCE uses the prep's own target.
     * Missing, closed, ambiguous or occupied targets are refusals.
     */
    Target resolveTarget(Session session, WebUser webUser, int workspaceId, ActionNext prep, AgentRun run,
            ActionAgentConfig config) {
        Target target = new Target();
        String mode = EligibleAgentWorkService.modeOf(config);
        LocalDate planningDate = webUser.toLocalDate(run.getPlanningDate());
        if (EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW.equals(mode)) {
            return target;
        }
        ActionNext candidate = null;
        if (EligibleAgentWorkService.MODE_ATTACH_TEMPLATE_INSTANCE.equals(mode)) {
            if (config.getHandoffTemplateId() == null || config.getHandoffProjectId() == null) {
                target.refusal = "The agent template has no handoff target configured.";
                return target;
            }
            Project targetProject = (Project) session.get(Project.class, config.getHandoffProjectId());
            if (!SessionAccess.canUseProject(targetProject, webUser, workspaceId)) {
                target.refusal = "The handoff project " + config.getHandoffProjectId() + " isn't one of your projects.";
                return target;
            }
            Query query = session.createQuery("from ActionNext an where an.templateActionNextId = :templateId "
                    + "and an.projectId = :projectId and an.nextActionStatusString = :ready "
                    + "and an.nextActionDate = :date and an.workspaceId = :workspaceId "
                    + "and (an.contactId = :contactId or an.nextContactId = :contactId)");
            query.setParameter("templateId", config.getHandoffTemplateId());
            query.setParameter("projectId", config.getHandoffProjectId());
            query.setParameter("ready", ProjectNextActionStatus.READY.getId());
            query.setParameter("date", java.sql.Date.valueOf(planningDate));
            query.setParameter("workspaceId", Integer.valueOf(workspaceId));
            query.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
            @SuppressWarnings("unchecked")
            List<ActionNext> found = query.list();
            if (found.isEmpty()) {
                target.refusal = "There is no open action from template " + config.getHandoffTemplateId()
                        + " on " + planningDate + " to hand off to (missing or already closed).";
                return target;
            }
            if (found.size() > 1) {
                target.refusal = "More than one open action from template " + config.getHandoffTemplateId()
                        + " is on " + planningDate + "; not choosing one.";
                return target;
            }
            candidate = found.get(0);
        } else if (EligibleAgentWorkService.MODE_PER_TARGET_INSTANCE.equals(mode)) {
            Query query = session.createQuery("from AgentPrepTarget where prepActionNextId = :prepId");
            query.setParameter("prepId", Integer.valueOf(prep.getActionNextId()));
            AgentPrepTarget link = (AgentPrepTarget) query.uniqueResult();
            candidate = link == null ? null
                    : SessionAccess.find(session, webUser, workspaceId, link.getTargetActionNextId());
            if (candidate == null || !ProjectNextActionStatus.READY.getId().equals(candidate.getNextActionStatusString())
                    || candidate.getNextActionDate() == null
                    || !webUser.toLocalDate(candidate.getNextActionDate()).equals(planningDate)) {
                target.refusal = "The action this prep is for is no longer open on " + planningDate + ".";
                return target;
            }
        } else {
            target.refusal = "Unknown handoff mode \"" + mode + "\".";
            return target;
        }
        LockingReads.refresh(session, candidate);
        if (!ProjectNextActionStatus.READY.getId().equals(candidate.getNextActionStatusString())
                || candidate.getNextActionDate() == null
                || !webUser.toLocalDate(candidate.getNextActionDate()).equals(planningDate)) {
            target.refusal = "The handoff target #" + candidate.getActionNextId() + " is no longer open on "
                    + planningDate + ".";
            return target;
        }
        if (candidate.isTemplate()) {
            target.refusal = "The handoff target is a template, not one of its actions.";
            return target;
        }
        ActionSession link = sessionService.find(session, candidate.getActionNextId());
        if (link != null) {
            if (isOwnHandoff(session, link, prep.getActionNextId())) {
                target.alreadyHandedOff = true;
            } else {
                target.refusal = "Action #" + candidate.getActionNextId()
                        + " already has its own session; not replacing it.";
                return target;
            }
        }
        target.action = candidate;
        return target;
    }

    /** The target's session came from a run of this same agent action (a successful handoff). */
    private boolean isOwnHandoff(Session session, ActionSession link, int prepActionNextId) {
        if (link.getLaunchId() != null || link.getRunId() == null) {
            return false;
        }
        AgentRun owner = (AgentRun) session.get(AgentRun.class, link.getRunId());
        return owner != null && owner.getActionNextId() == prepActionNextId;
    }

    private boolean blocksOthers(Session session, ActionNext action) {
        Query query = session.createQuery("select count(*) from ActionNext an where an.blockedBy.actionNextId = :id "
                + "and an.nextActionStatusString = :ready");
        query.setParameter("id", Integer.valueOf(action.getActionNextId()));
        query.setParameter("ready", ProjectNextActionStatus.READY.getId());
        Number count = (Number) query.uniqueResult();
        return count != null && count.intValue() > 0;
    }

    /** A locking read (see EligibleAgentWorkService.latestRun) of this user's active attempts. */
    private int countActive(Session session, WebUser webUser) {
        Object count = session.createSQLQuery("select count(*) from agent_run where web_user_id = :userId "
                + "and run_status in ('CLAIMED', 'RUNNING') for update")
                .setParameter("userId", Integer.valueOf(webUser.getWebUserId())).uniqueResult();
        return count instanceof Number ? ((Number) count).intValue() : 0;
    }

    // ---------------------------------------------------------------- heartbeat / target check

    public Outcome heartbeat(Session session, WebUser webUser, int workspaceId, String token, Date now) {
        lockUser(session, webUser);
        AgentRun run = requireRun(session, webUser, token);
        Outcome stale = refuseIfNotLive(session, webUser, workspaceId, run, now);
        if (stale != null) {
            return stale;
        }
        run.setRunStatus(STATUS_RUNNING);
        run.setHeartbeatDate(now);
        run.setLeaseExpiresDate(new Date(now.getTime() + LEASE_MS));
        session.update(run);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("attemptToken", token);
        body.put("leaseExpiresAt", iso(run.getLeaseExpiresDate()));
        return new Outcome(200, body);
    }

    /**
     * Rechecks, without changing anything, that the run's source and target are still what it
     * claimed (e.g. the Gmail run calls this before its first mail change).
     */
    public Outcome checkTarget(Session session, WebUser webUser, int workspaceId, String token, Date now) {
        lockUser(session, webUser);
        AgentRun run = requireRun(session, webUser, token);
        Outcome stale = refuseIfNotLive(session, webUser, workspaceId, run, now);
        if (stale != null) {
            return stale;
        }
        String problem = revalidate(session, webUser, workspaceId, run);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("attemptToken", token);
        body.put("ok", Boolean.valueOf(problem == null));
        if (problem != null) {
            body.put("reason", problem);
            return new Outcome(409, body);
        }
        return new Outcome(200, body);
    }

    /** Null when the source is still open and the bound target is still the right one. */
    String revalidate(Session session, WebUser webUser, int workspaceId, AgentRun run) {
        ActionNext source = LockingReads.refresh(session,
                SessionAccess.find(session, webUser, workspaceId, run.getActionNextId()));
        if (source == null || !ProjectNextActionStatus.READY.getId().equals(source.getNextActionStatusString())) {
            return "The agent action is no longer open.";
        }
        if (run.getTargetActionNextId() == null) {
            return null;
        }
        ActionAgentConfig config = eligibleService.configFor(session, source);
        Target fresh = resolveTarget(session, webUser, workspaceId, source, run, config);
        if (fresh.refusal != null) {
            return fresh.refusal;
        }
        if (fresh.action == null || fresh.action.getActionNextId() != run.getTargetActionNextId().intValue()) {
            return "The handoff target changed since the run started.";
        }
        return null;
    }

    // ---------------------------------------------------------------- complete

    /** The structured result the runner commits for the agent. */
    public static class Completion {
        public String completionDescription;
        public String outcomeNote;
        public String handoffNotes;
        public String reviewDescription;
    }

    public Outcome complete(Session session, WebUser webUser, int workspaceId, String token, Completion completion,
            Date now) {
        lockUser(session, webUser);
        AgentRun run = requireRun(session, webUser, token);
        if (STATUS_SUCCEEDED.equals(run.getRunStatus())) {
            Map<String, Object> body = readResult(run);
            body.put("replayed", Boolean.TRUE);
            return new Outcome(200, body);
        }
        Outcome stale = refuseIfNotLive(session, webUser, workspaceId, run, now);
        if (stale != null) {
            return stale;
        }
        validate(completion);
        ActionNext source = SessionAccess.find(session, webUser, workspaceId, run.getActionNextId());
        String problem = revalidate(session, webUser, workspaceId, run);
        if (problem != null) {
            return withStatus(409,
                    failInTx(session, webUser, run, source, "Could not hand off: " + problem, null, STATUS_FAILED, now));
        }
        ActionAgentConfig config = eligibleService.configFor(session, source);
        Target target = run.getTargetActionNextId() == null ? new Target()
                : resolveTarget(session, webUser, workspaceId, source, run, config);

        faults.at("before-completion");
        completionService.applyCompletion(session, webUser, source, completion.completionDescription,
                completion.outcomeNote, ProjectNextActionStatus.COMPLETED, now, 0);
        faults.at("after-completion");
        sessionService.save(session, source.getActionNextId(), source.getPerformedByAgent(), run.getSessionId(),
                run.getRootKey(), run.getRelativePath(), null, Integer.valueOf(run.getRunId()),
                ActionSessionService.SOURCE_RUNNER, now);

        ActionNext review;
        if (target.action == null) {
            Project project = SessionAccess.project(session, source);
            String description = completion.reviewDescription == null
                    || completion.reviewDescription.trim().length() == 0
                            ? "Review: " + source.getNextDescription()
                            : completion.reviewDescription;
            review = actionFactory.create(session, webUser, project, description, null, webUser.getLocalDateToday(),
                    completion.handoffNotes, Integer.valueOf(source.getActionNextId()), now);
        } else {
            review = target.action;
            String marker = HANDOFF_MARKER + source.getActionNextId() + ", " + webUser.toLocalDate(run.getPlanningDate())
                    + ")";
            if (!notesService.read(review).contains(marker)) {
                String block = "--- " + marker + " ---\n" + completion.handoffNotes.trim()
                        + "\nSource: action #" + source.getActionNextId() + ". Resume opens the agent's session.";
                ActionNotesService.Result result = notesService.append(session, review, block, null);
                if (!result.isOk()) {
                    throw SessionException.bad("Handoff notes: " + result.getErrorMessage());
                }
            }
        }
        faults.at("before-handoff-session");
        ActionSession saved = target.alreadyHandedOff ? sessionService.find(session, review.getActionNextId())
                : sessionService.save(session, review.getActionNextId(), source.getPerformedByAgent(),
                        run.getSessionId(), run.getRootKey(), run.getRelativePath(), null,
                        Integer.valueOf(run.getRunId()), ActionSessionService.SOURCE_HANDOFF, now);
        if (saved == null) {
            throw SessionException.conflict("target_occupied", "The handoff target got its own session meanwhile.");
        }
        List<Integer> resolved = resolveEarlierFailures(session, webUser, source, run, now);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("attemptToken", token);
        body.put("status", STATUS_SUCCEEDED);
        body.put("resolvedFailureActionNextIds", resolved);
        body.put("sourceActionNextId", Integer.valueOf(source.getActionNextId()));
        body.put("reviewActionNextId", Integer.valueOf(review.getActionNextId()));
        body.put("handoffMode", EligibleAgentWorkService.modeOf(config));
        body.put("sessionId", run.getSessionId());
        run.setRunStatus(STATUS_SUCCEEDED);
        run.setFinishedDate(now);
        run.setResultJson(toJson(body));
        session.update(run);
        faults.at("after-run-recorded");
        body.put("replayed", Boolean.FALSE);
        return new Outcome(200, body);
    }

    /**
     * After a successful run, the earlier failure notices of the same agent action are done with:
     * each still-open one is completed ("Resolved by agent run N", no time).
     */
    @SuppressWarnings("unchecked")
    List<Integer> resolveEarlierFailures(Session session, WebUser webUser, ActionNext source, AgentRun run, Date now) {
        List<Integer> resolved = new ArrayList<Integer>();
        Query query = session.createQuery("from AgentRun where actionNextId = :id and runId <> :runId "
                + "and failureActionNextId is not null order by runId");
        query.setParameter("id", Integer.valueOf(source.getActionNextId()));
        query.setParameter("runId", Integer.valueOf(run.getRunId()));
        for (AgentRun earlier : (List<AgentRun>) query.list()) {
            ActionNext notice = (ActionNext) session.get(ActionNext.class, earlier.getFailureActionNextId());
            if (notice == null || !ProjectNextActionStatus.READY.getId().equals(notice.getNextActionStatusString())) {
                continue;
            }
            completionService.applyCompletion(session, webUser, notice, "Resolved by agent run " + run.getRunId(),
                    "Agent action #" + source.getActionNextId() + " succeeded on a later run; nothing more to do.",
                    ProjectNextActionStatus.COMPLETED, now, 0);
            resolved.add(Integer.valueOf(notice.getActionNextId()));
        }
        return resolved;
    }

    static void validate(Completion completion) {
        if (completion == null) {
            throw SessionException.bad("A JSON body is required.");
        }
        if (completion.completionDescription == null || completion.completionDescription.trim().length() == 0) {
            throw SessionException.bad("\"completionDescription\" is required.");
        }
        if (completion.completionDescription.length() > 1200) {
            throw SessionException.bad("\"completionDescription\" is limited to 1,200 characters.");
        }
        if (completion.handoffNotes == null || completion.handoffNotes.trim().length() == 0) {
            throw SessionException.bad("\"handoffNotes\" is required: the summary the person reviews.");
        }
        if (completion.handoffNotes.length() > 15000 || (completion.outcomeNote != null
                && completion.outcomeNote.length() > ActionNotesService.MAX_LENGTH)) {
            throw SessionException.bad("The handoff or outcome is too long.");
        }
    }

    // ---------------------------------------------------------------- fail / expire / retry

    public Outcome fail(Session session, WebUser webUser, int workspaceId, String token, String error,
            String partialOutcome, Date now) {
        lockUser(session, webUser);
        AgentRun run = requireRun(session, webUser, token);
        if (STATUS_SUCCEEDED.equals(run.getRunStatus())) {
            Map<String, Object> body = readResult(run);
            body.put("reason", "This attempt already succeeded; nothing was changed.");
            return new Outcome(409, body);
        }
        if (STATUS_FAILED.equals(run.getRunStatus())) {
            Map<String, Object> body = failureBody(run);
            body.put("replayed", Boolean.TRUE);
            return new Outcome(200, body);
        }
        Outcome stale = refuseIfNotLive(session, webUser, workspaceId, run, now);
        if (stale != null) {
            return stale;
        }
        ActionNext source = SessionAccess.find(session, webUser, workspaceId, run.getActionNextId());
        return failInTx(session, webUser, run, source, error, partialOutcome, STATUS_FAILED, now);
    }

    /**
     * Records a failure in the caller's transaction: the source stays open with the redacted error
     * appended, the claim is released, one failure action is created in the source's project for
     * today, and (Gmail) redacted partial outcomes are added to the human target.
     */
    Outcome failInTx(Session session, WebUser webUser, AgentRun run, ActionNext source, String error,
            String partialOutcome, String status, Date now) {
        String reason = redact(error == null || error.trim().length() == 0 ? "No error was reported." : error);
        String partial = partialOutcome == null || partialOutcome.trim().length() == 0 ? null : redact(partialOutcome);
        String stamp = stamp(webUser, now);
        ActionNext failure = null;
        if (source != null) {
            String block = "Agent run " + (STATUS_EXPIRED.equals(status) ? "expired" : "failed") + " " + stamp
                    + " (attempt " + run.getAttemptToken().substring(0, 8) + "): " + reason
                    + (partial == null ? "" : "\nPartly done: " + partial);
            notesService.append(session, source, block, null);
            Project project = SessionAccess.project(session, source);
            if (project != null) {
                failure = actionFactory.create(session, webUser, project,
                        SessionActionFactory.clip(source.getNextDescription(), 1100) + " - agent run "
                                + (STATUS_EXPIRED.equals(status) ? "expired" : "failed") + ", see notes",
                        null, webUser.getLocalDateToday(),
                        "The agent run for action #" + source.getActionNextId() + " "
                                + (STATUS_EXPIRED.equals(status) ? "expired" : "failed") + " " + stamp + ":\n"
                                + reason + (partial == null ? "" : "\nPartly done: " + partial)
                                + "\nIt won't run again until you press Retry here or on the action.",
                        Integer.valueOf(source.getActionNextId()), now);
            }
        }
        if (partial != null && run.getTargetActionNextId() != null) {
            ActionNext target = (ActionNext) session.get(ActionNext.class, run.getTargetActionNextId());
            if (target != null && ProjectNextActionStatus.READY.getId().equals(target.getNextActionStatusString())) {
                notesService.append(session, target, "Agent prep #" + run.getActionNextId() + " stopped partway "
                        + stamp + ". Done so far: " + partial, null);
            }
        }
        run.setRunStatus(status);
        run.setErrorText(reason);
        run.setFinishedDate(now);
        run.setFailureActionNextId(failure == null ? null : Integer.valueOf(failure.getActionNextId()));
        session.update(run);
        Map<String, Object> body = failureBody(run);
        body.put("replayed", Boolean.FALSE);
        return new Outcome(200, body);
    }

    private static Outcome withStatus(int status, Outcome outcome) {
        return new Outcome(status, outcome.body);
    }

    private Map<String, Object> failureBody(AgentRun run) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("attemptToken", run.getAttemptToken());
        body.put("status", run.getRunStatus());
        body.put("sourceActionNextId", Integer.valueOf(run.getActionNextId()));
        body.put("failureActionNextId", run.getFailureActionNextId());
        body.put("error", run.getErrorText());
        return body;
    }

    /** Runs whose lease ran out become EXPIRED, visibly, with a failure action each. */
    @SuppressWarnings("unchecked")
    public int expireStale(Session session, WebUser webUser, int workspaceId, Date now) {
        List<AgentRun> stale = LockingReads.staleRuns(session, webUser.getWebUserId(), now);
        for (AgentRun run : stale) {
            if (!(STATUS_CLAIMED.equals(run.getRunStatus()) || STATUS_RUNNING.equals(run.getRunStatus()))
                    || !now.after(run.getLeaseExpiresDate())) {
                continue;
            }
            ActionNext source = SessionAccess.find(session, webUser, workspaceId, run.getActionNextId());
            failInTx(session, webUser, run, source, "The runner stopped reporting; its lease ran out at "
                    + stamp(webUser, run.getLeaseExpiresDate()) + ". The attempt was abandoned.", null,
                    STATUS_EXPIRED, now);
        }
        return stale.size();
    }

    /**
     * Retry from the failure action or the agent action itself: the next poll may claim it again.
     * Returns the agent action's id.
     */
    public int retry(Session session, WebUser webUser, int workspaceId, int actionNextId, Date now) {
        Query query = session.createQuery("from AgentRun where (actionNextId = :id or failureActionNextId = :id) "
                + "and webUserId = :userId order by runId desc");
        query.setParameter("id", Integer.valueOf(actionNextId));
        query.setParameter("userId", Integer.valueOf(webUser.getWebUserId()));
        query.setMaxResults(1);
        AgentRun run = (AgentRun) query.uniqueResult();
        if (run == null) {
            throw SessionException.notFound("Agent run");
        }
        AgentRun latest = eligibleService.latestRun(session, run.getActionNextId());
        if (latest == null || !(STATUS_FAILED.equals(latest.getRunStatus())
                || STATUS_EXPIRED.equals(latest.getRunStatus()))) {
            throw SessionException.conflict("nothing_to_retry", "The last run of this action did not fail.");
        }
        ActionNext source = SessionAccess.find(session, webUser, workspaceId, latest.getActionNextId());
        if (source == null) {
            throw SessionException.notFound("Action");
        }
        if (!ProjectNextActionStatus.READY.getId().equals(source.getNextActionStatusString())) {
            throw SessionException.conflict("source_closed",
                    "The agent action #" + source.getActionNextId() + " is closed, so there is nothing to retry.");
        }
        if (latest.getRetryRequestedDate() == null) {
            latest.setRetryRequestedDate(now);
            session.update(latest);
        }
        return latest.getActionNextId();
    }

    /** What the Focused Action page shows about an agent action's last run, or null. */
    public Map<String, Object> describeLastRun(Session session, WebUser webUser, int actionNextId) {
        AgentRun latest = eligibleService.latestRun(session, actionNextId);
        if (latest == null) {
            Query query = session.createQuery("from AgentRun where failureActionNextId = :id order by runId desc");
            query.setParameter("id", Integer.valueOf(actionNextId));
            query.setMaxResults(1);
            latest = (AgentRun) query.uniqueResult();
            if (latest == null) {
                return null;
            }
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", latest.getRunStatus());
        map.put("sourceActionNextId", Integer.valueOf(latest.getActionNextId()));
        map.put("finishedAt", latest.getFinishedDate() == null ? null : stamp(webUser, latest.getFinishedDate()));
        map.put("error", latest.getErrorText());
        AgentRun sourceLatest = eligibleService.latestRun(session, latest.getActionNextId());
        boolean retryable = sourceLatest != null && sourceLatest.getRunId() == latest.getRunId()
                && (STATUS_FAILED.equals(latest.getRunStatus()) || STATUS_EXPIRED.equals(latest.getRunStatus()));
        ActionNext source = (ActionNext) session.get(ActionNext.class, Integer.valueOf(latest.getActionNextId()));
        boolean sourceOpen = source != null
                && ProjectNextActionStatus.READY.getId().equals(source.getNextActionStatusString());
        map.put("sourceOpen", Boolean.valueOf(sourceOpen));
        map.put("retryable", Boolean.valueOf(retryable && sourceOpen && latest.getRetryRequestedDate() == null));
        map.put("retryRequested", Boolean.valueOf(latest.getRetryRequestedDate() != null));
        return map;
    }

    // ---------------------------------------------------------------- helpers

    private AgentRun requireRun(Session session, WebUser webUser, String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw SessionException.bad("Malformed attempt token.");
        }
        AgentRun run = LockingReads.runByToken(session, token);
        if (run == null || run.getWebUserId() != webUser.getWebUserId()) {
            throw SessionException.notFound("Attempt");
        }
        return run;
    }

    /**
     * Null while the attempt is live. A finished attempt is refused; one whose lease ran out is
     * first marked EXPIRED (that is recorded), then refused.
     */
    private Outcome refuseIfNotLive(Session session, WebUser webUser, int workspaceId, AgentRun run, Date now) {
        String status = run.getRunStatus();
        if (STATUS_CLAIMED.equals(status) || STATUS_RUNNING.equals(status)) {
            if (!now.after(run.getLeaseExpiresDate())) {
                return null;
            }
            ActionNext source = SessionAccess.find(session, webUser, workspaceId, run.getActionNextId());
            Outcome expired = failInTx(session, webUser, run, source,
                    "The runner stopped reporting; its lease ran out at " + stamp(webUser, run.getLeaseExpiresDate())
                            + ". A late result from it was refused.",
                    null, STATUS_EXPIRED, now);
            expired.body.put("reason", "stale_attempt");
            return new Outcome(409, expired.body);
        }
        Map<String, Object> body = failureBody(run);
        body.put("reason", "stale_attempt");
        throw new SessionException(409, "stale_attempt", "This attempt is " + status.toLowerCase()
                + "; its result was not recorded.", body);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readResult(AgentRun run) {
        try {
            return run.getResultJson() == null ? new LinkedHashMap<String, Object>()
                    : mapper.readValue(run.getResultJson(), LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<String, Object>();
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Pattern URL = Pattern.compile("(?i)\\b(https?|webcals?|file)://[^\\s\"'<>]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|secret|password|passwd|authorization|bearer|x-api-key)\\b\\s*[:=]?\\s*[^\\s,;]+");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[^\\s,;]+");
    private static final Pattern WINDOWS_PATH = Pattern.compile("(?i)(\\b[a-z]:[\\\\/]|\\\\\\\\)[^\\s\"'<>|]*");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\b[A-Za-z0-9+/_=-]{24,}\\b");
    private static final Pattern DIGITS = Pattern.compile("\\b\\d{9,}\\b");

    /**
     * Keeps failure text safe to store and show: URLs lose their path and query, key/token
     * assignments and long opaque strings (keys, tokens, ids) become [redacted], long digit runs
     * (account numbers) become [number], and the text is capped.
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        String value = text.replace("\r\n", "\n").replace('\r', '\n');
        java.util.regex.Matcher m = URL.matcher(value);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String url = m.group();
            int start = url.indexOf("://") + 3;
            int end = start;
            while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
                end++;
            }
            String host = url.substring(start, end);
            int at = host.lastIndexOf('@');
            if (at >= 0) {
                host = host.substring(at + 1);
            }
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + "://" + host + "/[redacted]"));
        }
        m.appendTail(sb);
        value = sb.toString();
        value = WINDOWS_PATH.matcher(value).replaceAll("[path]");
        value = BEARER.matcher(value).replaceAll("$1 [redacted]");
        value = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1 [redacted]");
        value = LONG_TOKEN.matcher(value).replaceAll("[redacted]");
        value = DIGITS.matcher(value).replaceAll("[number]");
        value = value.trim();
        if (value.length() > MAX_ERROR_LENGTH) {
            value = value.substring(0, MAX_ERROR_LENGTH - 3) + "...";
        }
        return value;
    }

    static String stamp(WebUser webUser, Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        format.setTimeZone(webUser.getTimeZone());
        return format.format(date);
    }

    static String iso(Date date) {
        return date == null ? null : date.toInstant().toString();
    }
}
