package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.Map;

import org.dandeliondaily.mcp.service.McpTemplateService;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

public class UpdateTemplateTool implements McpTool {

    private final McpTemplateService service = new McpTemplateService();

    @Override
    public String getName() {
        return "update_template";
    }

    @Override
    public String getDescription() {
        return "Creates, changes, deactivates or reactivates a recurring action template (work or personal), after "
                + "the user has explicitly approved the change. It works like the Templates page: the generated "
                + "actions follow the template, so they can't be edited directly (apply_changes rejects them).\n"
                + "After every change Dandelion cascades it: future open actions from today on get the new "
                + "description, type, time slot, estimate, link and performer (and the new notes, unless someone "
                + "already wrote on that occurrence; today's is left alone if time was billed to it); open actions "
                + "on dates that no longer fit the schedule are cancelled; and new actions are generated through "
                + "the advance window (advanceDays in list_templates). The result's cascade says how many were "
                + "refreshed, cancelled and created, and template.upcoming shows the next ones.\n"
                + "Operations:\n"
                + "- create: {operation, mode, description, templateType, ...any fields below}. Defaults: "
                + "autoGenerate true, missedActionBehavior AUTO_CANCEL, nextActionType WILL (work), timeSlot "
                + "AFTERNOON (personal), estimate 0, no project.\n"
                + "- update: {operation, templateId, asOf?, ...only the fields to change}. Fields left out keep "
                + "their values; null clears a text field or the project. A template's mode can't change. "
                + "autoGenerate false pauses it and cancels its future open actions; true resumes it.\n"
                + "- deactivate: {operation, templateId, asOf?}. Same as Delete on the Templates page: the template "
                + "stops, and its open actions from today on are cancelled. Completed and past actions stay.\n"
                + "- reactivate: {operation, templateId}. Restores a deactivated template (list_templates with "
                + "includeInactive) or turns autoGenerate back on, and generates its upcoming actions.\n"
                + "Modes: WORK templates are billable, go on billable projects and use nextActionType. PERSONAL "
                + "templates go on non-billable projects, are always WILL actions and use timeSlot.\n"
                + "Schedule patterns, set on the field matching templateType (DAILY needs none; an empty pattern "
                + "means every day): scheduleDaysOfWeek like MON,WED,FRI; scheduleDaysOfMonth like 1,15 or "
                + "W1-MON (first Monday) or WL-FRI (last Friday); scheduleDaysOfQuarter like 1,46 (day of quarter) "
                + "or W1-MON, W13-FRI, WL-FRI; scheduleDaysOfYear like 0101,1225 (MMDD). Work templates only land "
                + "on working days; missedActionBehavior decides what happens to a missed or non-working-day "
                + "action: AUTO_CANCEL, CARRY_FORWARD (to the next working day) or IGNORE.\n"
                + "Agents: agent {performedBy, handoffMode?, handoffTemplateId?, handoffProjectId?, workloadKey?} "
                + "makes an agent (e.g. claude-code) perform each generated action; the template's notes are the "
                + "instructions each run starts from. handoffMode SAME_PROJECT_REVIEW (default) hands the result "
                + "to a new review action in the same project; ATTACH_TEMPLATE_INSTANCE attaches it to the open "
                + "action of template handoffTemplateId on the same date; PER_TARGET_INSTANCE makes one prep per "
                + "occurrence of template handoffTemplateId (such a template has no schedule of its own). "
                + "workloadKey names the PC permission profile (lower-case letters, digits, dashes). Keys left "
                + "out of agent keep their values; agent null hands the template back to a person.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return McpSchema.object(McpSchema.properties(
                "operation", McpSchema.stringEnum(null,
                        McpTemplateService.OP_CREATE, McpTemplateService.OP_UPDATE,
                        McpTemplateService.OP_DEACTIVATE, McpTemplateService.OP_REACTIVATE),
                "templateId", McpSchema.integer("The template, from list_templates. Not for create."),
                "asOf", McpSchema.string("The template's asOf from list_templates; refused as stale if it changed."),
                "mode", McpSchema.stringEnum("create only.", "WORK", "PERSONAL"),
                "description", McpSchema.string("What each generated action says."),
                "templateType", McpSchema.stringEnum(null, "DAILY", "WEEKLY", "MONTHLY", "QUARTERLY", "YEARLY"),
                "scheduleDaysOfWeek", McpSchema.string("WEEKLY pattern, e.g. MON,WED,FRI."),
                "scheduleDaysOfMonth", McpSchema.string("MONTHLY pattern, e.g. 1,15 or W1-MON or WL-FRI."),
                "scheduleDaysOfQuarter", McpSchema.string("QUARTERLY pattern, e.g. 1,46 or W13-FRI."),
                "scheduleDaysOfYear", McpSchema.string("YEARLY pattern, MMDD, e.g. 0101,1225."),
                "autoGenerate", McpSchema.bool("false pauses the template and cancels its future open actions."),
                "missedActionBehavior", McpSchema.stringEnum(null, "AUTO_CANCEL", "CARRY_FORWARD", "IGNORE"),
                "nextActionType", McpSchema.stringEnum("Work templates only.",
                        ProjectNextActionType.WILL, ProjectNextActionType.WILL_CONTACT,
                        ProjectNextActionType.WILL_MEET, ProjectNextActionType.WILL_REVIEW,
                        ProjectNextActionType.WILL_DOCUMENT, ProjectNextActionType.WILL_FOLLOW_UP,
                        ProjectNextActionType.COMMITTED_TO, ProjectNextActionType.MIGHT,
                        ProjectNextActionType.WOULD_LIKE_TO),
                "timeSlot", McpSchema.stringEnum("Personal templates only.", "WAKE", "MORNING", "AFTERNOON",
                        "EVENING"),
                "estimateMinutes", McpSchema.integer("Estimate for each generated action, in minutes."),
                "projectId", McpSchema.integer("The project; null or 0 for none."),
                "linkUrl", McpSchema.string("A link shown on each generated action; null clears it."),
                "notes", McpSchema.string("Replaces the template's notes (an agent's standing instructions); "
                        + "null clears them."),
                "agent", agentSchema(McpSchema.properties(
                        "performedBy", McpSchema.string("Agent key, e.g. claude-code; null or empty for a person."),
                        "handoffMode", McpSchema.stringEnum(null, "SAME_PROJECT_REVIEW", "ATTACH_TEMPLATE_INSTANCE",
                                "PER_TARGET_INSTANCE"),
                        "handoffTemplateId", McpSchema.integer("The other template, for the last two modes."),
                        "handoffProjectId", McpSchema.integer(
                                "ATTACH_TEMPLATE_INSTANCE: its project (defaults to that template's)."),
                        "workloadKey", McpSchema.string("PC permission profile, e.g. work-hygiene-prep.")))),
                "operation");
    }

    /** An object, or null to hand the template back to a person. */
    private static Map<String, Object> agentSchema(Map<String, Object> properties) {
        Map<String, Object> schema = McpSchema.object(properties);
        schema.put("type", java.util.Arrays.asList("object", "null"));
        schema.put("description", "Who performs each generated action; null for a person.");
        return schema;
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        return service.update(context.getSession(), context.getWorkspaceId(), webUser, arguments);
    }
}
