package org.dandeliondaily.planahead.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Query;
import org.hibernate.Session;
import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.BillExpected;
import org.openimmunizationsoftware.pt.model.BillExpectedId;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TemplateType;

public class TemplateGenerationServiceTest {

    @Test
    public void performedByAgentReachesNewAndPropagatedInstancesOnly() {
        TemplateGenerationService service = new TemplateGenerationService();
        ActionNext template = new ActionNext();
        template.setActionNextId(778160);
        template.setProjectId(48627);
        template.setNextDescription("Prepare my workday");
        template.setPerformedByAgent("claude-code");
        ActionNext instance = service.createInstance(template, LocalDate.of(2026, 11, 23), false);
        Assert.assertEquals("claude-code", instance.getPerformedByAgent());
        Assert.assertEquals(Integer.valueOf(778160), instance.getTemplateActionNextId());

        // A template edit propagates the change (the existing policy for template fields)...
        template.setPerformedByAgent(null);
        service.copyTemplateFieldsToInstance(template, instance, false);
        Assert.assertNull(instance.getPerformedByAgent());

        // ...and a template without agent fields generates exactly what it did before.
        ActionNext human = new ActionNext();
        human.setActionNextId(1);
        human.setNextDescription("plan work for today");
        Assert.assertNull(service.createInstance(human, LocalDate.of(2026, 11, 23), false).getPerformedByAgent());
    }

    @Test
    public void billedReadyInstanceTodayIsPreserved() {
        Assert.assertTrue(TemplateGenerationService.shouldPreserveBilledToday(true, true));
    }

    @Test
    public void unbilledInstanceTodayCanBeReconciled() {
        Assert.assertFalse(TemplateGenerationService.shouldPreserveBilledToday(true, false));
    }

    @Test
    public void futureInstanceCanBeReconciled() {
        Assert.assertFalse(TemplateGenerationService.shouldPreserveBilledToday(false, true));
    }

    @Test
    public void cleanupPastActualTimesBuildsExpectedBulkUpdate() {
        TemplateGenerationService service = new TemplateGenerationService();
        RecordingSessionHandler sessionHandler = new RecordingSessionHandler();
        Session session = sessionHandler.createSessionProxy();

        LocalDate today = LocalDate.of(2026, 5, 26);
        service.cleanupPastActualTimes(session, 42, 99, today);

        Assert.assertEquals(
                "update ActionNext an set an.nextTimeActual = 0 "
                        + "where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.nextActionDate is not null and an.nextActionDate < :today "
                        + "and an.nextTimeActual > 0 "
                        + "and an.nextActionStatusString <> :cancelled "
                        + "and an.nextActionStatusString <> :completed",
                sessionHandler.queryString);
        Assert.assertEquals(Integer.valueOf(42), sessionHandler.parameters.get("workspaceId"));
        Assert.assertEquals(Integer.valueOf(99), sessionHandler.parameters.get("contactId"));
        Assert.assertEquals(java.sql.Date.valueOf(today), sessionHandler.parameters.get("today"));
        Assert.assertEquals("X", sessionHandler.parameters.get("cancelled"));
        Assert.assertEquals("C", sessionHandler.parameters.get("completed"));
        Assert.assertEquals(1, sessionHandler.executeUpdateCount);
    }

    @Test
    public void autoCancelDoesNotGenerateOnNonWorkingDay() {
        TemplateGenerationService service = new TemplateGenerationService();
        LocalDate fridayOff = LocalDate.of(2026, 8, 7);
        TemplateWorkdayCalendar calendar = calendar(
                availability(fridayOff, 0, "N"));

        Set<LocalDate> dates = service.resolveScheduledDates(
                Collections.singletonList(fridayOff), calendar, true,
                "AUTO_CANCEL", fridayOff, fridayOff.plusDays(10));

        Assert.assertTrue(dates.isEmpty());
    }

    @Test
    public void ignoreGeneratesOnScheduledNonWorkingDay() {
        TemplateGenerationService service = new TemplateGenerationService();
        LocalDate fridayOff = LocalDate.of(2026, 8, 7);
        TemplateWorkdayCalendar calendar = calendar(
                availability(fridayOff, 0, "N"));

        Set<LocalDate> dates = service.resolveScheduledDates(
                Collections.singletonList(fridayOff), calendar, true,
                "IGNORE", fridayOff, fridayOff.plusDays(10));

        Assert.assertEquals(Collections.singleton(fridayOff), dates);
    }

    @Test
    public void carryForwardUsesNextWorkingDayBeyondNominalWindow() {
        TemplateGenerationService service = new TemplateGenerationService();
        LocalDate fridayOff = LocalDate.of(2026, 8, 7);
        LocalDate mondayWorking = LocalDate.of(2026, 8, 10);
        TemplateWorkdayCalendar calendar = calendar(
                availability(fridayOff, 0, "N"),
                availability(LocalDate.of(2026, 8, 8), 0, "N"),
                availability(LocalDate.of(2026, 8, 9), 0, "N"),
                availability(mondayWorking, 480, "W"));

        Set<LocalDate> dates = service.resolveScheduledDates(
                Collections.singletonList(fridayOff), calendar, true,
                "CARRY_FORWARD", fridayOff, mondayWorking.plusDays(10));

        Assert.assertEquals(Collections.singleton(mondayWorking), dates);
    }

    @Test
    public void carryForwardRetainsEffectiveDateOnLaterSchedulerRun() {
        TemplateGenerationService service = new TemplateGenerationService();
        LocalDate fridayOff = LocalDate.of(2026, 8, 7);
        LocalDate sundayRun = LocalDate.of(2026, 8, 9);
        LocalDate mondayWorking = LocalDate.of(2026, 8, 10);
        TemplateWorkdayCalendar calendar = calendar(
                availability(fridayOff, 0, "N"),
                availability(LocalDate.of(2026, 8, 8), 0, "N"),
                availability(sundayRun, 0, "N"),
                availability(mondayWorking, 480, "W"));

        Set<LocalDate> dates = service.resolveScheduledDates(
                Collections.singletonList(fridayOff), calendar, true,
                "CARRY_FORWARD", sundayRun, mondayWorking.plusDays(10));

        Assert.assertEquals(Collections.singleton(mondayWorking), dates);
    }

    @Test
    public void missedFridaySurvivesRepeatedWeekendAndWeekdayRuns() {
        assertMissedFridaySurvives(true);
    }

    @Test
    public void personalMissedFridaySurvivesRepeatedRuns() {
        assertMissedFridaySurvives(false);
    }

    private void assertMissedFridaySurvives(boolean billable) {
        TemplateGenerationService service = new TemplateGenerationService();
        TemplateSessionHandler data = new TemplateSessionHandler(billable, "CARRY_FORWARD");
        Session session = data.createSessionProxy();
        LocalDate friday = LocalDate.of(2026, 10, 23);
        Assert.assertEquals(3, service.generateForwardWindow(session, 42, 99, friday, 14));
        ActionNext missed = data.instances.get(0);
        for (ActionNext instance : data.instances) {
            if (java.sql.Date.valueOf(friday).equals(instance.getNextActionDate())) {
                missed = instance;
            }
        }
        Assert.assertEquals(java.sql.Date.valueOf(friday), missed.getNextActionDate());

        for (int day = 1; day <= 5; day++) {
            LocalDate today = friday.plusDays(day);
            for (int run = 0; run < 2; run++) {
                service.generateForwardWindow(session, 42, 99, today, 14);
                service.applyMissedActionBehavior(session, 42, 99, today);
                LocalDate expected = billable && day <= 3 ? friday.plusDays(3) : today;
                Assert.assertEquals(java.sql.Date.valueOf(expected), missed.getNextActionDate());
                Assert.assertEquals(ProjectNextActionStatus.READY, missed.getNextActionStatus());
                Assert.assertFalse(missed.isRescheduleLocked());
            }
        }
        Assert.assertEquals(3, data.instances.size());
    }

    @Test
    public void explicitScheduleEditStillCancelsOffScheduleCarryForwardInstance() {
        TemplateGenerationService service = new TemplateGenerationService();
        TemplateSessionHandler data = new TemplateSessionHandler(true, "CARRY_FORWARD");
        ActionNext carried = data.addInstance(LocalDate.of(2026, 10, 26));
        data.config.setScheduleDaysOfWeek("WED");

        service.syncAfterEdit(data.createSessionProxy(), data.template, 42, 99,
                LocalDate.of(2026, 10, 24), 7);

        Assert.assertEquals(ProjectNextActionStatus.CANCELLED, carried.getNextActionStatus());
        Assert.assertEquals(2, data.instances.size());
        Assert.assertEquals(java.sql.Date.valueOf("2026-10-28"),
                data.instances.get(1).getNextActionDate());
    }

    @Test
    public void autoCancelStillReconcilesOffScheduleInstances() {
        TemplateGenerationService service = new TemplateGenerationService();
        TemplateSessionHandler data = new TemplateSessionHandler(true, "AUTO_CANCEL");
        ActionNext offSchedule = data.addInstance(LocalDate.of(2026, 10, 26));

        service.generateForwardWindow(data.createSessionProxy(), 42, 99,
                LocalDate.of(2026, 10, 24), 7);

        Assert.assertEquals(ProjectNextActionStatus.CANCELLED, offSchedule.getNextActionStatus());
    }

    @Test
    public void disablingGenerationStillCancelsFutureCarriedInstances() {
        TemplateGenerationService service = new TemplateGenerationService();
        TemplateSessionHandler data = new TemplateSessionHandler(true, "CARRY_FORWARD");
        ActionNext carried = data.addInstance(LocalDate.of(2026, 10, 26));
        data.config.setAutoGenerate(false);

        service.syncAfterEdit(data.createSessionProxy(), data.template, 42, 99,
                LocalDate.of(2026, 10, 24), 7);

        Assert.assertEquals(ProjectNextActionStatus.CANCELLED, carried.getNextActionStatus());
        Assert.assertEquals(1, data.instances.size());
    }

    private TemplateWorkdayCalendar calendar(BillExpected... availability) {
        return new TemplateWorkdayCalendar(Arrays.asList(availability));
    }

    private BillExpected availability(LocalDate date, int minutes, String status) {
        return new BillExpected(new BillExpectedId(7, java.sql.Date.valueOf(date)), minutes, 0, status);
    }

    private static final class TemplateSessionHandler implements InvocationHandler {
        private final ActionNext template = new ActionNext();
        private final ActionNextTemplateConfig config = new ActionNextTemplateConfig();
        private final List<ActionNext> instances = new ArrayList<ActionNext>();

        TemplateSessionHandler(boolean billable, String behavior) {
            template.setActionNextId(100);
            template.setWorkspaceId(42);
            template.setContactId(99);
            template.setNextContactId(99);
            template.setTemplateType(TemplateType.WEEKLY);
            template.setNextActionStatus(ProjectNextActionStatus.READY);
            template.setBillable(billable);
            config.setActionNextId(100);
            config.setMissedActionBehavior(behavior);
            config.setScheduleDaysOfWeek("FRI");
        }

        ActionNext addInstance(LocalDate date) {
            ActionNext instance = new ActionNext();
            instance.setActionNextId(200 + instances.size());
            instance.setTemplateActionNextId(100);
            instance.setNextActionStatus(ProjectNextActionStatus.READY);
            instance.setNextActionDate(java.sql.Date.valueOf(date));
            instances.add(instance);
            return instance;
        }

        Session createSessionProxy() {
            return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(),
                    new Class<?>[] { Session.class }, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "get":
                    return args[0] == ActionNextTemplateConfig.class ? config : template;
                case "save":
                    ActionNext instance = (ActionNext) args[0];
                    instance.setActionNextId(200 + instances.size());
                    instances.add(instance);
                    return instance.getActionNextId();
                case "update":
                    return null;
                case "createQuery":
                    return createQuery((String) args[0]);
                default:
                    throw new AssertionError("Unexpected session call: " + method.getName());
            }
        }

        private Query createQuery(final String hql) {
            final Map<String, Object> parameters = new HashMap<String, Object>();
            return (Query) Proxy.newProxyInstance(Query.class.getClassLoader(),
                    new Class<?>[] { Query.class }, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setParameter":
                                parameters.put((String) args[0], args[1]);
                                return proxy;
                            case "setMaxResults":
                                return proxy;
                            case "uniqueResult":
                                if (hql.startsWith("select wu.webUserId")) {
                                    return null;
                                }
                                if (hql.startsWith("select count(*) from BillEntry")) {
                                    return Long.valueOf(0);
                                }
                                return Long.valueOf(matchingInstances(hql, parameters).size());
                            case "list":
                                if (hql.contains("an.templateTypeString is not null")) {
                                    return Collections.singletonList(template);
                                }
                                List<ActionNext> matches = matchingInstances(hql, parameters);
                                if (hql.startsWith("select an.nextActionDate")) {
                                    List<Date> dates = new ArrayList<Date>();
                                    for (ActionNext match : matches) {
                                        dates.add(match.getNextActionDate());
                                    }
                                    return dates;
                                }
                                return matches;
                            default:
                                throw new AssertionError("Unexpected query call: " + method.getName());
                        }
                    });
        }

        private List<ActionNext> matchingInstances(String hql, Map<String, Object> parameters) {
            List<ActionNext> matches = new ArrayList<ActionNext>();
            for (ActionNext instance : instances) {
                Date date = instance.getNextActionDate();
                if (parameters.containsKey("ready")
                        && !parameters.get("ready").equals(instance.getNextActionStatusString())) {
                    continue;
                }
                if (parameters.containsKey("cancelled")
                        && parameters.get("cancelled").equals(instance.getNextActionStatusString())) {
                    continue;
                }
                if (parameters.containsKey("fromDate")
                        && date.before((Date) parameters.get("fromDate"))) {
                    continue;
                }
                Date end = (Date) parameters.get(parameters.containsKey("toDate") ? "toDate" : "windowEnd");
                if (end != null && date.after(end)) {
                    continue;
                }
                Date today = (Date) parameters.get("today");
                if (today != null) {
                    if (hql.contains("an.nextActionDate < :today") ? !date.before(today)
                            : hql.contains("an.nextActionDate = :today") ? !date.equals(today)
                            : date.before(today)) {
                        continue;
                    }
                }
                matches.add(instance);
            }
            return matches;
        }
    }

    private static final class RecordingSessionHandler implements InvocationHandler {

        private String queryString;
        private final Map<String, Object> parameters = new HashMap<String, Object>();
        private int executeUpdateCount;

        Session createSessionProxy() {
            return (Session) Proxy.newProxyInstance(
                    Session.class.getClassLoader(),
                    new Class<?>[] { Session.class },
                    this);
        }

        Query createQueryProxy() {
            return (Query) Proxy.newProxyInstance(
                    Query.class.getClassLoader(),
                    new Class<?>[] { Query.class },
                    new RecordingQueryHandler());
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            if ("createQuery".equals(methodName)) {
                queryString = (String) args[0];
                return createQueryProxy();
            }
            if ("toString".equals(methodName)) {
                return "RecordingSessionProxy";
            }
            return defaultValue(method.getReturnType());
        }

        private final class RecordingQueryHandler implements InvocationHandler {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String methodName = method.getName();
                if ("setParameter".equals(methodName)) {
                    parameters.put((String) args[0], args[1]);
                    return proxy;
                }
                if ("executeUpdate".equals(methodName)) {
                    executeUpdateCount++;
                    return Integer.valueOf(1);
                }
                if ("toString".equals(methodName)) {
                    return "RecordingQueryProxy";
                }
                return defaultValue(method.getReturnType());
            }
        }

        private Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return Boolean.FALSE;
            }
            if (returnType == byte.class) {
                return Byte.valueOf((byte) 0);
            }
            if (returnType == short.class) {
                return Short.valueOf((short) 0);
            }
            if (returnType == int.class) {
                return Integer.valueOf(0);
            }
            if (returnType == long.class) {
                return Long.valueOf(0L);
            }
            if (returnType == float.class) {
                return Float.valueOf(0F);
            }
            if (returnType == double.class) {
                return Double.valueOf(0D);
            }
            if (returnType == char.class) {
                return Character.valueOf('\0');
            }
            return null;
        }
    }
}