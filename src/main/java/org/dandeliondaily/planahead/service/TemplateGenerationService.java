package org.dandeliondaily.planahead.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.hibernate.Query;
import org.hibernate.Session;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.BillExpected;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TemplateType;

/**
 * Generates and manages recurring template instances.
 *
 * Callers are responsible for wrapping calls in a Hibernate transaction.
 * This service performs DB reads and writes but does not open, commit,
 * or roll back transactions itself.
 */
public class TemplateGenerationService {

    private static final Logger LOGGER = Logger.getLogger(TemplateGenerationService.class.getName());

    private static final String STATUS_READY = ProjectNextActionStatus.READY.getId();
    private static final String STATUS_CANCELLED = ProjectNextActionStatus.CANCELLED.getId();
    private static final int CARRY_SEARCH_DAYS = 366;

    private final SchedulePatternEvaluator patternEvaluator = new SchedulePatternEvaluator();
    private final ActionNotesService actionNotesService = new ActionNotesService();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Reconcile template instances from today through today+advanceDays for every
     * active template owned by the given workspace/contact. Billable templates are
     * restricted to working days; personal templates use calendar days.
     */
    public int generateForwardWindow(Session session, int workspaceId, int contactId,
            LocalDate today, int advanceDays) {
        LocalDate windowEnd = today.plusDays(advanceDays);
        List<ActionNext> templates = loadTemplateRoots(session, workspaceId, contactId);
        TemplateWorkdayCalendar workdayCalendar = loadWorkdayCalendar(
                session, contactId, today.minusDays(CARRY_SEARCH_DAYS),
                windowEnd.plusDays(CARRY_SEARCH_DAYS));
        LOGGER.fine("[TemplateGeneration] Begin workspace=" + workspaceId
                + " contact=" + contactId
                + " today=" + today
                + " windowEnd=" + windowEnd
                + " templates=" + templates.size());
        int total = 0;
        for (ActionNext template : templates) {
            try {
                int created = generateForTemplate(session, template, today, windowEnd, workdayCalendar, false);
                total += created;
                LOGGER.fine("[TemplateGeneration] Template " + template.getActionNextId()
                        + " created=" + created);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "[TemplateGeneration] Error template=" + template.getActionNextId(), e);
            }
        }
        LOGGER.fine("[TemplateGeneration] End workspace=" + workspaceId
                + " contact=" + contactId
                + " totalCreated=" + total);
        return total;
    }

    /**
     * After a template definition is saved, propagate changed fields to all future
     * READY instances (today and later). Today's instance is also updated unless it
     * has BillEntry records, in which case it is left untouched.
     *
     * If autoGenerate was just turned off, all future READY instances are
     * cancelled.
     */
    public void propagateTemplateEdit(Session session, ActionNext template,
            int workspaceId, int contactId, LocalDate today) {
        Date todayDate = toUtcDate(today);
        ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(
                ActionNextTemplateConfig.class, template.getActionNextId());
        boolean rescheduleLocked = isRescheduleLocked(config);

        if (config != null && !config.isAutoGenerate()) {
            cancelFutureReadyInstances(session, template.getActionNextId(),
                    workspaceId, contactId, todayDate);
            return;
        }

        Query query = session.createQuery(
                "from ActionNext an where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateActionNextId = :templateId "
                        + "and an.nextActionDate is not null and an.nextActionDate >= :today "
                        + "and an.nextActionStatusString = :ready");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("templateId", template.getActionNextId());
        query.setParameter("today", todayDate);
        query.setParameter("ready", STATUS_READY);
        @SuppressWarnings("unchecked")
        List<ActionNext> instances = query.list();

        for (ActionNext instance : instances) {
            boolean isToday = isSameDay(instance.getNextActionDate(), todayDate);
            if (shouldPreserveBilledToday(isToday,
                    isToday && hasBillEntries(session, instance.getActionNextId()))) {
                continue; // protect billed work
            }
            copyTemplateFieldsToInstance(template, instance, rescheduleLocked);
            session.update(instance);
        }
    }

    /**
     * Apply missed-action behavior to past READY template instances for all
     * templates owned by the given workspace/contact.
     *
     * AUTO_CANCEL: cancel the instance.
     * CARRY_FORWARD: move to the next eligible working date if no instance already
     * exists for this template on that date; otherwise cancel.
     * IGNORE: leave as-is.
     *
     * Called after generateForwardWindow so duplicate detection includes newly
     * generated occurrences. Routine reconciliation preserves carried instances
     * even when their new dates do not match the recurrence pattern.
     */
    public void applyMissedActionBehavior(Session session, int workspaceId, int contactId,
            LocalDate today) {
        Date todayDate = toUtcDate(today);
        LocalDate carrySearchEnd = today.plusDays(366);
        TemplateWorkdayCalendar workdayCalendar = loadWorkdayCalendar(
                session, contactId, today, carrySearchEnd);
        Query query = session.createQuery(
                "from ActionNext an where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateActionNextId is not null "
                        + "and an.nextActionDate is not null and an.nextActionDate < :today "
                        + "and an.nextActionStatusString = :ready");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("today", todayDate);
        query.setParameter("ready", STATUS_READY);
        @SuppressWarnings("unchecked")
        List<ActionNext> pastInstances = query.list();

        for (ActionNext instance : pastInstances) {
            ActionNext template = (ActionNext) session.get(ActionNext.class,
                    instance.getTemplateActionNextId());
            if (template == null) {
                continue;
            }
            ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(
                    ActionNextTemplateConfig.class, template.getActionNextId());
            if (config == null) {
                continue;
            }
            String behavior = config.getMissedActionBehavior();
            if ("AUTO_CANCEL".equals(behavior)) {
                instance.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
                instance.setNextChangeDate(new Date());
                session.update(instance);
            } else if ("CARRY_FORWARD".equals(behavior)) {
                LocalDate carryDate = findNextEligibleDate(
                        workdayCalendar, template.isBillable(), today, carrySearchEnd);
                if (carryDate == null) {
                    continue;
                }
                Date carryDateValue = toUtcDate(carryDate);
                boolean carryDateAlreadyHasInstance = dateHasActiveInstance(session,
                        template.getActionNextId(), workspaceId, contactId, carryDateValue);
                if (carryDateAlreadyHasInstance) {
                    instance.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
                } else {
                    instance.setNextActionDate(carryDateValue);
                    instance.setRescheduleLocked(false);
                }
                instance.setNextChangeDate(new Date());
                session.update(instance);
            }
            // IGNORE: leave as-is
        }
    }

    /**
     * Zero out actual minutes for past active items so today's display starts
     * fresh every day.
     */
    public void cleanupPastActualTimes(Session session, int workspaceId, int contactId,
            LocalDate today) {
        Date todayDate = toUtcDate(today);
        Query query = session.createQuery(
                "update ActionNext an set an.nextTimeActual = 0 "
                        + "where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.nextActionDate is not null and an.nextActionDate < :today "
                        + "and an.nextTimeActual > 0 "
                        + "and an.nextActionStatusString <> :cancelled "
                        + "and an.nextActionStatusString <> :completed");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("today", todayDate);
        query.setParameter("cancelled", STATUS_CANCELLED);
        query.setParameter("completed", ProjectNextActionStatus.COMPLETED.getId());
        query.executeUpdate();
    }

    /**
     * Called immediately after a template definition is saved via the UI.
     * Propagates field changes to future instances, cancels instances that no
     * longer match the updated schedule, and generates new instances for any
     * matching dates in [today, today+advanceDays] that don't yet have one.
     *
     * Callers must wrap this call in a Hibernate transaction.
     */
    public void syncAfterEdit(Session session, ActionNext template,
            int workspaceId, int contactId, LocalDate today, int advanceDays) {
        // Step 1 — propagate field changes and handle autoGenerate=N cancellation
        propagateTemplateEdit(session, template, workspaceId, contactId, today);

        ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(
                ActionNextTemplateConfig.class, template.getActionNextId());
        if (config == null || !config.isAutoGenerate()) {
            return;
        }

        TemplateType type = template.getTemplateType();
        if (type == null) {
            return;
        }
        if (template.getContactId() == null || template.getWorkspaceId() == null) {
            return;
        }

        LocalDate windowEnd = today.plusDays(advanceDays);
        TemplateWorkdayCalendar workdayCalendar = loadWorkdayCalendar(
                session, contactId, today.minusDays(CARRY_SEARCH_DAYS),
                windowEnd.plusDays(CARRY_SEARCH_DAYS));
        generateForTemplate(session, template, today, windowEnd, workdayCalendar, true);
    }

    // =========================================================================
    // Private generation helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<ActionNext> loadTemplateRoots(Session session, int workspaceId, int contactId) {
        Query query = session.createQuery(
                "from ActionNext an where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateTypeString is not null and an.templateTypeString <> '' "
                        + "and (an.templateActionNextId is null or an.templateActionNextId = 0) "
                        + "and an.nextActionStatusString <> :cancelled");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("cancelled", STATUS_CANCELLED);
        return query.list();
    }

    private int generateForTemplate(Session session, ActionNext template,
            LocalDate today, LocalDate windowEnd, TemplateWorkdayCalendar workdayCalendar,
            boolean templateEdited) {
        int templateId = template.getActionNextId();
        ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(
                ActionNextTemplateConfig.class, template.getActionNextId());
        if (config == null) {
            LOGGER.fine("[TemplateGeneration] Skip template=" + templateId + " reason=no_config");
            return 0;
        }
        if (!config.isAutoGenerate()) {
            LOGGER.fine("[TemplateGeneration] Skip template=" + templateId + " reason=auto_generate_off");
            return 0;
        }
        TemplateType type = template.getTemplateType();
        if (type == null) {
            LOGGER.fine("[TemplateGeneration] Skip template=" + templateId + " reason=no_template_type");
            return 0;
        }

        String missedActionBehavior = n(config.getMissedActionBehavior());
        boolean carryForward = template.isBillable() && "CARRY_FORWARD".equals(missedActionBehavior);
        LocalDate recurrenceStart = carryForward ? today.minusDays(CARRY_SEARCH_DAYS) : today;
        LocalDate carrySearchEnd = windowEnd.plusDays(CARRY_SEARCH_DAYS);
        String pattern = resolvePattern(type, config);
        List<LocalDate> recurrenceDates = patternEvaluator.matchingDates(
                type, pattern, recurrenceStart, windowEnd);
        Set<LocalDate> scheduledDates = resolveScheduledDates(recurrenceDates, workdayCalendar,
                template.isBillable(), missedActionBehavior, today, carrySearchEnd);
        LocalDate reconciliationEnd = windowEnd;
        for (LocalDate scheduledDate : scheduledDates) {
            if (scheduledDate.isAfter(reconciliationEnd)) {
                reconciliationEnd = scheduledDate;
            }
        }

        // A carried instance can legitimately be off-pattern. Only an explicit
        // template edit should invalidate those dates.
        if (templateEdited || !"CARRY_FORWARD".equals(missedActionBehavior)) {
            cancelIneligibleReadyInstances(session, template.getActionNextId(),
                    workspaceId(template), contactId(template), today, reconciliationEnd, scheduledDates);
        }

        Set<LocalDate> existingDates = loadExistingInstanceDates(session,
                template.getActionNextId(), workspaceId(template), contactId(template),
                today, reconciliationEnd);

        boolean rescheduleLocked = isRescheduleLocked(config);
        int count = 0;
        for (LocalDate date : scheduledDates) {
            if (!existingDates.contains(date)) {
                session.save(createInstance(template, date, rescheduleLocked));
                count++;
            }
        }

        LOGGER.fine("[TemplateGeneration] Evaluate template=" + templateId
                + " type=" + type
                + " pattern=" + n(pattern)
                + " from=" + today
                + " to=" + windowEnd
                + " scheduledDates=" + scheduledDates.size()
                + " existingDates=" + existingDates.size()
                + " created=" + count);

        config.setLastGeneratedDate(toUtcDate(windowEnd));
        session.update(config);
        return count;
    }

    Set<LocalDate> resolveScheduledDates(List<LocalDate> recurrenceDates,
            TemplateWorkdayCalendar workdayCalendar, boolean billable,
            String missedActionBehavior, LocalDate earliestDate, LocalDate carrySearchEnd) {
        Set<LocalDate> scheduledDates = new HashSet<LocalDate>();
        String behavior = n(missedActionBehavior);
        for (LocalDate recurrenceDate : recurrenceDates) {
            LocalDate scheduledDate = recurrenceDate;
            if (billable && !workdayCalendar.isEligible(true, recurrenceDate)) {
                if ("IGNORE".equals(behavior)) {
                    scheduledDate = recurrenceDate;
                } else if ("CARRY_FORWARD".equals(behavior)) {
                    scheduledDate = findNextEligibleDate(workdayCalendar, true,
                            recurrenceDate.plusDays(1), carrySearchEnd);
                } else {
                    scheduledDate = null;
                }
            }
            if (scheduledDate != null && !scheduledDate.isBefore(earliestDate)) {
                scheduledDates.add(scheduledDate);
            }
        }
        return scheduledDates;
    }

    private Set<LocalDate> loadExistingInstanceDates(Session session, int templateActionNextId,
            int workspaceId, int contactId, LocalDate from, LocalDate to) {
        Date fromDate = toUtcDate(from);
        Date toDate = toUtcDate(to);
        Query query = session.createQuery(
                "select an.nextActionDate from ActionNext an "
                        + "where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateActionNextId = :templateId "
                        + "and an.nextActionDate is not null "
                        + "and an.nextActionDate >= :fromDate and an.nextActionDate <= :toDate "
                        + "and an.nextActionStatusString <> :cancelled");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("templateId", templateActionNextId);
        query.setParameter("fromDate", fromDate);
        query.setParameter("toDate", toDate);
        query.setParameter("cancelled", STATUS_CANCELLED);
        @SuppressWarnings("unchecked")
        List<Date> dates = query.list();
        Set<LocalDate> result = new HashSet<>();
        for (Date d : dates) {
            result.add(toLocalDate(d));
        }
        return result;
    }

    private ActionNext createInstance(ActionNext template, LocalDate date, boolean rescheduleLocked) {
        ActionNext instance = new ActionNext();
        instance.setProjectId(template.getProjectId());
        instance.setProject(template.getProject());
        instance.setContactId(template.getContactId());
        instance.setContact(template.getContact());
        instance.setWorkspaceId(template.getWorkspaceId());
        instance.setNextContactId(template.getNextContactId());
        instance.setNextProjectContact(template.getNextProjectContact());
        instance.setNextActionDate(toUtcDate(date));
        instance.setNextActionStatus(ProjectNextActionStatus.READY);
        instance.setTemplateActionNextId(template.getActionNextId());
        instance.setTemplateType(null); // generated instances are not templates
        instance.setNextActionType(template.getNextActionType());
        instance.setNextDescription(n(template.getNextDescription()));
        instance.setNextTimeEstimate(
                template.getNextTimeEstimate() == null ? 0 : template.getNextTimeEstimate());
        instance.setGamePoints(template.getGamePoints());
        instance.setTimeSlot(template.getTimeSlot());
        instance.setLinkUrl(n(template.getLinkUrl()));
        actionNotesService.initialize(instance, template.getNextNotes());
        instance.setProcessStage(template.getProcessStage());
        instance.setBillable(template.isBillable());
        instance.setPriorityLevel(template.getPriorityLevel());
        instance.setCompletionOrder(0);
        instance.setRescheduleLocked(rescheduleLocked);
        instance.setNextChangeDate(new Date());
        return instance;
    }

    private void cancelFutureReadyInstances(Session session, int templateActionNextId,
            int workspaceId, int contactId, Date fromDate) {
        Query query = session.createQuery(
                "from ActionNext an where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateActionNextId = :templateId "
                        + "and an.nextActionDate is not null and an.nextActionDate >= :fromDate "
                        + "and an.nextActionStatusString = :ready");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("templateId", templateActionNextId);
        query.setParameter("fromDate", fromDate);
        query.setParameter("ready", STATUS_READY);
        @SuppressWarnings("unchecked")
        List<ActionNext> instances = query.list();
        for (ActionNext instance : instances) {
            boolean isToday = isSameDay(instance.getNextActionDate(), fromDate);
            if (shouldPreserveBilledToday(isToday,
                    isToday && hasBillEntries(session, instance.getActionNextId()))) {
                continue;
            }
            instance.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
            instance.setNextChangeDate(new Date());
            session.update(instance);
        }
    }

    @SuppressWarnings("unchecked")
    private void cancelIneligibleReadyInstances(Session session, int templateActionNextId,
            int workspaceId, int contactId, LocalDate today, LocalDate windowEnd,
            Set<LocalDate> eligibleDates) {
        Date todayDate = toUtcDate(today);
        Date windowEndDate = toUtcDate(windowEnd);
        Query query = session.createQuery(
                "from ActionNext an where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateActionNextId = :templateId "
                        + "and an.nextActionDate is not null and an.nextActionDate >= :today "
                        + "and an.nextActionDate <= :windowEnd "
                        + "and an.nextActionStatusString = :ready");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("templateId", templateActionNextId);
        query.setParameter("today", todayDate);
        query.setParameter("windowEnd", windowEndDate);
        query.setParameter("ready", STATUS_READY);
        List<ActionNext> instances = query.list();
        for (ActionNext instance : instances) {
            if (instance.getNextActionDate() == null) {
                continue;
            }
            LocalDate instanceDate = toLocalDate(instance.getNextActionDate());
            if (!eligibleDates.contains(instanceDate)) {
                boolean isToday = instanceDate.equals(today);
                if (shouldPreserveBilledToday(isToday,
                        isToday && hasBillEntries(session, instance.getActionNextId()))) {
                    continue;
                }
                instance.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
                instance.setNextChangeDate(new Date());
                session.update(instance);
            }
        }
    }

    private void copyTemplateFieldsToInstance(ActionNext template, ActionNext instance,
            boolean rescheduleLocked) {
        instance.setNextActionType(template.getNextActionType());
        instance.setNextDescription(n(template.getNextDescription()));
        instance.setNextTimeEstimate(
                template.getNextTimeEstimate() == null ? 0 : template.getNextTimeEstimate());
        instance.setGamePoints(template.getGamePoints());
        instance.setTimeSlot(template.getTimeSlot());
        instance.setLinkUrl(n(template.getLinkUrl()));
        instance.setProcessStage(template.getProcessStage());
        instance.setRescheduleLocked(rescheduleLocked);
        instance.setNextChangeDate(new Date());
    }

    // =========================================================================
    // Small private helpers
    // =========================================================================

    private boolean hasBillEntries(Session session, int actionNextId) {
        Long count = (Long) session.createQuery(
                "select count(*) from BillEntry where action.actionNextId = :id")
                .setParameter("id", actionNextId)
                .uniqueResult();
        return count != null && count > 0;
    }

    private boolean dateHasActiveInstance(Session session, int templateActionNextId,
            int workspaceId, int contactId, Date actionDate) {
        Long count = (Long) session.createQuery(
                "select count(*) from ActionNext an "
                        + "where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                        + "and an.templateActionNextId = :templateId "
                        + "and an.nextActionDate = :today "
                        + "and an.nextActionStatusString <> :cancelled")
                .setParameter("workspaceId", workspaceId)
                .setParameter("contactId", contactId)
                .setParameter("templateId", templateActionNextId)
                .setParameter("today", actionDate)
                .setParameter("cancelled", STATUS_CANCELLED)
                .uniqueResult();
        return count != null && count > 0;
    }

    private LocalDate findNextEligibleDate(TemplateWorkdayCalendar workdayCalendar,
            boolean billable, LocalDate from, LocalDate to) {
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (workdayCalendar.isEligible(billable, date)) {
                return date;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private TemplateWorkdayCalendar loadWorkdayCalendar(Session session, int contactId,
            LocalDate from, LocalDate to) {
        Query userQuery = session.createQuery(
                "select wu.webUserId from WebUser wu where wu.contactId = :contactId");
        userQuery.setParameter("contactId", contactId);
        userQuery.setMaxResults(1);
        Integer webUserId = (Integer) userQuery.uniqueResult();
        if (webUserId == null) {
            return new TemplateWorkdayCalendar(Collections.<BillExpected>emptyList());
        }

        Query availabilityQuery = session.createQuery(
                "from BillExpected be where be.id.webUserId = :webUserId "
                        + "and be.id.billDate >= :fromDate and be.id.billDate <= :toDate");
        availabilityQuery.setParameter("webUserId", webUserId);
        availabilityQuery.setParameter("fromDate", toUtcDate(from));
        availabilityQuery.setParameter("toDate", toUtcDate(to));
        return new TemplateWorkdayCalendar((List<BillExpected>) availabilityQuery.list());
    }

    private String resolvePattern(TemplateType type, ActionNextTemplateConfig config) {
        switch (type) {
            case WEEKLY:
                return config.getScheduleDaysOfWeek();
            case MONTHLY:
                return config.getScheduleDaysOfMonth();
            case QUARTERLY:
                return config.getScheduleDaysOfQuarter();
            case YEARLY:
                return config.getScheduleDaysOfYear();
            default:
                return null; // DAILY — pattern field is not used
        }
    }

    private boolean isRescheduleLocked(ActionNextTemplateConfig config) {
        return config != null && "AUTO_CANCEL".equals(config.getMissedActionBehavior());
    }

    private boolean isSameDay(Date a, Date b) {
        if (a == null || b == null) {
            return false;
        }
        return toLocalDate(a).equals(toLocalDate(b));
    }

    static boolean shouldPreserveBilledToday(boolean isToday, boolean hasBillEntries) {
        return isToday && hasBillEntries;
    }

    private int workspaceId(ActionNext template) {
        return template.getWorkspaceId() == null ? 0 : template.getWorkspaceId();
    }

    private int contactId(ActionNext template) {
        Integer value = template.getContactId();
        return value == null ? 0 : value.intValue();
    }

    static LocalDate toLocalDate(Date date) {
        if (date instanceof java.sql.Date) {
            // java.sql.Date.toLocalDate() is timezone-free — use it directly.
            return ((java.sql.Date) date).toLocalDate();
        }
        // Fall back: interpret the instant in the JVM's local timezone, which is
        // how JDBC will have set the time when reading a DATE column.
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    static Date toUtcDate(LocalDate localDate) {
        // java.sql.Date has no time/timezone component, so JDBC writes the date
        // value directly to a DATE column without any timezone shift.
        return java.sql.Date.valueOf(localDate);
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
