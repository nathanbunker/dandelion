package org.dandeliondaily.sessions.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.dandeliondaily.shared.service.ActionNotesService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.AgentPrepTarget;
import org.openimmunizationsoftware.pt.model.AgentRun;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The one answer to "what agent work is due?", used by the runner's poll, its claim recheck and
 * the agent-key work-list MCP read. Dandelion's own recurrence and missed-action rules decide
 * which actions exist; this only picks the user's still-open agent actions scheduled today or
 * earlier, by the user's local date, that are not templates, not blocked, not already running and
 * not waiting on a Retry after a failure.
 *
 * <p>For a PER_TARGET_INSTANCE agent template (personal Gmail) it first makes sure each open,
 * due human target instance has exactly one prep (agent_prep_target), moves a prep with its
 * rescheduled target and cancels the prep of a closed target. Nothing is decided on the PC.
 */
public class EligibleAgentWorkService {

    public static final String MODE_SAME_PROJECT_REVIEW = "SAME_PROJECT_REVIEW";
    public static final String MODE_ATTACH_TEMPLATE_INSTANCE = "ATTACH_TEMPLATE_INSTANCE";
    public static final String MODE_PER_TARGET_INSTANCE = "PER_TARGET_INSTANCE";

    private final ActionNotesService notesService = new ActionNotesService();

    /** Today for a user, by the user's own time zone (not UTC, not the server's). */
    public static LocalDate localToday(Instant now, ZoneId zone) {
        return now.atZone(zone).toLocalDate();
    }

    /** Due from midnight of the scheduled day: today or earlier. */
    public static boolean isDue(LocalDate scheduled, LocalDate today) {
        return scheduled != null && !scheduled.isAfter(today);
    }

    /** Due, open agent actions for this user (optionally one agent), oldest first. */
    @SuppressWarnings("unchecked")
    public List<ActionNext> eligible(Session session, WebUser webUser, int workspaceId, String agentKey,
            LocalDate today, Date now) {
        syncPerTargetPreps(session, webUser, workspaceId, today, now);
        return eligibleWithoutSync(session, webUser, workspaceId, agentKey, today);
    }

    /** The same answer without creating or moving preps, for read-only callers (MCP). */
    @SuppressWarnings("unchecked")
    public List<ActionNext> eligibleWithoutSync(Session session, WebUser webUser, int workspaceId, String agentKey,
            LocalDate today) {
        Query query = session.createQuery("from ActionNext an where an.workspaceId = :workspaceId "
                + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                + "and an.performedByAgent is not null "
                + (agentKey == null ? "" : "and an.performedByAgent = :agentKey ")
                + "and an.nextActionStatusString = :ready "
                + "and (an.templateTypeString is null or an.templateTypeString = '') "
                + "and an.nextActionDate is not null and an.nextActionDate <= :today "
                + "order by an.nextActionDate, an.actionNextId");
        query.setParameter("workspaceId", Integer.valueOf(workspaceId));
        query.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
        if (agentKey != null) {
            query.setParameter("agentKey", agentKey);
        }
        query.setParameter("ready", ProjectNextActionStatus.READY.getId());
        query.setParameter("today", java.sql.Date.valueOf(today));
        List<ActionNext> candidates = query.list();
        List<ActionNext> eligible = new ArrayList<ActionNext>();
        for (ActionNext action : candidates) {
            if (whyNotEligible(session, webUser, workspaceId, action, today) == null) {
                eligible.add(action);
            }
        }
        return eligible;
    }

    /** Null when the action may be claimed now, otherwise the reason it may not. */
    public String whyNotEligible(Session session, WebUser webUser, int workspaceId, ActionNext action,
            LocalDate today) {
        if (!SessionAccess.canUse(action, webUser, workspaceId)) {
            return "not_found";
        }
        if (action.getPerformedByAgent() == null || action.getPerformedByAgent().length() == 0) {
            return "not_an_agent_action";
        }
        if (action.isTemplate()) {
            return "template";
        }
        if (!ProjectNextActionStatus.READY.getId().equals(action.getNextActionStatusString())) {
            return "not_open";
        }
        if (action.getBlockedBy() != null) {
            return "blocked";
        }
        if (!isDue(webUser.toLocalDate(action.getNextActionDate()), today)) {
            return "not_due";
        }
        AgentRun latest = latestRun(session, action.getActionNextId());
        if (latest != null) {
            String status = latest.getRunStatus();
            if (AgentRunService.STATUS_CLAIMED.equals(status) || AgentRunService.STATUS_RUNNING.equals(status)) {
                return "running";
            }
            if (AgentRunService.STATUS_SUCCEEDED.equals(status)) {
                return "already_done";
            }
            if (latest.getRetryRequestedDate() == null) {
                return "waiting_for_retry";
            }
        }
        return null;
    }

    public AgentRun latestRun(Session session, int actionNextId) {
        Query query = session.createQuery("from AgentRun where actionNextId = :id order by runId desc");
        query.setParameter("id", Integer.valueOf(actionNextId));
        query.setMaxResults(1);
        return (AgentRun) query.uniqueResult();
    }

    /** The agent config of an action: its own, else its template root's; null means defaults. */
    public ActionAgentConfig configFor(Session session, ActionNext action) {
        ActionAgentConfig own = (ActionAgentConfig) session.get(ActionAgentConfig.class,
                Integer.valueOf(action.getActionNextId()));
        if (own != null || action.getTemplateActionNextId() == null) {
            return own;
        }
        return (ActionAgentConfig) session.get(ActionAgentConfig.class, action.getTemplateActionNextId());
    }

    public static String modeOf(ActionAgentConfig config) {
        return config == null || config.getHandoffMode() == null ? MODE_SAME_PROJECT_REVIEW : config.getHandoffMode();
    }

    /**
     * For each PER_TARGET_INSTANCE agent template of this user: one prep per open, due target
     * instance (created once, keyed by the target), moved with a rescheduled target, cancelled
     * with a closed one. A prep that is running is left alone; the runner rechecks the target
     * before it changes anything outside Dandelion.
     */
    @SuppressWarnings("unchecked")
    public void syncPerTargetPreps(Session session, WebUser webUser, int workspaceId, LocalDate today, Date now) {
        Query configQuery = session.createQuery("from ActionAgentConfig where handoffMode = :mode");
        configQuery.setParameter("mode", MODE_PER_TARGET_INSTANCE);
        List<ActionAgentConfig> configs = configQuery.list();
        for (ActionAgentConfig config : configs) {
            ActionNext template = (ActionNext) session.get(ActionNext.class, Integer.valueOf(config.getActionNextId()));
            if (template == null || !template.isTemplate() || config.getHandoffTemplateId() == null
                    || ProjectNextActionStatus.CANCELLED.getId().equals(template.getNextActionStatusString())
                    || !SessionAccess.canUse(template, webUser, workspaceId)) {
                continue;
            }
            Project prepProject = SessionAccess.project(session, template);
            if (!SessionAccess.canUseProject(prepProject, webUser, workspaceId)) {
                continue;
            }
            // Existing preps follow their target.
            Query existingQuery = session.createQuery("from AgentPrepTarget where prepTemplateId = :templateId");
            existingQuery.setParameter("templateId", Integer.valueOf(template.getActionNextId()));
            List<AgentPrepTarget> links = existingQuery.list();
            List<Integer> targetsWithPrep = new ArrayList<Integer>();
            for (AgentPrepTarget link : links) {
                targetsWithPrep.add(Integer.valueOf(link.getTargetActionNextId()));
                ActionNext prep = (ActionNext) session.get(ActionNext.class, Integer.valueOf(link.getPrepActionNextId()));
                ActionNext target = (ActionNext) session.get(ActionNext.class,
                        Integer.valueOf(link.getTargetActionNextId()));
                if (prep == null || !ProjectNextActionStatus.READY.getId().equals(prep.getNextActionStatusString())) {
                    continue;
                }
                AgentRun latest = latestRun(session, prep.getActionNextId());
                boolean running = latest != null && (AgentRunService.STATUS_CLAIMED.equals(latest.getRunStatus())
                        || AgentRunService.STATUS_RUNNING.equals(latest.getRunStatus()));
                if (running) {
                    continue;
                }
                if (target == null || !ProjectNextActionStatus.READY.getId().equals(target.getNextActionStatusString())
                        || target.getNextActionDate() == null) {
                    prep.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
                    prep.setNextChangeDate(now);
                    session.update(prep);
                } else if (!webUser.toLocalDate(target.getNextActionDate())
                        .equals(webUser.toLocalDate(prep.getNextActionDate()))) {
                    prep.setNextActionDate(java.sql.Date.valueOf(webUser.toLocalDate(target.getNextActionDate())));
                    prep.setNextChangeDate(now);
                    session.update(prep);
                }
            }
            // New preps for due targets that have none.
            Query targetQuery = session.createQuery("from ActionNext an where an.templateActionNextId = :templateId "
                    + "and an.nextActionStatusString = :ready and an.nextActionDate is not null "
                    + "and an.nextActionDate <= :today and an.workspaceId = :workspaceId "
                    + "and (an.contactId = :contactId or an.nextContactId = :contactId) order by an.nextActionDate");
            targetQuery.setParameter("templateId", config.getHandoffTemplateId());
            targetQuery.setParameter("ready", ProjectNextActionStatus.READY.getId());
            targetQuery.setParameter("today", java.sql.Date.valueOf(today));
            targetQuery.setParameter("workspaceId", Integer.valueOf(workspaceId));
            targetQuery.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
            List<ActionNext> targets = targetQuery.list();
            for (ActionNext target : targets) {
                if (targetsWithPrep.contains(Integer.valueOf(target.getActionNextId()))) {
                    continue;
                }
                ActionNext prep = createPrep(session, webUser, template, prepProject,
                        webUser.toLocalDate(target.getNextActionDate()), now);
                AgentPrepTarget link = new AgentPrepTarget();
                link.setPrepActionNextId(prep.getActionNextId());
                link.setTargetActionNextId(target.getActionNextId());
                link.setPrepTemplateId(template.getActionNextId());
                link.setCreatedDate(now);
                session.save(link);
                targetsWithPrep.add(Integer.valueOf(target.getActionNextId()));
            }
        }
    }

    private ActionNext createPrep(Session session, WebUser webUser, ActionNext template, Project project,
            LocalDate date, Date now) {
        ActionNext prep = new SessionActionFactory().create(session, webUser, project, template.getNextDescription(),
                template.getNextActionType(), date, template.getNextNotes(), null, now);
        prep.setTemplateActionNextId(Integer.valueOf(template.getActionNextId()));
        prep.setPerformedByAgent(template.getPerformedByAgent());
        prep.setPriorityLevel(template.getPriorityLevel());
        session.update(prep);
        return prep;
    }

    /** Text for notes; kept for callers that want the action's current notes. */
    String notes(ActionNext action) {
        return notesService.read(action);
    }
}
