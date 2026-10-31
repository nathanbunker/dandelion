package org.dandeliondaily.mcp.service;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import org.hibernate.Query;
import org.hibernate.Session;
import org.junit.Before;
import org.junit.Test;
import org.openimmunizationsoftware.pt.api.v1.mcp.McpToolException;
import org.openimmunizationsoftware.pt.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class McpDiscoveryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpDiscoveryService service = new McpDiscoveryService();
    private final Map<String, Object> parameters = new HashMap<String, Object>();
    private final List<String> queries = new ArrayList<String>();
    private List<Object[]> rows;
    private Session session;
    private WebUser user;
    private int offset;
    private int max;

    @Before
    public void setup() {
        rows = new ArrayList<Object[]>();
        user = new WebUser();
        user.setWebUserId(1);
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, args) -> {
                    if ("createQuery".equals(method.getName())) {
                        String hql = (String) args[0];
                        queries.add(hql);
                        return Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[] {Query.class},
                                (q, m, a) -> {
                                    if (m.getName().startsWith("set")) {
                                        if ("setFirstResult".equals(m.getName())) { offset = (Integer) a[0]; }
                                        else if ("setMaxResults".equals(m.getName())) { max = (Integer) a[0]; }
                                        else { parameters.put((String) a[0], a[1]); }
                                        return q;
                                    }
                                    if ("list".equals(m.getName())) {
                                        return hql.startsWith("select p,") || hql.startsWith("select a,")
                                                ? rows : Collections.emptyList();
                                    }
                                    if ("uniqueResult".equals(m.getName())) { return null; }
                                    throw new UnsupportedOperationException(m.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void projectDiscoveryIncludesEmptyProjectsAndUsesLiteralNamePagination() {
        Project project = new Project();
        project.setProjectId(7);
        project.setProjectName("100%_!");
        project.setWorkspaceId(4);
        Workspace workspace = new Workspace();
        workspace.setWorkspaceId(4);
        workspace.setWorkspaceType(Workspace.TYPE_PRIVATE);
        rows.add(new Object[] {project, workspace});
        rows.add(new Object[] {project, workspace});
        ObjectNode args = mapper.createObjectNode().put("nameContains", "100%_!").put("limit", 1).put("offset", 2);
        Map<String, Object> result = service.listProjects(session, 4, user, args);
        assertEquals("%100!%!_!!%", parameters.get("name"));
        assertEquals(Arrays.asList(4), parameters.get("workspaceIds"));
        assertEquals(2, offset);
        assertEquals(2, max);
        assertEquals(true, result.get("hasMore"));
        assertEquals(3, result.get("nextOffset"));
        assertEquals(1, ((List<?>) result.get("projects")).size());
        assertTrue(queries.stream().anyMatch(h -> h.contains("p.projectStatus not in ('Closed', 'Complete')")));
    }

    @Test
    public void searchUsesActualActionTypesAndExplicitHistoryFields() {
        ActionNext action = new ActionNext();
        action.setActionNextId(5);
        action.setNextActionStatus(ProjectNextActionStatus.COMPLETED);
        action.setNextDescription("Review");
        Date work = Date.from(Instant.parse("2026-10-26T15:00:00Z"));
        rows.add(new Object[] {action, work});
        ObjectNode args = search();
        args.putArray("actionTypes").add("REVIEW").add("DOCUMENT");
        args.put("includeNotes", true).put("hasLinkedMeeting", false);
        Map<String, Object> result = service.searchActions(session, 4, user, args);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) result.get("actions")).get(0);
        assertEquals("2026-10-26T15:00:00Z", item.get("completionDate"));
        assertEquals(true, item.get("completionDateKnown"));
        assertFalse(item.containsKey("sharedWithProjects"));
        assertEquals(Arrays.asList("REVIEW", "DOCUMENT"), parameters.get("actionTypes"));
        assertTrue(queries.get(0).contains("select count(s.actionNextId)"));
        assertTrue(queries.get(0).contains("lower(a.nextNotes) like :text"));
        assertFalse(queries.get(0).contains("ActionNextNote"));
        assertTrue(queries.get(0).contains("not exists"));
        assertEquals("%a!%!_%", parameters.get("text"));
    }

    @Test
    public void projectFiltersDoNotReplaceWorkspaceAuthorization() {
        service.listProjects(session, 4, user, mapper.createObjectNode()
                .put("workspaceId", 9).put("externalSourceKey", "InteropHub-ES"));
        assertEquals(Arrays.asList(4), parameters.get("workspaceIds"));
        assertEquals(9, parameters.get("selectedWorkspaceId"));
        assertEquals("InteropHub-ES", parameters.get("externalSourceKey"));
        assertTrue(queries.stream().anyMatch(h -> h.contains("p.workspaceId = :selectedWorkspaceId")
                && h.contains("p.externalSourceKey = :externalSourceKey")));
        try {
            service.listProjects(session, 4, user, mapper.createObjectNode().put("workspaceId", "9"));
            fail("Expected invalid workspace filter");
        } catch (McpToolException expected) {
            assertEquals("invalid_arguments", expected.getCode());
        }
    }

    @Test
    public void closedBucketsAreNullAndCalendarDatesDoNotShiftWithUserTimezone() {
        user.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        ActionNext action = new ActionNext();
        action.setNextActionType(ProjectNextActionType.WILL);
        action.setNextActionDate(java.sql.Date.valueOf("2026-10-28"));
        action.setNextDeadlineDate(java.sql.Date.valueOf("2026-10-29"));
        action.setNextTargetDate(java.sql.Date.valueOf("2026-10-30"));
        for (ProjectNextActionStatus status : ProjectNextActionStatus.values()) {
            action.setNextActionStatus(status);
            Map<String, Object> map = McpActionContextSupport.toActionMap(session, action, false, user, false);
            assertEquals("2026-10-28", map.get("scheduledDate"));
            assertEquals("2026-10-29", map.get("deadlineDate"));
            assertEquals("2026-10-30", map.get("targetDate"));
            if (status == ProjectNextActionStatus.COMPLETED || status == ProjectNextActionStatus.CANCELLED) {
                assertTrue(map.containsKey("dashboardBucket"));
                assertNull(map.get("dashboardBucket"));
            } else {
                assertNotNull(map.get("dashboardBucket"));
            }
        }
        action.setNextActionDate(null);
        assertNull(McpActionContextSupport.toActionMap(session, action, false).get("scheduledDate"));
    }

    @Test
    public void ambiguousHistoryNeverFabricatesCompletionFromModification() {
        ActionNext action = new ActionNext();
        action.setActionNextId(5);
        action.setNextActionStatus(ProjectNextActionStatus.COMPLETED);
        action.setNextChangeDate(new Date());
        rows.add(new Object[] {action, null});
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) service.searchActions(session, 4, user, search())
                .get("actions")).get(0);
        assertNull(item.get("completionDate"));
        assertEquals(false, item.get("completionDateKnown"));
        assertEquals("NO_UNAMBIGUOUS_WORK_RECORD", item.get("completionDateUnknownReason"));
    }

    @Test
    public void invalidFiltersAreRejectedBeforeQueries() {
        rejects(search().put("endDate", "2027-10-27"));
        rejects(search().put("includeNotes", "yes"));
        rejects(search().put("limit", 0));
        ObjectNode args = search();
        args.putArray("statuses").add("READY");
        rejects(args);
        args = search();
        args.putArray("actionTypes").add("WILL_REVIEW");
        rejects(args);
        rejects(search().put("extra", 1));
        assertTrue(queries.isEmpty());
    }

    @Test
    public void sharedActionSerializationDoesNotLoadPrivateMeetingOrSiblingData() {
        ActionNext action = new ActionNext();
        action.setWorkspaceId(9);
        ActionSet set = new ActionSet();
        set.setActionSetType(ActionSetType.SHARED);
        action.setActionSet(set);
        Map<String, Object> map = McpActionContextSupport.toActionMap(session, action, false, null, false);
        assertFalse(map.containsKey("linkedMeeting"));
        assertFalse(map.containsKey("sharedWithProjects"));
        assertTrue(queries.isEmpty());
    }

    private ObjectNode search() {
        return mapper.createObjectNode().put("text", "A%_").put("startDate", "2026-10-01").put("endDate", "2026-10-31");
    }

    private void rejects(ObjectNode args) {
        try {
            service.searchActions(session, 4, user, args);
            fail("Expected invalid_arguments");
        } catch (McpToolException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
