package org.dandeliondaily.focus.service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

public class FocusActionSearchServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12); // a Thursday

    @Test
    public void words_needTwoCharactersAndSplitOnSpaces() {
        Assert.assertEquals(Collections.emptyList(), FocusActionSearchService.words(null));
        Assert.assertEquals(Collections.emptyList(), FocusActionSearchService.words(" a "));
        Assert.assertEquals(Arrays.asList("ais", "rep"), FocusActionSearchService.words("  AIS   Rep "));
        Assert.assertEquals(Arrays.asList("ai"), FocusActionSearchService.words("ai ai"));
    }

    @Test
    public void openWhenLabel_namesNearDaysAndDatesFurtherOut() {
        Assert.assertEquals("No date", FocusActionSearchService.openWhenLabel(null, TODAY));
        Assert.assertEquals("Today", FocusActionSearchService.openWhenLabel(TODAY, TODAY));
        Assert.assertEquals("Tomorrow", FocusActionSearchService.openWhenLabel(TODAY.plusDays(1), TODAY));
        Assert.assertEquals("Tue", FocusActionSearchService.openWhenLabel(TODAY.plusDays(5), TODAY));
        Assert.assertEquals("Nov 19", FocusActionSearchService.openWhenLabel(TODAY.plusDays(7), TODAY));
        Assert.assertEquals("Nov 3", FocusActionSearchService.openWhenLabel(TODAY.minusDays(9), TODAY));
    }

    @Test
    public void doneWhenLabel_saysWhenItWasDone() {
        Assert.assertEquals("Done today", FocusActionSearchService.doneWhenLabel(TODAY, TODAY));
        Assert.assertEquals("Done yesterday", FocusActionSearchService.doneWhenLabel(TODAY.minusDays(1), TODAY));
        Assert.assertEquals("Done Nov 5", FocusActionSearchService.doneWhenLabel(TODAY.minusDays(7), TODAY));
    }
}
