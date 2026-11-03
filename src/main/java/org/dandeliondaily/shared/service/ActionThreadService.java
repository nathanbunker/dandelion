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
        Thread thread = new Thread();
        if (action == null) {
            return thread;
        }
        int max = Math.max(1, Math.min(limit, MAX_LIMIT));
        Set<Integer> visited = new HashSet<Integer>();
        visited.add(Integer.valueOf(action.getActionNextId()));
        Integer previousId = action.getFollowsActionNextId();
        while (previousId != null && visited.add(previousId)) {
            ActionNext previous = (ActionNext) dataSession.get(ActionNext.class, previousId);
            if (previous == null) {
                break;
            }
            if (thread.steps.size() >= max) {
                thread.more = true;
                break;
            }
            thread.steps.add(toStep(dataSession, previous));
            previousId = previous.getFollowsActionNextId();
        }
        return thread;
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
