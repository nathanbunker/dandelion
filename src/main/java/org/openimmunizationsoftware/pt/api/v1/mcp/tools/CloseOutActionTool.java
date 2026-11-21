package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.util.Map;

import org.dandeliondaily.mcp.service.McpActionContextSupport;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.dandeliondaily.personal.service.PersonalUserSupport;
import org.dandeliondaily.sessions.service.CloseOutJson;
import org.dandeliondaily.sessions.service.CloseOutService;
import org.dandeliondaily.sessions.service.SessionException;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Close out an action in one atomic step (the same service as the web close-out view): complete
 * it, create its next action(s), and rewrite, reschedule, cancel or merge the project's other open
 * actions. Preview first; apply only what the person confirmed.
 */
public class CloseOutActionTool implements McpTool {

    private final CloseOutService service = new CloseOutService();

    @Override
    public String getName() {
        return "close_out_action";
    }

    @Override
    public String getDescription() {
        return "Closes out an action: records its completion (completionDescription + outcomeNote, what reports "
                + "read), creates one or more next actions that follow it (or noSuccessor: true, which must be "
                + "chosen explicitly), and optionally changes other open actions on the same project: "
                + "{op: rewrite, description} | {op: reschedule, scheduledDate} | {op: cancel, reason?} | "
                + "{op: merge, survivorActionNextId, survivorAsOf, combinedDescription}. Each change needs that "
                + "action's asOf. mode=preview (default) writes nothing and returns every affected row before/after, "
                + "including linked twins and actions this unblocks, plus confirmRows. Show it to the person; after "
                + "they confirm, call again with mode=apply and the same fields plus confirmRows. Apply is one "
                + "transaction: if any listed row changed since the preview, nothing is applied. Template roots "
                + "and template-generated actions other than the one being closed are read-only here. "
                + "durationMinutes is optional and normally omitted (agents record no time).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> successor = McpSchema.object(McpSchema.properties(
                "description", McpSchema.string("What the next action is."),
                "scheduledDate", McpSchema.string("yyyy-MM-dd, today or later; omit for undated."),
                "notes", McpSchema.string("The brief for whoever does it."),
                "nextActionType", McpSchema.string("Optional action type, default WILL.")), "description");
        Map<String, Object> change = McpSchema.object(McpSchema.properties(
                "actionNextId", McpSchema.integer("Another open action on the same project."),
                "asOf", McpSchema.string("Its asOf from the last read."),
                "op", McpSchema.stringEnum("The change.", "rewrite", "reschedule", "cancel", "merge"),
                "description", McpSchema.string("rewrite: the new text."),
                "scheduledDate", McpSchema.string("reschedule: yyyy-MM-dd."),
                "reason", McpSchema.string("cancel: optional reason."),
                "survivorActionNextId", McpSchema.integer("merge: the open action that absorbs this one."),
                "survivorAsOf", McpSchema.string("merge: the survivor's asOf."),
                "combinedDescription", McpSchema.string("merge: the survivor's new text.")), "actionNextId", "asOf",
                "op");
        Map<String, Object> rowRef = McpSchema.object(McpSchema.properties(
                "actionNextId", McpSchema.integer("A row id from the preview."),
                "asOf", McpSchema.string("Its asOf from the preview.")), "actionNextId", "asOf");
        return McpSchema.object(McpSchema.properties(
                "actionNextId", McpSchema.integer("The action to close out."),
                "asOf", McpSchema.string("Its asOf from the last read."),
                "mode", McpSchema.stringEnum("preview (default) or apply.", "preview", "apply"),
                "completionDescription", McpSchema.string("What was done (the record reports read)."),
                "outcomeNote", McpSchema.string("Optional detail: what happened, decisions, where it was left."),
                "durationMinutes", McpSchema.integer("Optional; omit for agents (no time is recorded)."),
                "successors", McpSchema.array("Next actions to create, each following this one.", successor),
                "noSuccessor", McpSchema.bool("True only when the person chose no next action."),
                "changes", McpSchema.array("Changes to other open actions on the project.", change),
                "confirmRows", McpSchema.array("apply only: the confirmRows the preview returned.", rowRef)),
                "actionNextId", "asOf", "completionDescription");
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        int actionNextId = McpArgs.requireInt(arguments, "actionNextId");
        String mode = McpArgs.optString(arguments, "mode", "preview");
        if (!"preview".equals(mode) && !"apply".equals(mode)) {
            throw new McpToolException("invalid_arguments", "\"mode\" must be preview or apply.");
        }
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        WebUser zoned = PersonalUserSupport.loadWebUser(context.getSession(), context.getUsername());
        if (zoned != null) {
            webUser.setTimeZone(zoned.getTimeZone());
        }
        try {
            ObjectNode body = ((ObjectNode) arguments).deepCopy();
            body.remove("mode");
            CloseOutService.Request request = CloseOutJson.parse(body, actionNextId, "apply".equals(mode), true);
            if ("preview".equals(mode)) {
                return CloseOutJson.planMap(service.preview(context.getSession(), webUser, context.getWorkspaceId(),
                        request));
            }
            return service.apply(context.getSession(), webUser, context.getWorkspaceId(), request,
                    McpActionContextSupport.truncatedNow());
        } catch (SessionException refusal) {
            throw new McpToolException(refusal.getCode(), refusal.getMessage());
        }
    }
}
