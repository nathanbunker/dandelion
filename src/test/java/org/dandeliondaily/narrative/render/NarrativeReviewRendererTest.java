package org.dandeliondaily.narrative.render;

import static org.junit.Assert.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Date;

import org.dandeliondaily.narrative.model.NarrativeReviewQueue;
import org.dandeliondaily.narrative.model.TrackerNarrativeScope;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;

public class NarrativeReviewRendererTest {

    @Test
    public void diffMarksAddedAndRemovedLinesAndSaysWhenNothingChanged() {
        String html = LineDiff.html("one\ntwo\nthree", "one\n2\nthree\nfour");
        assertTrue(html.contains("<div class=\"d-del\">two</div>"));
        assertTrue(html.contains("<div class=\"d-add\">2</div>"));
        assertTrue(html.contains("<div class=\"d-add\">four</div>"));
        assertTrue(html.contains("<div class=\"d-same\">one</div>"));
        assertTrue(LineDiff.html("same\r\ntext", "same\ntext").contains("No edits"));
        assertTrue(LineDiff.html("<b>", "<i>").contains("&lt;i&gt;"));
        String longDiff = LineDiff.html("a\nb\nc\nd\ne\nf\ng\nh", "a\nb\nc\nd\ne\nf\ng\nh\nadded");
        assertTrue(longDiff.contains("6 unchanged lines"));
        assertTrue(longDiff.contains("<div class=\"d-same\">g</div><div class=\"d-same\">h</div>"
                + "<div class=\"d-add\">added</div>"));
    }

    @Test
    public void periodLabels() {
        LocalDate sunday = LocalDate.of(2026, 11, 1);
        assertEquals("Nov 1 – 7", NarrativeReviewRenderer.shortPeriod("WEEKLY", sunday, sunday.plusDays(6)));
        assertEquals("Nov 29 – Dec 5",
                NarrativeReviewRenderer.shortPeriod("WEEKLY", LocalDate.of(2026, 11, 29), LocalDate.of(2026, 12, 5)));
        assertEquals("Mon Nov 9", NarrativeReviewRenderer.shortPeriod("DAILY", LocalDate.of(2026, 11, 9), null));
        assertEquals("Week of Nov 1 – 7, 2026",
                NarrativeReviewRenderer.longPeriod("WEEKLY", sunday, sunday.plusDays(6)));
        assertEquals("45m", NarrativeReviewRenderer.hours(45));
        assertEquals("2h", NarrativeReviewRenderer.hours(120));
        assertEquals("3.5h", NarrativeReviewRenderer.hours(210));
    }

    @Test
    public void draftRendersWithApproveAndPointsAtTheNextQueueItem() {
        TrackerNarrative first = draft(108, "2026-11-10", "# Tuesday\n\nShipped <it>.");
        TrackerNarrative second = draft(107, "2026-11-09", "Monday");
        NarrativeReviewRenderer.Page page = page();
        page.queue.getToReview().add(new NarrativeReviewQueue.Draft(first, false));
        page.queue.getToReview().add(new NarrativeReviewQueue.Draft(second, false));
        page.selected = first;
        page.scope = new TrackerNarrativeScope();
        page.scope.setNarrativeType("DAILY");
        page.scope.setPeriodStart(LocalDate.of(2026, 11, 10));
        page.scope.setPeriodEnd(LocalDate.of(2026, 11, 10));

        String html = render(page);
        assertTrue(html.contains("<h2>Tuesday, Nov 10, 2026</h2>"));
        assertTrue(html.contains("name=\"nextId\" value=\"107\""));
        assertTrue(html.contains("id=\"nr-approve\" data-action=\"approve\""));
        assertTrue(html.contains("<h1>Tuesday</h1>"));
        assertTrue(html.contains("Shipped &lt;it&gt;."));
        assertTrue(html.contains("nextHref='trackerNarrative?id=107'"));
        assertTrue(html.contains("class=\"nr-item is-current\" href=\"trackerNarrative?id=108\""));
    }

    @Test
    public void emptyQueueSaysCaughtUpAndListsMissingPeriodsWithGenerate() {
        NarrativeReviewRenderer.Page page = page();
        page.queue.getMissing().add(new NarrativeReviewQueue.MissingPeriod("DAILY", LocalDate.of(2026, 11, 6),
                LocalDate.of(2026, 11, 6), 90));
        String html = render(page);
        assertTrue(html.contains("All caught up"));
        assertTrue(html.contains("Needs a report"));
        assertTrue(html.contains("name=\"action\" value=\"generate\""));
        assertTrue(html.contains("name=\"date\" value=\"2026-11-06\""));
    }

    private static NarrativeReviewRenderer.Page page() {
        NarrativeReviewRenderer.Page page = new NarrativeReviewRenderer.Page();
        page.queue = new NarrativeReviewQueue();
        page.today = LocalDate.of(2026, 11, 11);
        page.calendarMonth = YearMonth.of(2026, 11);
        page.lookbackDays = 30;
        page.generationAvailable = true;
        return page;
    }

    private static TrackerNarrative draft(int id, String day, String markdown) {
        TrackerNarrative narrative = new TrackerNarrative();
        narrative.setNarrativeId(id);
        narrative.setNarrativeType("DAILY");
        narrative.setPeriodStart(java.sql.Date.valueOf(day));
        narrative.setPeriodEnd(java.sql.Date.valueOf(day));
        narrative.setReviewStatus(TrackerNarrativeReviewStatus.GENERATED);
        narrative.setMarkdownGenerated(markdown);
        narrative.setMarkdownFinal(markdown);
        narrative.setLastUpdated(new Date());
        return narrative;
    }

    private static String render(NarrativeReviewRenderer.Page page) {
        StringWriter buffer = new StringWriter();
        new NarrativeReviewRenderer().render(new PrintWriter(buffer), null, page);
        return buffer.toString();
    }
}
