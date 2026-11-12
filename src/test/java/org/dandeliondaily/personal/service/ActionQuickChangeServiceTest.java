package org.dandeliondaily.personal.service;

import java.time.LocalDate;
import java.util.Date;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;

public class ActionQuickChangeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    /** /m/ "→ Tomorrow", as MobileBaseServlet.postponeToTomorrow always did it. */
    @Test
    public void postponeBringsOverdueToTodayAndEverythingElseToTomorrow() {
        Assert.assertEquals(TODAY, ActionQuickChangeService.postponeTarget(TODAY.minusDays(3), TODAY));
        Assert.assertEquals(TODAY.plusDays(1), ActionQuickChangeService.postponeTarget(TODAY, TODAY));
        Assert.assertEquals(TODAY.plusDays(1), ActionQuickChangeService.postponeTarget(TODAY.plusDays(5), TODAY));
        Assert.assertEquals(TODAY.plusDays(1), ActionQuickChangeService.postponeTarget(null, TODAY));
    }

    /** The old rescheduleActionSimple rule, kept as a pure function. */
    @Test
    public void simpleReschedule() {
        Assert.assertEquals(TODAY, ActionQuickChangeService.simpleRescheduleTarget(null, TODAY));
        Assert.assertEquals(TODAY, ActionQuickChangeService.simpleRescheduleTarget(TODAY.minusDays(1), TODAY));
        Assert.assertEquals(TODAY.plusDays(1), ActionQuickChangeService.simpleRescheduleTarget(TODAY, TODAY));
        Assert.assertNull(ActionQuickChangeService.simpleRescheduleTarget(TODAY.plusDays(1), TODAY));
    }

    /** A DATE column value is a calendar day; it must not shift through UTC. */
    @Test
    public void localDateOfADateColumn() {
        Assert.assertEquals(TODAY, ActionQuickChangeService.localDate(java.sql.Date.valueOf(TODAY)));
        Assert.assertEquals("2026-11-12", ActionQuickChangeService.dateKey(java.sql.Date.valueOf(TODAY)));
        Assert.assertNull(ActionQuickChangeService.localDate(null));
    }

    private static ActionNext action(ProjectNextActionStatus status, LocalDate date, TimeSlot slot) {
        ActionNext a = new ActionNext();
        a.setActionNextId(5);
        a.setNextActionStatus(status);
        a.setNextActionDate(date == null ? null : java.sql.Date.valueOf(date));
        a.setTimeSlot(slot);
        return a;
    }

    private static ActionQuickChangeService.RowState completedRow() {
        ActionQuickChangeService.RowState row = new ActionQuickChangeService.RowState();
        row.actionNextId = 5;
        row.statusBefore = "R";
        row.dateBefore = "2026-11-12";
        row.slotBefore = "MORNING";
        row.orderBefore = 3;
        row.statusAfter = "C";
        row.dateAfter = "2026-11-12";
        row.slotAfter = "MORNING";
        return row;
    }

    @Test
    public void undoOnlyWhenNothingChangedSince() {
        ActionQuickChangeService.RowState row = completedRow();
        Assert.assertTrue(ActionUndoService.stillAsLeft(
                action(ProjectNextActionStatus.COMPLETED, TODAY, TimeSlot.MORNING), row));
        // Reopened and moved on the desktop in the meantime.
        Assert.assertFalse(ActionUndoService.stillAsLeft(
                action(ProjectNextActionStatus.READY, TODAY, TimeSlot.MORNING), row));
        Assert.assertFalse(ActionUndoService.stillAsLeft(
                action(ProjectNextActionStatus.COMPLETED, TODAY.plusDays(1), TimeSlot.MORNING), row));
        Assert.assertFalse(ActionUndoService.stillAsLeft(
                action(ProjectNextActionStatus.COMPLETED, TODAY, TimeSlot.EVENING), row));
    }

    @Test
    public void undoPutsEveryFieldBack() {
        ActionNext a = action(ProjectNextActionStatus.COMPLETED, TODAY, TimeSlot.MORNING);
        a.setCompletionOrder(0);
        Date now = new Date();
        ActionUndoService.restoreFields(a, completedRow(), null, now);
        Assert.assertEquals(ProjectNextActionStatus.READY, a.getNextActionStatus());
        Assert.assertEquals("2026-11-12", ActionQuickChangeService.dateKey(a.getNextActionDate()));
        Assert.assertEquals(TimeSlot.MORNING, a.getTimeSlot());
        Assert.assertEquals(3, a.getCompletionOrder());
        Assert.assertNull(a.getBlockedBy());
        Assert.assertEquals(now, a.getNextChangeDate());
    }

    @Test
    public void undoTokensAndIdempotencyKeys() {
        String token = ActionUndoService.newToken();
        Assert.assertEquals(32, token.length());
        Assert.assertNotEquals(token, ActionUndoService.newToken());
        Assert.assertTrue(IdempotencyService.isValidKey("3f1c9a1e-8b7d-4c1e-9a55-0f8b2a6c1d42"));
        Assert.assertFalse(IdempotencyService.isValidKey(null));
        Assert.assertFalse(IdempotencyService.isValidKey("short"));
        Assert.assertFalse(IdempotencyService.isValidKey("has space in it"));
    }
}
