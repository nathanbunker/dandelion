package org.openimmunizationsoftware.pt.manager;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.ProjectNarrative;
import org.openimmunizationsoftware.pt.model.ProjectNarrativeVerb;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;

public class StubNarrativeGeneratorTest {

    @Test
    public void generateDailyMarkdownProducesHeadings() {
        LocalDate day = LocalDate.of(2026, 2, 10);
        List<ActionTaken> completed = Collections.emptyList();
        Map<Integer, Integer> timeByProject = new LinkedHashMap<Integer, Integer>();
        Map<Integer, String> projectNames = new LinkedHashMap<Integer, String>();
        Map<Integer, Project> projectsById = new LinkedHashMap<Integer, Project>();
        Map<Integer, List<String>> openIssuesByProject = new LinkedHashMap<Integer, List<String>>();
        List<ProjectNarrative> projectNarratives = Collections.emptyList();
        List<ActionNext> waiting = Collections.emptyList();

        GenerationContext ctx = new GenerationContext(day, day, "", completed, timeByProject, projectNames,
                projectsById, openIssuesByProject, projectNarratives, waiting);
        NarrativeGenerator generator = new StubNarrativeGenerator();

        String markdown = generator.generateMarkdown("DAILY", ctx);

        Assert.assertTrue(markdown.contains("# Summary"));
        Assert.assertTrue(markdown.contains("# Time By Project"));
        Assert.assertTrue(markdown.contains("# Completed Actions"));
    }

    @Test
    public void weeklyPromptRequestsSupervisorBriefingWithoutRepeatingTables() {
        LocalDate sunday = LocalDate.of(2026, 8, 2);
        GenerationContext ctx = new GenerationContext(sunday, sunday.plusDays(6), "",
                Collections.<ActionTaken>emptyList(), Collections.<Integer, Integer>emptyMap(),
                Collections.<Integer, String>emptyMap(), Collections.<Integer, Project>emptyMap(),
                Collections.<Integer, List<String>>emptyMap(), Collections.<ProjectNarrative>emptyList(),
                Collections.<ActionNext>emptyList());

        String prompt = OpenAiNarrativeGenerator.buildPromptForInspection("WEEKLY", ctx);

        Assert.assertTrue(prompt.contains("## Supervisor Attention"));
        Assert.assertTrue(prompt.contains("## Next Week"));
        Assert.assertTrue(prompt.contains("Do not repeat those tables"));
        Assert.assertTrue(prompt.contains("Period: 2026-08-02 through 2026-08-08"));
    }

    @Test
    public void weeklyPayloadUsesApprovedBriefingsOutcomesFocusAndUpcomingWork() {
        LocalDate sunday = LocalDate.of(2026, 8, 2);
        Project project = new Project();
        project.setProjectId(7);
        project.setProjectName("Weekly Reports");
        project.setOutcomeText("Supervisors can understand the week quickly.");
        project.setCurrentFocusText("Validate the report with a supervisor.");

        TrackerNarrative daily = new TrackerNarrative();
        daily.setPeriodStart(java.sql.Date.valueOf(sunday.plusDays(1)));
        daily.setMarkdownFinal("## Summary\nShipped the report shell.");

        ActionNext completed = new ActionNext();
        completed.setProject(project);
        completed.setNextSummary("The access-key workflow passed validation.");

        ActionNext upcoming = new ActionNext();
        upcoming.setProject(project);
        upcoming.setNextDescription("Review the weekly briefing with a supervisor.");
        upcoming.setNextActionDate(java.sql.Date.valueOf(sunday.plusWeeks(1)));

        ProjectNarrative note = new ProjectNarrative();
        note.setProject(project);
        note.setNarrativeVerb(ProjectNarrativeVerb.NOTE);
        note.setNarrativeText("Reviewed/no comments");

        ProjectNarrative risk = new ProjectNarrative();
        risk.setProject(project);
        risk.setNarrativeVerb(ProjectNarrativeVerb.RISK);
        risk.setNarrativeText("Production migration state is not verified.");

        Map<Integer, Integer> time = new LinkedHashMap<Integer, Integer>();
        time.put(Integer.valueOf(7), Integer.valueOf(180));
        Map<Integer, String> names = new LinkedHashMap<Integer, String>();
        names.put(Integer.valueOf(7), project.getProjectName());
        Map<Integer, Project> projects = new LinkedHashMap<Integer, Project>();
        projects.put(Integer.valueOf(7), project);

        GenerationContext ctx = new GenerationContext(sunday, sunday.plusDays(6), "",
                Collections.<ActionTaken>emptyList(), time, names, projects,
                Collections.<Integer, List<String>>emptyMap(), Arrays.asList(note, risk),
                Collections.<ActionNext>emptyList(), Arrays.asList(completed), Arrays.asList(upcoming),
                Arrays.asList(daily), "America/Denver");

        String prompt = OpenAiNarrativeGenerator.buildPromptForInspection("WEEKLY", ctx);

        Assert.assertTrue(prompt.contains("Shipped the report shell"));
        Assert.assertTrue(prompt.contains("access-key workflow passed validation"));
        Assert.assertTrue(prompt.contains("Validate the report with a supervisor"));
        Assert.assertTrue(prompt.contains("Production migration state is not verified"));
        Assert.assertTrue(prompt.contains("Review the weekly briefing with a supervisor"));
        Assert.assertFalse(prompt.contains("Reviewed/no comments"));
        Assert.assertTrue(prompt.contains("Timezone: America/Denver"));
    }

    @Test
    public void dailyPromptRetainsDailyStructure() {
        LocalDate day = LocalDate.of(2026, 8, 8);
        GenerationContext ctx = new GenerationContext(day, day, "", Collections.<ActionTaken>emptyList(),
                Collections.<Integer, Integer>emptyMap(), Collections.<Integer, String>emptyMap(),
                Collections.<Integer, Project>emptyMap(), Collections.<Integer, List<String>>emptyMap(),
                Collections.<ProjectNarrative>emptyList(), Collections.<ActionNext>emptyList());

        String prompt = OpenAiNarrativeGenerator.buildPromptForInspection("DAILY", ctx);

        Assert.assertTrue(prompt.contains("# Daily Summary - {DATE}"));
        Assert.assertFalse(prompt.contains("## Supervisor Attention"));
    }

    @Test
    public void weeklyPayloadIncludesOutlooksForPlanVersusActual() {
        LocalDate sunday = LocalDate.of(2026, 9, 27);
        Map<String, String> months = new LinkedHashMap<String, String>();
        months.put("September 2026", "Wrap up the Phase 2 MCP work.");
        months.put("October 2026", "Finish the Conformance Reboot.");
        GenerationContext ctx = weeklyContext(sunday, "Ship MCP Phase 3 items 3.1 to 3.3.",
                "Weekly narrative plan-vs-actual work.", months);

        String prompt = OpenAiNarrativeGenerator.buildPromptForInspection("WEEKLY", ctx);

        Assert.assertTrue(prompt.contains("## Plan vs. Actual"));
        Assert.assertTrue(prompt.contains("statements of intent, never evidence that work happened"));
        Assert.assertTrue(prompt.contains("never quote them"));
        Assert.assertTrue(prompt.contains(
                "THIS WEEK'S OUTLOOK (the plan for this report period, written in advance)\nShip MCP Phase 3"));
        Assert.assertTrue(prompt.contains("September 2026:\nWrap up the Phase 2 MCP work."));
        Assert.assertTrue(prompt.contains("October 2026:\nFinish the Conformance Reboot."));
        Assert.assertTrue(prompt.contains(
                "NEXT WEEK'S OUTLOOK (the plan for the following week)\nWeekly narrative plan-vs-actual work."));
        Assert.assertEquals("weekly-supervisor-v3", OpenAiNarrativeGenerator.promptVersionFor("WEEKLY"));
    }

    @Test
    public void weeklyPayloadMarksMissingOutlooksAsNone() {
        LocalDate sunday = LocalDate.of(2026, 9, 27);
        GenerationContext ctx = weeklyContext(sunday, null, "  ", Collections.<String, String>emptyMap());

        String prompt = OpenAiNarrativeGenerator.buildPromptForInspection("WEEKLY", ctx);

        Assert.assertTrue(prompt.contains("If THIS WEEK'S OUTLOOK is None, omit this section entirely"));
        Assert.assertTrue(prompt.contains("(the plan for this report period, written in advance)\nNone\n"));
        Assert.assertTrue(prompt.contains("MONTH OUTLOOK (wider context for the plan)\nNone\n"));
        Assert.assertTrue(prompt.contains("NEXT WEEK'S OUTLOOK (the plan for the following week)\nNone\n"));
    }

    @Test
    public void dailyPayloadHasNoOutlooks() {
        LocalDate sunday = LocalDate.of(2026, 9, 27);
        GenerationContext ctx = weeklyContext(sunday, "A plan.", "Another plan.",
                Collections.<String, String>emptyMap());

        String prompt = OpenAiNarrativeGenerator.buildPromptForInspection("DAILY", ctx);

        Assert.assertFalse(prompt.contains("OUTLOOK"));
    }

    private static GenerationContext weeklyContext(LocalDate sunday, String weekOutlook, String nextWeekOutlook,
            Map<String, String> monthOutlooks) {
        return new GenerationContext(sunday, sunday.plusDays(6), "", Collections.<ActionTaken>emptyList(),
                Collections.<Integer, Integer>emptyMap(), Collections.<Integer, String>emptyMap(),
                Collections.<Integer, Project>emptyMap(), Collections.<Integer, List<String>>emptyMap(),
                Collections.<ProjectNarrative>emptyList(), Collections.<ActionNext>emptyList(),
                Collections.<ActionNext>emptyList(), Collections.<ActionNext>emptyList(),
                Collections.<TrackerNarrative>emptyList(), "America/Denver", weekOutlook, nextWeekOutlook,
                monthOutlooks);
    }

    @Test
    public void completedActionCarriesOutcomeNoteOnOneLine() {
        ActionTaken action = new ActionTaken();
        action.setActionDescription(" Finished Small Group support ");
        action.setActionNote("Merged PR 12.\n\nLeft: deploy needs Chris's OK.");

        Assert.assertEquals("Finished Small Group support -- Outcome: Merged PR 12. Left: deploy needs Chris's OK.",
                OpenAiNarrativeGenerator.withOutcome(action));
    }

    @Test
    public void completedActionWithoutOutcomeNoteIsJustTheDescription() {
        ActionTaken action = new ActionTaken();
        action.setActionDescription("Met with Prometheus.");

        Assert.assertEquals("Met with Prometheus.", OpenAiNarrativeGenerator.withOutcome(action));
    }

    @Test
    public void longOutcomeNoteIsClipped() {
        ActionTaken action = new ActionTaken();
        action.setActionDescription("Read background");
        StringBuilder note = new StringBuilder();
        while (note.length() < 2000) {
            note.append("word ");
        }
        action.setActionNote(note.toString());

        String line = OpenAiNarrativeGenerator.withOutcome(action);
        Assert.assertTrue(line.endsWith("..."));
        Assert.assertEquals("Read background -- Outcome: ".length() + 600, line.length());
    }
}
