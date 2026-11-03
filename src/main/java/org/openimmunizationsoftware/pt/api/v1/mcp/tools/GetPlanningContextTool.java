package org.openimmunizationsoftware.pt.api.v1.mcp.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.mcp.service.McpPlanningContextService;
import org.dandeliondaily.mcp.service.McpWebUserSupport;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpArgs;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpSchema;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

import com.fasterxml.jackson.databind.JsonNode;

public class GetPlanningContextTool implements McpTool {

    private final McpPlanningContextService service = new McpPlanningContextService();

    @Override
    public String getName() {
        return "get_planning_context";
    }

    @Override
    public String getDescription() {
        return "Reads scheduled and overdue actions across all projects in a date range (inclusive), optionally "
                + "filtered by action type (e.g. WILL_MEET for meetings only) or project. Covers 'all meetings "
                + "this week' and 'everything scheduled Thursday' as narrower calls of the same tool. Each day's "
                + "actions are sorted the way the dashboard Today column shows them: grouped by dashboardBucket "
                + "(Start of Work Day, Overdue, Committed, Will, Personal (Morning), Might, Waiting, Will Meet, "
                + "End of Work Day, Other), then by completionOrder within the bucket. \"dayOrder\" lists each "
                + "day's buckets with their action ids in that order. "
                + "An action that continues an earlier one (its thread) also has followsActionNextId and history: up to 3 "
                + "earlier steps, newest first, each with description, status, date, done (what was recorded), "
                + "outcomeNotes, notes and minutes; historyContinues is true when the thread goes back further. "
                + "Read-only.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return McpSchema.object(
                McpSchema.properties(
                        "startDate", McpSchema.string("Inclusive start date, yyyy-MM-dd."),
                        "endDate", McpSchema.string("Inclusive end date, yyyy-MM-dd. Max 62 days after startDate."),
                        "actionTypes", McpSchema.array("Optional filter on ActionNext's action type.",
                                McpSchema.stringEnum(null,
                                        ProjectNextActionType.WILL, ProjectNextActionType.WILL_CONTACT,
                                        ProjectNextActionType.WILL_MEET, ProjectNextActionType.WILL_REVIEW,
                                        ProjectNextActionType.WILL_DOCUMENT, ProjectNextActionType.WILL_FOLLOW_UP,
                                        ProjectNextActionType.MIGHT, ProjectNextActionType.WOULD_LIKE_TO,
                                        ProjectNextActionType.COMMITTED_TO, ProjectNextActionType.GOAL,
                                        ProjectNextActionType.WAITING, ProjectNextActionType.OVERDUE_TO)),
                        "projectIds", McpSchema.array("Optional filter to only these project ids.",
                                McpSchema.integer(null))),
                "startDate", "endDate");
    }

    @Override
    public Object call(JsonNode arguments, McpToolContext context) {
        LocalDate startDate = parseDate(McpArgs.requireString(arguments, "startDate"), "startDate");
        LocalDate endDate = parseDate(McpArgs.requireString(arguments, "endDate"), "endDate");
        List<String> actionTypes = McpArgs.optStringList(arguments, "actionTypes");
        List<Integer> projectIds = McpArgs.optIntList(arguments, "projectIds");
        WebUser webUser = McpWebUserSupport.requireWebUser(context.getSession(), context.getUsername());
        return service.getPlanningContext(context.getSession(), context.getWorkspaceId(), startDate, endDate,
                actionTypes, projectIds, webUser);
    }

    private LocalDate parseDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new McpToolException("invalid_arguments", "\"" + field + "\" must be yyyy-MM-dd.");
        }
    }
}
