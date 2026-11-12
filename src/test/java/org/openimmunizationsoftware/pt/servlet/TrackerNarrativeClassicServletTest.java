package org.openimmunizationsoftware.pt.servlet;

import static org.junit.Assert.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.Query;
import org.hibernate.Session;
import org.junit.Test;
import org.openimmunizationsoftware.pt.doa.TrackerNarrativeDao;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

public class TrackerNarrativeClassicServletTest {
    @Test
    public void navigationOpensAdjacentReportsInPreviewForEveryType() throws Exception {
        for (String type : new String[] { "DAILY", "WEEKLY", "MONTHLY" }) {
            TrackerNarrative current = report(94, type, "2026-01-01");
            TrackerNarrative previous = report(82, type, "2025-12-01");
            TrackerNarrative next = report(108, type, "2026-02-01");
            TrackerNarrativeDao dao = new TrackerNarrativeDao((Session) null) {
                @Override
                public TrackerNarrative findAdjacentByContactAndType(int contactId, String narrativeType,
                        LocalDate periodStart, boolean forward) {
                    assertEquals(20, contactId);
                    assertEquals(type, narrativeType);
                    assertEquals(LocalDate.of(2026, 1, 1), periodStart);
                    return forward ? next : previous;
                }
            };
            for (boolean editing : new boolean[] { false, true }) {
                // The URL's selected date need not match the displayed report's period.
                String html = render(current, dao, editing);
                String navigation = html.substring(html.indexOf("<p>"), html.indexOf("</p>"));
                assertTrue(navigation.contains("date=2026-01-15\">Back to list</a>"));
                assertTrue(navigation.contains("id=82&type=" + type + "&date=2025-12-01\">Previous</a>"));
                assertTrue(navigation.contains("id=108&type=" + type + "&date=2026-02-01\">Next</a>"));
                assertFalse(navigation.contains("view=edit"));
            }
        }
    }

    @Test
    public void navigationHidesUnavailableDirections() throws Exception {
        TrackerNarrative current = report(94, "DAILY", "2026-10-20");
        for (int available = 0; available < 3; available++) {
            final int direction = available;
            TrackerNarrativeDao dao = new TrackerNarrativeDao((Session) null) {
                @Override
                public TrackerNarrative findAdjacentByContactAndType(int contactId, String type,
                        LocalDate periodStart, boolean next) {
                    return direction == (next ? 2 : 1) ? report(95, type, "2026-10-21") : null;
                }
            };
            String html = render(current, dao, false);
            assertTrue(html.contains(">Back to list</a>"));
            assertEquals(available == 1, html.contains(">Previous</a>"));
            assertEquals(available == 2, html.contains(">Next</a>"));
        }
    }

    @Test
    public void adjacentQueryScopesOwnerTypeDateAndStatusAndSelectsOneReport() {
        for (boolean next : new boolean[] { false, true }) {
            for (boolean found : new boolean[] { false, true }) {
                Map<String, Object> parameters = new LinkedHashMap<String, Object>();
                TrackerNarrative adjacent = report(95, "DAILY", "2026-10-23");
                Query query = (Query) Proxy.newProxyInstance(Query.class.getClassLoader(),
                        new Class<?>[] { Query.class }, (proxy, method, args) -> {
                            if ("setMaxResults".equals(method.getName())) {
                                assertEquals(1, args[0]);
                                return proxy;
                            }
                            if (method.getName().startsWith("set")) {
                                parameters.put((String) args[0], args[1]);
                                return proxy;
                            }
                            if ("list".equals(method.getName())) {
                                return found ? Collections.singletonList(adjacent) : Collections.emptyList();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
                Session session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
                        new Class<?>[] { Session.class }, (proxy, method, args) -> {
                            assertEquals("createQuery", method.getName());
                            String hql = (String) args[0];
                            assertTrue(hql.contains("contactId = :contactId and narrativeType = :type"));
                            assertTrue(hql.contains("periodStart " + (next ? ">" : "<") + " :periodStart"));
                            assertTrue(hql.contains("reviewStatusString <> :deleted"));
                            assertTrue(hql.contains("order by periodStart " + (next ? "asc" : "desc")
                                    + ", case when reviewStatusString = :approved then 0 else 1 end, narrativeId desc"));
                            return query;
                        });
                assertSame(found ? adjacent : null, new TrackerNarrativeDao(session)
                        .findAdjacentByContactAndType(20, "DAILY", LocalDate.of(2026, 10, 20), next));
                assertEquals(20, parameters.get("contactId"));
                assertEquals("DAILY", parameters.get("type"));
                assertEquals(Date.valueOf("2026-10-20"), parameters.get("periodStart"));
                assertEquals(TrackerNarrativeReviewStatus.DELETED.getId(), parameters.get("deleted"));
                assertEquals(TrackerNarrativeReviewStatus.APPROVED.getId(), parameters.get("approved"));
            }
        }
    }

    private String render(TrackerNarrative current, TrackerNarrativeDao dao, boolean editing) throws Exception {
        WebUser user = new WebUser();
        user.setContactId(20);
        Method editor = TrackerNarrativeClassicServlet.class.getDeclaredMethod("printEditor",
                PrintWriter.class, WebUser.class, TrackerNarrative.class, String.class, LocalDate.class,
                boolean.class, TrackerNarrativeDao.class);
        editor.setAccessible(true);
        StringWriter text = new StringWriter();
        editor.invoke(new TrackerNarrativeClassicServlet(), new PrintWriter(text), user, current, current.getNarrativeType(),
                LocalDate.of(2026, 1, 15), editing, dao);
        return text.toString();
    }

    private static TrackerNarrative report(int id, String type, String start) {
        TrackerNarrative report = new TrackerNarrative();
        report.setNarrativeId(id);
        report.setNarrativeType(type);
        report.setPeriodStart(Date.valueOf(start));
        report.setReviewStatus(TrackerNarrativeReviewStatus.GENERATED);
        return report;
    }
}
