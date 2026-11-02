package org.dandeliondaily.focus.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.LinkedMeeting;

public class FocusMeetingServiceTest {

    private final FocusMeetingService service = new FocusMeetingService();

    @Test
    public void normalizeChimeAcceptsOnlyKnownSounds() {
        Assert.assertEquals("bell", FocusMeetingService.normalizeChime(" Bell "));
        Assert.assertEquals("off", FocusMeetingService.normalizeChime("off"));
        Assert.assertNull(FocusMeetingService.normalizeChime("siren"));
        Assert.assertNull(FocusMeetingService.normalizeChime(null));
    }

    @Test
    public void combine_ordersTimedByStartThenUntimed() {
        ActionNext handMade = action(1);
        ActionNext late = action(2);
        ActionNext early = action(3);
        Map<Integer, LinkedMeeting> linked = new HashMap<Integer, LinkedMeeting>();
        linked.put(2, linked(2, 5000, 6000, "CONFIRMED", null));
        linked.put(3, linked(3, 1000, 2000, "TENTATIVE", " https://join.example/x "));

        List<FocusMeetingService.TodayMeeting> meetings = service.combine(Arrays.asList(handMade, late, early), linked);

        Assert.assertEquals(3, meetings.size());
        Assert.assertSame(early, meetings.get(0).getAction());
        Assert.assertEquals("https://join.example/x", meetings.get(0).getJoinUrl());
        Assert.assertSame(late, meetings.get(1).getAction());
        Assert.assertNull(meetings.get(1).getJoinUrl());
        Assert.assertSame(handMade, meetings.get(2).getAction());
        Assert.assertFalse(meetings.get(2).isTimed());
    }

    @Test
    public void combine_skipsCancelledOccurrences() {
        Map<Integer, LinkedMeeting> linked = new HashMap<Integer, LinkedMeeting>();
        linked.put(1, linked(1, 1000, 2000, "CANCELLED", null));
        linked.put(2, linked(2, 1000, 2000, "MISSING", null));

        List<FocusMeetingService.TodayMeeting> meetings = service.combine(Arrays.asList(action(1), action(2)), linked);

        Assert.assertEquals(1, meetings.size());
        Assert.assertEquals(2, meetings.get(0).getAction().getActionNextId());
    }

    private ActionNext action(int id) {
        ActionNext action = new ActionNext();
        action.setActionNextId(id);
        return action;
    }

    private LinkedMeeting linked(int actionId, long start, long end, String status, String joinUrl) {
        LinkedMeeting meeting = new LinkedMeeting();
        meeting.setActionNextId(actionId);
        meeting.setScheduledStartEpochMs(start);
        meeting.setScheduledEndEpochMs(end);
        meeting.setSourceStatus(status);
        meeting.setJoinUrl(joinUrl);
        return meeting;
    }
}
