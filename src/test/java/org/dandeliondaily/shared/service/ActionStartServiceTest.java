package org.dandeliondaily.shared.service;

import java.time.LocalDate;
import java.util.TimeZone;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.WebUser;

public class ActionStartServiceTest {

    private WebUser user;
    private LocalDate today;

    @Before
    public void setUp() {
        user = new WebUser();
        user.setTimeZone(TimeZone.getTimeZone("America/Denver"));
        today = user.getLocalDateToday();
    }

    @Test
    public void openActionOnAnotherDayOrUndatedMoves() {
        Assert.assertTrue(ActionStartService.needsMoveToToday(action("R", today.plusDays(6)), user));
        Assert.assertTrue(ActionStartService.needsMoveToToday(action("R", today.minusDays(2)), user));
        Assert.assertTrue(ActionStartService.needsMoveToToday(action("P", today.plusDays(1)), user));
        Assert.assertTrue(ActionStartService.needsMoveToToday(action("R", null), user));
    }

    @Test
    public void todaysAndClosedActionsStay() {
        Assert.assertFalse(ActionStartService.needsMoveToToday(action("R", today), user));
        Assert.assertFalse(ActionStartService.needsMoveToToday(action("C", today.minusDays(3)), user));
        Assert.assertFalse(ActionStartService.needsMoveToToday(action("X", today.plusDays(3)), user));
        Assert.assertFalse(ActionStartService.needsMoveToToday(null, user));
    }

    private ActionNext action(String status, LocalDate date) {
        ActionNext action = new ActionNext();
        action.setNextActionStatusString(status);
        action.setNextActionDate(date == null ? null : java.sql.Date.valueOf(date));
        return action;
    }
}
