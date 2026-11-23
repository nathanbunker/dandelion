package org.dandeliondaily.planahead.service;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.dandeliondaily.planahead.service.TemplateEditService.TemplateEditException;
import org.hibernate.Query;
import org.hibernate.Session;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionAgentConfig;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.TemplateType;
import org.openimmunizationsoftware.pt.model.TimeSlot;

public class TemplateEditServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    private final TemplateEditService service = new TemplateEditService();
    private final TemplateEditService.Owner owner = new TemplateEditService.Owner(42, 99, null, TODAY);
    private FakeSession data;

    @Before
    public void setUp() {
        data = new FakeSession();
    }

    @Test
    public void updateChangesOnlyTheFieldsSent() {
        ActionNext template = data.workTemplate(100);
        template.setNextDescription("Old");
        template.setNextTimeEstimate(Integer.valueOf(30));
        template.setLinkUrl("https://example.org/check");
        ActionNextTemplateConfig config = data.config(100, "FRI", "CARRY_FORWARD");

        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.description = "New";
        TemplateEditService.Saved saved = service.save(data.session(), owner, edit);

        Assert.assertFalse(saved.isCreated());
        Assert.assertEquals("New", template.getNextDescription());
        Assert.assertEquals(Integer.valueOf(30), template.getNextTimeEstimate());
        Assert.assertEquals("https://example.org/check", template.getLinkUrl());
        Assert.assertEquals(TemplateType.WEEKLY, template.getTemplateType());
        Assert.assertEquals("FRI", config.getScheduleDaysOfWeek());
        Assert.assertEquals("CARRY_FORWARD", config.getMissedActionBehavior());
        Assert.assertTrue(config.isAutoGenerate());
    }

    @Test
    public void anExistingTemplateKeepsItsOwnMode() {
        ActionNext template = data.workTemplate(100);
        template.setBillable(false);
        template.setNextActionType(ProjectNextActionType.WILL);
        template.setTimeSlot(TimeSlot.MORNING);
        data.config(100, "FRI", "AUTO_CANCEL");

        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.mode = TemplateEditService.MODE_WORK;
        edit.nextActionType = ProjectNextActionType.MIGHT;
        service.save(data.session(), owner, edit);

        Assert.assertFalse(template.isBillable());
        Assert.assertEquals(ProjectNextActionType.WILL, template.getNextActionType());
        Assert.assertEquals(TimeSlot.MORNING, template.getTimeSlot());
    }

    @Test
    public void createNeedsAMode() {
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.description = "Water the plants";
        edit.templateType = TemplateType.DAILY;
        assertRefused("invalid_arguments", "mode", edit);
    }

    @Test
    public void createFillsInDefaults() {
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.mode = "personal";
        edit.description = "Water the plants";
        edit.templateType = TemplateType.WEEKLY;
        edit.scheduleDaysOfWeek = "MON, THU";
        edit.notes = "Front porch too.";

        TemplateEditService.Saved saved = service.save(data.session(), owner, edit);

        Assert.assertTrue(saved.isCreated());
        ActionNext created = data.actions.get(Integer.valueOf(saved.getTemplateId()));
        Assert.assertFalse(created.isBillable());
        Assert.assertEquals(ProjectNextActionType.WILL, created.getNextActionType());
        Assert.assertEquals(TimeSlot.AFTERNOON, created.getTimeSlot());
        Assert.assertEquals(Integer.valueOf(0), created.getNextTimeEstimate());
        Assert.assertEquals("Front porch too.", created.getNextNotes());
        Assert.assertEquals(ProjectNextActionStatus.READY, created.getNextActionStatus());
        ActionNextTemplateConfig config = data.configs.get(Integer.valueOf(saved.getTemplateId()));
        Assert.assertTrue(config.isAutoGenerate());
        Assert.assertEquals("AUTO_CANCEL", config.getMissedActionBehavior());
        Assert.assertEquals("MON, THU", config.getScheduleDaysOfWeek());
    }

    @Test
    public void anUnreadableScheduleDayIsRefused() {
        data.workTemplate(100);
        data.config(100, "FRI", "AUTO_CANCEL");
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.scheduleDaysOfWeek = "MON,XYZ";
        assertRefused("invalid_arguments", "XYZ", edit);
    }

    @Test
    public void monthlyAndYearlyPatternsAreAccepted() {
        ActionNext template = data.workTemplate(100);
        ActionNextTemplateConfig config = data.config(100, null, "AUTO_CANCEL");
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.templateType = TemplateType.MONTHLY;
        edit.scheduleDaysOfMonth = "1,15,W1-MON,WL-FRI";
        service.save(data.session(), owner, edit);
        Assert.assertEquals(TemplateType.MONTHLY, template.getTemplateType());
        Assert.assertEquals("1,15,W1-MON,WL-FRI", config.getScheduleDaysOfMonth());

        edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.templateType = TemplateType.YEARLY;
        edit.scheduleDaysOfYear = "0101,1225";
        service.save(data.session(), owner, edit);
        Assert.assertEquals("0101,1225", config.getScheduleDaysOfYear());
    }

    @Test
    public void aWorkTemplateCannotMoveToAPersonalProject() {
        data.workTemplate(100);
        data.config(100, "FRI", "AUTO_CANCEL");
        data.project(7, "HOME");
        data.billCodes.put("HOME", "N");
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.projectId = Integer.valueOf(7);
        assertRefused("invalid_arguments", "billable project", edit);
    }

    @Test
    public void aWorkTemplateCanMoveToABillableProject() {
        ActionNext template = data.workTemplate(100);
        data.config(100, "FRI", "AUTO_CANCEL");
        data.project(8, "AIRA");
        data.billCodes.put("AIRA", "Y");
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.projectId = Integer.valueOf(8);
        service.save(data.session(), owner, edit);
        Assert.assertEquals(8, template.getProjectId());
    }

    @Test
    public void someoneElsesTemplateIsNotFound() {
        ActionNext template = data.workTemplate(100);
        template.setContactId(55);
        template.setNextContactId(55);
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.description = "Mine now";
        assertRefused("not_found", "not found", edit);
    }

    @Test
    public void aGeneratedActionIsNotATemplate() {
        ActionNext instance = data.workTemplate(100);
        instance.setTemplateType(null);
        instance.setTemplateActionNextId(Integer.valueOf(90));
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.description = "Changed";
        assertRefused("invalid_arguments", "not a template", edit);
    }

    @Test
    public void aStaleChangeDateIsRefused() {
        ActionNext template = data.workTemplate(100);
        template.setNextChangeDate(new Date(1_000_000_000_000L));
        TemplateEditService.Edit edit = new TemplateEditService.Edit();
        edit.templateId = Integer.valueOf(100);
        edit.description = "Changed";
        edit.expectedChangeDate = new Date(999_000_000_000L);
        assertRefused("stale", "changed", edit);
    }

    @Test
    public void deleteCancelsTheTemplate() {
        ActionNext template = data.workTemplate(100);
        service.delete(data.session(), owner, 100);
        Assert.assertEquals(ProjectNextActionStatus.CANCELLED, template.getNextActionStatus());
    }

    @Test
    public void reactivateRestoresADeletedTemplateAndTurnsGenerationOn() {
        ActionNext template = data.workTemplate(100);
        template.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
        ActionNextTemplateConfig config = data.config(100, "FRI", "AUTO_CANCEL");
        config.setAutoGenerate(false);

        TemplateEditService.Saved saved = service.reactivate(data.session(), owner, 100);

        Assert.assertEquals(ProjectNextActionStatus.READY, template.getNextActionStatus());
        Assert.assertTrue(config.isAutoGenerate());
        Assert.assertFalse(saved.isPerTargetAgent());
    }

    private void assertRefused(String code, String messagePart, TemplateEditService.Edit edit) {
        try {
            service.save(data.session(), owner, edit);
            Assert.fail("expected a refusal");
        } catch (TemplateEditException refused) {
            Assert.assertEquals(code, refused.getCode());
            Assert.assertTrue(refused.getMessage(), refused.getMessage().contains(messagePart));
        }
    }

    /** Just enough of a Hibernate session for TemplateEditService. */
    private static final class FakeSession implements InvocationHandler {
        final Map<Integer, ActionNext> actions = new HashMap<Integer, ActionNext>();
        final Map<Integer, ActionNextTemplateConfig> configs = new HashMap<Integer, ActionNextTemplateConfig>();
        final Map<Integer, Project> projects = new HashMap<Integer, Project>();
        final Map<String, String> billCodes = new HashMap<String, String>();
        private int nextId = 500;

        ActionNext workTemplate(int id) {
            ActionNext template = new ActionNext();
            template.setActionNextId(id);
            template.setWorkspaceId(42);
            template.setContactId(99);
            template.setNextContactId(99);
            template.setBillable(true);
            template.setTemplateType(TemplateType.WEEKLY);
            template.setNextActionType(ProjectNextActionType.WILL);
            template.setNextDescription("Weekly check");
            template.setNextActionStatus(ProjectNextActionStatus.READY);
            actions.put(Integer.valueOf(id), template);
            return template;
        }

        ActionNextTemplateConfig config(int id, String daysOfWeek, String missed) {
            ActionNextTemplateConfig config = new ActionNextTemplateConfig();
            config.setActionNextId(id);
            config.setAutoGenerate(true);
            config.setScheduleDaysOfWeek(daysOfWeek);
            config.setMissedActionBehavior(missed);
            configs.put(Integer.valueOf(id), config);
            return config;
        }

        Project project(int id, String billCode) {
            Project project = new Project();
            project.setProjectId(id);
            project.setWorkspaceId(42);
            project.setBillCode(billCode);
            projects.put(Integer.valueOf(id), project);
            return project;
        }

        Session session() {
            return (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] { Session.class },
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "get":
                    Integer id = Integer.valueOf(((Number) args[1]).intValue());
                    if (args[0] == ActionNext.class) {
                        return actions.get(id);
                    }
                    if (args[0] == ActionNextTemplateConfig.class) {
                        return configs.get(id);
                    }
                    if (args[0] == Project.class) {
                        return projects.get(id);
                    }
                    if (args[0] == ActionAgentConfig.class) {
                        return null;
                    }
                    return null;
                case "save":
                    if (args[0] instanceof ActionNext) {
                        ActionNext action = (ActionNext) args[0];
                        action.setActionNextId(nextId++);
                        actions.put(Integer.valueOf(action.getActionNextId()), action);
                        return Integer.valueOf(action.getActionNextId());
                    }
                    if (args[0] instanceof ActionNextTemplateConfig) {
                        ActionNextTemplateConfig config = (ActionNextTemplateConfig) args[0];
                        configs.put(Integer.valueOf(config.getActionNextId()), config);
                        return Integer.valueOf(config.getActionNextId());
                    }
                    return null;
                case "update":
                    return null;
                case "createQuery":
                    return query((String) args[0]);
                default:
                    throw new AssertionError("Unexpected session call: " + method.getName());
            }
        }

        private Query query(final String hql) {
            final Map<String, Object> parameters = new HashMap<String, Object>();
            return (Query) Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[] { Query.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "setParameter":
                                parameters.put((String) args[0], args[1]);
                                return proxy;
                            case "setMaxResults":
                                return proxy;
                            case "uniqueResult":
                                if (hql.startsWith("from BillCode")) {
                                    // bill_code is part of BillCode's composite id.
                                    Assert.assertTrue(hql, hql.contains("id.billCode = :billCode"));
                                    String billable = billCodes.get(parameters.get("billCode"));
                                    if (billable == null) {
                                        return null;
                                    }
                                    BillCode billCode = new BillCode();
                                    billCode.setBillCode((String) parameters.get("billCode"));
                                    billCode.setBillable(billable);
                                    return billCode;
                                }
                                return Long.valueOf(0);
                            case "list":
                                return Collections.emptyList();
                            default:
                                throw new AssertionError("Unexpected query call: " + method.getName());
                        }
                    });
        }
    }
}
