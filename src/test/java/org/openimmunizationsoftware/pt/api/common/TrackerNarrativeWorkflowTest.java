package org.openimmunizationsoftware.pt.api.common;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.ws.rs.core.Response;

import org.dandeliondaily.mcp.service.McpNarrativesService;
import org.dandeliondaily.narrative.model.TrackerNarrativeScope;
import org.dandeliondaily.narrative.model.TrackerNarrativeViewModel;
import org.dandeliondaily.timereview.model.TimeReviewDayModel;
import org.dandeliondaily.timereview.render.TimeReviewRenderer;
import org.dandeliondaily.narrative.service.NarrativeWorkflowException;
import org.dandeliondaily.narrative.service.TrackerNarrativeService;
import org.hibernate.LockMode;
import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolContext;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolRegistry;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.TrackerNarrativeTool;
import org.openimmunizationsoftware.pt.api.v1.mcp.tools.TrackerNarrativeTool.Operation;
import org.openimmunizationsoftware.pt.api.v1.resource.McpResource;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.doa.TrackerNarrativeDao;
import org.openimmunizationsoftware.pt.servlet.TrackerNarrativeClassicServlet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class TrackerNarrativeWorkflowTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TrackerNarrativeService service = new TrackerNarrativeService();
    private final Map<Integer, TrackerNarrative> narratives = new LinkedHashMap<Integer, TrackerNarrative>();
    private final List<Synchronization> callbacks = new ArrayList<Synchronization>();
    private final List<Long> queued = new ArrayList<Long>();
    private WebUser owner;
    private Project project;
    private Session session;
    private Transaction transaction;
    private boolean active;
    private int nextId;
    private int contactLocks;
    private int narrativeLocks;
    private int commits;
    private int rollbacks;
    private int queryLocks;
    private TrackerNarrative refreshReplacement;
    private boolean hideSnapshotReads;

    @Before
    public void setUp() {
        narratives.clear();
        callbacks.clear();
        queued.clear();
        nextId = 1;
        active = false;
        hideSnapshotReads = false;
        contactLocks = narrativeLocks = commits = rollbacks = queryLocks = 0;
        owner = new WebUser();
        owner.setWebUserId(10);
        owner.setContactId(20);
        owner.setUsername("tester");
        owner.setTimeZone(TimeZone.getTimeZone("Pacific/Auckland"));
        owner.setProjectContact(new ProjectContact());
        project = new Project();
        project.setProjectId(30);
        project.setWorkspaceId(Integer.valueOf(40));
        transaction = (Transaction) Proxy.newProxyInstance(Transaction.class.getClassLoader(),
                new Class<?>[] { Transaction.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "isActive": return Boolean.valueOf(active);
                    case "commit":
                        commits++;
                        finish(Status.STATUS_COMMITTED);
                        return null;
                    case "rollback":
                        rollbacks++;
                        finish(Status.STATUS_ROLLEDBACK);
                        return null;
                    case "registerSynchronization":
                        callbacks.add((Synchronization) args[0]);
                        return null;
                    default: throw new UnsupportedOperationException(method.getName());
                    }
                });
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
                new Class<?>[] { Session.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                    case "getTransaction": return transaction;
                    case "beginTransaction": active = true; return transaction;
                    case "get":
                        if (args[0] == ProjectContact.class) {
                            assertEquals(LockMode.UPGRADE, args[2]);
                            contactLocks++;
                            return owner.getProjectContact();
                        }
                        if (args[0] == Project.class) {
                            return ((Number) args[1]).intValue() == 30 ? project : null;
                        }
                        if (args[0] == TrackerNarrative.class) {
                            if (args.length == 2 && hideSnapshotReads) {
                                return null;
                            }
                            if (args.length == 3) {
                                assertEquals(LockMode.UPGRADE, args[2]);
                            }
                            return narratives.get(Integer.valueOf(((Number) args[1]).intValue()));
                        }
                        throw new UnsupportedOperationException("get " + args[0]);
                    case "refresh":
                        assertEquals(LockMode.UPGRADE, args[1]);
                        narrativeLocks++;
                        if (refreshReplacement != null) {
                            TrackerNarrative target = (TrackerNarrative) args[0];
                            target.setLastUpdated(refreshReplacement.getLastUpdated());
                            target.setMarkdownFinal(refreshReplacement.getMarkdownFinal());
                            refreshReplacement = null;
                        }
                        return null;
                    case "save":
                        TrackerNarrative saved = (TrackerNarrative) args[0];
                        saved.setNarrativeId(nextId++);
                        narratives.put(saved.getNarrativeId(), saved);
                        return Integer.valueOf(saved.getNarrativeId());
                    case "update":
                    case "flush": return null;
                    case "createQuery": return query((String) args[0]);
                    default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @After
    public void clearContexts() {
        HibernateRequestContext.clear();
        ApiRequestContext.clear();
    }

    @Test
    public void submitReadEditApproveThroughJsonRpc() throws Exception {
        JsonNode submitted = call("submit_tracker_narrative", "{\"projectId\":30,\"periodType\":\"WEEKLY\","
                + "\"date\":\"2026-10-23\",\"markdown\":\"# Week\\nOriginal\","
                + "\"modelName\":\"external-model\",\"promptVersion\":\"weekly-v1\",\"promptUsedText\":\"source\"}");
        assertEquals("GENERATED", submitted.path("reviewStatus").asText());
        assertFalse(submitted.path("isApproved").asBoolean());
        assertTrue(submitted.path("isFinal").asBoolean());
        assertEquals("2026-10-18", submitted.path("periodStartDate").asText());
        assertEquals("2026-10-24", submitted.path("periodEndDate").asText());
        int id = submitted.path("narrativeId").asInt();
        JsonNode read = call("get_tracker_narrative", "{\"narrativeId\":" + id + ",\"includePrompt\":true}");
        assertEquals("source", read.path("promptUsedText").asText());
        assertEquals("external-model", read.path("modelName").asText());
        assertEquals("# Week\nOriginal", read.path("markdownGenerated").asText());
        assertFalse(submitted.has("promptUsedText"));

        JsonNode edited = call("update_tracker_narrative", writeArgs(submitted, ",\"markdown\":\"Edited\""));
        assertNotEquals(read.path("lastUpdated"), edited.path("lastUpdated"));
        assertEquals("# Week\nOriginal", edited.path("markdownGenerated").asText());
        assertEquals("Edited", edited.path("markdownFinal").asText());
        assertFalse(edited.path("isApproved").asBoolean());
        JsonNode approved = call("approve_tracker_narrative", writeArgs(edited, ""));
        assertTrue(approved.path("isApproved").asBoolean());
        assertFalse(approved.path("dateApproved").isNull());
        assertEquals(0, approved.path("displacedNarrativeIds").size());
        assertTrue(contactLocks >= 3);
        assertTrue(narrativeLocks >= 2);
        assertTrue(queryLocks >= 2);
    }

    @Test
    public void approvedEditCreatesDraftAndApprovalDisplacesOldReport() throws Exception {
        TrackerNarrative original = submit("Original");
        approve(original);
        Date approvedAt = original.getDateApproved();
        String approvedVersion = TrackerNarrativeService.version(original);
        TrackerNarrative revision = service.updateDraft(session, owner, 40, original.getNarrativeId(),
                approvedVersion, "Revised");
        assertNotEquals(original.getNarrativeId(), revision.getNarrativeId());
        assertEquals(TrackerNarrativeReviewStatus.APPROVED, original.getReviewStatus());
        assertEquals("Original", original.getMarkdownFinal());
        assertEquals(approvedVersion, TrackerNarrativeService.version(original));
        assertEquals(TrackerNarrativeReviewStatus.GENERATED, revision.getReviewStatus());
        assertNull(revision.getDateApproved());
        assertEquals("Original", revision.getMarkdownGenerated());
        assertEquals(Collections.singletonList(original.getNarrativeId()), service.approve(session, owner, 40,
                revision.getNarrativeId(), TrackerNarrativeService.version(revision)));
        assertEquals(TrackerNarrativeReviewStatus.REJECTED, original.getReviewStatus());
        assertEquals(approvedAt, original.getDateApproved());
        assertEquals(TrackerNarrativeReviewStatus.APPROVED, revision.getReviewStatus());
    }

    @Test
    public void identicalSubmissionRetriesReturnOriginalEvenAfterRevisionAndDeletion() {
        TrackerNarrative first = submit("Original");
        approve(first);
        service.updateDraft(session, owner, 40, first.getNarrativeId(), TrackerNarrativeService.version(first), "Revision");
        service.changeStatus(session, owner, 40, first.getNarrativeId(), TrackerNarrativeService.version(first),
                TrackerNarrativeReviewStatus.DELETED);
        assertSame(first, submit("Original"));
        assertEquals(2, narratives.size());
        assertEquals(TrackerNarrativeReviewStatus.DELETED, first.getReviewStatus());
        assertNotSame(first, submit("Different source"));
    }

    @Test
    public void changedSourceMetadataCreatesSeparateCandidate() {
        TrackerNarrative first = submit("Original");
        TrackerNarrative second = service.submit(session, owner, 40, 30, scope(), "Original", "model2", null, null);
        assertNotEquals(first.getNarrativeId(), second.getNarrativeId());
        assertEquals(2, narratives.size());
    }

    @Test
    public void staleApprovalAndEditFailWithoutChangingReport() {
        TrackerNarrative report = submit("Original");
        String oldVersion = TrackerNarrativeService.version(report);
        service.updateDraft(session, owner, 40, report.getNarrativeId(), oldVersion, "Changed");
        expectWorkflow("stale", () -> service.approve(session, owner, 40, report.getNarrativeId(), oldVersion));
        expectWorkflow("stale", () -> service.updateDraft(session, owner, 40, report.getNarrativeId(), oldVersion, "Lost"));
        assertEquals("Changed", report.getMarkdownFinal());
        assertEquals(TrackerNarrativeReviewStatus.GENERATED, report.getReviewStatus());
        assertTrue(rollbacks >= 2);
    }

    @Test
    public void refreshUnderLockChecksCurrentDatabaseTokenNotCachedToken() {
        TrackerNarrative report = submit("Original");
        String oldVersion = TrackerNarrativeService.version(report);
        refreshReplacement = new TrackerNarrative();
        refreshReplacement.setLastUpdated(new Date(report.getLastUpdated().getTime() + 1000));
        refreshReplacement.setMarkdownFinal("Concurrent browser edit");
        expectWorkflow("stale", () -> service.approve(session, owner, 40, report.getNarrativeId(), oldVersion));
        assertEquals("Concurrent browser edit", report.getMarkdownFinal());
        assertFalse(report.getReviewStatus() == TrackerNarrativeReviewStatus.APPROVED);
    }

    @Test
    public void mutationUsesCurrentLockingReadInsteadOfAnOlderRepeatableReadSnapshot() {
        TrackerNarrative report = submit("Committed after request snapshot");
        hideSnapshotReads = true;
        approve(report);
        assertEquals(TrackerNarrativeReviewStatus.APPROVED, report.getReviewStatus());
    }

    @Test
    public void timestampTokensAdvanceAtLegacyDatabasePrecision() {
        TrackerNarrative report = submit("Original");
        long previous = report.getLastUpdated().getTime();
        for (int i = 0; i < 5; i++) {
            service.updateDraft(session, owner, 40, report.getNarrativeId(), TrackerNarrativeService.version(report), "v" + i);
            assertTrue(report.getLastUpdated().getTime() >= previous + 1000);
            assertEquals(0, report.getLastUpdated().getTime() % 1000);
            previous = report.getLastUpdated().getTime();
        }
    }

    @Test
    public void ownershipAndWorkspaceAreEnforcedOnReadsAndWrites() {
        TrackerNarrative report = submit("Original");
        expectWorkflow("not_found", () -> service.read(session, owner, 999, report.getNarrativeId()));
        expectWorkflow("not_found", () -> service.updateDraft(session, owner, 999, report.getNarrativeId(),
                TrackerNarrativeService.version(report), "Wrong workspace"));
        report.setContactId(999);
        expectWorkflow("not_found", () -> service.read(session, owner, 40, report.getNarrativeId()));
        expectWorkflow("not_found", () -> service.approve(session, owner, 40, report.getNarrativeId(),
                TrackerNarrativeService.version(report)));
        expectWorkflow("not_found", () -> service.submit(session, owner, 999, 30, scope(), "Wrong", null, null, null));
        assertEquals(1, narratives.size());
    }

    @Test
    public void blankMarkdownAndMissingTokensAreRejected() {
        expectWorkflow("invalid_arguments", () -> submit(" \n "));
        TrackerNarrative report = submit("Original");
        expectWorkflow("invalid_arguments", () -> service.updateDraft(session, owner, 40, report.getNarrativeId(),
                TrackerNarrativeService.version(report), "\n "));
        expectWorkflow("invalid_arguments", () -> service.approve(session, owner, 40, report.getNarrativeId(), null));
        report.setMarkdownFinal(null);
        expectWorkflow("invalid_arguments", () -> approve(report));
        assertEquals(TrackerNarrativeReviewStatus.GENERATED, report.getReviewStatus());
    }

    @Test
    public void invalidStatesCannotBeApprovedOrEdited() {
        TrackerNarrative report = submit("Original");
        for (TrackerNarrativeReviewStatus state : new TrackerNarrativeReviewStatus[] {
                TrackerNarrativeReviewStatus.GENERATING, TrackerNarrativeReviewStatus.FAILED,
                TrackerNarrativeReviewStatus.REJECTED, TrackerNarrativeReviewStatus.DELETED }) {
            report.setReviewStatus(state);
            expectWorkflow("invalid_state", () -> approve(report));
            if (state != TrackerNarrativeReviewStatus.REJECTED) {
                expectWorkflow("invalid_state", () -> service.updateDraft(session, owner, 40, report.getNarrativeId(),
                        TrackerNarrativeService.version(report), "Blocked"));
            }
        }
    }

    @Test
    public void rejectionAndDeletionAreSoftAndVersionChecked() throws Exception {
        TrackerNarrative report = submit("Original");
        JsonNode read = call("get_tracker_narrative", "{\"narrativeId\":" + report.getNarrativeId() + "}");
        JsonNode rejected = call("reject_tracker_narrative", writeArgs(read, ""));
        assertEquals("REJECTED", rejected.path("reviewStatus").asText());
        JsonNode deleted = call("delete_tracker_narrative", writeArgs(rejected, ""));
        assertEquals("DELETED", deleted.path("reviewStatus").asText());
        assertEquals("Original", deleted.path("markdownFinal").asText());
        assertEquals(1, narratives.size());
    }

    @Test
    public void listFiltersStatusHistoryDeletedAndWorkspace() throws Exception {
        TrackerNarrative first = submit("Approved");
        approve(first);
        TrackerNarrative draft = submit("Draft");
        TrackerNarrative deleted = submit("Deleted");
        deleted.setReviewStatus(TrackerNarrativeReviewStatus.DELETED);
        TrackerNarrative otherWorkspace = submit("Other workspace");
        otherWorkspace.setProjectId(999);
        String base = "{\"periodType\":\"WEEKLY\",\"fromPeriodStart\":\"2026-10-18\","
                + "\"toPeriodStart\":\"2026-10-24\"";
        JsonNode all = call("get_narratives", base + "}");
        assertEquals(2, all.path("narratives").size());
        assertEquals(draft.getNarrativeId(), all.path("narratives").get(0).path("narrativeId").asInt());
        assertTrue(all.path("narratives").get(0).has("text"));
        assertTrue(all.path("narratives").get(0).has("isFinal"));
        assertEquals(1, call("get_narratives", base + ",\"includeHistory\":false}").path("narratives").size());
        assertEquals(3, call("get_narratives", base + ",\"includeDeleted\":true}").path("narratives").size());
        JsonNode approved = call("get_narratives", base + ",\"reviewStatus\":\"APPROVED\"}");
        assertEquals(first.getNarrativeId(), approved.path("narratives").get(0).path("narrativeId").asInt());
    }

    @Test
    public void invalidToolArgumentsFailExplicitly() throws Exception {
        McpToolRegistry registry = new McpToolRegistry();
        String[] invalid = {
                "{\"narrativeId\":\"1\"}", "{\"narrativeId\":1.5}", "{\"narrativeId\":0}",
                "{\"narrativeId\":2147483648}", "{\"narrativeId\":1,\"includePrompt\":\"true\"}",
                "{\"narrativeId\":1,\"extra\":true}", "{}" };
        for (String args : invalid) {
            try {
                registry.require("get_tracker_narrative").call(mapper.readTree(args),
                        new McpToolContext(session, 40, "tester", "test"));
                fail(args);
            } catch (McpToolException e) {
                assertEquals("invalid_arguments", e.getCode());
            }
        }
        assertRpcError("submit_tracker_narrative", "{\"projectId\":30,\"periodType\":\"BAD\","
                + "\"date\":\"2026-10-23\",\"markdown\":\"report\"}", "invalid_arguments");
        assertRpcError("submit_tracker_narrative", "{\"projectId\":30,\"periodType\":\"DAILY\","
                + "\"date\":\"2026-02-30\",\"markdown\":\"report\"}", "invalid_arguments");
        assertRpcError("approve_tracker_narrative", "{\"narrativeId\":1}", "invalid_arguments");
        assertRpcError("get_narratives", "{\"periodType\":\"BAD\",\"fromPeriodStart\":\"2026-10-18\","
                + "\"toPeriodStart\":\"2026-10-24\"}", "invalid_arguments");
    }

    @Test
    public void allToolsAreDiscoverableViaEndpoint() throws Exception {
        JsonNode response = response(new McpResource().handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"));
        List<String> names = new ArrayList<String>();
        for (JsonNode tool : response.path("result").path("tools")) {
            names.add(tool.path("name").asText());
        }
        for (Operation op : Operation.values()) {
            assertTrue(names.contains(new TrackerNarrativeTool(op).getName()));
        }
        assertTrue(names.contains("get_narratives"));
    }

    @Test
    public void generationEnqueuesOnlyAfterOuterTransactionCommits() {
        TrackerNarrativeService generation = generationService(true);
        active = true;
        TrackerNarrative report = generation.generate(session, owner, 40, 30, scope());
        assertEquals(TrackerNarrativeReviewStatus.GENERATING, report.getReviewStatus());
        assertEquals(0, commits);
        assertTrue(queued.isEmpty());
        finish(Status.STATUS_COMMITTED);
        assertEquals(Collections.singletonList(Long.valueOf(report.getNarrativeId())), queued);
    }

    @Test
    public void rolledBackGenerationIsNotEnqueuedAndRegenerationKeepsOriginal() {
        TrackerNarrative source = submit("Original");
        approve(source);
        TrackerNarrativeService generation = generationService(true);
        active = true;
        TrackerNarrative replacement = generation.regenerate(session, owner, 40, source.getNarrativeId(),
                TrackerNarrativeService.version(source));
        assertNotEquals(source.getNarrativeId(), replacement.getNarrativeId());
        assertEquals(TrackerNarrativeReviewStatus.APPROVED, source.getReviewStatus());
        finish(Status.STATUS_ROLLEDBACK);
        assertTrue(queued.isEmpty());
    }

    @Test
    public void unavailableGenerationDoesNotCreateReportAndSubmitStillWorks() {
        expectWorkflow("generation_unavailable", () -> generationService(false).generate(session, owner, 40, 30, scope()));
        assertTrue(narratives.isEmpty());
        assertNotNull(submit("External report"));
    }

    @Test
    public void dailyAndMonthlyPeriodsUseDateOnlySemantics() {
        TrackerNarrative daily = service.submit(session, owner, 40, 30,
                service.resolveScope("DAILY", LocalDate.of(2026, 10, 23)), "Daily", null, null, null);
        TrackerNarrative monthly = service.submit(session, owner, 40, 30,
                service.resolveScope("MONTHLY", LocalDate.of(2026, 2, 14)), "Month", null, null, null);
        assertEquals("2026-10-23", new McpNarrativesService().toMap(daily, owner, false).get("periodStartDate"));
        assertTrue(daily.getPeriodStart() instanceof java.sql.Date);
        assertEquals("2026-10-23", ((java.sql.Date) daily.getPeriodStart()).toLocalDate().toString());
        assertEquals("2026-02-01", owner.toLocalDate(monthly.getPeriodStart()).toString());
        assertEquals("2026-02-28", owner.toLocalDate(monthly.getPeriodEnd()).toString());
    }

    @Test
    public void metadataLimitsAndDeletedOrGeneratingStateChangesAreExplicitErrors() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            longName.append('x');
        }
        expectWorkflow("invalid_arguments", () -> service.submit(session, owner, 40, 30, scope(),
                "Text", longName.toString(), null, null));
        assertTrue(narratives.isEmpty());
        TrackerNarrative report = submit("Original");
        for (TrackerNarrativeReviewStatus status : new TrackerNarrativeReviewStatus[] {
                TrackerNarrativeReviewStatus.GENERATING, TrackerNarrativeReviewStatus.DELETED }) {
            report.setReviewStatus(status);
            expectWorkflow("invalid_state", () -> service.changeStatus(session, owner, 40, report.getNarrativeId(),
                    TrackerNarrativeService.version(report), TrackerNarrativeReviewStatus.REJECTED));
            expectWorkflow("invalid_state", () -> generationService(true).regenerate(session, owner, 40,
                    report.getNarrativeId(), TrackerNarrativeService.version(report)));
        }
    }

    @Test
    public void staleFailuresAreReturnedAsMcpToolErrors() throws Exception {
        TrackerNarrative report = submit("Original");
        JsonNode original = call("get_tracker_narrative", "{\"narrativeId\":" + report.getNarrativeId() + "}");
        call("update_tracker_narrative", writeArgs(original, ",\"markdown\":\"New text\""));
        assertRpcError("approve_tracker_narrative", writeArgs(original, ""), "stale");
        assertEquals(TrackerNarrativeReviewStatus.GENERATED, report.getReviewStatus());
        assertEquals("New text", report.getMarkdownFinal());
    }

    @Test
    public void dashboardFormsCarryTheDisplayedVersionAndRequireSavingBeforeApproval() {
        TrackerNarrative report = submit("Text");
        TrackerNarrative history = submit("History");
        TrackerNarrativeViewModel model = new TrackerNarrativeViewModel();
        model.setScope(scope());
        model.setActiveNarrative(report);
        model.getHistoryItems().add(history);
        TimeReviewDayModel day = new TimeReviewDayModel();
        day.setSelectedDate(owner.toDate(LocalDate.of(2026, 10, 23)));
        day.setSelectedDateIso("2026-10-23");
        TimeReviewRenderer renderer = new TimeReviewRenderer();
        for (String view : new String[] { "edit", "rendered" }) {
            StringWriter text = new StringWriter();
            renderer.renderPage(new PrintWriter(text), owner, day, "today", new TimeReviewRenderer.EditFormModel(),
                    null, model, view, true);
            String html = text.toString();
            assertFormToken(html, "RegenerateNarrative", report);
            assertFormToken(html, "RejectNarrative", report);
            assertFormToken(html, "DeleteNarrative", history);
            if ("edit".equals(view)) {
                assertFormToken(html, "SaveNarrative", report);
                assertFalse(html.contains("value=\"ApproveNarrative\""));
                assertTrue(html.contains("Save your edits before approving"));
            } else {
                assertFormToken(html, "ApproveNarrative", report);
            }
        }
    }

    @Test
    public void legacyEditorCarriesTheDisplayedVersionAndRequiresSavingBeforeApproval() throws Exception {
        TrackerNarrative report = submit("Text");
        java.lang.reflect.Method editor = TrackerNarrativeClassicServlet.class.getDeclaredMethod("printEditor",
                PrintWriter.class, WebUser.class, TrackerNarrative.class, String.class, LocalDate.class, boolean.class,
                TrackerNarrativeDao.class);
        editor.setAccessible(true);
        for (boolean editing : new boolean[] { true, false }) {
            StringWriter text = new StringWriter();
            editor.invoke(new TrackerNarrativeClassicServlet(), new PrintWriter(text), owner, report, "WEEKLY",
                    LocalDate.of(2026, 10, 23), editing, new TrackerNarrativeDao(session) {
                        @Override
                        public TrackerNarrative findAdjacentByContactAndType(int contactId, String type,
                                LocalDate periodStart, boolean next) {
                            return null;
                        }
                    });
            String html = text.toString();
            assertTrue(html.contains("name=\"lastUpdated\" value=\"" + TrackerNarrativeService.version(report) + "\""));
            assertEquals(!editing, html.contains("value=\"Approve\""));
            if (editing) {
                assertTrue(html.contains("Save your edits before approving"));
            }
        }
    }

    private void assertFormToken(String html, String action, TrackerNarrative report) {
        int actionIndex = html.indexOf("value=\"" + action + "\"");
        assertTrue(action, actionIndex >= 0);
        String form = html.substring(html.lastIndexOf("<form", actionIndex), html.indexOf("</form>", actionIndex));
        assertTrue(form, form.contains("name=\"narrativeId\" value=\"" + report.getNarrativeId() + "\""));
        assertTrue(form, form.contains("name=\"lastUpdated\" value=\"" + TrackerNarrativeService.version(report) + "\""));
    }

    private TrackerNarrativeScope scope() {
        return service.resolveScope("WEEKLY", LocalDate.of(2026, 10, 23));
    }

    private TrackerNarrative submit(String markdown) {
        return service.submit(session, owner, 40, 30, scope(), markdown, null, null, null);
    }

    private void approve(TrackerNarrative report) {
        service.approve(session, owner, 40, report.getNarrativeId(), TrackerNarrativeService.version(report));
    }

    private TrackerNarrativeService generationService(boolean available) {
        return new TrackerNarrativeService() {
            protected boolean isGenerationAvailable() { return available; }
            protected void enqueueGeneration(long id) { queued.add(Long.valueOf(id)); }
        };
    }

    private void expectWorkflow(String code, Runnable action) {
        try {
            action.run();
            fail("Expected " + code);
        } catch (NarrativeWorkflowException e) {
            assertEquals(code, e.getCode());
        }
    }

    private String writeArgs(JsonNode report, String more) {
        return "{\"narrativeId\":" + report.path("narrativeId").asInt() + ",\"lastUpdated\":\""
                + report.path("lastUpdated").asText() + "\"" + more + "}";
    }

    private JsonNode rpc(String tool, String args) throws Exception {
        HibernateRequestContext.set(session);
        ApiRequestContext.set(new ApiRequestContext.ApiClientInfo(1, "tester", 40, "test"));
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.putObject("params").put("name", tool).set("arguments", mapper.readTree(args));
        active = true;
        JsonNode result = response(new McpResource().handle(request.toString()));
        if (active) {
            finish(Status.STATUS_COMMITTED);
        }
        return result.path("result");
    }

    private JsonNode call(String tool, String args) throws Exception {
        JsonNode result = rpc(tool, args);
        assertFalse(result.toString(), result.path("isError").asBoolean());
        assertTrue(result.toString(), result.path("structuredContent").isObject());
        return result.path("structuredContent");
    }

    private void assertRpcError(String tool, String args, String code) throws Exception {
        JsonNode result = rpc(tool, args);
        assertTrue(result.toString(), result.path("isError").asBoolean());
        assertTrue(result.toString(), result.toString().contains(code));
    }

    private JsonNode response(Response response) {
        assertEquals(200, response.getStatus());
        return mapper.valueToTree(response.getEntity());
    }

    private void finish(int status) {
        active = false;
        List<Synchronization> completed = new ArrayList<Synchronization>(callbacks);
        callbacks.clear();
        for (Synchronization callback : completed) {
            callback.afterCompletion(status);
        }
    }

    private Query query(String hql) {
        Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        return (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[] { Query.class },
                (proxy, method, args) -> {
                    if ("setLockMode".equals(method.getName())) {
                        assertEquals(LockMode.UPGRADE, args[1]);
                        queryLocks++;
                        return proxy;
                    }
                    if (method.getName().startsWith("set")) {
                        parameters.put((String) args[0], args[1]);
                        return proxy;
                    }
                    if ("uniqueResult".equals(method.getName()) && hql.contains("WebUser")) {
                        return owner;
                    }
                    if ("list".equals(method.getName())) {
                        List<TrackerNarrative> matching = new ArrayList<TrackerNarrative>();
                        for (TrackerNarrative n : narratives.values()) {
                            LocalDate start = owner.toLocalDate(n.getPeriodStart());
                            LocalDate end = owner.toLocalDate(n.getPeriodEnd());
                            LocalDate from = ((java.sql.Date) parameters.get("start")).toLocalDate();
                            LocalDate to = ((java.sql.Date) parameters.get("end")).toLocalDate();
                            if (n.getContactId() == ((Integer) parameters.get("contactId")).intValue()
                                    && n.getNarrativeType().equals(parameters.get("type"))
                                    && (hql.contains("periodStart >=") ? !start.isBefore(from) && !start.isAfter(to)
                                            : start.equals(from) && end.equals(to))) {
                                matching.add(n);
                            }
                        }
                        if (hql.contains("narrativeId desc")) {
                            matching.sort(Comparator.comparingInt(TrackerNarrative::getNarrativeId).reversed());
                        }
                        return matching;
                    }
                    throw new UnsupportedOperationException(method.getName() + " " + hql);
                });
    }
}
