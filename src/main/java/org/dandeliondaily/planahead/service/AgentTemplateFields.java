package org.dandeliondaily.planahead.service;

import org.dandeliondaily.sessions.service.AgentCatalogService;
import org.dandeliondaily.sessions.service.EligibleAgentWorkService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;

/**
 * The template editor's "Performed by" and agent handoff fields. Null from {@link #read} when the
 * form did not send them (older callers), so nothing about agents changes then.
 */
class AgentTemplateFields {

    String agentKey;
    String handoffMode;
    Integer handoffTemplateId;
    Integer handoffProjectId;
    String workloadKey;

    static AgentTemplateFields read(AppReq appReq, Session session) {
        String agent = appReq.getRequest().getParameter("performedByAgent");
        if (agent == null) {
            return null;
        }
        AgentTemplateFields fields = new AgentTemplateFields();
        fields.agentKey = agent.trim().length() == 0 ? null : agent.trim();
        if (fields.agentKey != null && new AgentCatalogService().findActive(session, fields.agentKey) == null) {
            throw new IllegalArgumentException("Unknown agent \"" + fields.agentKey + "\".");
        }
        String mode = trim(appReq.getRequest().getParameter("agentHandoffMode"));
        fields.handoffMode = mode.length() == 0 ? EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW : mode;
        if (!EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW.equals(fields.handoffMode)
                && !EligibleAgentWorkService.MODE_ATTACH_TEMPLATE_INSTANCE.equals(fields.handoffMode)
                && !EligibleAgentWorkService.MODE_PER_TARGET_INSTANCE.equals(fields.handoffMode)) {
            throw new IllegalArgumentException("Unknown agent handoff.");
        }
        fields.handoffTemplateId = positive(appReq.getRequest().getParameter("agentHandoffTemplateId"), "template");
        fields.handoffProjectId = positive(appReq.getRequest().getParameter("agentHandoffProjectId"), "project");
        String workload = trim(appReq.getRequest().getParameter("agentWorkloadKey"));
        if (workload.length() > 0 && !workload.matches("[a-z0-9][a-z0-9-]{0,39}")) {
            throw new IllegalArgumentException("A workload is lower-case letters, digits and dashes.");
        }
        fields.workloadKey = workload.length() == 0 ? null : workload;
        if (fields.agentKey != null && !EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW.equals(fields.handoffMode)) {
            if (fields.handoffTemplateId == null) {
                throw new IllegalArgumentException("Say which template's action the agent hands its work to.");
            }
            ActionNext target = (ActionNext) session.get(ActionNext.class, fields.handoffTemplateId);
            if (target == null || !target.isTemplate()
                    || !target.getWorkspaceId().equals(appReq.getActiveWorkspaceId())) {
                throw new IllegalArgumentException("Template #" + fields.handoffTemplateId + " isn't one of your templates.");
            }
            if (EligibleAgentWorkService.MODE_ATTACH_TEMPLATE_INSTANCE.equals(fields.handoffMode)) {
                if (fields.handoffProjectId == null) {
                    fields.handoffProjectId = Integer.valueOf(target.getProjectId());
                }
                Project project = (Project) session.get(Project.class, fields.handoffProjectId);
                if (project == null || project.getWorkspaceId() == null
                        || !project.getWorkspaceId().equals(appReq.getActiveWorkspaceId())) {
                    throw new IllegalArgumentException("Project #" + fields.handoffProjectId + " isn't one of your projects.");
                }
            }
        }
        return fields;
    }

    /** True when this template's preps are derived per occurrence of another template. */
    static boolean isPerTarget(Session session, int templateId) {
        ActionAgentConfig config = (ActionAgentConfig) session.get(ActionAgentConfig.class, Integer.valueOf(templateId));
        return config != null && EligibleAgentWorkService.MODE_PER_TARGET_INSTANCE.equals(config.getHandoffMode());
    }

    /** Saves (or, for a person, removes) the agent config, in the caller's transaction. */
    void save(Session session, int templateId) {
        ActionAgentConfig config = (ActionAgentConfig) session.get(ActionAgentConfig.class, Integer.valueOf(templateId));
        if (agentKey == null) {
            if (config != null) {
                session.delete(config);
            }
            return;
        }
        boolean isNew = config == null;
        if (isNew) {
            config = new ActionAgentConfig();
            config.setActionNextId(templateId);
        }
        config.setHandoffMode(handoffMode);
        config.setHandoffTemplateId(handoffTemplateId);
        config.setHandoffProjectId(handoffProjectId);
        config.setWorkloadKey(workloadKey);
        if (isNew) {
            session.save(config);
        } else {
            session.update(config);
        }
    }

    private static Integer positive(String raw, String what) {
        String value = trim(raw);
        if (value.length() == 0) {
            return null;
        }
        try {
            int number = Integer.parseInt(value);
            if (number > 0) {
                return Integer.valueOf(number);
            }
        } catch (NumberFormatException ignored) {
            // falls through
        }
        throw new IllegalArgumentException("The handoff " + what + " must be a number.");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
