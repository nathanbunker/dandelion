package org.dandeliondaily.sessions.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dandeliondaily.mcp.service.McpActionContextSupport;
import org.dandeliondaily.shared.service.ActionCompletionService;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.SharedActionSiblings;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.ProjectActionDao;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Close out: complete an action with its completion description and outcome, create the next
 * action(s) that follow it, and rewrite, reschedule, cancel or merge the project's other open
 * actions, all at once. One shared service for the web close-out view, MCP close_out_action and
 * the /close-out session command.
 *
 * <p>{@link #preview} writes nothing: it lists every row the close-out would change, before and
 * after, including linked twins and actions this close-out unblocks. {@link #apply} rebuilds the
 * same plan, refuses if any previewed row changed since (naming it) or the set of rows differs,
 * and then applies everything in the caller's single transaction; any failure rolls all of it
 * back. Template roots are never touched; generated instances are read-only here ("edit via
 * template"). Time is recorded only when a duration is given (the web view never gives one, like
 * Done; MCP omits it).
 */
public class CloseOutService {

    public static final String OP_REWRITE = "rewrite";
    public static final String OP_RESCHEDULE = "reschedule";
    public static final String OP_CANCEL = "cancel";
    public static final String OP_MERGE = "merge";
    public static final int MAX_SUCCESSORS = 10;
    public static final int MAX_CHANGES = 50;

    private final ActionCompletionService completionService = new ActionCompletionService();
    private final ActionNotesService notesService = new ActionNotesService();
    private final HistoryEntryWriter historyWriter = new HistoryEntryWriter();

    /** Test hook: fail at a step to prove nothing is kept. */
    public interface FaultInjector {
        void at(String step);
    }

    private FaultInjector faults = new FaultInjector() {
        public void at(String step) {
        }
    };

    public void setFaultInjector(FaultInjector faults) {
        this.faults = faults;
    }

    public static class SuccessorSpec {
        public String description;
        public LocalDate scheduledDate;
        public String notes;
        public String actionType;
    }

    public static class ChangeSpec {
        public int actionNextId;
        public String asOf;
        public String op;
        public String description;
        public LocalDate scheduledDate;
        public String reason;
        public Integer survivorActionNextId;
        public String survivorAsOf;
        public String combinedDescription;
    }

    public static class RowRef {
        public int actionNextId;
        public String asOf;

        public RowRef() {
        }

        public RowRef(int actionNextId, String asOf) {
            this.actionNextId = actionNextId;
            this.asOf = asOf;
        }
    }

    public static class Request {
        public int actionNextId;
        public String asOf;
        public String completionDescription;
        public String outcomeNote;
        public int durationMinutes;
        public List<SuccessorSpec> successors = new ArrayList<SuccessorSpec>();
        public boolean noSuccessor;
        public List<ChangeSpec> changes = new ArrayList<ChangeSpec>();
        /** Apply only: every row the preview listed, with its asOf then. */
        public List<RowRef> confirmRows;
    }

    /** One row the close-out changes. New successors have actionNextId 0. */
    public static class Row {
        public int actionNextId;
        public String role;
        public String projectName;
        public String asOf;
        public String beforeDescription;
        public String beforeDate;
        public String beforeStatus;
        public String afterDescription;
        public String afterDate;
        public String afterStatus;
        /** Completion and outcome (completed row) or the brief (new next action). */
        public String detail;
    }

    public static class Plan {
        public final List<Row> rows = new ArrayList<Row>();
        public final List<String> errors = new ArrayList<String>();
        ActionNext closed;
        final List<ActionNext> changeActions = new ArrayList<ActionNext>();
        final List<ActionNext> survivors = new ArrayList<ActionNext>();

        public boolean isOk() {
            return errors.isEmpty();
        }
    }

    // ------------------------------------------------------------------ listing for the view

    /** An open action on the project, as the close-out view lists it. */
    public static class ProjectRow {
        public ActionNext action;
        public boolean generated;
        public Integer seriesTemplateId;
        public String seriesName;
        public List<String> twinProjects = new ArrayList<String>();
    }

    /**
     * Every other open action on the closed action's project the user can use, including undated
     * and future ones. Template roots are left out; generated instances come back marked, grouped
     * by series (ordered by series, then date).
     */
    @SuppressWarnings("unchecked")
    public List<ProjectRow> listOtherOpen(Session session, WebUser webUser, int workspaceId, ActionNext closed) {
        Query query = session.createQuery("from ActionNext an where an.projectId = :projectId "
                + "and an.nextActionStatusString = :ready and an.actionNextId <> :closedId "
                + "and (an.templateTypeString is null or an.templateTypeString = '') "
                + "order by case when an.templateActionNextId is null then 0 else 1 end, an.templateActionNextId, "
                + "case when an.nextActionDate is null then 1 else 0 end, an.nextActionDate, an.actionNextId");
        query.setParameter("projectId", Integer.valueOf(closed.getProjectId()));
        query.setParameter("ready", ProjectNextActionStatus.READY.getId());
        query.setParameter("closedId", Integer.valueOf(closed.getActionNextId()));
        List<ProjectRow> rows = new ArrayList<ProjectRow>();
        for (ActionNext action : (List<ActionNext>) query.list()) {
            if (!SessionAccess.canUse(action, webUser, workspaceId)) {
                continue;
            }
            ProjectRow row = new ProjectRow();
            row.action = action;
            row.generated = action.getTemplateActionNextId() != null;
            if (row.generated) {
                row.seriesTemplateId = action.getTemplateActionNextId();
                ActionNext template = (ActionNext) session.get(ActionNext.class, action.getTemplateActionNextId());
                row.seriesName = template == null ? "#" + action.getTemplateActionNextId() : template.getNextDescription();
            }
            for (ActionNext twin : SharedActionSiblings.resolve(session, action)) {
                if (twin.getActionNextId() != action.getActionNextId()) {
                    Project project = SessionAccess.project(session, twin);
                    row.twinProjects.add(project == null ? "#" + twin.getProjectId() : project.getProjectName());
                }
            }
            rows.add(row);
        }
        return rows;
    }

    // ------------------------------------------------------------------ plan

    public Plan preview(Session session, WebUser webUser, int workspaceId, Request request) {
        return plan(session, webUser, workspaceId, request);
    }

    Plan plan(Session session, WebUser webUser, int workspaceId, Request request) {
        Plan plan = new Plan();
        LocalDate today = webUser.getLocalDateToday();
        ActionNext closed = SessionAccess.find(session, webUser, workspaceId, request.actionNextId);
        if (closed == null) {
            plan.errors.add("Action #" + request.actionNextId + " not found.");
            return plan;
        }
        plan.closed = closed;
        if (closed.isTemplate()) {
            plan.errors.add("A template can't be closed out; close out one of its actions.");
            return plan;
        }
        if (!ProjectNextActionStatus.READY.getId().equals(closed.getNextActionStatusString())) {
            plan.errors.add("Action #" + closed.getActionNextId() + " is no longer open.");
            return plan;
        }
        if (request.asOf != null && !request.asOf.equals(asOf(closed))) {
            plan.errors.add(staleMessage(closed));
        }
        if (request.completionDescription == null || request.completionDescription.trim().length() == 0) {
            plan.errors.add("Say what was done (the completion description).");
        } else if (request.completionDescription.length() > HistoryEntryWriter.MAX_DESCRIPTION) {
            plan.errors.add("The completion description is too long.");
        }
        if (request.outcomeNote != null && notesService.lengthError(request.outcomeNote) != null) {
            plan.errors.add("The outcome note is limited to 20,000 characters.");
        }
        if (request.durationMinutes < 0 || request.durationMinutes > ActionCompletionService.MAX_DURATION_MINS) {
            plan.errors.add("Minutes must be between 0 and " + ActionCompletionService.MAX_DURATION_MINS + ".");
        }

        List<SuccessorSpec> successors = request.successors == null ? new ArrayList<SuccessorSpec>()
                : request.successors;
        if (successors.isEmpty() && !request.noSuccessor) {
            plan.errors.add("Type a next action (the grey text in the box is only a suggestion), or choose "
                    + "\"No next action\".");
        }
        if (!successors.isEmpty() && request.noSuccessor) {
            plan.errors.add("\"No next action\" was chosen but next actions were given.");
        }
        if (successors.size() > MAX_SUCCESSORS) {
            plan.errors.add("At most " + MAX_SUCCESSORS + " next actions at once.");
        }
        for (SuccessorSpec successor : successors) {
            if (successor.description == null || successor.description.trim().length() == 0) {
                plan.errors.add("Each next action needs a description.");
            } else if (successor.description.trim().length() > SessionActionFactory.MAX_DESCRIPTION) {
                plan.errors.add("A next action's description is limited to 1,200 characters.");
            }
            if (successor.scheduledDate != null && successor.scheduledDate.isBefore(today)) {
                plan.errors.add("A next action can't be scheduled in the past.");
            }
            if (successor.notes != null && notesService.lengthError(successor.notes) != null) {
                plan.errors.add("A next action's notes are limited to 20,000 characters.");
            }
            if (successor.actionType != null && !isActionType(successor.actionType)) {
                plan.errors.add("Unknown action type \"" + successor.actionType + "\".");
            }
        }

        // Rows: the closed action and its twins.
        Set<Integer> touched = new HashSet<Integer>();
        for (ActionNext twin : SharedActionSiblings.resolve(session, closed)) {
            touched.add(Integer.valueOf(twin.getActionNextId()));
            Row row = row(session, webUser, twin, twin == closed || twin.getActionNextId() == closed.getActionNextId()
                    ? "completed" : "completed (linked twin)");
            row.afterStatus = ProjectNextActionStatus.COMPLETED.getId();
            row.detail = "Completion: " + (request.completionDescription == null ? "" : request.completionDescription.trim())
                    + (request.outcomeNote == null || request.outcomeNote.trim().length() == 0 ? ""
                            : "\nOutcome: " + request.outcomeNote.trim());
            plan.rows.add(row);
        }

        List<ChangeSpec> changes = request.changes == null ? new ArrayList<ChangeSpec>() : request.changes;
        if (changes.size() > MAX_CHANGES) {
            plan.errors.add("At most " + MAX_CHANGES + " changes at once.");
        }
        Set<Integer> changeIds = new HashSet<Integer>();
        Set<Integer> survivorIds = new HashSet<Integer>();
        List<ActionNext> blockerSources = new ArrayList<ActionNext>();
        blockerSources.addAll(SharedActionSiblings.resolve(session, closed));
        for (ChangeSpec change : changes) {
            ActionNext action = checkOther(session, webUser, workspaceId, closed, change.actionNextId, change.asOf,
                    plan);
            plan.changeActions.add(action);
            if (action == null) {
                continue;
            }
            if (!changeIds.add(Integer.valueOf(action.getActionNextId()))) {
                plan.errors.add("Action #" + action.getActionNextId() + " is changed twice.");
                continue;
            }
            String op = change.op == null ? "" : change.op;
            String roleNote = "";
            List<ActionNext> twins = SharedActionSiblings.resolve(session, action);
            if (twins.size() > 1) {
                roleNote = " (linked)";
            }
            for (ActionNext twin : twins) {
                touched.add(Integer.valueOf(twin.getActionNextId()));
                Row row = row(session, webUser, twin, op + (twin.getActionNextId() == action.getActionNextId()
                        ? roleNote : " (linked twin)"));
                if (OP_REWRITE.equals(op)) {
                    if (change.description == null || change.description.trim().length() == 0) {
                        plan.errors.add("Rewrite of #" + action.getActionNextId() + " needs the new text.");
                    } else {
                        row.afterDescription = change.description.trim();
                    }
                } else if (OP_RESCHEDULE.equals(op)) {
                    if (change.scheduledDate == null) {
                        plan.errors.add("Reschedule of #" + action.getActionNextId() + " needs a date.");
                    } else if (change.scheduledDate.isBefore(today)) {
                        plan.errors.add("#" + action.getActionNextId() + " can't be moved into the past.");
                    } else {
                        row.afterDate = change.scheduledDate.toString();
                    }
                } else if (OP_CANCEL.equals(op)) {
                    row.afterStatus = ProjectNextActionStatus.CANCELLED.getId();
                } else if (OP_MERGE.equals(op)) {
                    row.afterStatus = ProjectNextActionStatus.CANCELLED.getId();
                    row.role = "merged into #" + change.survivorActionNextId
                            + (twin.getActionNextId() == action.getActionNextId() ? "" : " (linked twin)");
                } else {
                    plan.errors.add("Unknown change \"" + op + "\" for #" + action.getActionNextId() + ".");
                }
                plan.rows.add(row);
            }
            if (OP_CANCEL.equals(op) || OP_MERGE.equals(op)) {
                blockerSources.addAll(twins);
            }
            if (OP_MERGE.equals(op)) {
                if (change.survivorActionNextId == null) {
                    plan.errors.add("Merge of #" + action.getActionNextId() + " needs the action that survives.");
                    continue;
                }
                ActionNext survivor = checkOther(session, webUser, workspaceId, closed,
                        change.survivorActionNextId.intValue(), change.survivorAsOf, plan);
                if (survivor == null) {
                    continue;
                }
                if (survivor.getActionNextId() == action.getActionNextId()) {
                    plan.errors.add("An action can't be merged into itself.");
                    continue;
                }
                plan.survivors.add(survivor);
                if (!survivorIds.add(Integer.valueOf(survivor.getActionNextId()))) {
                    plan.errors.add("Merge several actions into #" + survivor.getActionNextId()
                            + " in one step: give one combined text.");
                    continue;
                }
                for (ActionNext twin : SharedActionSiblings.resolve(session, survivor)) {
                    touched.add(Integer.valueOf(twin.getActionNextId()));
                    Row row = row(session, webUser, twin, "merge survivor"
                            + (twin.getActionNextId() == survivor.getActionNextId() ? "" : " (linked twin)"));
                    row.afterDescription = combined(change, survivor);
                    plan.rows.add(row);
                }
            }
        }
        for (Integer survivorId : survivorIds) {
            if (changeIds.contains(survivorId)) {
                plan.errors.add("#" + survivorId + " survives a merge, so it can't also be changed here.");
            }
        }

        // Actions this close-out unblocks (completing or cancelling what they wait on).
        for (ActionNext blocked : blockedBy(session, blockerSources)) {
            if (touched.contains(Integer.valueOf(blocked.getActionNextId()))) {
                plan.errors.add("#" + blocked.getActionNextId()
                        + " waits on an action closed here and is also changed; change it afterwards.");
                continue;
            }
            touched.add(Integer.valueOf(blocked.getActionNextId()));
            Row row = row(session, webUser, blocked, "unblocked");
            row.afterDate = today.toString();
            plan.rows.add(row);
        }

        Project project = SessionAccess.project(session, closed);
        for (SuccessorSpec successor : successors) {
            Row row = new Row();
            row.role = "new next action";
            row.projectName = project == null ? "" : project.getProjectName();
            row.afterDescription = successor.description == null ? "" : successor.description.trim();
            row.afterDate = successor.scheduledDate == null ? null : successor.scheduledDate.toString();
            row.afterStatus = ProjectNextActionStatus.READY.getId();
            row.detail = successor.notes == null || successor.notes.trim().length() == 0 ? null
                    : "Brief: " + successor.notes.trim();
            plan.rows.add(row);
        }
        return plan;
    }

    /** The merge's combined text; blank keeps the survivor's own text. */
    static String combined(ChangeSpec change, ActionNext survivor) {
        return change.combinedDescription == null || change.combinedDescription.trim().length() == 0
                ? survivor.getNextDescription() : change.combinedDescription.trim();
    }

    private ActionNext checkOther(Session session, WebUser webUser, int workspaceId, ActionNext closed, int id,
            String asOf, Plan plan) {
        ActionNext action = SessionAccess.find(session, webUser, workspaceId, id);
        if (action == null || action.getProjectId() != closed.getProjectId()) {
            plan.errors.add("Action #" + id + " is not an open action on this project.");
            return null;
        }
        if (action.getActionNextId() == closed.getActionNextId()) {
            plan.errors.add("The action being closed can't also be changed.");
            return null;
        }
        if (action.isTemplate()) {
            plan.errors.add("#" + id + " is a template; edit templates in the template editor.");
            return null;
        }
        if (action.getTemplateActionNextId() != null) {
            plan.errors.add("#" + id + " was generated by a recurring template; edit it via the template.");
            return null;
        }
        if (!ProjectNextActionStatus.READY.getId().equals(action.getNextActionStatusString())) {
            plan.errors.add("#" + id + " is no longer open.");
            return null;
        }
        if (asOf == null || !asOf.equals(asOf(action))) {
            plan.errors.add(staleMessage(action));
        }
        return action;
    }

    @SuppressWarnings("unchecked")
    private List<ActionNext> blockedBy(Session session, Collection<ActionNext> blockers) {
        List<ActionNext> result = new ArrayList<ActionNext>();
        Set<Integer> seen = new HashSet<Integer>();
        for (ActionNext blocker : blockers) {
            Query query = session.createQuery("from ActionNext where blockedBy.actionNextId = :id order by actionNextId");
            query.setParameter("id", Integer.valueOf(blocker.getActionNextId()));
            for (ActionNext blocked : (List<ActionNext>) query.list()) {
                if (seen.add(Integer.valueOf(blocked.getActionNextId()))) {
                    result.add(blocked);
                }
            }
        }
        return result;
    }

    private Row row(Session session, WebUser webUser, ActionNext action, String role) {
        Row row = new Row();
        row.actionNextId = action.getActionNextId();
        row.role = role;
        Project project = SessionAccess.project(session, action);
        row.projectName = project == null ? "" : project.getProjectName();
        row.asOf = asOf(action);
        row.beforeDescription = action.getNextDescription();
        row.beforeDate = action.getNextActionDate() == null ? null : webUser.toLocalDate(action.getNextActionDate()).toString();
        row.beforeStatus = action.getNextActionStatusString();
        row.afterDescription = row.beforeDescription;
        row.afterDate = row.beforeDate;
        row.afterStatus = row.beforeStatus;
        return row;
    }

    // ------------------------------------------------------------------ apply

    /**
     * Applies the close-out in the caller's transaction. Refuses (writing nothing) when the plan
     * has errors, when the confirmed rows don't match the rows it would change now, or when any
     * of them changed since the preview.
     */
    public Map<String, Object> apply(Session session, WebUser webUser, int workspaceId, Request request, Date now) {
        Plan plan = plan(session, webUser, workspaceId, request);
        if (!plan.isOk()) {
            throw new SessionException(409, plan.errors.get(0).startsWith("stale") ? "stale" : "invalid",
                    join(plan.errors), detail(plan));
        }
        checkConfirmed(plan, request.confirmRows);
        if (request.durationMinutes > 0) {
            String timeError = completionService.validateCompletion(webUser, session, plan.closed,
                    ActionCompletionService.CompletionTime.of(
                            new Date(now.getTime() - request.durationMinutes * 60000L), request.durationMinutes));
            if (timeError != null) {
                throw SessionException.bad(timeError);
            }
        }
        List<ChangeSpec> changes = request.changes == null ? new ArrayList<ChangeSpec>() : request.changes;
        int step = 0;
        for (int i = 0; i < changes.size(); i++) {
            ChangeSpec change = changes.get(i);
            ActionNext action = plan.changeActions.get(i);
            faults.at("change-" + (++step));
            if (OP_REWRITE.equals(change.op)) {
                for (ActionNext twin : SharedActionSiblings.resolve(session, action)) {
                    twin.setNextDescription(change.description.trim());
                    twin.setNextSummary(change.description.trim());
                    twin.setNextChangeDate(now);
                    session.update(twin);
                }
            } else if (OP_RESCHEDULE.equals(change.op)) {
                for (ActionNext twin : SharedActionSiblings.resolve(session, action)) {
                    twin.setNextActionDate(java.sql.Date.valueOf(change.scheduledDate));
                    twin.setNextChangeDate(now);
                    session.update(twin);
                }
            } else if (OP_CANCEL.equals(change.op)) {
                String reason = change.reason == null || change.reason.trim().length() == 0 ? ""
                        : ": " + change.reason.trim();
                completionService.applyCompletion(session, webUser, action,
                        "Cancelled at close-out of #" + plan.closed.getActionNextId() + reason, null,
                        ProjectNextActionStatus.CANCELLED, now, 0);
            } else if (OP_MERGE.equals(change.op)) {
                ActionNext survivor = (ActionNext) session.get(ActionNext.class, change.survivorActionNextId);
                String combined = combined(change, survivor);
                historyWriter.write(session, webUser, action, "Merged into #" + survivor.getActionNextId() + ": "
                        + SessionActionFactory.clip(combined, 1000), null, null,
                        Integer.valueOf(survivor.getActionNextId()), now);
                completionService.applyCompletion(session, webUser, action, null, null,
                        ProjectNextActionStatus.CANCELLED, now, 0);
                for (ActionNext twin : SharedActionSiblings.resolve(session, survivor)) {
                    twin.setNextDescription(combined);
                    twin.setNextSummary(combined);
                    twin.setNextChangeDate(now);
                    session.update(twin);
                }
            }
        }
        faults.at("complete");
        completionService.applyCompletion(session, webUser, plan.closed, request.completionDescription.trim(),
                request.outcomeNote, ProjectNextActionStatus.COMPLETED,
                request.durationMinutes > 0 ? new Date(now.getTime() - request.durationMinutes * 60000L) : now,
                request.durationMinutes);
        List<Integer> successorIds = new ArrayList<Integer>();
        for (final SuccessorSpec successor : request.successors == null ? new ArrayList<SuccessorSpec>()
                : request.successors) {
            faults.at("successor-" + (++step));
            final Session builderSession = session;
            final WebUser builderUser = webUser;
            final Date builderNow = now;
            List<ActionNext> created = completionService.createFollowUp(session, webUser, plan.closed,
                    new ActionCompletionService.FollowUpBuilder() {
                        public ActionNext build(Project project) {
                            return buildSuccessor(builderSession, builderUser, project, successor, builderNow);
                        }
                    }, successor.notes);
            successorIds.add(Integer.valueOf(created.get(0).getActionNextId()));
        }
        faults.at("done");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("closedActionNextId", Integer.valueOf(plan.closed.getActionNextId()));
        result.put("successorActionNextIds", successorIds);
        List<Integer> changed = new ArrayList<Integer>();
        for (Row row : plan.rows) {
            if (row.actionNextId > 0) {
                changed.add(Integer.valueOf(row.actionNextId));
            }
        }
        result.put("changedActionNextIds", changed);
        return result;
    }

    /** The confirmed rows must be exactly the rows the plan changes now, with the same asOf. */
    static void checkConfirmed(Plan plan, List<RowRef> confirmRows) {
        if (confirmRows == null) {
            throw SessionException.bad("Preview first, then confirm the rows it listed (confirmRows).");
        }
        Map<Integer, String> confirmed = new LinkedHashMap<Integer, String>();
        for (RowRef ref : confirmRows) {
            confirmed.put(Integer.valueOf(ref.actionNextId), ref.asOf);
        }
        List<String> problems = new ArrayList<String>();
        Set<Integer> planned = new HashSet<Integer>();
        for (Row row : plan.rows) {
            if (row.actionNextId <= 0) {
                continue;
            }
            planned.add(Integer.valueOf(row.actionNextId));
            String asOf = confirmed.get(Integer.valueOf(row.actionNextId));
            if (!confirmed.containsKey(Integer.valueOf(row.actionNextId))) {
                problems.add("#" + row.actionNextId + " \"" + row.beforeDescription
                        + "\" would also change now but wasn't in the preview.");
            } else if (asOf == null || !asOf.equals(row.asOf)) {
                problems.add("#" + row.actionNextId + " \"" + row.beforeDescription + "\" changed since the preview.");
            }
        }
        for (Integer id : confirmed.keySet()) {
            if (!planned.contains(id)) {
                problems.add("#" + id + " was in the preview but would no longer change.");
            }
        }
        if (!problems.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<String, Object>();
            details.put("errors", problems);
            throw new SessionException(409, "stale", "Nothing was changed. " + join(problems)
                    + " Preview again.", details);
        }
    }

    private ActionNext buildSuccessor(Session session, WebUser webUser, Project project, SuccessorSpec spec, Date now) {
        ActionNext action = new ActionNext();
        action.setProject(project);
        action.setProjectId(project.getProjectId());
        action.setWorkspaceId(project.getWorkspaceId());
        ProjectContact ownerContact = webUser.getProjectContact();
        if (ownerContact == null) {
            ownerContact = (ProjectContact) session.get(ProjectContact.class, Integer.valueOf(webUser.getContactId()));
        }
        action.setContact(ownerContact);
        action.setContactId(webUser.getContactId());
        String type = spec.actionType == null ? ProjectNextActionType.WILL : spec.actionType;
        action.setNextActionType(type);
        String text = spec.description.trim();
        action.setNextDescription(text);
        action.setNextSummary(text);
        action.setNextTimeEstimate(Integer.valueOf(0));
        action.setNextActionStatus(ProjectNextActionStatus.READY);
        action.setNextActionDate(spec.scheduledDate == null ? null : java.sql.Date.valueOf(spec.scheduledDate));
        action.setLinkUrl("");
        action.setNextChangeDate(now);
        action.setPriorityLevel(ProjectNextActionType.defaultPriority(type));
        action.setBillable(ProjectActionDao.isBillable(session, project));
        return action;
    }

    private static boolean isActionType(String value) {
        return ProjectNextActionType.WILL.equals(value) || ProjectNextActionType.WILL_CONTACT.equals(value)
                || ProjectNextActionType.WILL_MEET.equals(value) || ProjectNextActionType.WILL_REVIEW.equals(value)
                || ProjectNextActionType.WILL_DOCUMENT.equals(value)
                || ProjectNextActionType.WILL_FOLLOW_UP.equals(value) || ProjectNextActionType.MIGHT.equals(value)
                || ProjectNextActionType.WOULD_LIKE_TO.equals(value)
                || ProjectNextActionType.COMMITTED_TO.equals(value) || ProjectNextActionType.WAITING.equals(value);
    }

    public static String asOf(ActionNext action) {
        return McpActionContextSupport.toIso(action.getNextChangeDate());
    }

    private static String staleMessage(ActionNext action) {
        return "stale: #" + action.getActionNextId() + " \"" + action.getNextDescription()
                + "\" changed since it was read (current asOf " + asOf(action) + ").";
    }

    private static Map<String, Object> detail(Plan plan) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("errors", plan.errors);
        return details;
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    public static Map<String, Object> rowMap(Row row) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("actionNextId", row.actionNextId == 0 ? null : Integer.valueOf(row.actionNextId));
        map.put("role", row.role);
        map.put("projectName", row.projectName);
        map.put("asOf", row.asOf);
        Map<String, Object> before = new LinkedHashMap<String, Object>();
        before.put("description", row.beforeDescription);
        before.put("scheduledDate", row.beforeDate);
        before.put("status", row.beforeStatus);
        Map<String, Object> after = new LinkedHashMap<String, Object>();
        after.put("description", row.afterDescription);
        after.put("scheduledDate", row.afterDate);
        after.put("status", row.afterStatus);
        map.put("before", row.actionNextId == 0 ? null : before);
        if (row.detail != null) {
            map.put("detail", row.detail);
        }
        map.put("after", after);
        return map;
    }
}
