package org.dandeliondaily.shared.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;

/**
 * Reads an action's thread: the earlier steps of the same work, found by
 * following {@code followsActionNextId} back from the action. Each step brings
 * what was recorded when it was worked on: the action taken text, outcome
 * notes, the step's own notes, and minutes logged against it. The UI and MCP
 * both read threads here.
 */
public class ActionThreadService {

    public static final int DEFAULT_LIMIT = 5;

    /** Upper bound on how far back a thread is walked, even with a larger limit. */
    public static final int MAX_LIMIT = 50;

    /** One earlier step of the thread. */
    public static class Step {
        private int actionNextId;
        private int projectId;
        private String description;
        private String status;
        private Date date;
        private final List<String> done = new ArrayList<String>();
        private final List<String> outcomeNotes = new ArrayList<String>();
        private String notes;
        private int minutes;
        private boolean current;
        private Integer mergedIntoActionNextId;

        /** True for the action itself (its own History entries, e.g. Park), shown first. */
        public boolean isCurrent() {
            return current;
        }

        /** Set when this step was absorbed into another action by a close-out merge. */
        public Integer getMergedIntoActionNextId() {
            return mergedIntoActionNextId;
        }

        public int getActionNextId() {
            return actionNextId;
        }

        public int getProjectId() {
            return projectId;
        }

        /** The step as it was planned. */
        public String getDescription() {
            return description;
        }

        /** Status id: C completed, X cancelled, R ready, ... */
        public String getStatus() {
            return status;
        }

        /** When the step was closed, or when it was last worked on if still open. */
        public Date getDate() {
            return date;
        }

        /** The action taken descriptions recorded for the step, oldest first. */
        public List<String> getDone() {
            return done;
        }

        /** The outcome notes recorded for the step, oldest first. */
        public List<String> getOutcomeNotes() {
            return outcomeNotes;
        }

        /** The step's own notes (written before or during the work), or null. */
        public String getNotes() {
            return notes;
        }

        public int getMinutes() {
            return minutes;
        }
    }

    /** Earlier steps, newest first, and whether the thread goes back further. */
    public static class Thread {
        private final List<Step> steps = new ArrayList<Step>();
        private boolean more;

        public List<Step> getSteps() {
            return steps;
        }

        public boolean isMore() {
            return more;
        }

        public boolean isEmpty() {
            return steps.isEmpty();
        }
    }

    /**
     * Up to {@code limit} earlier steps of {@code action}'s thread, newest
     * first. Empty when the action does not follow another.
     */
    public Thread history(Session dataSession, ActionNext action, int limit) {
        return history(dataSession, action, limit, false, null);
    }

    /** Decides whether a step may be shown (e.g. the session read omits inaccessible steps). */
    public interface StepFilter {
        boolean allow(ActionNext step);
    }

    /**
     * The thread as the Focused Action page shows it: the action's own History entries first
     * (progress saved or a Park on the still-open action), then the earlier steps.
     */
    public Thread historyWithCurrent(Session dataSession, ActionNext action, int limit) {
        return history(dataSession, action, limit, true, null);
    }

    /**
     * Earlier steps of {@code action}, newest first: the chain it follows plus, when a close-out
     * merged other actions into it, each absorbed action and the chain behind that. With
     * {@code includeCurrent}, the action itself comes first when it has History entries of its own.
     * Steps the filter refuses are left out, and so is the chain behind them.
     */
    public Thread history(Session dataSession, ActionNext action, int limit, boolean includeCurrent,
            StepFilter filter) {
        Thread thread = new Thread();
        if (action == null) {
            return thread;
        }
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<Integer> visited = new HashSet<Integer>();
        visited.add(Integer.valueOf(action.getActionNextId()));
        if (includeCurrent) {
            Step own = toStep(dataSession, action);
            if (!own.done.isEmpty() || !own.outcomeNotes.isEmpty()) {
                own.current = true;
                own.notes = null;
                thread.steps.add(own);
            }
        }
        List<Step> earlier = new ArrayList<Step>();
        java.util.LinkedList<ActionNext> queue = new java.util.LinkedList<ActionNext>();
        enqueueNeighbours(dataSession, action, visited, queue);
        int walked = 0;
        while (!queue.isEmpty()) {
            ActionNext previous = queue.removeFirst();
            if (filter != null && !filter.allow(previous)) {
                continue;
            }
            if (walked >= Math.min(MAX_LIMIT, max * 2 + 1)) {
                thread.more = true;
                break;
            }
            walked++;
            earlier.add(toStep(dataSession, previous));
            enqueueNeighbours(dataSession, previous, visited, queue);
        }
        java.util.Collections.sort(earlier, new java.util.Comparator<Step>() {
            public int compare(Step a, Step b) {
                if (a.date == null || b.date == null) {
                    return a.date == null ? (b.date == null ? 0 : 1) : -1;
                }
                return b.date.compareTo(a.date);
            }
        });
        for (Step step : earlier) {
            if (thread.steps.size() >= max + (includeCurrent && !thread.steps.isEmpty()
                    && thread.steps.get(0).current ? 1 : 0)) {
                thread.more = true;
                break;
            }
            thread.steps.add(step);
        }
        return thread;
    }

    /** The step this one follows, then any actions a merge folded into it. */
    private void enqueueNeighbours(Session dataSession, ActionNext action, Set<Integer> visited,
            java.util.LinkedList<ActionNext> queue) {
        Integer previousId = action.getFollowsActionNextId();
        if (previousId != null && visited.add(previousId)) {
            ActionNext previous = (ActionNext) dataSession.get(ActionNext.class, previousId);
            if (previous != null) {
                queue.addLast(previous);
            }
        }
        Query absorbedQuery = dataSession.createQuery("select an from ActionNext an, ActionTaken at "
                + "where at.survivorActionNextId = :survivorId and an.actionSet = at.actionSet "
                + "and an.projectId = at.projectId order by an.actionNextId");
        absorbedQuery.setParameter("survivorId", Integer.valueOf(action.getActionNextId()));
        @SuppressWarnings("unchecked")
        List<ActionNext> absorbed = absorbedQuery.list();
        for (ActionNext merged : absorbed) {
            if (visited.add(Integer.valueOf(merged.getActionNextId()))) {
                queue.addLast(merged);
            }
        }
    }

    private Step toStep(Session dataSession, ActionNext action) {
        Step step = new Step();
        step.actionNextId = action.getActionNextId();
        step.projectId = action.getProjectId();
        step.description = action.getNextDescription();
        step.status = action.getNextActionStatusString();
        step.notes = blankToNull(action.getNextNotes());
        Date lastTaken = null;
        if (action.getActionSet() != null) {
            Query query = dataSession.createQuery("from ActionTaken where actionSet.actionSetId = :actionSetId "
                    + "and projectId = :projectId order by actionDate, actionTakenId");
            query.setParameter("actionSetId", Integer.valueOf(action.getActionSet().getActionSetId()));
            query.setParameter("projectId", Integer.valueOf(action.getProjectId()));
            @SuppressWarnings("unchecked")
            List<ActionTaken> takenList = query.list();
            for (ActionTaken taken : takenList) {
                if (blankToNull(taken.getActionDescription()) != null) {
                    step.done.add(taken.getActionDescription().trim());
                }
                if (blankToNull(taken.getActionNote()) != null) {
                    step.outcomeNotes.add(taken.getActionNote());
                }
                if (taken.getSurvivorActionNextId() != null) {
                    step.mergedIntoActionNextId = taken.getSurvivorActionNextId();
                }
                lastTaken = taken.getActionDate();
            }
        }
        Query minutesQuery = dataSession.createQuery(
                "select sum(be.billMins) from BillEntry be where be.action.actionNextId = :actionNextId");
        minutesQuery.setParameter("actionNextId", Integer.valueOf(action.getActionNextId()));
        Object minutes = minutesQuery.uniqueResult();
        step.minutes = minutes instanceof Number ? ((Number) minutes).intValue() : 0;
        boolean closed = "C".equals(step.status) || "X".equals(step.status);
        step.date = closed || lastTaken == null ? action.getNextChangeDate() : lastTaken;
        return step;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().length() == 0 ? null : value;
    }
}
