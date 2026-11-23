package org.dandeliondaily.planahead.service;

import org.dandeliondaily.sessions.service.AgentCatalogService;
import org.dandeliondaily.sessions.service.EligibleAgentWorkService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;

/**
 * The template editor's "Performed by" and agent handoff fields, validated by {@link #of}. Callers
 * that don't send them (older forms) leave a template's agent settings as they are.
 */
class AgentTemplateFields {

    String agentKey;
    String handoffMode;
    Integer handoffTemplateId;
    Integer handoffProjectId;
    String workloadKey;

    /**
     * Validates "Performed by" and the agent handoff for a workspace; an empty {@code agent}
     * means a person. Throws IllegalArgumentException with a message for the user.
     */
    static AgentTemplateFields of(String agent, String handoffMode, String handoffTemplateId,
            String handoffProjectId, String workloadKey, int workspaceId, Session session) {
        AgentTemplateFields fields = new AgentTemplateFields();
        fields.agentKey = trim(agent).length() == 0 ? null : trim(agent);
        if (fields.agentKey != null && new AgentCatalogService().findActive(session, fields.agentKey) == null) {
            throw new IllegalArgumentException("Unknown agent \"" + fields.agentKey + "\".");
        }
        String mode = trim(handoffMode);
        fields.handoffMode = mode.length() == 0 ? EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW : mode;
        if (!EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW.equals(fields.handoffMode)
                && !EligibleAgentWorkService.MODE_ATTACH_TEMPLATE_INSTANCE.equals(fields.handoffMode)
                && !EligibleAgentWorkService.MODE_PER_TARGET_INSTANCE.equals(fields.handoffMode)) {
            throw new IllegalArgumentException("Unknown agent handoff.");
        }
        fields.handoffTemplateId = positive(handoffTemplateId, "template");
        fields.handoffProjectId = positive(handoffProjectId, "project");
        String workload = trim(workloadKey);
        if (workload.length() > 0 && !workload.matches("[a-z0-9][a-z0-9-]{0,39}")) {
            throw new IllegalArgumentException("A workload is lower-case letters, digits and dashes.");
        }
        fields.workloadKey = workload.length() == 0 ? null : workload;
        Integer workspace = Integer.valueOf(workspaceId);
        if (fields.agentKey != null && !EligibleAgentWorkService.MODE_SAME_PROJECT_REVIEW.equals(fields.handoffMode)) {
            if (fields.handoffTemplateId == null) {
                throw new IllegalArgumentException("Say which template's action the agent hands its work to.");
            }
            ActionNext target = (ActionNext) session.get(ActionNext.class, fields.handoffTemplateId);
            if (target == null || !target.isTemplate() || !workspace.equals(target.getWorkspaceId())) {
                throw new IllegalArgumentException("Template #" + fields.handoffTemplateId + " isn't one of your templates.");
            }
            if (EligibleAgentWorkService.MODE_ATTACH_TEMPLATE_INSTANCE.equals(fields.handoffMode)) {
                if (fields.handoffProjectId == null) {
                    fields.handoffProjectId = Integer.valueOf(target.getProjectId());
                }
                Project project = (Project) session.get(Project.class, fields.handoffProjectId);
                if (project == null || !workspace.equals(project.getWorkspaceId())) {
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
