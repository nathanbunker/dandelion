package org.dandeliondaily.personal.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.dandeliondaily.dashboard.service.ProjectDisplayLabelService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Turns a personal action into the JSON the phone reads. One instance per request: it
 * caches template settings and project names.
 *
 * <p>Template instances (routines from Plan Ahead) carry their template id and missed-action
 * behavior; {@code expires} is true for AUTO_CANCEL instances, which the app never shows as
 * overdue and never moves to tomorrow (Sundial Plan.md, change 11).
 */
public class PersonalActionJson {

    public static final String AUTO_CANCEL = "AUTO_CANCEL";

    private final Session session;
    private final WebUser webUser;
    private final ProjectDisplayLabelService projectLabels = new ProjectDisplayLabelService();
    private final Map<Integer, String> behaviorByTemplate = new HashMap<Integer, String>();
    private final Map<Integer, String> projectNames = new HashMap<Integer, String>();

    public PersonalActionJson(Session session, WebUser webUser) {
        this.session = session;
        this.webUser = webUser;
    }

    public Map<String, Object> summary(ActionNext action, boolean overdue) {
        Map<String, Object> json = new LinkedHashMap<String, Object>();
        json.put("id", action.getActionNextId());
        json.put("description", action.getNextDescription());
        json.put("displayText", plainText(action.getNextDescriptionForDisplay(webUser.getProjectContact())));
        json.put("actionType", emptyToNull(action.getNextActionType()));
        json.put("date", PersonalDayService.databaseDateKey(action.getNextActionDate()));
        json.put("part", PersonalDayService.partOf(action).getId());
        json.put("priorityLevel", action.getPriorityLevel());
        json.put("timeEstimateMins", action.getNextTimeEstimate());
        json.put("linkUrl", emptyToNull(action.getLinkUrl()));
        Project project = action.getProject();
        json.put("projectId", action.getProjectId() > 0 ? Integer.valueOf(action.getProjectId()) : null);
        json.put("projectName", project == null ? null : projectName(project));
        json.put("projectIcon", project == null ? null : emptyToNull(project.getProjectIcon()));
        Integer templateId = action.getTemplateActionNextId();
        String behavior = templateId == null ? null : missedActionBehavior(templateId);
        json.put("templateActionNextId", templateId);
        json.put("missedActionBehavior", behavior);
        json.put("expires", Boolean.valueOf(AUTO_CANCEL.equals(behavior)));
        json.put("overdue", Boolean.valueOf(overdue));
        return json;
    }

    public Map<String, Object> detail(ActionNext action, String todayDateKey) {
        String dateKey = PersonalDayService.databaseDateKey(action.getNextActionDate());
        boolean overdue = dateKey != null && todayDateKey != null && dateKey.compareTo(todayDateKey) < 0;
        Map<String, Object> json = summary(action, overdue);
        json.put("status", action.getNextActionStatus() == null ? null : action.getNextActionStatus().getId());
        json.put("notes", emptyToNull(action.getNextNotes()));
        json.put("deadline", PersonalDayService.databaseDateKey(action.getNextDeadlineDate()));
        return json;
    }

    /** True for an Auto Cancel template instance: it expires instead of moving to another day. */
    public boolean expires(ActionNext action) {
        Integer templateId = action.getTemplateActionNextId();
        return templateId != null && AUTO_CANCEL.equals(missedActionBehavior(templateId));
    }

    private String missedActionBehavior(Integer templateId) {
        if (!behaviorByTemplate.containsKey(templateId)) {
            ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(ActionNextTemplateConfig.class,
                    templateId);
            // No config row means the template uses the default, AUTO_CANCEL.
            behaviorByTemplate.put(templateId, config == null || config.getMissedActionBehavior() == null
                    ? AUTO_CANCEL
                    : config.getMissedActionBehavior());
        }
        return behaviorByTemplate.get(templateId);
    }

    private String projectName(Project project) {
        Integer id = Integer.valueOf(project.getProjectId());
        if (!projectNames.containsKey(id)) {
            projectNames.put(id, projectLabels.buildDisplayName(session, project));
        }
        return projectNames.get(id);
    }

    /** The display text without the italic markup the web pages use. */
    static String plainText(String html) {
        if (html == null) {
            return null;
        }
        String text = html.replaceAll("<[^>]*>", "");
        text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }
}
