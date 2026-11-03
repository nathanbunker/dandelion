package org.openimmunizationsoftware.pt.api.v1.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openimmunizationsoftware.pt.api.v1.mcp.tools.AddProjectAiThoughtTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.AddProjectNarrativeTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.ApplyChangesTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.DeleteProjectAiThoughtTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.DeleteProjectNarrativeTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetDayAvailabilityTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetLinkedMeetingsTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.ListProjectsTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.SearchActionsTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetNarrativesTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.TrackerNarrativeTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetOutlookTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetPlanningContextTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetProjectContextTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetTimeAllocationContextTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetTimeEntriesTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.GetWorkDayReviewTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.ListOutlooksTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.SaveWorkDayReviewTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.SetDayAvailabilityTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.SetOutlookTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.UpdateProjectAiThoughtTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.UpdateProjectIconTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.UpdateProjectLanguageTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.UpdateProjectNarrativeTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.UpdateProjectReviewCadenceTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.UpdateTimeEntriesTool;

/** The small, task-oriented tool surface for Phases 0-4 (see assessment sections 4-5). */
public class McpToolRegistry {

    private final Map<String, McpTool> toolsByName = new LinkedHashMap<String, McpTool>();

    public McpToolRegistry() {
        register(new GetProjectContextTool());
        register(new ListProjectsTool());
        register(new SearchActionsTool());
        register(new GetPlanningContextTool());
        register(new GetLinkedMeetingsTool());
        register(new GetOutlookTool());
        register(new ListOutlooksTool());
        register(new SetOutlookTool());
        register(new GetTimeAllocationContextTool());
        register(new GetDayAvailabilityTool());
        register(new SetDayAvailabilityTool());
        register(new GetNarrativesTool());
        for (TrackerNarrativeTool.Operation operation : TrackerNarrativeTool.Operation.values()) {
            register(new TrackerNarrativeTool(operation));
        }
        register(new AddProjectAiThoughtTool());
        register(new UpdateProjectAiThoughtTool());
        register(new DeleteProjectAiThoughtTool());
        register(new UpdateProjectLanguageTool());
        register(new UpdateProjectReviewCadenceTool());
        register(new UpdateProjectIconTool());
        register(new GetWorkDayReviewTool());
        register(new SaveWorkDayReviewTool());
        register(new AddProjectNarrativeTool());
        register(new UpdateProjectNarrativeTool());
        register(new DeleteProjectNarrativeTool());
        register(new ApplyChangesTool());
        register(new GetTimeEntriesTool());
        register(new UpdateTimeEntriesTool());
    }

    private void register(McpTool tool) {
        toolsByName.put(tool.getName(), tool);
    }

    public List<McpTool> list() {
        return new ArrayList<McpTool>(toolsByName.values());
    }

    public McpTool require(String name) {
        McpTool tool = toolsByName.get(name);
        if (tool == null) {
            throw new McpToolException("unknown_tool", "No tool named \"" + name + "\".");
        }
        return tool;
    }
}
