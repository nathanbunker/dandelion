package org.dandeliondaily.shared.service;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.WebUser;

public class ActionCompletionServiceTest {

    private ActionCompletionService service;
    private WebUser webUser;

    @Before
    public void setUp() {
        service = new ActionCompletionService();
        webUser = new WebUser();
        webUser.setTimeZone(TimeZone.getTimeZone("America/Denver"));
    }

    @Test
    public void parseCompletionTime_parsesExplicitDateTimeAndDuration() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "2026-04-15", "09:30",
                "45");

        Assert.assertFalse(result.hasError());
        Assert.assertEquals(45, result.getDurationMins());

        Calendar calendar = webUser.getCalendar();
        calendar.setTime(result.getMoment());
        Assert.assertEquals(2026, calendar.get(Calendar.YEAR));
        Assert.assertEquals(Calendar.APRIL, calendar.get(Calendar.MONTH));
        Assert.assertEquals(15, calendar.get(Calendar.DAY_OF_MONTH));
        Assert.assertEquals(9, calendar.get(Calendar.HOUR_OF_DAY));
        Assert.assertEquals(30, calendar.get(Calendar.MINUTE));
        Assert.assertEquals(0, calendar.get(Calendar.SECOND));
    }

    @Test
    public void parseCompletionTime_blankDateAndTimeDefaultToNowWithZeroDuration() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "", "", "");

        Assert.assertFalse(result.hasError());
        Assert.assertEquals(0, result.getDurationMins());
        Assert.assertNotNull(result.getMoment());
    }

    @Test
    public void parseCompletionTime_rejectsMalformedDate() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "04/15/2026", "09:30",
                "0");

        Assert.assertTrue(result.hasError());
        Assert.assertEquals("Completion date must be in yyyy-MM-dd format.", result.getErrorMessage());
    }

    @Test
    public void parseCompletionTime_rejectsMalformedTime() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "2026-04-15", "9:30am",
                "0");

        Assert.assertTrue(result.hasError());
        Assert.assertEquals("Completion time must be in HH:mm format.", result.getErrorMessage());
    }

    @Test
    public void parseCompletionTime_rejectsNegativeDuration() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "2026-04-15", "09:30",
                "-5");

        Assert.assertTrue(result.hasError());
        Assert.assertEquals("Minutes spent cannot be negative.", result.getErrorMessage());
    }

    @Test
    public void parseCompletionTime_rejectsDurationOverMax() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "2026-04-15", "09:30",
                String.valueOf(ActionCompletionService.MAX_DURATION_MINS + 1));

        Assert.assertTrue(result.hasError());
    }

    @Test
    public void parseCompletionTime_rejectsNonNumericDuration() {
        ActionCompletionService.CompletionTime result = service.parseCompletionTime(webUser, "2026-04-15", "09:30",
                "abc");

        Assert.assertTrue(result.hasError());
        Assert.assertEquals("Minutes spent must be a whole number.", result.getErrorMessage());
    }

    @Test
    public void validateCompletion_returnsParseErrorWithoutTouchingAppReq() {
        ActionCompletionService.CompletionTime badTime = service.parseCompletionTime(webUser, "not-a-date", "09:30",
                "0");

        String message = service.validateCompletion(null, null, badTime);

        Assert.assertEquals("Completion date must be in yyyy-MM-dd format.", message);
    }

    @Test
    public void validateCompletion_returnsRequiredMessageWhenCompletionTimeMissing() {
        String message = service.validateCompletion(null, null, null);

        Assert.assertEquals("Completion time is required.", message);
    }

    @Test
    public void validateCompletion_returnsActionNotFoundBeforeTouchingAppReq() {
        ActionCompletionService.CompletionTime completionTime = service.parseCompletionTime(webUser, "2026-04-15",
                "09:30", "0");

        String message = service.validateCompletion(null, null, completionTime);

        Assert.assertEquals("Action not found.", message);
    }

    @Test
    public void validateHandoff_acceptsBlankAndNormalNotes() {
        Assert.assertNull(service.validateHandoff(null, null));
        Assert.assertNull(service.validateHandoff("Shipped it; Chris reviews Monday.", "Start from the PR."));
    }

    @Test
    public void validateHandoff_rejectsOverLongOutcomeNote() {
        String message = service.validateHandoff(repeat('x', ActionNotesService.MAX_LENGTH + 1), null);

        Assert.assertEquals("The outcome note is limited to 20,000 characters.", message);
    }

    @Test
    public void validateHandoff_rejectsOverLongFollowUpNotes() {
        String message = service.validateHandoff("ok", repeat('x', ActionNotesService.MAX_LENGTH + 1));

        Assert.assertEquals("Notes for the next action are limited to 20,000 characters.", message);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
