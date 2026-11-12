package org.dandeliondaily.personal.service;

import java.time.LocalDate;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

public class ActionCaptureServiceTest {

    /** Complete & Next with only a verb just completes, as on /m/action. */
    @Test
    public void bareVerbIsNotANextAction() {
        Assert.assertFalse(ActionCaptureService.hasContent(null));
        Assert.assertFalse(ActionCaptureService.hasContent("  "));
        Assert.assertFalse(ActionCaptureService.hasContent("I will"));
        Assert.assertFalse(ActionCaptureService.hasContent("i am waiting "));
        Assert.assertTrue(ActionCaptureService.hasContent("I will call him back thursday"));
        Assert.assertTrue(ActionCaptureService.hasContent("buy milk"));
    }

    /** The parser hands back a moment; the action keeps the user's calendar day, not the server's. */
    @Test
    public void parserMomentBecomesTheUsersDay() {
        WebUser user = new WebUser();
        user.setTimeZone(TimeZone.getTimeZone("US/Mountain"));
        // 2026-11-13 03:30 UTC is still the evening of Nov 12 in Mountain time.
        Date moment = Date.from(java.time.Instant.parse("2026-11-13T03:30:00Z"));
        Assert.assertEquals(LocalDate.of(2026, 11, 12), ActionCaptureService.userDate(user, moment));
        Assert.assertEquals(LocalDate.of(2026, 11, 20),
                ActionCaptureService.userDate(user, java.sql.Date.valueOf(LocalDate.of(2026, 11, 20))));
    }

    @Test
    public void editableTypesAreTheEverydayOnes() {
        Assert.assertTrue(ActionCaptureService.EDITABLE_TYPES.contains(ProjectNextActionType.WILL));
        Assert.assertTrue(ActionCaptureService.EDITABLE_TYPES.contains(ProjectNextActionType.WAITING));
        Assert.assertFalse(ActionCaptureService.EDITABLE_TYPES.contains(ProjectNextActionType.OVERDUE_TO));
    }
}
