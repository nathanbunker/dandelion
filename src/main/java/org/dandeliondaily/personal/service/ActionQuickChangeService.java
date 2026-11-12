package org.dandeliondaily.personal.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.shared.service.ActionCompletionService;
import org.dandeliondaily.shared.service.SharedActionSiblings;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionChangeLog;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActorType;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The quick changes the phone (and the /m/ pages) make to an action: complete, cancel and move.
 * Completing and cancelling go through {@link ActionCompletionService}, the same code the desktop
 * dashboard and MCP use, so linked-project siblings, the action-taken record and unblocking work the
 * same way everywhere.
 *
 * Every method runs inside the caller's open transaction and returns a {@link Change}: the
 * before/after state of every action it touched plus the action-taken rows it created, which
 * {@link ActionUndoService} can put back.
 */
public class ActionQuickChangeService {

    private final ActionCompletionService completionService = new ActionCompletionService();

    /** Who made the change, for the action change log. */
    public static class Actor {
        final String id;
        final String source;

        public Actor(String id, String source) {
            this.id = id;
            this.source = source;
        }
    }

    /** One action's state before and after a change. Dates are local date keys (yyyy-MM-dd). */
    public static class RowState {
        public int actionNextId;
        public String statusBefore;
        public String dateBefore;
        public String slotBefore;
        public int orderBefore;
        public Integer blockedByBefore;
        public String statusAfter;
        public String dateAfter;
        public String slotAfter;
        public Integer blockedByAfter;
    }

    /** What a change did: enough to undo it. */
    public static class Change {
        public final String kind;
        public final ActionNext action;
        public final List<RowState> rows = new ArrayList<RowState>();
        public final List<Integer> createdActionTakenIds = new ArrayList<Integer>();
        /** The first action this change unblocked, if any. */
        public ActionNext unblocked;
        public String summary;

        Change(String kind, ActionNext action) {
            this.kind = kind;
            this.action = action;
        }
    }

    public Change complete(Session session, WebUser webUser, ActionNext action, Actor actor) {
        return close(session, webUser, action, ProjectNextActionStatus.COMPLETED, actor);
    }

    public Change cancel(Session session, WebUser webUser, ActionNext action, Actor actor) {
        return close(session, webUser, action, ProjectNextActionStatus.CANCELLED, actor);
    }

    private Change close(Session session, WebUser webUser, ActionNext action, ProjectNextActionStatus status,
            Actor actor) {
        boolean completing = status == ProjectNextActionStatus.COMPLETED;
        Change change = new Change(completing ? "complete" : "cancel", action);
        List<ActionNext> touched = new ArrayList<ActionNext>();
        for (ActionNext sibling : SharedActionSiblings.resolve(session, action)) {
            touched.add(sibling);
            touched.addAll(blockedBy(session, sibling));
        }
        Map<Integer, RowState> before = capture(touched);

        // MySQL keeps whole seconds; a second-aligned moment lets us find the rows we wrote.
        Date now = new Date(System.currentTimeMillis() / 1000L * 1000L);
        // Completing records "what was done" like the dashboard and MCP do; cancelling records nothing.
        String description = completing ? action.getNextDescription() : null;
        change.unblocked = completionService.applyCompletion(session, webUser, action, description, status, now, 0);
        if (completing) {
            change.createdActionTakenIds.addAll(actionTakenIds(session, webUser, now, description));
        }
        finish(change, before, touched);
        change.summary = (completing ? "Completed " : "Cancelled ") + quote(action);
        log(session, action, change.kind, null, actor);
        return change;
    }

    /**
     * Moves an action to a date and part of the day. Only this action moves (as on /m/ today), not
     * linked-project siblings. A null slot keeps the current one. The date is a calendar day, stored
     * as-is (not via UTC midnight, which lands on the previous day when the JVM runs west of UTC).
     */
    public Change move(Session session, ActionNext action, LocalDate date, TimeSlot slot, Actor actor) {
        Change change = new Change("move", action);
        List<ActionNext> touched = new ArrayList<ActionNext>();
        touched.add(action);
        Map<Integer, RowState> before = capture(touched);
        String from = describe(action.getNextActionDate(), action.getTimeSlot());

        action.setNextActionDate(date == null ? null : java.sql.Date.valueOf(date));
        if (slot != null) {
            action.setTimeSlot(slot);
        }
        action.setNextChangeDate(new Date());
        session.update(action);

        finish(change, before, touched);
        String to = describe(action.getNextActionDate(), action.getTimeSlot());
        change.summary = "Moved " + quote(action) + " to " + to;
        log(session, action, "move", "Moved from " + from + " to " + to, actor);
        return change;
    }

    // ---- /m/ helpers: the date rules the mobile pages have always used ----

    /** "→ Tomorrow" on /m/: overdue items come to today, anything else goes to tomorrow. */
    public static LocalDate postponeTarget(LocalDate current, LocalDate today) {
        if (current != null && current.isBefore(today)) {
            return today;
        }
        return today.plusDays(1);
    }

    /** The simple reschedule on /m/: overdue or undated → today; today → tomorrow; later → no date. */
    public static LocalDate simpleRescheduleTarget(LocalDate current, LocalDate today) {
        if (current == null || current.isBefore(today)) {
            return today;
        }
        if (current.isBefore(today.plusDays(1))) {
            return today.plusDays(1);
        }
        return null;
    }

    /** The date of an action as the user's calendar day (the column is a DATE). */
    public static LocalDate localDate(Date date) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return new java.sql.Date(date.getTime()).toLocalDate();
    }

    // ---- snapshot ----

    private static Map<Integer, RowState> capture(List<ActionNext> actions) {
        Map<Integer, RowState> rows = new LinkedHashMap<Integer, RowState>();
        for (ActionNext a : actions) {
            if (rows.containsKey(Integer.valueOf(a.getActionNextId()))) {
                continue;
            }
            RowState row = new RowState();
            row.actionNextId = a.getActionNextId();
            row.statusBefore = statusOf(a);
            row.dateBefore = dateKey(a.getNextActionDate());
            row.slotBefore = a.getTimeSlot() == null ? null : a.getTimeSlot().getId();
            row.orderBefore = a.getCompletionOrder();
            row.blockedByBefore = a.getBlockedBy() == null ? null : Integer.valueOf(a.getBlockedBy().getActionNextId());
            rows.put(Integer.valueOf(row.actionNextId), row);
        }
        return rows;
    }

    private static void finish(Change change, Map<Integer, RowState> before, List<ActionNext> touched) {
        for (ActionNext a : touched) {
            RowState row = before.get(Integer.valueOf(a.getActionNextId()));
            if (row == null || change.rows.contains(row)) {
                continue;
            }
            row.statusAfter = statusOf(a);
            row.dateAfter = dateKey(a.getNextActionDate());
            row.slotAfter = a.getTimeSlot() == null ? null : a.getTimeSlot().getId();
            row.blockedByAfter = a.getBlockedBy() == null ? null : Integer.valueOf(a.getBlockedBy().getActionNextId());
            change.rows.add(row);
        }
    }

    static String statusOf(ActionNext a) {
        return a.getNextActionStatus() == null ? null : a.getNextActionStatus().getId();
    }

    static String dateKey(Date date) {
        LocalDate d = localDate(date);
        return d == null ? null : d.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<ActionNext> blockedBy(Session session, ActionNext blocker) {
        Query query = session.createQuery("from ActionNext where blockedBy.actionNextId = :id");
        query.setParameter("id", Integer.valueOf(blocker.getActionNextId()));
        return query.list();
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> actionTakenIds(Session session, WebUser webUser, Date moment, String description) {
        if (description == null || description.trim().isEmpty()) {
            return new ArrayList<Integer>();
        }
        Query query = session.createQuery("select actionTakenId from ActionTaken where contactId = :contactId "
                + "and actionDate = :moment and actionDescription = :description");
        query.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
        query.setTimestamp("moment", moment);
        query.setParameter("description", description.trim());
        return query.list();
    }

    private static void log(Session session, ActionNext action, String kind, String reason, Actor actor) {
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

    private static String describe(Date date, TimeSlot slot) {
        String d = dateKey(date);
        String s = slot == null ? "Afternoon" : slot.getLabel();
        return (d == null ? "no date" : d) + " " + s;
    }

    private static String quote(ActionNext action) {
        String text = PersonalActionJson.plainText(action.getNextDescription());
        if (text == null) {
            text = "";
        }
        if (text.length() > 60) {
            text = text.substring(0, 57) + "…";
        }
        return "\"" + text + "\"";
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
