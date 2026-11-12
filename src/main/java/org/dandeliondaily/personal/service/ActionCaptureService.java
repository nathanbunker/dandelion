package org.dandeliondaily.personal.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.dandeliondaily.dashboard.service.ActionSentenceImportService;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.model.ActionChangeLog;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActorType;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Creating and editing personal actions from the phone (Sundial step 4): quick capture, Complete &
 * Next, Block on a new action, and Edit details. New actions come from the web's sentence parser
 * ({@link ActionSentenceImportService}), so "tuesday", "next friday", "for 30 minutes", "I might …"
 * and the "project: …" prefix work as they do on the dashboard. Everything stays personal: a sentence
 * can only name a personal project, and a result that would be billable is refused.
 *
 * All methods run inside the caller's open transaction. A {@link CaptureException} is thrown before
 * anything is saved.
 */
public class ActionCaptureService {

    /** The verbs offered for Complete & Next, as on /m/action. */
    public static final List<String> SENTENCE_VERBS = Arrays.asList("I will", "I might", "I am waiting",
            "I will meet", "I would like to", "I have committed");

    /** Action types the phone may set in Edit details. */
    public static final List<String> EDITABLE_TYPES = Arrays.asList(ProjectNextActionType.WILL,
            ProjectNextActionType.WILL_CONTACT, ProjectNextActionType.WILL_MEET, ProjectNextActionType.WILL_REVIEW,
            ProjectNextActionType.WILL_DOCUMENT, ProjectNextActionType.WILL_FOLLOW_UP, ProjectNextActionType.MIGHT,
            ProjectNextActionType.WOULD_LIKE_TO, ProjectNextActionType.COMMITTED_TO, ProjectNextActionType.GOAL,
            ProjectNextActionType.WAITING);

    public static final int MAX_DESCRIPTION = 1200;

    public static class CaptureException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String code;

        public CaptureException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** What the phone sends for a new action. Date and part override what the sentence says. */
    public static class NewAction {
        public String sentence;
        public Project project;
        public LocalDate date;
        public TimeSlot part;
    }

    /** Fields to change in Edit details; null leaves a field as it is. */
    public static class Edit {
        public String description;
        public String actionType;
        public LocalDate date;
        public boolean clearDate;
        public TimeSlot part;
        public String linkUrl;
        public Project project;
    }

    private final ActionSentenceImportService sentenceService = new ActionSentenceImportService();
    private final ActionQuickChangeService quickChangeService = new ActionQuickChangeService();
    private final PersonalProjectService projectService = new PersonalProjectService();

    /** Quick capture. The project comes from the "project:" prefix, else {@code request.project}. */
    public ActionNext capture(Session session, WebUser webUser, int workspaceId, NewAction request,
            ActionQuickChangeService.Actor actor) {
        ActionNext action = build(session, webUser, workspaceId, request);
        session.save(action);
        log(session, action, "create_action", "Captured on the phone", actor);
        return action;
    }

    /**
     * Completes the action and creates the next one it describes, in the same project unless the
     * sentence names another. A bare verb ("I will") just completes, like /m/action.
     */
    public ActionQuickChangeService.Change completeAndNext(Session session, WebUser webUser, int workspaceId,
            ActionNext action, NewAction next, ActionQuickChangeService.Actor actor) {
        ActionNext created = null;
        if (hasContent(next.sentence)) {
            if (next.project == null) {
                next.project = action.getProject();
            }
            created = build(session, webUser, workspaceId, next);
            session.save(created);
            log(session, created, "create_action", "Next after completing #" + action.getActionNextId(), actor);
        }
        ActionQuickChangeService.Change change = quickChangeService.complete(session, webUser, action, actor);
        if (created != null) {
            change.created.add(created);
            change.summary = change.summary + "; next: " + quote(created);
        }
        return change;
    }

    /**
     * Block on a new action (/m/ "Schedule & Block"): creates "what has to happen first" for today in the
     * same project, and parks this action until it's done (ProjectActionBlockerManager brings it back).
     */
    public ActionQuickChangeService.Change block(Session session, WebUser webUser, ActionNext action,
            String description, LocalDate today, ActionQuickChangeService.Actor actor) {
        String text = description == null ? "" : description.trim();
        if (text.isEmpty()) {
            throw new CaptureException("bad_request", "Say what has to happen first.");
        }
        if (text.length() > MAX_DESCRIPTION) {
            throw new CaptureException("bad_request", "That's too long.");
        }
        Project project = action.getProject();
        if (project == null || PersonalProjectService.isBillable(session, project)) {
            throw new CaptureException("work_project", "This action's project isn't personal.");
        }
        ActionQuickChangeService.Change change = quickChangeService.beginChange("block", action);

        ActionNext blocking = new ActionNext();
        blocking.setProject(project);
        blocking.setProjectId(action.getProjectId());
        blocking.setContact(webUser.getProjectContact());
        blocking.setContactId(webUser.getContactId());
        blocking.setWorkspaceId(action.getWorkspaceId());
        blocking.setNextDescription(text);
        blocking.setNextActionDate(java.sql.Date.valueOf(today));
        blocking.setTimeSlot(action.getTimeSlot() == null ? TimeSlot.AFTERNOON : action.getTimeSlot());
        blocking.setNextActionStatus(ProjectNextActionStatus.READY);
        blocking.setNextActionType(ProjectNextActionType.WILL);
        blocking.setBillable(false);
        blocking.setNextTimeEstimate(20);
        blocking.setNextChangeDate(new Date());
        blocking.setActionSet(new ActionSetDao(session).createStandardActionSet(webUser));
        session.save(blocking);

        action.setBlockedBy(blocking);
        action.setNextActionDate(null);
        action.setNextChangeDate(new Date());
        session.update(action);

        quickChangeService.endChange(change);
        change.created.add(blocking);
        change.summary = "Waiting on " + quote(blocking);
        log(session, action, "block", "Blocked by new action #" + blocking.getActionNextId(), actor);
        log(session, blocking, "create_action", "Blocks action #" + action.getActionNextId(), actor);
        return change;
    }

    /** Edit details. Returns a short description of what changed (for the change log). */
    public String edit(Session session, WebUser webUser, ActionNext action, Edit edit, LocalDate today,
            ActionQuickChangeService.Actor actor) {
        List<String> changed = new ArrayList<String>();
        if (edit.description != null) {
            String text = edit.description.trim();
            if (text.isEmpty()) {
                throw new CaptureException("bad_request", "The description can't be empty.");
            }
            if (text.length() > MAX_DESCRIPTION) {
                throw new CaptureException("bad_request", "The description is limited to 1,200 characters.");
            }
            if (!text.equals(action.getNextDescription())) {
                changed.add("description");
            }
            action.setNextDescription(text);
        }
        if (edit.actionType != null) {
            if (!EDITABLE_TYPES.contains(edit.actionType)) {
                throw new CaptureException("bad_request", "Unknown action type.");
            }
            String meetingError = LinkedMeetingService.actionTypeError(session, action, edit.actionType);
            if (meetingError != null) {
                throw new CaptureException("bad_request", meetingError);
            }
            if (!edit.actionType.equals(action.getNextActionType())) {
                changed.add("type");
            }
            action.setNextActionType(edit.actionType);
        }
        if (edit.clearDate) {
            if (action.getNextActionDate() != null) {
                changed.add("date");
            }
            action.setNextActionDate(null);
        } else if (edit.date != null) {
            LocalDate current = ActionQuickChangeService.localDate(action.getNextActionDate());
            if (!edit.date.equals(current)) {
                if (edit.date.isBefore(today)) {
                    throw new CaptureException("bad_request", "Can't move an action into the past.");
                }
                changed.add("date");
                action.setNextActionDate(java.sql.Date.valueOf(edit.date));
            }
        }
        if (edit.part != null) {
            if (edit.part != action.getTimeSlot()) {
                changed.add("part");
            }
            action.setTimeSlot(edit.part);
        }
        if (edit.linkUrl != null) {
            String link = edit.linkUrl.trim();
            if (link.length() > MAX_DESCRIPTION) {
                throw new CaptureException("bad_request", "The link is too long.");
            }
            String value = link.isEmpty() ? null : link;
            if (value == null ? action.getLinkUrl() != null : !value.equals(action.getLinkUrl())) {
                changed.add("link");
            }
            action.setLinkUrl(value);
        }
        if (edit.project != null && edit.project.getProjectId() != action.getProjectId()) {
            if (PersonalProjectService.isBillable(session, edit.project)) {
                throw new CaptureException("work_project", "Pick a personal project.");
            }
            changed.add("project");
            action.setProject(edit.project);
            action.setProjectId(edit.project.getProjectId());
            action.setPriorityLevel(edit.project.getPriorityLevel());
            action.setBillable(false);
        }
        if (changed.isEmpty()) {
            return "";
        }
        action.setNextChangeDate(new Date());
        session.update(action);
        String what = String.join(", ", changed);
        log(session, action, "update_action", "Edited " + what, actor);
        return what;
    }

    // ---- building a new action ----

    ActionNext build(Session session, WebUser webUser, int workspaceId, NewAction request) {
        String sentence = request.sentence == null ? "" : request.sentence.trim();
        if (sentence.isEmpty()) {
            throw new CaptureException("bad_request", "Say what to do.");
        }
        if (sentence.length() > MAX_DESCRIPTION) {
            throw new CaptureException("bad_request", "That's too long.");
        }
        List<Project> personal = projectService.listPersonalProjects(session, webUser, workspaceId);
        boolean prefixed = sentenceService.startsWithProjectToken(session, personal, sentence);
        if (!prefixed && request.project == null) {
            throw new CaptureException("project_required", "Pick a project, or start with \"project: \".");
        }
        if (prefixed && sentence.split(":", 2)[0].matches(".*[/-].*")) {
            // Linked-project aliases ("church/home: …") create shared twins; the phone doesn't yet.
            throw new CaptureException("bad_request", "Linked-project prefixes aren't supported on the phone yet.");
        }
        ActionNext action = sentenceService.buildActionFromSentence(webUser, session, request.project, personal,
                sentence, Integer.valueOf(workspaceId));
        if (action == null || !action.hasNextDescription()) {
            throw new CaptureException("bad_request", "Say what to do.");
        }
        if (action.isBillable() || PersonalProjectService.isBillable(session, action.getProject())) {
            throw new CaptureException("work_project", "Pick a personal project.");
        }
        action.setContact(webUser.getProjectContact());
        action.setContactId(webUser.getContactId());
        action.setPriorityLevel(action.getProject().getPriorityLevel());
        if (request.date != null) {
            action.setNextActionDate(java.sql.Date.valueOf(request.date));
        } else if (action.getNextActionDate() != null) {
            // The parser gives a moment ("now", "next tuesday"); keep the user's calendar day.
            action.setNextActionDate(java.sql.Date.valueOf(userDate(webUser, action.getNextActionDate())));
        }
        if (request.part != null) {
            action.setTimeSlot(request.part);
        }
        if (action.getNextActionDate() != null) {
            action.setNextActionStatus(ProjectNextActionStatus.READY);
        }
        return action;
    }

    static LocalDate userDate(WebUser webUser, Date moment) {
        if (moment instanceof java.sql.Date) {
            return ((java.sql.Date) moment).toLocalDate();
        }
        ZoneId zone = webUser.getTimeZone() == null ? ZoneId.systemDefault() : webUser.getTimeZone().toZoneId();
        return moment.toInstant().atZone(zone).toLocalDate();
    }

    /** True when the sentence says more than a bare verb. */
    public static boolean hasContent(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) {
            return false;
        }
        String s = sentence.trim();
        for (String verb : SENTENCE_VERBS) {
            if (s.equalsIgnoreCase(verb)) {
                return false;
            }
        }
        return true;
    }

    private static void log(Session session, ActionNext action, String kind, String reason,
            ActionQuickChangeService.Actor actor) {
        if (actor == null || action.getProject() == null) {
            return;
        }
        ActionChangeLog log = new ActionChangeLog();
        log.setAction(action);
        log.setProject(action.getProject());
        log.setChangeDate(new Date());
        log.setActorType(ActorType.USER);
        log.setActorId(clip(actor.id, 60));
        log.setSourceType(clip(actor.source, 24));
        log.setChangeSummary(kind);
        log.setChangeReason(reason);
        session.save(log);
    }

    private static String quote(ActionNext action) {
        String text = PersonalActionJson.plainText(action.getNextDescription());
        if (text == null) {
            text = "";
        }
        return "\"" + (text.length() > 60 ? text.substring(0, 57) + "…" : text) + "\"";
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
