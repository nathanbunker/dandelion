package org.dandeliondaily.personal.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.TimeSlot;

/**
 * Characterization tests: how the mobile Todo page ({@code /m/todo}) sorted a day before its
 * logic moved into PersonalDayService. The phone API uses the same code, so these also pin
 * what Sundial shows.
 */
public class PersonalDayServiceTest {

    private static int nextId = 1;

    private static ActionNext action(String dateKey, TimeSlot slot, boolean billable) {
        ActionNext action = new ActionNext();
        action.setActionNextId(nextId++);
        action.setNextActionDate(dateKey == null ? null : utc(dateKey));
        action.setTimeSlot(slot);
        action.setBillable(billable);
        action.setNextDescription("action " + action.getActionNextId());
        return action;
    }

    /** Date-only values come back from MySQL as midnight; use UTC midnight so the test doesn't depend on the JVM zone. */
    private static java.util.Date utc(String dateKey) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(dateKey);
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<Integer> ids(List<ActionNext> actions) {
        List<Integer> ids = new ArrayList<Integer>();
        for (ActionNext a : actions) {
            ids.add(a.getActionNextId());
        }
        return ids;
    }

    @Test
    public void today_splitsOverdueAndParts() {
        ActionNext overdue = action("2026-11-10", TimeSlot.MORNING, false);
        ActionNext wake = action("2026-11-12", TimeSlot.WAKE, false);
        ActionNext morning = action("2026-11-12", TimeSlot.MORNING, false);
        ActionNext afternoon = action("2026-11-12", TimeSlot.AFTERNOON, false);
        ActionNext evening = action("2026-11-12", TimeSlot.EVENING, false);

        PersonalDayService.Day day = PersonalDayService.organize(
                Arrays.asList(overdue, wake, morning, afternoon, evening), "2026-11-12", "2026-11-12", true);

        Assert.assertEquals(ids(Arrays.asList(overdue)), ids(day.getOverdue()));
        Assert.assertEquals(ids(Arrays.asList(wake)), ids(day.getPart(TimeSlot.WAKE)));
        Assert.assertEquals(ids(Arrays.asList(morning)), ids(day.getPart(TimeSlot.MORNING)));
        Assert.assertEquals(ids(Arrays.asList(afternoon)), ids(day.getPart(TimeSlot.AFTERNOON)));
        Assert.assertEquals(ids(Arrays.asList(evening)), ids(day.getPart(TimeSlot.EVENING)));
    }

    @Test
    public void workActionsAreNeverShown() {
        ActionNext work = action("2026-11-12", TimeSlot.MORNING, true);
        ActionNext workOverdue = action("2026-11-01", TimeSlot.MORNING, true);
        ActionNext personal = action("2026-11-12", TimeSlot.MORNING, false);

        PersonalDayService.Day today = PersonalDayService.organize(Arrays.asList(work, workOverdue, personal),
                "2026-11-12", "2026-11-12", true);
        Assert.assertTrue(today.getOverdue().isEmpty());
        Assert.assertEquals(ids(Arrays.asList(personal)), ids(today.getPart(TimeSlot.MORNING)));

        PersonalDayService.Day otherDay = PersonalDayService.organize(Arrays.asList(work, personal),
                "2026-11-12", "2026-11-12", false);
        Assert.assertEquals(ids(Arrays.asList(personal)), ids(otherDay.getPart(TimeSlot.MORNING)));
    }

    @Test
    public void noPartOfDayCountsAsAfternoon() {
        ActionNext none = action("2026-11-12", null, false);
        PersonalDayService.Day day = PersonalDayService.organize(Arrays.asList(none), "2026-11-12", "2026-11-12",
                true);
        Assert.assertEquals(ids(Arrays.asList(none)), ids(day.getPart(TimeSlot.AFTERNOON)));
    }

    @Test
    public void otherDay_showsOnlyThatDayAndNoOverdue() {
        ActionNext earlier = action("2026-11-10", TimeSlot.MORNING, false);
        ActionNext thatDay = action("2026-11-14", TimeSlot.EVENING, false);

        PersonalDayService.Day day = PersonalDayService.organize(Arrays.asList(earlier, thatDay), "2026-11-14",
                "2026-11-14", false);

        Assert.assertTrue(day.getOverdue().isEmpty());
        Assert.assertTrue(day.getPart(TimeSlot.MORNING).isEmpty());
        Assert.assertEquals(ids(Arrays.asList(thatDay)), ids(day.getPart(TimeSlot.EVENING)));
    }

    @Test
    public void otherDay_matchesOnTheUsersDateKey() {
        // TodoServlet compared other days against the user's key, today against the UTC key.
        ActionNext a = action("2026-11-14", TimeSlot.MORNING, false);
        PersonalDayService.Day day = PersonalDayService.organize(Arrays.asList(a), "2026-11-13", "2026-11-14",
                false);
        Assert.assertEquals(ids(Arrays.asList(a)), ids(day.getPart(TimeSlot.MORNING)));
    }

    @Test
    public void today_laterDatesAreLeftOut() {
        ActionNext tomorrow = action("2026-11-13", TimeSlot.MORNING, false);
        PersonalDayService.Day day = PersonalDayService.organize(Arrays.asList(tomorrow), "2026-11-12",
                "2026-11-12", true);
        Assert.assertTrue(day.getPart(TimeSlot.MORNING).isEmpty());
        Assert.assertTrue(day.getOverdue().isEmpty());
    }

    @Test
    public void actionsWithoutADateAreLeftOut() {
        ActionNext undated = action(null, TimeSlot.MORNING, false);
        PersonalDayService.Day day = PersonalDayService.organize(Arrays.asList(undated), "2026-11-12",
                "2026-11-12", true);
        Assert.assertTrue(day.getPart(TimeSlot.MORNING).isEmpty());
    }

    @Test
    public void orderIsKeptWithinEachList() {
        ActionNext first = action("2026-11-12", TimeSlot.MORNING, false);
        ActionNext second = action("2026-11-12", TimeSlot.MORNING, false);
        ActionNext third = action("2026-11-12", TimeSlot.MORNING, false);
        PersonalDayService.Day day = PersonalDayService.organize(Arrays.asList(third, first, second), "2026-11-12",
                "2026-11-12", true);
        Assert.assertEquals(ids(Arrays.asList(third, first, second)), ids(day.getPart(TimeSlot.MORNING)));
    }

    @Test
    public void databaseDateKeyIsUtc() {
        Assert.assertEquals("2026-11-12", PersonalDayService.databaseDateKey(utc("2026-11-12")));
        Assert.assertNull(PersonalDayService.databaseDateKey(null));
    }
}
