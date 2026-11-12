package org.dandeliondaily.narrative.service;

import static org.junit.Assert.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.dandeliondaily.narrative.model.NarrativeReviewQueue;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;

public class NarrativeReviewQueueTest {

    // Wednesday
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final Date NOW = at(TODAY.atTime(15, 0));

    @Test
    public void draftsWaitUnlessTheirPeriodWasApprovedAfterThem() {
        TrackerNarrative fresh = narrative(1, "DAILY", "2026-11-10", TrackerNarrativeReviewStatus.GENERATED,
                TODAY.minusDays(1).atTime(22, 0));
        TrackerNarrative passedOver = narrative(2, "DAILY", "2026-11-09", TrackerNarrativeReviewStatus.GENERATED,
                TODAY.minusDays(2).atTime(21, 0));
        TrackerNarrative revision = narrative(3, "DAILY", "2026-11-06", TrackerNarrativeReviewStatus.GENERATED,
                TODAY.minusDays(1).atTime(9, 0));
        List<Object[]> approved = Arrays.asList(
                approved("DAILY", "2026-11-09", TODAY.minusDays(2).atTime(22, 0)),
                approved("DAILY", "2026-11-06", TODAY.minusDays(4).atTime(18, 0)));

        NarrativeReviewQueue queue = TrackerNarrativeService.buildReviewQueue(
                Arrays.asList(fresh, passedOver, revision), approved, new HashMap<LocalDate, Integer>(), TODAY,
                TODAY.minusDays(30), NOW);

        assertEquals(2, queue.getToReview().size());
        assertSame(fresh, queue.getToReview().get(0).getNarrative());
        assertFalse(queue.getToReview().get(0).isRevision());
        assertSame(revision, queue.getToReview().get(1).getNarrative());
        assertTrue(queue.getToReview().get(1).isRevision());
        assertEquals(1, queue.getStaleDraftCount());
    }

    @Test
    public void generationsSplitIntoRunningAndStuckAndFailuresOnlyCountWithoutAnApproval() {
        TrackerNarrative running = narrative(1, "DAILY", "2026-11-10", TrackerNarrativeReviewStatus.GENERATING,
                TODAY.atTime(14, 55));
        TrackerNarrative stuck = narrative(2, "DAILY", "2026-03-06", TrackerNarrativeReviewStatus.GENERATING,
                LocalDate.of(2026, 3, 6).atTime(15, 0));
        TrackerNarrative failed = narrative(3, "DAILY", "2026-08-03", TrackerNarrativeReviewStatus.FAILED,
                LocalDate.of(2026, 8, 3).atTime(15, 0));
        TrackerNarrative failedButApproved = narrative(4, "WEEKLY", "2026-08-02",
                TrackerNarrativeReviewStatus.FAILED, LocalDate.of(2026, 8, 8).atTime(15, 0));

        NarrativeReviewQueue queue = TrackerNarrativeService.buildReviewQueue(
                Arrays.asList(running, stuck, failed, failedButApproved),
                Arrays.<Object[]>asList(approved("WEEKLY", "2026-08-02", LocalDate.of(2026, 8, 8).atTime(17, 0))),
                new HashMap<LocalDate, Integer>(), TODAY, TODAY.minusDays(30), NOW);

        assertEquals(Arrays.asList(running), queue.getGenerating());
        assertEquals(Arrays.asList(stuck), queue.getStuck());
        assertEquals(Arrays.asList(failed), queue.getFailed());
    }

    @Test
    public void missingPeriodsNeedBillableTimeAndNoReportAndWeeksMustBeOver() {
        Map<LocalDate, Integer> billable = new HashMap<LocalDate, Integer>();
        billable.put(LocalDate.of(2026, 11, 11), 120); // today, current week
        billable.put(LocalDate.of(2026, 11, 10), 300); // a draft is generating
        billable.put(LocalDate.of(2026, 11, 9), 240); // approved
        billable.put(LocalDate.of(2026, 11, 6), 90); // missing; its week Nov 1-7 is over
        billable.put(LocalDate.of(2026, 10, 1), 60); // before the lookback

        TrackerNarrative generating = narrative(1, "DAILY", "2026-11-10", TrackerNarrativeReviewStatus.GENERATING,
                TODAY.atTime(14, 59));
        NarrativeReviewQueue queue = TrackerNarrativeService.buildReviewQueue(Arrays.asList(generating),
                Arrays.<Object[]>asList(approved("DAILY", "2026-11-09", TODAY.minusDays(1).atTime(8, 0))), billable,
                TODAY, TODAY.minusDays(30), NOW);

        List<String> missing = new ArrayList<String>();
        for (NarrativeReviewQueue.MissingPeriod period : queue.getMissing()) {
            missing.add(period.getNarrativeType() + " " + period.getPeriodStart() + " " + period.getBillableMinutes());
        }
        // Newest period end first; the current week (Nov 8-14) is not over; no monthly reports are in use.
        assertEquals(Arrays.asList("DAILY 2026-11-11 120", "WEEKLY 2026-11-01 90", "DAILY 2026-11-06 90"), missing);
    }

    @Test
    public void monthlyPeriodsAppearOnlyOnceMonthlyReportsAreInUse() {
        Map<LocalDate, Integer> billable = new HashMap<LocalDate, Integer>();
        billable.put(LocalDate.of(2026, 10, 20), 60);
        NarrativeReviewQueue queue = TrackerNarrativeService.buildReviewQueue(new ArrayList<TrackerNarrative>(),
                Arrays.<Object[]>asList(approved("MONTHLY", "2026-09-01", LocalDate.of(2026, 10, 2).atTime(9, 0)),
                        approved("WEEKLY", "2026-10-18", LocalDate.of(2026, 10, 25).atTime(9, 0)),
                        approved("DAILY", "2026-10-20", LocalDate.of(2026, 10, 20).atTime(9, 0))),
                billable, TODAY, TODAY.minusDays(30), NOW);
        assertEquals(1, queue.getMissing().size());
        assertEquals("MONTHLY", queue.getMissing().get(0).getNarrativeType());
        assertEquals(LocalDate.of(2026, 10, 1), queue.getMissing().get(0).getPeriodStart());
    }

    private static TrackerNarrative narrative(int id, String type, String start, TrackerNarrativeReviewStatus status,
            LocalDateTime lastUpdated) {
        TrackerNarrative narrative = new TrackerNarrative();
        narrative.setNarrativeId(id);
        narrative.setNarrativeType(type);
        narrative.setPeriodStart(java.sql.Date.valueOf(start));
        narrative.setPeriodEnd(java.sql.Date.valueOf(start));
        narrative.setReviewStatus(status);
        narrative.setLastUpdated(at(lastUpdated));
        return narrative;
    }

    private static Object[] approved(String type, String start, LocalDateTime when) {
        return new Object[] { type, java.sql.Date.valueOf(start), at(when) };
    }

    private static Date at(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
    }
}
