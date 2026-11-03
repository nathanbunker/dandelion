package org.dandeliondaily.shared.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.shared.service.ActionThreadService;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Display text for an action's thread (its earlier steps), shared by the Edit
 * Action box and the Focused Action page so both show a step the same way.
 */
public final class ActionThreadView {

    private ActionThreadView() {
    }

    /** The step's date in the user's short weekday format, or "". */
    public static String dateLabel(WebUser webUser, ActionThreadService.Step step) {
        if (step.getDate() == null) {
            return "";
        }
        return webUser.getDateFormatService().formatPattern(step.getDate(),
                webUser.getDateDisplayPatternWithWeekdayShort(), webUser.getTimeZone());
    }

    /** "Cancelled", "Open" or "" (completed, the usual case). */
    public static String statusLabel(ActionThreadService.Step step) {
        if ("X".equals(step.getStatus())) {
            return "Cancelled";
        }
        if ("C".equals(step.getStatus())) {
            return "";
        }
        return "Open";
    }

    /** What was done, or the step as planned when nothing was recorded. */
    public static String headline(ActionThreadService.Step step) {
        if (!step.getDone().isEmpty()) {
            return join(step.getDone(), "; ");
        }
        return step.getDescription() == null ? "" : step.getDescription();
    }

    /** "1h 15m", "40m" or "". */
    public static String minutesLabel(int minutes) {
        if (minutes <= 0) {
            return "";
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + "m";
        }
        return rest == 0 ? hours + "h" : hours + "h " + rest + "m";
    }

    /** The thread as plain values for a JSON response, newest step first. */
    public static List<Map<String, Object>> toJsonList(WebUser webUser, ActionThreadService.Thread thread) {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (ActionThreadService.Step step : thread.getSteps()) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("actionNextId", Integer.valueOf(step.getActionNextId()));
            map.put("dateLabel", dateLabel(webUser, step));
            map.put("status", statusLabel(step));
            map.put("headline", headline(step));
            map.put("planned", step.getDone().isEmpty() ? "" : n(step.getDescription()));
            map.put("outcome", join(step.getOutcomeNotes(), "\n\n"));
            map.put("notes", n(step.getNotes()));
            map.put("minutes", minutesLabel(step.getMinutes()));
            list.add(map);
        }
        return list;
    }

    public static String join(List<String> values, String separator) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(value.trim());
        }
        return sb.toString();
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }
}
