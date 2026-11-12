package org.dandeliondaily.personal.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.ActionUndo;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Undo receipts for phone changes. {@link #save} stores what an {@link ActionQuickChangeService.Change}
 * did and returns a token; {@link #undo} puts every touched action back, but only for the client that
 * made the change, within {@link #WINDOW_MS}, and only if none of those actions changed since.
 */
public class ActionUndoService {

    public static final long WINDOW_MS = 5L * 60L * 1000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What the receipt stores: the row states and the action-taken rows to remove. */
    public static class Snapshot {
        public String kind;
        public int actionNextId;
        public List<ActionQuickChangeService.RowState> rows = new ArrayList<ActionQuickChangeService.RowState>();
        public List<Integer> createdActionTakenIds = new ArrayList<Integer>();
        public List<Integer> createdActionIds = new ArrayList<Integer>();
    }

    public enum Outcome {
        UNDONE, NOT_FOUND, EXPIRED, ALREADY_UNDONE, CHANGED_ELSEWHERE
    }

    public static class Result {
        public final Outcome outcome;
        public final String summary;
        public final int actionNextId;

        Result(Outcome outcome, String summary, int actionNextId) {
            this.outcome = outcome;
            this.summary = summary;
            this.actionNextId = actionNextId;
        }
    }

    /** Stores a receipt for the change and returns its token. Also clears out expired receipts. */
    public String save(Session session, int clientId, ActionQuickChangeService.Change change, Date now) {
        Query purge = session.createQuery("delete from ActionUndo where expiresDate < :cutoff");
        purge.setTimestamp("cutoff", new Date(now.getTime() - WINDOW_MS));
        purge.executeUpdate();

        Snapshot snapshot = new Snapshot();
        snapshot.kind = change.kind;
        snapshot.actionNextId = change.action.getActionNextId();
        snapshot.rows.addAll(change.rows);
        snapshot.createdActionTakenIds.addAll(change.createdActionTakenIds);
        for (ActionNext created : change.created) {
            snapshot.createdActionIds.add(Integer.valueOf(created.getActionNextId()));
        }

        ActionUndo undo = new ActionUndo();
        undo.setClientId(clientId);
        undo.setToken(newToken());
        undo.setSummary(clip(change.summary, 250));
        try {
            undo.setSnapshot(MAPPER.writeValueAsString(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the undo receipt.", e);
        }
        undo.setCreatedDate(now);
        undo.setExpiresDate(new Date(now.getTime() + WINDOW_MS));
        session.save(undo);
        return undo.getToken();
    }

    public Result undo(Session session, int clientId, String token, Date now) {
        ActionUndo undo = find(session, token);
        if (undo == null || undo.getClientId() != clientId) {
            return new Result(Outcome.NOT_FOUND, null, 0);
        }
        Snapshot snapshot = read(undo);
        if (undo.getUsedDate() != null) {
            return new Result(Outcome.ALREADY_UNDONE, undo.getSummary(), snapshot.actionNextId);
        }
        if (now.after(undo.getExpiresDate())) {
            return new Result(Outcome.EXPIRED, undo.getSummary(), snapshot.actionNextId);
        }

        // Check every row first, so an undo is all or nothing.
        List<ActionNext> actions = new ArrayList<ActionNext>();
        for (ActionQuickChangeService.RowState row : snapshot.rows) {
            ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(row.actionNextId));
            if (action == null || !stillAsLeft(action, row)) {
                return new Result(Outcome.CHANGED_ELSEWHERE, undo.getSummary(), snapshot.actionNextId);
            }
            actions.add(action);
        }

        // Actions the change created must still be open and untouched-by-status; they get cancelled.
        List<ActionNext> created = new ArrayList<ActionNext>();
        for (Integer id : snapshot.createdActionIds) {
            ActionNext action = (ActionNext) session.get(ActionNext.class, id);
            if (action == null) {
                continue;
            }
            if (action.getNextActionStatus() != ProjectNextActionStatus.READY
                    && action.getNextActionStatus() != ProjectNextActionStatus.PROPOSED) {
                return new Result(Outcome.CHANGED_ELSEWHERE, undo.getSummary(), snapshot.actionNextId);
            }
            created.add(action);
        }
        for (ActionNext action : created) {
            action.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
            action.setNextChangeDate(now);
            session.update(action);
        }
        for (int i = 0; i < actions.size(); i++) {
            restore(session, actions.get(i), snapshot.rows.get(i), now);
        }
        for (Integer id : snapshot.createdActionTakenIds) {
            Object taken = session.get(ActionTaken.class, id);
            if (taken != null) {
                session.delete(taken);
            }
        }
        undo.setUsedDate(now);
        session.update(undo);
        return new Result(Outcome.UNDONE, undo.getSummary(), snapshot.actionNextId);
    }

    /** True when the action is exactly as the change left it. */
    static boolean stillAsLeft(ActionNext action, ActionQuickChangeService.RowState row) {
        return same(ActionQuickChangeService.statusOf(action), row.statusAfter)
                && same(ActionQuickChangeService.dateKey(action.getNextActionDate()), row.dateAfter)
                && same(action.getTimeSlot() == null ? null : action.getTimeSlot().getId(), row.slotAfter)
                && same(action.getBlockedBy() == null ? null : Integer.valueOf(action.getBlockedBy().getActionNextId()),
                        row.blockedByAfter);
    }

    static void restoreFields(ActionNext action, ActionQuickChangeService.RowState row, ActionNext blockedBy,
            Date now) {
        action.setNextActionStatus(row.statusBefore == null ? null
                : ProjectNextActionStatus.getProjectNextActionStatus(row.statusBefore));
        action.setNextActionDate(row.dateBefore == null ? null : java.sql.Date.valueOf(LocalDate.parse(row.dateBefore)));
        action.setTimeSlot(row.slotBefore == null ? null : TimeSlot.getTimeSlot(row.slotBefore));
        action.setCompletionOrder(row.orderBefore);
        action.setBlockedBy(blockedBy);
        action.setNextChangeDate(now);
    }

    private static void restore(Session session, ActionNext action, ActionQuickChangeService.RowState row, Date now) {
        ActionNext blockedBy = row.blockedByBefore == null ? null
                : (ActionNext) session.get(ActionNext.class, row.blockedByBefore);
        restoreFields(action, row, blockedBy, now);
        session.update(action);
    }

    private static ActionUndo find(Session session, String token) {
        if (token == null || token.isEmpty() || token.length() > 64) {
            return null;
        }
        Query query = session.createQuery("from ActionUndo where token = :token");
        query.setParameter("token", token);
        return (ActionUndo) query.uniqueResult();
    }

    private static Snapshot read(ActionUndo undo) {
        try {
            return MAPPER.readValue(undo.getSnapshot(), new TypeReference<Snapshot>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Could not read undo receipt " + undo.getUndoId(), e);
        }
    }

    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
