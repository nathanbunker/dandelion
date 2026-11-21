package org.dandeliondaily.sessions.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.cfg.Configuration;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Opt-in: lifecycle, race and atomicity checks against a real MySQL schema. Never the app database.
 * Prepare a disposable schema with the current structure and v5.17 applied, e.g.
 *
 * <pre>
 * mysql -e "CREATE DATABASE as_test_m2"
 * mysqldump --no-data dandelion | mysql as_test_m2
 * mysql as_test_m2 &lt; src/db/v5.17.sql
 * DANDELION_AS_TEST_JDBC_URL="jdbc:mysql://127.0.0.1:3306/as_test_m2?useSSL=false&amp;allowPublicKeyRetrieval=true"
 * DANDELION_AS_TEST_USER=root MYSQL_PWD=... mvn test -Dtest=ActionSessionsPersistenceTest
 * </pre>
 *
 * Each test wipes and reseeds the fixture rows it needs.
 */
public class ActionSessionsPersistenceTest {

    private static SessionFactory factory;
    private static String url;
    private static String user;
    private static String password;

    static final int USER_ID = 900001;
    static final int CONTACT_ID = 900001;
    static final int OTHER_USER_ID = 900002;
    static final int OTHER_CONTACT_ID = 900002;
    static final int WORKSPACE_ID = 900001;
    static final int PROJECT_ID = 900010;
    static final int OTHER_PROJECT_ID = 900011;
    static final int PERSONAL_PROJECT_ID = 900012;
    static final int CLIENT_ID = 900001;

    private final AgentRunService runService = new AgentRunService();
    private final LaunchService launchService = new LaunchService();
    private final SessionCommandService commandService = new SessionCommandService();
    private final CloseOutService closeOutService = new CloseOutService();

    @BeforeClass
    public static void connect() {
        url = System.getenv("DANDELION_AS_TEST_JDBC_URL");
        Assume.assumeTrue("Requires an isolated local as_test_* schema.", url != null);
        if (!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/as_test_[a-z0-9]+(\\?.*)?")) {
            throw new IllegalStateException("Never run this test on the application database.");
        }
        user = System.getenv("DANDELION_AS_TEST_USER");
        password = System.getenv("MYSQL_PWD");
        Configuration config = new Configuration().configure();
        config.setProperty("hibernate.connection.url", url);
        config.setProperty("hibernate.connection.username", user);
        config.setProperty("hibernate.connection.password", password == null ? "" : password);
        config.setProperty("connection.password", password == null ? "" : password);
        config.setProperty("hibernate.show_sql", "false");
        factory = config.buildSessionFactory();
    }

    @AfterClass
    public static void close() {
        if (factory != null) {
            factory.close();
        }
    }

    // ------------------------------------------------------------------ fixtures

    @Before
    public void seed() throws SQLException {
        exec("delete from agent_run", "delete from agent_prep_target", "delete from action_agent_config",
                "delete from action_session", "delete from action_launch", "delete from project_folder",
                "delete from bill_entry", "delete from action_taken", "delete from action_change_log",
                "update action_next set blocked_by_id = null", "delete from action_next", "delete from action_set",
                "delete from project", "delete from workspace_member", "delete from workspace",
                "delete from web_user", "delete from project_contact", "delete from tracker_keys where key_id = 'as-fixture'");
        exec("insert into project_contact (contact_id, name_last, name_first) values (" + CONTACT_ID + ", 'Fixture', 'AS'),"
                + " (" + OTHER_CONTACT_ID + ", 'Other', 'AS')");
        exec("insert into web_user (web_user_id, username, contact_id, created_date) values (" + USER_ID
                + ", 'as-fixture', " + CONTACT_ID + ", now()), (" + OTHER_USER_ID + ", 'as-other', " + OTHER_CONTACT_ID
                + ", now())");
        exec("insert into workspace (workspace_id, workspace_name, workspace_type, created_by_web_user_id, workspace_status, created_date)"
                + " values (" + WORKSPACE_ID + ", '[AS-FIXTURE] ws', 'PRIVATE', " + USER_ID + ", 'ACTIVE', now())");
        exec("insert into project (project_id, project_name, web_user_id, workspace_id, bill_code) values ("
                + PROJECT_ID + ", '[AS-FIXTURE] Work Hygiene', " + USER_ID + ", " + WORKSPACE_ID + ", null), ("
                + OTHER_PROJECT_ID + ", '[AS-FIXTURE] Linked', " + USER_ID + ", " + WORKSPACE_ID + ", null), ("
                + PERSONAL_PROJECT_ID + ", '[AS-FIXTURE] Personal Hygene', " + USER_ID + ", " + WORKSPACE_ID + ", null)");
        exec("insert into project_folder (project_id, folder_role, primary_project_id, root_key, relative_path, sort_order) values ("
                + PROJECT_ID + ", 'PRIMARY', " + PROJECT_ID + ", 'dev', 'as-fixtures/work-hygiene', 0), ("
                + PERSONAL_PROJECT_ID + ", 'PRIMARY', " + PERSONAL_PROJECT_ID + ", 'dev', 'as-fixtures/personal-hygene', 0)");
    }

    private void exec(String... sqls) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            Statement s = c.createStatement();
            s.execute("set foreign_key_checks = 0");
            for (String sql : sqls) {
                s.execute(sql);
            }
            s.execute("set foreign_key_checks = 1");
        } finally {
            c.close();
        }
    }

    private long count(String sql) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            ResultSet rs = c.createStatement().executeQuery(sql);
            rs.next();
            return rs.getLong(1);
        } finally {
            c.close();
        }
    }

    private String string(String sql) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            ResultSet rs = c.createStatement().executeQuery(sql);
            return rs.next() ? rs.getString(1) : null;
        } finally {
            c.close();
        }
    }

    /** Every action_next row as text, to prove an atomic failure changed nothing. */
    private String snapshot() throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            ResultSet rs = c.createStatement().executeQuery("select action_next_id, next_action_status, next_description, "
                    + "next_action_date, next_change_date, blocked_by_id, next_notes from action_next order by action_next_id");
            StringBuilder sb = new StringBuilder();
            while (rs.next()) {
                for (int i = 1; i <= 7; i++) {
                    sb.append(rs.getString(i)).append('|');
                }
                sb.append('\n');
            }
            sb.append(count("select count(*) from action_taken")).append(" taken\n");
            return sb.toString();
        } finally {
            c.close();
        }
    }

    private static LocalDate today() {
        return LocalDate.now(TimeZone.getTimeZone(WebUser.AMERICA_DENVER).toZoneId());
    }

    private int action(int projectId, String description, LocalDate date, String agent, Integer templateId)
            throws SQLException {
        return action(projectId, description, date, agent, templateId, null, null);
    }

    private int action(int projectId, String description, LocalDate date, String agent, Integer templateId,
            String templateType, Integer actionSetId) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            PreparedStatement ps = c.prepareStatement("insert into action_next (project_id, contact_id, workspace_id, "
                    + "next_action_status, next_change_date, next_description, next_action_type, next_time_estimate, "
                    + "priority_level, billable, next_action_date, completion_order, reschedule_locked, performed_by_agent, "
                    + "template_action_next_id, template_type, action_set_id, next_notes, link_url) "
                    + "values (?, ?, ?, 'R', '2026-01-01 08:00:00', ?, 'WILL', 15, 1, 'N', ?, 0, 'N', ?, ?, ?, ?, ?, '')",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, projectId);
            ps.setInt(2, CONTACT_ID);
            ps.setInt(3, WORKSPACE_ID);
            ps.setString(4, "[AS-FIXTURE] " + description);
            ps.setDate(5, date == null ? null : java.sql.Date.valueOf(date));
            ps.setString(6, agent);
            ps.setObject(7, templateId);
            ps.setString(8, templateType);
            ps.setObject(9, actionSetId);
            ps.setString(10, "Original notes for " + description);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            return keys.getInt(1);
        } finally {
            c.close();
        }
    }

    interface Work<T> {
        T run(Session session, WebUser webUser);
    }

    /** One "request": its own session and transaction; refusals roll back like the resources do. */
    private <T> T request(int webUserId, Work<T> work) {
        Session session = factory.openSession();
        Transaction tx = session.beginTransaction();
        try {
            WebUser webUser = (WebUser) session.get(WebUser.class, Integer.valueOf(webUserId));
            webUser.setTimeZone(TimeZone.getTimeZone(WebUser.AMERICA_DENVER));
            webUser.setProjectContact((ProjectContact) session.get(ProjectContact.class,
                    Integer.valueOf(webUser.getContactId())));
            T result = work.run(session, webUser);
            tx.commit();
            return result;
        } catch (RuntimeException e) {
            if (tx.isActive()) {
                tx.rollback();
            }
            throw e;
        } finally {
            session.close();
        }
    }

    private <T> T request(Work<T> work) {
        return request(USER_ID, work);
    }

    private AgentRunService.Outcome claim(final int actionId, final Date now) {
        return request(new Work<AgentRunService.Outcome>() {
            public AgentRunService.Outcome run(Session session, WebUser webUser) {
                return runService.claim(session, webUser, WORKSPACE_ID, CLIENT_ID, actionId, now);
            }
        });
    }

    private AgentRunService.Outcome complete(final String token, final Date now) {
        return request(new Work<AgentRunService.Outcome>() {
            public AgentRunService.Outcome run(Session session, WebUser webUser) {
                AgentRunService.Completion completion = new AgentRunService.Completion();
                completion.completionDescription = "Prepared the workday";
                completion.outcomeNote = "Found 3 meetings";
                completion.handoffNotes = "Summary: 3 meetings; proposed Outlook batch; questions.";
                return runService.complete(session, webUser, WORKSPACE_ID, token, completion, now);
            }
        });
    }

    private AgentRunService.Outcome failRun(final String token, final String error, final Date now) {
        return request(new Work<AgentRunService.Outcome>() {
            public AgentRunService.Outcome run(Session session, WebUser webUser) {
                return runService.fail(session, webUser, WORKSPACE_ID, token, error, null, now);
            }
        });
    }

    private List<Integer> eligible() {
        return request(new Work<List<Integer>>() {
            @SuppressWarnings("unchecked")
            public List<Integer> run(Session session, WebUser webUser) {
                List<Integer> ids = new ArrayList<Integer>();
                Map<String, Object> list = runService.listEligible(session, webUser, WORKSPACE_ID, null, new Date());
                for (Map<String, Object> item : (List<Map<String, Object>>) list.get("eligible")) {
                    ids.add((Integer) item.get("actionNextId"));
                }
                return ids;
            }
        });
    }

    private static String token(AgentRunService.Outcome outcome) {
        return (String) outcome.body.get("attemptToken");
    }

    // ------------------------------------------------------------------ claims (AC-73, AC-76)

    @Test
    public void neverMoreThanThreeActiveClaimsAndOneWinnerPerAction() throws Exception {
        final List<Integer> ids = new ArrayList<Integer>();
        for (int i = 0; i < 5; i++) {
            ids.add(Integer.valueOf(action(PROJECT_ID, "prep " + i, today(), "claude-code", null)));
        }
        action(PROJECT_ID, "future prep", today().plusDays(1), "claude-code", null);
        action(PROJECT_ID, "human work", today(), null, null);
        assertEquals("only due agent actions", ids, eligible());

        ExecutorService pool = Executors.newFixedThreadPool(10);
        final CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 10; i++) {
            final int actionId = ids.get(i % 5).intValue();
            results.add(pool.submit(new Callable<Integer>() {
                public Integer call() throws Exception {
                    start.await();
                    try {
                        return Integer.valueOf(claim(actionId, new Date()).status);
                    } catch (SessionException refused) {
                        return Integer.valueOf(refused.getStatus());
                    }
                }
            }));
        }
        start.countDown();
        int won = 0;
        for (Future<Integer> f : results) {
            if (f.get().intValue() == 200) {
                won++;
            }
        }
        pool.shutdown();
        assertEquals(3, won);
        assertEquals(3, count("select count(*) from agent_run where run_status in ('CLAIMED','RUNNING')"));
        assertEquals("one winner per action", 3,
                count("select count(distinct action_next_id) from agent_run where run_status in ('CLAIMED','RUNNING')"));
    }

    // ------------------------------------------------------------------ success, replay, no time (AC-46, AC-71, AC-88)

    @Test
    public void successHandsOffToOneReviewRecordsNoTimeAndReplaysAfterCommit() throws Exception {
        int prep = action(PROJECT_ID, "Prepare daily triage", today(), "claude-code", null);
        AgentRunService.Outcome claimed = claim(prep, new Date());
        assertEquals(200, claimed.status);
        assertEquals("dev", ((Map<?, ?>) claimed.body.get("folder")).get("root"));
        AgentRunService.Outcome done = complete(token(claimed), new Date());
        assertEquals(200, done.status);
        int review = ((Integer) done.body.get("reviewActionNextId")).intValue();
        assertEquals("C", string("select next_action_status from action_next where action_next_id = " + prep));
        assertEquals(String.valueOf(prep), string("select follows_action_next_id from action_next where action_next_id = " + review));
        assertEquals(today().toString(), string("select next_action_date from action_next where action_next_id = " + review));
        assertEquals(claimed.body.get("sessionId"), string("select session_id from action_session where action_next_id = " + review));
        assertEquals(0, count("select count(*) from bill_entry"));

        // The response was lost; the retry gets the committed result and changes nothing.
        String before = snapshot();
        AgentRunService.Outcome replay = complete(token(claimed), new Date());
        assertEquals(Boolean.TRUE, replay.body.get("replayed"));
        assertEquals(Integer.valueOf(review), replay.body.get("reviewActionNextId"));
        // A crash report arriving after the commit never turns success into failure.
        AgentRunService.Outcome lateFail = failRun(token(claimed), "runner crashed after commit", new Date());
        assertEquals(409, lateFail.status);
        assertEquals(before, snapshot());
        assertEquals("SUCCEEDED", string("select run_status from agent_run where attempt_token = '" + token(claimed) + "'"));
        assertFalse(eligible().contains(Integer.valueOf(prep)));
    }

    // ------------------------------------------------------------------ failure, retry (AC-69, AC-70)

    @Test
    public void failureLeavesSourceOpenWithOneFailureActionUntilRetry() throws Exception {
        int prep = action(PROJECT_ID, "Prepare daily triage", today().minusDays(1), "claude-code", null);
        AgentRunService.Outcome claimed = claim(prep, new Date());
        AgentRunService.Outcome failed = failRun(token(claimed),
                "Feed fetch failed for https://calendar.example.com/secret/feed.ics?k=abc", new Date());
        assertEquals(200, failed.status);
        assertEquals("R", string("select next_action_status from action_next where action_next_id = " + prep));
        String notes = string("select next_notes from action_next where action_next_id = " + prep);
        assertTrue(notes, notes.startsWith("Original notes"));
        assertTrue(notes, notes.contains("Agent run failed"));
        assertFalse("redacted", notes.contains("feed.ics"));
        assertEquals(1, count("select count(*) from action_next where follows_action_next_id = " + prep
                + " and next_description like '%agent run failed%' and project_id = " + PROJECT_ID
                + " and next_action_date = '" + today() + "'"));
        assertEquals(0, count("select count(*) from agent_run where run_status in ('CLAIMED','RUNNING')"));
        // Repeating the report adds nothing.
        failRun(token(claimed), "again", new Date());
        assertEquals(1, count("select count(*) from action_next where follows_action_next_id = " + prep));
        assertFalse("no reclaim before Retry", eligible().contains(Integer.valueOf(prep)));
        final int failureAction = ((Integer) failed.body.get("failureActionNextId")).intValue();
        request(new Work<Integer>() {
            public Integer run(Session session, WebUser webUser) {
                return Integer.valueOf(runService.retry(session, webUser, WORKSPACE_ID, failureAction, new Date()));
            }
        });
        assertTrue("Retry makes it eligible", eligible().contains(Integer.valueOf(prep)));
        assertEquals(200, claim(prep, new Date()).status);
    }

    // ------------------------------------------------------------------ expiry (AC-72)

    @Test
    public void expiredLeaseIsVisibleAndLateWritesAreRejected() throws Exception {
        int prep = action(PROJECT_ID, "Prepare daily triage", today(), "claude-code", null);
        Date claimedAt = new Date();
        AgentRunService.Outcome claimed = claim(prep, claimedAt);
        Date late = new Date(claimedAt.getTime() + AgentRunService.LEASE_MS + 60000L);
        AgentRunService.Outcome result = complete(token(claimed), late);
        assertEquals(409, result.status);
        assertEquals("EXPIRED", string("select run_status from agent_run where attempt_token = '" + token(claimed) + "'"));
        assertEquals("R", string("select next_action_status from action_next where action_next_id = " + prep));
        assertEquals(1, count("select count(*) from action_next where follows_action_next_id = " + prep
                + " and next_description like '%agent run expired%'"));
        try {
            complete(token(claimed), new Date(late.getTime() + 1000));
            fail("late write accepted");
        } catch (SessionException refused) {
            assertEquals(409, refused.getStatus());
        }
        assertEquals(0, count("select count(*) from bill_entry"));
    }

    // ------------------------------------------------------------------ attach to a human instance (AC-46, AC-47, AC-56, AC-57)

    private int[] attachFixture(LocalDate prepDate, LocalDate targetDate) throws SQLException {
        int humanTemplate = action(PROJECT_ID, "plan work for today", null, null, null, "D", null);
        int prepTemplate = action(PROJECT_ID, "Prepare my workday", null, "claude-code", null, "D", null);
        exec("insert into action_agent_config (action_next_id, workload_key, handoff_mode, handoff_project_id, handoff_template_id)"
                + " values (" + prepTemplate + ", 'work-hygiene-prep', 'ATTACH_TEMPLATE_INSTANCE', " + PROJECT_ID + ", "
                + humanTemplate + ")");
        int target = action(PROJECT_ID, "plan work for today", targetDate, null, Integer.valueOf(humanTemplate));
        int prep = action(PROJECT_ID, "Prepare my workday", prepDate, "claude-code", Integer.valueOf(prepTemplate));
        return new int[] { prep, target, humanTemplate };
    }

    @Test
    public void attachKeepsTheHumanInstanceAndAddsOneHandoff() throws Exception {
        int[] f = attachFixture(today(), today());
        int prep = f[0];
        int target = f[1];
        String targetBefore = string("select concat_ws('|', action_next_id, next_description, next_action_date, contact_id,"
                + " next_time_estimate, link_url, coalesce(follows_action_next_id,'-')) from action_next where action_next_id = " + target);
        AgentRunService.Outcome claimed = claim(prep, new Date());
        assertEquals(Integer.valueOf(target), ((Map<?, ?>) claimed.body.get("handoff")).get("targetActionNextId"));
        AgentRunService.Outcome done = complete(token(claimed), new Date());
        assertEquals(Integer.valueOf(target), done.body.get("reviewActionNextId"));
        assertEquals(targetBefore, string("select concat_ws('|', action_next_id, next_description, next_action_date, contact_id,"
                + " next_time_estimate, link_url, coalesce(follows_action_next_id,'-')) from action_next where action_next_id = " + target));
        String notes = string("select next_notes from action_next where action_next_id = " + target);
        assertTrue(notes, notes.startsWith("Original notes for plan work for today"));
        assertTrue(notes.contains("Summary: 3 meetings"));
        assertEquals("no new review action", 0, count("select count(*) from action_next where follows_action_next_id = " + prep));
        assertEquals("HANDOFF", string("select source from action_session where action_next_id = " + target));
        // Running the prep again finds nothing to do: one handoff, same id, no duplicated notes.
        assertFalse(eligible().contains(Integer.valueOf(prep)));
        complete(token(claimed), new Date());
        assertEquals(notes, string("select next_notes from action_next where action_next_id = " + target));
        assertEquals(0, count("select count(*) from bill_entry"));
    }

    @Test
    public void missingClosedOrOccupiedTargetFailsAndLeavesItAlone() throws Exception {
        int[] f = attachFixture(today(), today());
        exec("update action_next set next_action_status = 'C' where action_next_id = " + f[1]);
        AgentRunService.Outcome closed = claim(f[0], new Date());
        assertEquals(422, closed.status);
        assertEquals("R", string("select next_action_status from action_next where action_next_id = " + f[0]));

        seed();
        f = attachFixture(today(), today());
        exec("insert into action_session (action_next_id, agent_key, session_id, root_key, relative_path, saved_date, launch_id,"
                + " session_state, source) values (" + f[1] + ", 'claude-code', '11111111-1111-1111-1111-111111111111', 'dev', 'x', now(), 7,"
                + " 'ACTIVE', 'LAUNCH')");
        AgentRunService.Outcome occupied = claim(f[0], new Date());
        assertEquals(422, occupied.status);
        assertEquals("his link untouched", "11111111-1111-1111-1111-111111111111",
                string("select session_id from action_session where action_next_id = " + f[1]));
    }

    @Test
    public void yesterdaysPrepNeverTakesTodaysInstance() throws Exception {
        int[] f = attachFixture(today().minusDays(1), today());
        AgentRunService.Outcome claimed = claim(f[0], new Date());
        assertEquals(422, claimed.status);
        assertNull(string("select session_id from action_session where action_next_id = " + f[1]));
        assertTrue(string("select error_text from agent_run").contains(today().minusDays(1).toString()));
    }

    // ------------------------------------------------------------------ per-target preps (AC-59, AC-60)

    @Test
    public void oneGmailPrepPerOpenOccurrenceFollowingItsTarget() throws Exception {
        int clearEmail = action(PROJECT_ID, "clear personal email", null, null, null, "W", null);
        int gmailPrep = action(PERSONAL_PROJECT_ID, "Triage personal Gmail", null, "claude-code", null, "W", null);
        exec("insert into action_agent_config (action_next_id, workload_key, handoff_mode, handoff_template_id)"
                + " values (" + gmailPrep + ", 'gmail-triage', 'PER_TARGET_INSTANCE', " + clearEmail + ")");
        int todays = action(PROJECT_ID, "clear personal email", today(), null, Integer.valueOf(clearEmail));
        int tomorrows = action(PROJECT_ID, "clear personal email", today().plusDays(1), null, Integer.valueOf(clearEmail));
        eligible();
        eligible();
        assertEquals("exactly one prep, only for the due occurrence", 1, count("select count(*) from agent_prep_target"));
        assertEquals(todays, count("select target_action_next_id from agent_prep_target"));
        long prep = count("select prep_action_next_id from agent_prep_target");
        assertEquals("claude-code", string("select performed_by_agent from action_next where action_next_id = " + prep));
        assertEquals(PERSONAL_PROJECT_ID, count("select project_id from action_next where action_next_id = " + prep));
        assertTrue(eligible().contains(Integer.valueOf((int) prep)));

        exec("update action_next set next_action_date = '" + today().plusDays(2) + "' where action_next_id = " + todays);
        eligible();
        assertEquals("prep moves with its occurrence", today().plusDays(2).toString(),
                string("select next_action_date from action_next where action_next_id = " + prep));
        exec("update action_next set next_action_status = 'X' where action_next_id = " + todays);
        eligible();
        assertEquals("cancelled occurrence has no prep", "X",
                string("select next_action_status from action_next where action_next_id = " + prep));
        assertEquals(1, count("select count(*) from agent_prep_target"));
        assertTrue(tomorrows > 0);
    }

    // ------------------------------------------------------------------ bail, park, launches (AC-19..21, AC-28)

    private int launch(final int actionId, final String verb) {
        final String[] uri = new String[1];
        request(new Work<Object>() {
            public Object run(Session session, WebUser webUser) {
                uri[0] = launchService.issue(session, webUser, WORKSPACE_ID, actionId, verb, null, null, "local",
                        new Date()).uri;
                return null;
            }
        });
        final String nonce = uri[0].substring(uri[0].indexOf("&n=") + 3);
        assertTrue(uri[0], uri[0].matches("dandelion:" + verb + "/" + actionId + "\\?e=local&n=[A-Za-z0-9_-]{43}"));
        Map<String, Object> redeemed = request(new Work<Map<String, Object>>() {
            public Map<String, Object> run(Session session, WebUser webUser) {
                return launchService.redeem(session, webUser, WORKSPACE_ID, CLIENT_ID, nonce, verb, actionId, null,
                        "local", "test", new Date());
            }
        });
        try {
            request(new Work<Object>() {
                public Object run(Session session, WebUser webUser) {
                    return launchService.redeem(session, webUser, WORKSPACE_ID, CLIENT_ID, nonce, verb, actionId, null,
                            "local", "test", new Date());
                }
            });
            fail("nonce worked twice");
        } catch (SessionException expected) {
            assertEquals("launch_used", expected.getCode());
        }
        final int launchId = ((Integer) redeemed.get("launchId")).intValue();
        final String sessionId = (String) redeemed.get("sessionId");
        request(new Work<Object>() {
            public Object run(Session session, WebUser webUser) {
                return launchService.bind(session, webUser, launchId, sessionId, new Date());
            }
        });
        return launchId;
    }

    @Test
    public void startBThenClosingAKeepsBsSession() throws Exception {
        final int actionId = action(PROJECT_ID, "write the plan", today(), null, null);
        final int a = launch(actionId, "start");
        final int b = launch(actionId, "start");
        request(new Work<Object>() {
            public Object run(Session session, WebUser webUser) {
                return launchService.end(session, webUser, a, new Date());
            }
        });
        assertEquals(b, count("select launch_id from action_session where action_next_id = " + actionId));
        String bSession = string("select bound_session_id from action_launch where launch_id = " + b);
        assertEquals(bSession, string("select session_id from action_session where action_next_id = " + actionId));
        // A late SessionStart from A can't take it back either.
        final String aSession = string("select bound_session_id from action_launch where launch_id = " + a);
        Map<String, Object> rebound = request(new Work<Map<String, Object>>() {
            public Map<String, Object> run(Session session, WebUser webUser) {
                return launchService.bind(session, webUser, a, aSession, new Date());
            }
        });
        assertEquals(Boolean.FALSE, rebound.get("applied"));
        assertEquals(bSession, string("select session_id from action_session where action_next_id = " + actionId));
    }

    @Test
    public void bailWritesNotesOnlyAndParkIsOneRetrySafeHistoryEntry() throws Exception {
        final int actionId = action(PROJECT_ID, "write the plan", today(), null, null);
        final int launchId = launch(actionId, "start");
        String dateBefore = string("select next_action_date from action_next where action_next_id = " + actionId);
        request(new Work<Object>() {
            public Object run(Session session, WebUser webUser) {
                return commandService.bail(session, webUser, launchId, "Drafted section 2.\nNext: tables.", new Date());
            }
        });
        String notes = string("select next_notes from action_next where action_next_id = " + actionId);
        assertTrue(notes, notes.startsWith("Original notes for write the plan\n"));
        assertTrue(notes.endsWith("Drafted section 2.\nNext: tables."));
        assertEquals(0, count("select count(*) from action_taken"));
        assertEquals(dateBefore, string("select next_action_date from action_next where action_next_id = " + actionId));
        assertEquals("R", string("select next_action_status from action_next where action_next_id = " + actionId));
        assertEquals("BAILED", string("select session_state from action_session where action_next_id = " + actionId));

        final String key = "park-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            request(new Work<Object>() {
                public Object run(Session session, WebUser webUser) {
                    return commandService.park(session, webUser, launchId, "Waiting on Kim's numbers.", null, key, new Date());
                }
            });
        }
        assertEquals("exactly one History entry", 1, count("select count(*) from action_taken"));
        assertEquals(today().plusDays(1).toString(),
                string("select next_action_date from action_next where action_next_id = " + actionId));
        assertEquals("R", string("select next_action_status from action_next where action_next_id = " + actionId));
        assertEquals(0, count("select count(*) from bill_entry"));
        notes = string("select next_notes from action_next where action_next_id = " + actionId);
        assertEquals("handoff appended once", notes.indexOf("Waiting on Kim"), notes.lastIndexOf("Waiting on Kim"));
        // History of the still-open action shows the Park.
        Integer shown = request(new Work<Integer>() {
            public Integer run(Session session, WebUser webUser) {
                ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(actionId));
                return Integer.valueOf(new org.dandeliondaily.shared.service.ActionThreadService()
                        .historyWithCurrent(session, action, 5).getSteps().size());
            }
        });
        assertEquals(1, shown.intValue());
    }

    @Test
    public void foreignUserCannotRedeemOrUseALaunch() throws Exception {
        final int actionId = action(PROJECT_ID, "write the plan", today(), null, null);
        final String[] uri = new String[1];
        request(new Work<Object>() {
            public Object run(Session session, WebUser webUser) {
                uri[0] = launchService.issue(session, webUser, WORKSPACE_ID, actionId, "terminal", null, null, "local",
                        new Date()).uri;
                return null;
            }
        });
        final String nonce = uri[0].substring(uri[0].indexOf("&n=") + 3);
        try {
            request(OTHER_USER_ID, new Work<Object>() {
                public Object run(Session session, WebUser webUser) {
                    return launchService.redeem(session, webUser, WORKSPACE_ID, CLIENT_ID, nonce, "terminal", actionId,
                            null, "local", "test", new Date());
                }
            });
            fail("another user redeemed the launch");
        } catch (SessionException expected) {
            assertEquals(404, expected.getStatus());
        }
        try {
            request(new Work<Object>() {
                public Object run(Session session, WebUser webUser) {
                    return launchService.redeem(session, webUser, WORKSPACE_ID, CLIENT_ID, nonce, "terminal", actionId,
                            null, "local", "test", new Date(System.currentTimeMillis() + 120000L));
                }
            });
            fail("expired nonce accepted");
        } catch (SessionException expected) {
            assertEquals("launch_expired", expected.getCode());
        }
    }

    // ------------------------------------------------------------------ close-out (AC-33..40)

    private CloseOutService.Request closeOut(int closedId) throws SQLException {
        CloseOutService.Request request = new CloseOutService.Request();
        request.actionNextId = closedId;
        request.completionDescription = "Drafted the plan";
        request.outcomeNote = "Sections 1-3 done";
        CloseOutService.SuccessorSpec next = new CloseOutService.SuccessorSpec();
        next.description = "Review the plan with Kim";
        next.scheduledDate = today().plusDays(1);
        next.notes = "Brief: start at section 2";
        request.successors.add(next);
        return request;
    }

    private CloseOutService.ChangeSpec change(int id, String op) throws SQLException {
        CloseOutService.ChangeSpec change = new CloseOutService.ChangeSpec();
        change.actionNextId = id;
        change.op = op;
        change.asOf = asOf(id);
        return change;
    }

    private String asOf(int id) throws SQLException {
        Connection c = DriverManager.getConnection(url, user, password);
        try {
            ResultSet rs = c.createStatement().executeQuery("select next_change_date from action_next where action_next_id = " + id);
            rs.next();
            return rs.getTimestamp(1).toInstant().toString();
        } finally {
            c.close();
        }
    }

    private CloseOutService.Plan preview(final CloseOutService.Request request) {
        return request(new Work<CloseOutService.Plan>() {
            public CloseOutService.Plan run(Session session, WebUser webUser) {
                return closeOutService.preview(session, webUser, WORKSPACE_ID, request);
            }
        });
    }

    private Map<String, Object> apply(final CloseOutService.Request request, final CloseOutService.FaultInjector faults) {
        return request(new Work<Map<String, Object>>() {
            public Map<String, Object> run(Session session, WebUser webUser) {
                CloseOutService service = new CloseOutService();
                if (faults != null) {
                    service.setFaultInjector(faults);
                }
                return service.apply(session, webUser, WORKSPACE_ID, request, new Date());
            }
        });
    }

    private static void confirm(CloseOutService.Request request, CloseOutService.Plan plan) {
        request.confirmRows = new ArrayList<CloseOutService.RowRef>();
        for (CloseOutService.Row row : plan.rows) {
            if (row.actionNextId > 0) {
                request.confirmRows.add(new CloseOutService.RowRef(row.actionNextId, row.asOf));
            }
        }
    }

    @Test
    public void injectedFailureOnTheThirdOfFiveChangesAppliesNothing() throws Exception {
        int closed = action(PROJECT_ID, "draft plan", today(), null, null);
        List<CloseOutService.ChangeSpec> changes = new ArrayList<CloseOutService.ChangeSpec>();
        for (int i = 0; i < 5; i++) {
            int other = action(PROJECT_ID, "other " + i, i == 4 ? null : today().plusDays(i), null, null);
            CloseOutService.ChangeSpec change = change(other, i % 2 == 0 ? "reschedule" : "cancel");
            change.scheduledDate = today().plusDays(7);
            changes.add(change);
        }
        CloseOutService.Request request = closeOut(closed);
        request.changes.addAll(changes);
        CloseOutService.Plan plan = preview(request);
        assertTrue(plan.errors.toString(), plan.isOk());
        assertEquals("closed + 5 changes + 1 new", 7, plan.rows.size());
        confirm(request, plan);
        String before = snapshot();
        try {
            apply(request, new CloseOutService.FaultInjector() {
                public void at(String step) {
                    if ("change-3".equals(step)) {
                        throw new IllegalStateException("injected");
                    }
                }
            });
            fail("fault not raised");
        } catch (IllegalStateException expected) {
            assertEquals("injected", expected.getMessage());
        }
        assertEquals("nothing applied", before, snapshot());
        Map<String, Object> result = apply(request, null);
        int successor = ((List<?>) result.get("successorActionNextIds")).get(0) instanceof Integer
                ? ((Integer) ((List<?>) result.get("successorActionNextIds")).get(0)).intValue() : 0;
        assertEquals(String.valueOf(closed), string("select follows_action_next_id from action_next where action_next_id = " + successor));
        assertEquals("C", string("select next_action_status from action_next where action_next_id = " + closed));
        assertEquals("Drafted the plan", string("select action_description from action_taken where action_set_id = "
                + "(select action_set_id from action_next where action_next_id = " + closed + ") and action_note = 'Sections 1-3 done'"));
        assertEquals(0, count("select count(*) from bill_entry"));
    }

    @Test
    public void editAfterPreviewRefusesNamingTheRowAndNoSuccessorNeedsAChoice() throws Exception {
        int closed = action(PROJECT_ID, "draft plan", today(), null, null);
        int other = action(PROJECT_ID, "other", today(), null, null);
        CloseOutService.Request request = closeOut(closed);
        CloseOutService.ChangeSpec change = change(other, "rewrite");
        change.description = "other, rewritten";
        request.changes.add(change);
        CloseOutService.Plan plan = preview(request);
        confirm(request, plan);
        exec("update action_next set next_change_date = '2026-02-02 09:00:00' where action_next_id = " + other);
        String before = snapshot();
        try {
            apply(request, null);
            fail("stale accepted");
        } catch (SessionException refused) {
            assertEquals(409, refused.getStatus());
            assertTrue(refused.getMessage(), refused.getMessage().contains("#" + other));
        }
        assertEquals(before, snapshot());

        CloseOutService.Request none = closeOut(closed);
        none.successors.clear();
        assertFalse(preview(none).isOk());
        none.noSuccessor = true;
        CloseOutService.Plan nonePlan = preview(none);
        assertTrue(nonePlan.errors.toString(), nonePlan.isOk());
        confirm(none, nonePlan);
        apply(none, null);
        assertEquals("C", string("select next_action_status from action_next where action_next_id = " + closed));
        assertEquals(0, count("select count(*) from action_next where follows_action_next_id = " + closed));
    }

    @Test
    public void linkedTwinsArePreviewedAndOnlyPreviewedRowsChange() throws Exception {
        exec("insert into action_set (action_set_id, action_set_type, created_by_web_user_id, created_date) values (900500, 'H', "
                + USER_ID + ", now())");
        int closed = action(PROJECT_ID, "draft plan", today(), null, null);
        int twinHere = action(PROJECT_ID, "shared task", today(), null, null, null, Integer.valueOf(900500));
        int twinThere = action(OTHER_PROJECT_ID, "shared task", today(), null, null, null, Integer.valueOf(900500));
        int bystander = action(OTHER_PROJECT_ID, "unrelated", today(), null, null);
        int blocked = action(OTHER_PROJECT_ID, "waits on draft", null, null, null);
        exec("update action_next set blocked_by_id = " + closed + " where action_next_id = " + blocked);
        CloseOutService.Request request = closeOut(closed);
        CloseOutService.ChangeSpec change = change(twinHere, "reschedule");
        change.scheduledDate = today().plusDays(3);
        request.changes.add(change);
        CloseOutService.Plan plan = preview(request);
        assertTrue(plan.errors.toString(), plan.isOk());
        List<Integer> listed = new ArrayList<Integer>();
        for (CloseOutService.Row row : plan.rows) {
            listed.add(Integer.valueOf(row.actionNextId));
        }
        assertTrue("twin previewed", listed.contains(Integer.valueOf(twinThere)));
        assertTrue("unblocked previewed", listed.contains(Integer.valueOf(blocked)));
        assertFalse(listed.contains(Integer.valueOf(bystander)));
        String bystanderBefore = string("select concat_ws('|', next_description, next_action_date, next_change_date) "
                + "from action_next where action_next_id = " + bystander);
        confirm(request, plan);
        apply(request, null);
        assertEquals(today().plusDays(3).toString(), string("select next_action_date from action_next where action_next_id = " + twinThere));
        assertEquals(bystanderBefore, string("select concat_ws('|', next_description, next_action_date, next_change_date) "
                + "from action_next where action_next_id = " + bystander));
        assertNull(string("select blocked_by_id from action_next where action_next_id = " + blocked));
    }

    @Test
    public void mergeCancelsTheAbsorbedActionAndTheSurvivorsHistoryReachesIt() throws Exception {
        int closed = action(PROJECT_ID, "draft plan", today(), null, null);
        int olderStep = action(PROJECT_ID, "earlier step of B", today().minusDays(5), null, null);
        exec("update action_next set next_action_status = 'C' where action_next_id = " + olderStep);
        final int absorbed = action(PROJECT_ID, "B", today(), null, null);
        exec("update action_next set follows_action_next_id = " + olderStep + " where action_next_id = " + absorbed);
        final int survivor = action(PROJECT_ID, "A", today(), null, null);
        CloseOutService.Request request = closeOut(closed);
        CloseOutService.ChangeSpec change = change(absorbed, "merge");
        change.survivorActionNextId = Integer.valueOf(survivor);
        change.survivorAsOf = asOf(survivor);
        change.combinedDescription = "A and B together";
        request.changes.add(change);
        CloseOutService.Plan plan = preview(request);
        assertTrue(plan.errors.toString(), plan.isOk());
        confirm(request, plan);
        apply(request, null);
        assertEquals("X", string("select next_action_status from action_next where action_next_id = " + absorbed));
        assertEquals("A and B together", string("select next_description from action_next where action_next_id = " + survivor));
        assertEquals(survivor, count("select survivor_action_next_id from action_taken where survivor_action_next_id is not null"));
        List<Integer> steps = request(new Work<List<Integer>>() {
            public List<Integer> run(Session session, WebUser webUser) {
                ActionNext action = (ActionNext) session.get(ActionNext.class, Integer.valueOf(survivor));
                return SessionCommandService.stepIds(new org.dandeliondaily.shared.service.ActionThreadService()
                        .history(session, action, 10));
            }
        });
        assertTrue(steps.toString(), steps.contains(Integer.valueOf(absorbed)));
        assertTrue(steps.toString(), steps.contains(Integer.valueOf(olderStep)));
    }

    @Test
    public void startBriefOmitsInaccessiblePredecessors() throws Exception {
        int privateStep = action(PROJECT_ID, "someone else's step", today().minusDays(2), null, null);
        exec("update action_next set contact_id = " + OTHER_CONTACT_ID + ", next_action_status = 'C' where action_next_id = " + privateStep);
        int ownStep = action(PROJECT_ID, "my earlier step", today().minusDays(1), null, null);
        exec("update action_next set next_action_status = 'C', follows_action_next_id = " + privateStep
                + " where action_next_id = " + ownStep);
        final int current = action(PROJECT_ID, "current", today(), null, null);
        exec("update action_next set follows_action_next_id = " + ownStep + " where action_next_id = " + current);
        final int launchId = launch(current, "start");
        Map<String, Object> brief = request(new Work<Map<String, Object>>() {
            public Map<String, Object> run(Session session, WebUser webUser) {
                return commandService.brief(session, webUser, launchId);
            }
        });
        String thread = String.valueOf(brief.get("thread"));
        assertTrue(thread, thread.contains("my earlier step"));
        assertFalse(thread, thread.contains("someone else's step"));
        assertNotNull(((Map<?, ?>) brief.get("action")).get("notes"));
    }
}
