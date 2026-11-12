package org.dandeliondaily.narrative.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.openimmunizationsoftware.pt.model.TrackerNarrative;

/**
 * What the owner still has to do with their narratives: drafts to review, generations under way or stuck,
 * failures, and recent periods with billable time that have no report yet.
 */
public class NarrativeReviewQueue {

    /** A draft waiting for review. */
    public static class Draft {
        private final TrackerNarrative narrative;
        private final boolean revision;

        public Draft(TrackerNarrative narrative, boolean revision) {
            this.narrative = narrative;
            this.revision = revision;
        }

        public TrackerNarrative getNarrative() {
            return narrative;
        }

        /** True when the period already has an approved report and this draft would replace it. */
        public boolean isRevision() {
            return revision;
        }
    }

    /** A period with billable time but no approved report and nothing pending. */
    public static class MissingPeriod {
        private final String narrativeType;
        private final LocalDate periodStart;
        private final LocalDate periodEnd;
        private final int billableMinutes;

        public MissingPeriod(String narrativeType, LocalDate periodStart, LocalDate periodEnd, int billableMinutes) {
            this.narrativeType = narrativeType;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.billableMinutes = billableMinutes;
        }

        public String getNarrativeType() {
            return narrativeType;
        }

        public LocalDate getPeriodStart() {
            return periodStart;
        }

        public LocalDate getPeriodEnd() {
            return periodEnd;
        }

        public int getBillableMinutes() {
            return billableMinutes;
        }
    }

    private final List<Draft> toReview = new ArrayList<Draft>();
    private final List<TrackerNarrative> generating = new ArrayList<TrackerNarrative>();
    private final List<TrackerNarrative> stuck = new ArrayList<TrackerNarrative>();
    private final List<TrackerNarrative> failed = new ArrayList<TrackerNarrative>();
    private final List<MissingPeriod> missing = new ArrayList<MissingPeriod>();
    private int staleDraftCount;

    public List<Draft> getToReview() {
        return toReview;
    }

    public List<TrackerNarrative> getGenerating() {
        return generating;
    }

    /** Generations that started long ago and never finished. */
    public List<TrackerNarrative> getStuck() {
        return stuck;
    }

    public List<TrackerNarrative> getFailed() {
        return failed;
    }

    public List<MissingPeriod> getMissing() {
        return missing;
    }

    /** Drafts left over from before their period was approved; not shown in the queue. */
    public int getStaleDraftCount() {
        return staleDraftCount;
    }

    public void setStaleDraftCount(int staleDraftCount) {
        this.staleDraftCount = staleDraftCount;
    }
}
