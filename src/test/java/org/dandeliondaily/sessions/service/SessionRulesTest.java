package org.dandeliondaily.sessions.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.openimmunizationsoftware.pt.api.common.SessionClientScope;
import org.openimmunizationsoftware.pt.model.ActionSession;
import org.openimmunizationsoftware.pt.model.WebApiClient;

/** Rules of the Action Sessions feature that need no database. */
public class SessionRulesTest {

    private static final String FAKE_KEY = "0123456789abcdef0123456789abcdef";

    // ---- folders (AC-11, AC-16)

    @Test
    public void foldersParseKnownRootsAndNormalize() {
        FolderRoots.Folder folder = FolderRoots.parse("brain:projects\\work-hygiene//");
        assertEquals("brain", folder.root);
        assertEquals("projects/work-hygiene", folder.path);
        assertEquals("dropbox:", FolderRoots.parse("DROPBOX:").spec());
        assertEquals("Personal/Bob's (2026) & co", FolderRoots.parse("dev:Personal/Bob's (2026) & co").path);
    }

    @Test
    public void foldersRejectEscapesAbsolutePathsAndUnknownRoots() {
        String[] bad = { "brain:../secrets", "brain:a/../../b", "brain:/etc", "brain:C:/Windows", "C:\\Windows",
                "home:docs", "brain:a/./b", "brain:a%2F..%2Fb", "brain:a|b", "brain:trailing. /x", "brain:a\u0000b",
                "", "brain" };
        for (String spec : bad) {
            try {
                FolderRoots.parse(spec);
                fail("accepted " + spec);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    // ---- client scope (AC-96, AC-84)

    @Test
    public void runnerAndLauncherKeysStayInTheirLanes() {
        assertNull(SessionClientScope.refusal(WebApiClient.TYPE_RUNNER, "v1/runner/claims"));
        assertNotNull(SessionClientScope.refusal(WebApiClient.TYPE_RUNNER, "v1/mcp"));
        assertNotNull(SessionClientScope.refusal(WebApiClient.TYPE_RUNNER, "v1/launcher/redeem"));
        assertNotNull(SessionClientScope.refusal(WebApiClient.TYPE_RUNNER, "v1/runner/../mcp"));
        assertNull(SessionClientScope.refusal(WebApiClient.TYPE_LAUNCHER, "/v1/launcher/launches/4/bail"));
        assertNotNull(SessionClientScope.refusal(WebApiClient.TYPE_LAUNCHER, "v1/actions"));
        assertNotNull(SessionClientScope.refusal(WebApiClient.TYPE_AGENT, "v1/runner/claims"));
        assertNotNull(SessionClientScope.refusal(WebApiClient.TYPE_AGENT, "v1/launcher/redeem"));
        assertNull(SessionClientScope.refusal(WebApiClient.TYPE_AGENT, "v1/mcp"));
    }

    // ---- session link guard (AC-28, AC-56)

    @Test
    public void olderLaunchNeverReplacesNewerSession() {
        ActionSession owned = new ActionSession();
        owned.setLaunchId(Integer.valueOf(20));
        assertTrue(ActionSessionService.mayReplace(null, Integer.valueOf(1), null));
        assertTrue(ActionSessionService.mayReplace(owned, Integer.valueOf(20), null));
        assertTrue(ActionSessionService.mayReplace(owned, Integer.valueOf(21), null));
        assertFalse("Start A after Start B must not win", ActionSessionService.mayReplace(owned, Integer.valueOf(19), null));
        assertFalse("a run never replaces a person's session", ActionSessionService.mayReplace(owned, null, Integer.valueOf(5)));
        ActionSession handoff = new ActionSession();
        handoff.setRunId(Integer.valueOf(5));
        assertTrue("a person's launch takes over a handoff", ActionSessionService.mayReplace(handoff, Integer.valueOf(1), null));
        assertTrue(ActionSessionService.mayReplace(handoff, null, Integer.valueOf(5)));
        assertFalse(ActionSessionService.mayReplace(handoff, null, Integer.valueOf(6)));
    }

    // ---- resume window (AC-26)

    @Test
    public void resumeShownAt29DaysHiddenFrom30() {
        LocalDate today = LocalDate.of(2026, 11, 21);
        assertTrue(ActionSessionService.isResumable(ActionSessionService.ageDays(today, today)));
        assertTrue(ActionSessionService.isResumable(ActionSessionService.ageDays(today.minusDays(29), today)));
        assertFalse(ActionSessionService.isResumable(ActionSessionService.ageDays(today.minusDays(30), today)));
        assertFalse(ActionSessionService.isResumable(ActionSessionService.ageDays(today.plusDays(1), today)));
    }

    // ---- user-local midnight (AC-76)

    @Test
    public void tomorrowsPrepIsDueAtLocalMidnightNotUtcMidnight() {
        ZoneId denver = ZoneId.of("America/Denver");
        LocalDate prepDay = LocalDate.of(2026, 11, 24);
        // 23:30 in Denver on the 23rd is already the 24th in UTC.
        Instant lateEvening = Instant.parse("2026-11-24T06:30:00Z");
        assertEquals(LocalDate.of(2026, 11, 23), EligibleAgentWorkService.localToday(lateEvening, denver));
        assertFalse(EligibleAgentWorkService.isDue(prepDay, EligibleAgentWorkService.localToday(lateEvening, denver)));
        Instant justAfterMidnight = Instant.parse("2026-11-24T07:05:00Z");
        assertTrue(EligibleAgentWorkService.isDue(prepDay, EligibleAgentWorkService.localToday(justAfterMidnight, denver)));
        assertTrue("overdue stays due", EligibleAgentWorkService.isDue(prepDay.minusDays(3),
                EligibleAgentWorkService.localToday(justAfterMidnight, denver)));
        assertFalse(EligibleAgentWorkService.isDue(null, prepDay));
    }

    // ---- environment key (AC-91, R1)

    @Test
    public void environmentComesFromThePageOriginNotAProxyHost() {
        assertEquals("local", LaunchService.environmentFor("localhost:8089", null));
        assertEquals("local", LaunchService.environmentFor("127.0.0.1", null));
        assertEquals("local", LaunchService.environmentFor("[::1]:8089", null));
        assertEquals("prod", LaunchService.environmentFor("dandelion-daily.org", null));
        assertEquals("prod", LaunchService.environmentForPage("https://dandelion-daily.org", "localhost:8080", null));
        assertEquals("local", LaunchService.environmentForPage("http://localhost:8089", "localhost:8089", null));
        assertEquals("local", LaunchService.environmentForPage(null, "localhost:8089", null));
        assertEquals("staging", LaunchService.environmentForPage("https://x.org", "x.org", "staging"));
        assertEquals("prod", LaunchService.environmentFor("evil.localhost.example", null));
    }

    @Test
    public void noncesAreUrlSafeSingleTokens() {
        for (int i = 0; i < 50; i++) {
            String nonce = LaunchService.newNonce();
            assertTrue(nonce, LaunchService.NONCE.matcher(nonce).matches());
        }
        assertEquals(64, LaunchService.sha256("x").length());
    }

    // ---- redaction (AC-69, AC-90)

    @Test
    public void failureTextIsRedacted() {
        String raw = "GET https://calendar.example.com/owa/calendar/abc/feed.ics?token=s3cr3t failed; "
                + "api_key=" + FAKE_KEY + " account 123456789012 Authorization: Bearer abcdef";
        String redacted = AgentRunService.redact(raw);
        assertFalse(redacted, redacted.contains("feed.ics"));
        assertFalse(redacted, redacted.contains("s3cr3t"));
        assertFalse(redacted, redacted.contains(FAKE_KEY));
        assertFalse(redacted, redacted.contains("123456789012"));
        assertFalse(redacted, redacted.contains("abcdef"));
        assertTrue(redacted, redacted.contains("calendar.example.com"));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            huge.append("x ");
        }
        assertTrue(AgentRunService.redact(huge.toString()).length() <= AgentRunService.MAX_ERROR_LENGTH);
    }

    @Test
    public void failureTextNeverKeepsAbsoluteOrProfilePaths() {
        String redacted = AgentRunService.redact("Folder missing: C:\\dev\\as-fixtures\\nope and "
                + "C:/Users/someone/AppData/Local/x.json and \\\\server\\share\\f; dev:as-fixtures/nope stays");
        assertFalse(redacted, redacted.contains("as-fixtures\\nope"));
        assertFalse(redacted, redacted.contains("Users/someone"));
        assertFalse(redacted, redacted.contains("server"));
        assertTrue(redacted, redacted.contains("dev:as-fixtures/nope"));
    }

    @Test
    public void blankMergeTextKeepsTheSurvivorsOwnText() {
        org.openimmunizationsoftware.pt.model.ActionNext survivor = new org.openimmunizationsoftware.pt.model.ActionNext();
        survivor.setNextDescription("Survivor text");
        CloseOutService.ChangeSpec change = new CloseOutService.ChangeSpec();
        change.combinedDescription = "  ";
        assertEquals("Survivor text", CloseOutService.combined(change, survivor));
        change.combinedDescription = "Both together";
        assertEquals("Both together", CloseOutService.combined(change, survivor));
    }

    // ---- bail (AC-19)

    @Test
    public void bailIsAFewLinesOnly() {
        assertEquals("a\nb\nc", SessionCommandService.validateBail(" a\r\nb\nc \n"));
        try {
            SessionCommandService.validateBail("1\n2\n3\n4\n5\n6");
            fail("six lines accepted");
        } catch (SessionException expected) {
            assertEquals(400, expected.getStatus());
        }
        try {
            SessionCommandService.validateBail("  ");
            fail("blank accepted");
        } catch (SessionException expected) {
            assertEquals(400, expected.getStatus());
        }
    }

    // ---- close-out confirmation (AC-36, AC-39)

    @Test
    public void closeOutApplyNeedsExactlyThePreviewedRows() {
        CloseOutService.Plan plan = new CloseOutService.Plan();
        plan.rows.add(row(10, "a"));
        plan.rows.add(row(11, "b"));
        plan.rows.add(row(0, null));
        List<CloseOutService.RowRef> same = new ArrayList<CloseOutService.RowRef>();
        same.add(new CloseOutService.RowRef(10, "a"));
        same.add(new CloseOutService.RowRef(11, "b"));
        CloseOutService.checkConfirmed(plan, same);

        expectStale(plan, refs(10, "a", 11, "changed"), "#11");
        expectStale(plan, refs(10, "a"), "#11");
        expectStale(plan, refs(10, "a", 11, "b", 12, "c"), "#12");
        try {
            CloseOutService.checkConfirmed(plan, null);
            fail("apply without preview");
        } catch (SessionException expected) {
            assertEquals(400, expected.getStatus());
        }
    }

    @Test
    public void agentCloseOutCannotRecordTime() {
        try {
            CloseOutJson.parse("{\"completionDescription\":\"x\",\"durationMinutes\":5,\"noSuccessor\":true}", 4, false,
                    false);
            fail("duration accepted from a session");
        } catch (SessionException expected) {
            assertTrue(expected.getMessage().contains("durationMinutes"));
        }
        CloseOutService.Request request = CloseOutJson.parse(
                "{\"completionDescription\":\"x\",\"successors\":[{\"description\":\"next\",\"scheduledDate\":\"2026-11-24\"}]}",
                4, false, false);
        assertEquals(0, request.durationMinutes);
        assertEquals(LocalDate.of(2026, 11, 24), request.successors.get(0).scheduledDate);
        try {
            CloseOutJson.parse("{\"completionDescription\":\"x\",\"evil\":1}", 4, false, false);
            fail("unknown field accepted");
        } catch (SessionException expected) {
            assertEquals(400, expected.getStatus());
        }
    }

    @Test
    public void agentModelNamesCannotBecomeFlags() {
        assertEquals("opus", AgentCatalogService.validateModel(" opus "));
        assertEquals("claude-opus-5-5[1m]", AgentCatalogService.validateModel("claude-opus-5-5[1m]"));
        assertNull(AgentCatalogService.validateModel(""));
        for (String bad : new String[] { "--dangerously-skip-permissions", "-m x", "a b", "a;b", "a\"b" }) {
            try {
                AgentCatalogService.validateModel(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                assertNotNull(expected.getMessage());
            }
        }
    }

    private static void expectStale(CloseOutService.Plan plan, List<CloseOutService.RowRef> refs, String names) {
        try {
            CloseOutService.checkConfirmed(plan, refs);
            fail("stale accepted");
        } catch (SessionException expected) {
            assertEquals(409, expected.getStatus());
            assertTrue(expected.getMessage(), expected.getMessage().contains(names));
        }
    }

    private static List<CloseOutService.RowRef> refs(Object... pairs) {
        List<CloseOutService.RowRef> list = new ArrayList<CloseOutService.RowRef>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new CloseOutService.RowRef(((Integer) pairs[i]).intValue(), (String) pairs[i + 1]));
        }
        return list;
    }

    private static CloseOutService.Row row(int id, String asOf) {
        CloseOutService.Row row = new CloseOutService.Row();
        row.actionNextId = id;
        row.asOf = asOf;
        row.beforeDescription = "action " + id;
        return row;
    }

    @Test
    public void workSnapshotNamesOnlyWorkTwinProjects() {
        java.util.Map<String, Object> personal = new java.util.HashMap<String, Object>();
        personal.put("projectId", Integer.valueOf(2));
        personal.put("projectName", "Home");
        java.util.Map<String, Object> work = new java.util.HashMap<String, Object>();
        work.put("projectId", Integer.valueOf(1));
        java.util.Map<String, Object> action = new java.util.HashMap<String, Object>();
        action.put("sharedWithProjects", new java.util.ArrayList<Object>(java.util.Arrays.asList(work, personal)));
        java.util.Map<String, Object> other = new java.util.HashMap<String, Object>();
        other.put("sharedWithProjects", new java.util.ArrayList<Object>(java.util.Arrays.asList(personal)));
        java.util.Map<String, Object> planning = new java.util.HashMap<String, Object>();
        planning.put("scheduledActions", java.util.Arrays.asList(action, other));
        RunScopedReadsService.keepOnlyProjects(planning, new java.util.HashSet<Integer>(java.util.Arrays.asList(1)));
        org.junit.Assert.assertEquals(java.util.Arrays.asList(work), action.get("sharedWithProjects"));
        org.junit.Assert.assertFalse(other.containsKey("sharedWithProjects"));
    }
}
