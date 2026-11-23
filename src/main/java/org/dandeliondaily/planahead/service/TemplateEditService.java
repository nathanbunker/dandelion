package org.dandeliondaily.planahead.service;

import java.time.LocalDate;
import java.util.Date;

import org.dandeliondaily.shared.service.ActionNotesService;
import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.doa.LinkedMeetingDao;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionNextTemplateConfig;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.TemplateType;
import org.openimmunizationsoftware.pt.model.TimeSlot;

/**
 * Creates, edits, deletes and reactivates recurring action templates. The Templates page
 * (through {@link PlanAheadMutationService}) and the MCP {@code update_template} tool both call
 * this, so a template behaves the same whichever way it was changed.
 *
 * <p>Nothing here opens or commits a transaction. A change is two steps: {@link #save} (or
 * {@link #reactivate}) writes the template, then {@link #sync} cascades it to the template's
 * generated actions -- refreshing future open ones, cancelling those whose date no longer fits
 * the schedule, and generating new ones in the advance window. {@link #delete} cancels the
 * template and its open actions from today on in one step.
 */
public class TemplateEditService {

    public static final String MODE_WORK = "WORK";
    public static final String MODE_PERSONAL = "PERSONAL";

    public static final String MISSED_AUTO_CANCEL = "AUTO_CANCEL";
    public static final String MISSED_CARRY_FORWARD = "CARRY_FORWARD";
    public static final String MISSED_IGNORE = "IGNORE";

    static final int MAX_DESCRIPTION = 12000;
    static final int MAX_LINK_URL = 1200;
    static final int MAX_DAYS_OF_WEEK = 30;
    static final int MAX_DAYS_OF_MONTH = 100;
    static final int MAX_DAYS_OF_QUARTER = 200;
    static final int MAX_DAYS_OF_YEAR = 400;

    private final ActionNotesService actionNotesService = new ActionNotesService();
    private final PlanAheadBoardService boardService = new PlanAheadBoardService();
    private final SchedulePatternEvaluator patternEvaluator = new SchedulePatternEvaluator();

    /** A message for the person or agent making the change; nothing was written. */
    public static class TemplateEditException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public TemplateEditException(String code, String message) {
            super(message);
            this.code = code;
        }

        /** "invalid_arguments", "not_found" or "stale". */
        public String getCode() {
            return code;
        }
    }

    /** Whose templates these are, and what day it is for them. */
    public static class Owner {
        final int workspaceId;
        final int contactId;
        final ProjectContact contact;
        final LocalDate today;

        public Owner(int workspaceId, int contactId, ProjectContact contact, LocalDate today) {
            this.workspaceId = workspaceId;
            this.contactId = contactId;
            this.contact = contact;
            this.today = today;
        }

        public LocalDate getToday() {
            return today;
        }
    }

    /**
     * A new template, or a change to one. For a change, a null field is left as it is; for a new
     * template it takes its default. An empty string clears a text field.
     */
    public static class Edit {
        /** Null or 0 creates a template. */
        public Integer templateId;
        /** WORK or PERSONAL; used when creating. An existing template keeps its own. */
        public String mode;
        /** The Templates page may turn an ordinary action into a template; MCP may not. */
        public boolean allowConvertAction;
        public String description;
        public TemplateType templateType;
        /** Work templates only; personal templates are always WILL. */
        public String nextActionType;
        /** Personal templates only. */
        public String timeSlot;
        public Integer estimateMinutes;
        public String missedActionBehavior;
        public Boolean autoGenerate;
        public String scheduleDaysOfWeek;
        public String scheduleDaysOfMonth;
        public String scheduleDaysOfQuarter;
        public String scheduleDaysOfYear;
        public String linkUrl;
        /** Replaces the notes the template's actions are generated with. */
        public String notes;
        /** 0 for no project. */
        public Integer projectId;
        /** When true, "Performed by" and the agent handoff below are replaced. */
        public boolean agentSent;
        /** An agent key, or empty for a person. */
        public String agent;
        public String agentHandoffMode;
        public Integer agentHandoffTemplateId;
        public Integer agentHandoffProjectId;
        public String agentWorkloadKey;
        /** When set, the save is refused if the template changed since (its nextChangeDate). */
        public Date expectedChangeDate;
    }

    /** What {@link #save} or {@link #reactivate} wrote, for the {@link #sync} that follows. */
    public static class Saved {
        int templateId;
        boolean created;
        boolean perTargetAgent;
        String previousNotes;

        public int getTemplateId() {
            return templateId;
        }

        public boolean isCreated() {
            return created;
        }

        /**
         * True for an agent template whose preps come from each occurrence of another template: it
         * has no schedule of its own, so there is nothing to sync.
         */
        public boolean isPerTargetAgent() {
            return perTargetAgent;
        }
    }

    // =========================================================================
    // Save
    // =========================================================================

    public Saved save(Session session, Owner owner, Edit edit) {
        boolean creating = edit.templateId == null || edit.templateId.intValue() <= 0;
        ActionNext action;
        boolean existingTemplate;
        if (creating) {
            action = null;
            existingTemplate = false;
        } else {
            action = (ActionNext) session.get(ActionNext.class, edit.templateId);
            if (action == null || action.getWorkspaceId() == null
                    || action.getWorkspaceId().intValue() != owner.workspaceId) {
                throw new TemplateEditException("not_found", "Template #" + edit.templateId + " not found.");
            }
            existingTemplate = isTemplateRoot(action);
            if (existingTemplate) {
                requireOwnTemplate(action, owner);
            } else if (!edit.allowConvertAction) {
                throw new TemplateEditException("invalid_arguments",
                        "Action #" + edit.templateId + " is not a template.");
            }
            if (edit.expectedChangeDate != null && (action.getNextChangeDate() == null
                    || toSeconds(action.getNextChangeDate()) != toSeconds(edit.expectedChangeDate))) {
                throw new TemplateEditException("stale",
                        "Template #" + edit.templateId + " has changed since it was last read.");
            }
        }

        boolean personal;
        if (existingTemplate) {
            personal = !action.isBillable();
        } else {
            String mode = n(edit.mode).trim().toUpperCase();
            if (!MODE_WORK.equals(mode) && !MODE_PERSONAL.equals(mode)) {
                throw new TemplateEditException("invalid_arguments", "mode must be WORK or PERSONAL.");
            }
            personal = MODE_PERSONAL.equals(mode);
        }

        ActionNextTemplateConfig config = creating ? null
                : (ActionNextTemplateConfig) session.get(ActionNextTemplateConfig.class, edit.templateId);

        // ---- Resolve every value: what was sent, else what is there, else the default ----
        String description = edit.description != null ? edit.description.trim()
                : (action == null ? "" : n(action.getNextDescription()));
        if (description.length() == 0) {
            throw new TemplateEditException("invalid_arguments", "Description is required.");
        }
        if (description.length() > MAX_DESCRIPTION) {
            throw new TemplateEditException("invalid_arguments",
                    "Description is limited to " + MAX_DESCRIPTION + " characters.");
        }

        TemplateType templateType = edit.templateType != null ? edit.templateType
                : (existingTemplate ? action.getTemplateType() : null);
        if (templateType == null) {
            throw new TemplateEditException("invalid_arguments", "templateType is required.");
        }

        String nextActionType = null;
        TimeSlot timeSlot = null;
        if (personal) {
            if (edit.timeSlot != null && edit.timeSlot.trim().length() > 0) {
                timeSlot = TimeSlot.getTimeSlot(edit.timeSlot.trim().toUpperCase());
                if (timeSlot == null) {
                    throw new TemplateEditException("invalid_arguments",
                            "timeSlot must be WAKE, MORNING, AFTERNOON or EVENING.");
                }
            } else if (action != null && action.getTimeSlot() != null) {
                timeSlot = action.getTimeSlot();
            } else {
                timeSlot = TimeSlot.AFTERNOON;
            }
        } else {
            nextActionType = edit.nextActionType != null ? edit.nextActionType.trim()
                    : (existingTemplate ? n(action.getNextActionType()) : ProjectNextActionType.WILL);
            if (nextActionType.length() == 0
                    || boardService.resolveRowKeyForActionType(nextActionType).length() == 0) {
                throw new TemplateEditException("invalid_arguments", "nextActionType \"" + nextActionType
                        + "\" can't be used on a template (use WILL, WILL_CONTACT, WILL_MEET, REVIEW, DOCUMENT, "
                        + "WILL_FOLLOW_UP, COMMITTED_TO, MIGHT or WOULD_LIKE_TO).");
            }
        }

        Integer estimate = edit.estimateMinutes != null ? edit.estimateMinutes
                : (action != null && action.getNextTimeEstimate() != null ? action.getNextTimeEstimate()
                        : Integer.valueOf(0));
        if (estimate.intValue() < 0) {
            throw new TemplateEditException("invalid_arguments", "estimateMinutes cannot be negative.");
        }

        String missed = edit.missedActionBehavior != null ? edit.missedActionBehavior.trim().toUpperCase()
                : (config != null ? n(config.getMissedActionBehavior()) : "");
        if (missed.length() == 0) {
            missed = MISSED_AUTO_CANCEL;
        }
        if (!MISSED_AUTO_CANCEL.equals(missed) && !MISSED_CARRY_FORWARD.equals(missed)
                && !MISSED_IGNORE.equals(missed)) {
            throw new TemplateEditException("invalid_arguments",
                    "missedActionBehavior must be AUTO_CANCEL, CARRY_FORWARD or IGNORE.");
        }

        boolean autoGenerate = edit.autoGenerate != null ? edit.autoGenerate.booleanValue()
                : (config == null || config.isAutoGenerate());

        String daysOfWeek = edit.scheduleDaysOfWeek != null
                ? PlanAheadMutationService.compactDays(edit.scheduleDaysOfWeek.trim())
                : (config == null ? "" : n(config.getScheduleDaysOfWeek()));
        String daysOfMonth = edit.scheduleDaysOfMonth != null ? edit.scheduleDaysOfMonth.trim()
                : (config == null ? "" : n(config.getScheduleDaysOfMonth()));
        String daysOfQuarter = edit.scheduleDaysOfQuarter != null ? edit.scheduleDaysOfQuarter.trim()
                : (config == null ? "" : n(config.getScheduleDaysOfQuarter()));
        String daysOfYear = edit.scheduleDaysOfYear != null ? edit.scheduleDaysOfYear.trim()
                : (config == null ? "" : n(config.getScheduleDaysOfYear()));
        checkLength(daysOfWeek, MAX_DAYS_OF_WEEK, "scheduleDaysOfWeek", " (e.g. MON,TUE,WED)");
        checkLength(daysOfMonth, MAX_DAYS_OF_MONTH, "scheduleDaysOfMonth", "");
        checkLength(daysOfQuarter, MAX_DAYS_OF_QUARTER, "scheduleDaysOfQuarter", "");
        checkLength(daysOfYear, MAX_DAYS_OF_YEAR, "scheduleDaysOfYear", "");
        checkPattern(templateType, daysOfWeek, daysOfMonth, daysOfQuarter, daysOfYear, owner.today);

        String linkUrl = edit.linkUrl != null ? edit.linkUrl.trim() : (action == null ? "" : n(action.getLinkUrl()));
        if (linkUrl.length() > MAX_LINK_URL) {
            throw new TemplateEditException("invalid_arguments",
                    "linkUrl is limited to " + MAX_LINK_URL + " characters.");
        }
        if (edit.notes != null) {
            String error = actionNotesService.lengthError(edit.notes);
            if (error != null) {
                throw new TemplateEditException("invalid_arguments", error);
            }
        }

        Project project = null;
        boolean projectChanged = false;
        if (edit.projectId != null) {
            int currentProjectId = action == null ? 0 : action.getProjectId();
            projectChanged = creating || !existingTemplate || edit.projectId.intValue() != currentProjectId;
            if (edit.projectId.intValue() > 0) {
                project = (Project) session.get(Project.class, edit.projectId);
                if (project == null || project.getWorkspaceId() == null
                        || project.getWorkspaceId().intValue() != owner.workspaceId) {
                    throw new TemplateEditException("not_found", "Project #" + edit.projectId + " not found.");
                }
                if (projectChanged) {
                    checkProjectFitsMode(session, owner, project, personal);
                }
            }
        }

        AgentTemplateFields agentFields = null;
        if (edit.agentSent) {
            try {
                agentFields = AgentTemplateFields.of(edit.agent, edit.agentHandoffMode,
                        edit.agentHandoffTemplateId == null ? null : String.valueOf(edit.agentHandoffTemplateId),
                        edit.agentHandoffProjectId == null ? null : String.valueOf(edit.agentHandoffProjectId),
                        edit.agentWorkloadKey, owner.workspaceId, session);
            } catch (IllegalArgumentException invalid) {
                throw new TemplateEditException("invalid_arguments", invalid.getMessage());
            }
        }

        if (action != null && action.isWillMeet() && action.getActionNextId() > 0
                && new LinkedMeetingDao(session).findByAction(action.getWorkspaceId(),
                        action.getActionNextId()) != null) {
            throw new TemplateEditException("invalid_arguments",
                    "Explicitly unlink the meeting through MCP before converting this action to a template.");
        }

        // ---- Write ----
        Saved saved = new Saved();
        saved.created = creating;
        saved.previousNotes = action == null || !existingTemplate ? null : n(action.getNextNotes());
        Date now = truncatedNow();
        if (creating) {
            // contact_id is mapped both as a scalar and as a non-insertable many-to-one, so the
            // association must be set or Hibernate nulls the column back out on flush.
            ProjectContact contact = owner.contact;
            if (contact == null) {
                contact = (ProjectContact) session.get(ProjectContact.class, Integer.valueOf(owner.contactId));
            }
            action = new ActionNext();
            action.setWorkspaceId(Integer.valueOf(owner.workspaceId));
            action.setContact(contact);
            action.setNextProjectContact(contact);
            action.setBillable(!personal);
            action.setNextActionStatus(ProjectNextActionStatus.READY);
        }
        action.setTemplateType(templateType);
        action.setNextDescription(description);
        action.setNextTimeEstimate(estimate);
        action.setLinkUrl(linkUrl);
        if (agentFields != null) {
            action.setPerformedByAgent(agentFields.agentKey);
        }
        if (personal) {
            action.setTimeSlot(timeSlot);
            action.setNextActionType(ProjectNextActionType.WILL);
        } else {
            action.setNextActionType(nextActionType);
        }
        if (edit.projectId != null) {
            if (project != null) {
                action.setProject(project);
            } else {
                action.setProject(null);
                action.setProjectId(0);
            }
        }
        if (edit.notes != null) {
            if (creating) {
                actionNotesService.initialize(action, edit.notes);
            } else {
                ActionNotesService.Result notesResult = actionNotesService.replace(session, action, edit.notes,
                        null, null);
                if (!notesResult.isOk()) {
                    throw new TemplateEditException("invalid_arguments", notesResult.getErrorMessage());
                }
            }
        }
        action.setNextChangeDate(now);
        if (creating) {
            session.save(action);
        } else {
            session.update(action);
        }
        int templateId = action.getActionNextId();
        saved.templateId = templateId;

        boolean newConfig = config == null;
        if (newConfig) {
            config = new ActionNextTemplateConfig();
            config.setActionNextId(templateId);
        }
        config.setAutoGenerate(autoGenerate);
        config.setMissedActionBehavior(missed);
        config.setScheduleDaysOfWeek(emptyToNull(daysOfWeek));
        config.setScheduleDaysOfMonth(emptyToNull(daysOfMonth));
        config.setScheduleDaysOfQuarter(emptyToNull(daysOfQuarter));
        config.setScheduleDaysOfYear(emptyToNull(daysOfYear));
        if (newConfig) {
            session.save(config);
        } else {
            session.update(config);
        }
        if (agentFields != null) {
            agentFields.save(session, templateId);
        }
        saved.perTargetAgent = AgentTemplateFields.isPerTarget(session, templateId);
        if (saved.perTargetAgent && config.isAutoGenerate()) {
            // Its preps come from the other template's occurrences, never from its own schedule.
            config.setAutoGenerate(false);
            session.update(config);
        }
        return saved;
    }

    // =========================================================================
    // Sync, delete, reactivate
    // =========================================================================

    /**
     * Cascades a saved template to its actions. A per-target agent template has nothing to sync:
     * syncing would cancel the preps Dandelion made for each occurrence of the other template.
     */
    public TemplateSyncCounts sync(Session session, Owner owner, Saved saved) {
        if (saved.perTargetAgent) {
            return new TemplateSyncCounts();
        }
        ActionNext template = (ActionNext) session.get(ActionNext.class, Integer.valueOf(saved.templateId));
        if (template == null) {
            return new TemplateSyncCounts();
        }
        return new TemplateGenerationService().syncAfterEdit(session, template, owner.workspaceId,
                owner.contactId, owner.today, advanceDays(session), saved.previousNotes);
    }

    /**
     * Deletes a template: it is cancelled, and so are its open actions from today on (today's is
     * kept when time has been billed to it). Completed and past actions are left alone.
     */
    public TemplateSyncCounts delete(Session session, Owner owner, int templateId) {
        ActionNext template = requireTemplate(session, owner, templateId);
        template.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
        template.setNextChangeDate(truncatedNow());
        session.update(template);
        return new TemplateGenerationService().cancelFutureInstances(session, template, owner.today);
    }

    /**
     * Brings a template back: a deleted one is restored, and auto-generate is turned on. Follow
     * with {@link #sync} to generate its upcoming actions.
     */
    public Saved reactivate(Session session, Owner owner, int templateId) {
        ActionNext template = requireTemplate(session, owner, templateId);
        Saved saved = new Saved();
        saved.templateId = templateId;
        if (ProjectNextActionStatus.CANCELLED.equals(template.getNextActionStatus())) {
            template.setNextActionStatus(ProjectNextActionStatus.READY);
        }
        template.setNextChangeDate(truncatedNow());
        session.update(template);
        ActionNextTemplateConfig config = (ActionNextTemplateConfig) session.get(ActionNextTemplateConfig.class,
                Integer.valueOf(templateId));
        boolean newConfig = config == null;
        if (newConfig) {
            config = new ActionNextTemplateConfig();
            config.setActionNextId(templateId);
            config.setMissedActionBehavior(MISSED_AUTO_CANCEL);
        }
        saved.perTargetAgent = AgentTemplateFields.isPerTarget(session, templateId);
        config.setAutoGenerate(!saved.perTargetAgent);
        if (newConfig) {
            session.save(config);
        } else {
            session.update(config);
        }
        return saved;
    }

    /** A template root of this workspace's owner, deleted or not; otherwise not_found. */
    public ActionNext requireTemplate(Session session, Owner owner, int templateId) {
        ActionNext template = (ActionNext) session.get(ActionNext.class, Integer.valueOf(templateId));
        if (template == null || template.getWorkspaceId() == null
                || template.getWorkspaceId().intValue() != owner.workspaceId || !isTemplateRoot(template)) {
            throw new TemplateEditException("not_found", "Template #" + templateId + " not found.");
        }
        requireOwnTemplate(template, owner);
        return template;
    }

    /** The advance window, in days, templates are generated over (a global key; 14 by default). */
    public static int advanceDays(Session session) {
        try {
            return Integer.parseInt(TrackerKeysManager.getKeyValue(TrackerKeysManager.KEY_TEMPLATE_ADVANCE_DAYS,
                    TrackerKeysManager.KEY_TYPE_GLOBAL, TrackerKeysManager.KEY_ID_GLOBAL, "14", session).trim());
        } catch (NumberFormatException nfe) {
            return 14;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    static boolean isTemplateRoot(ActionNext action) {
        return action.getTemplateType() != null
                && (action.getTemplateActionNextId() == null || action.getTemplateActionNextId().intValue() == 0);
    }

    private static void requireOwnTemplate(ActionNext template, Owner owner) {
        Integer contactId = template.getContactId();
        Integer nextContactId = template.getNextContactId();
        boolean own = (contactId != null && contactId.intValue() == owner.contactId)
                || (nextContactId != null && nextContactId.intValue() == owner.contactId);
        if (!own) {
            throw new TemplateEditException("not_found", "Template #" + template.getActionNextId() + " not found.");
        }
    }

    /** Work templates go on billable projects and personal templates on the others, as on the Templates page. */
    private void checkProjectFitsMode(Session session, Owner owner, Project project, boolean personal) {
        String status = project.getProjectStatus();
        if (status != null && !ProjectStatus.ACTIVE.getDatabaseValue().equals(status)) {
            throw new TemplateEditException("invalid_arguments",
                    "Project #" + project.getProjectId() + " is not active.");
        }
        boolean billable = isBillableProject(session, owner.workspaceId, project);
        if (personal && billable) {
            throw new TemplateEditException("invalid_arguments", "Project #" + project.getProjectId()
                    + " is a work (billable) project; a personal template needs a personal project.");
        }
        if (!personal && !billable) {
            throw new TemplateEditException("invalid_arguments", "Project #" + project.getProjectId()
                    + " is a personal (non-billable) project; a work template needs a billable project.");
        }
    }

    static boolean isBillableProject(Session session, int workspaceId, Project project) {
        String code = project.getBillCode();
        if (code == null || code.trim().length() == 0) {
            return false;
        }
        Query query = session.createQuery("from BillCode where workspaceId = :workspaceId and id.billCode = :billCode");
        query.setParameter("workspaceId", Integer.valueOf(workspaceId));
        query.setParameter("billCode", code.trim());
        query.setMaxResults(1);
        BillCode billCode = (BillCode) query.uniqueResult();
        return billCode != null && "Y".equals(billCode.getBillable());
    }

    /**
     * A schedule pattern for the template's type must name at least one date in the next three
     * years; a typo otherwise leaves a template that silently never generates.
     */
    void checkPattern(TemplateType type, String daysOfWeek, String daysOfMonth, String daysOfQuarter,
            String daysOfYear, LocalDate today) {
        String pattern;
        String field;
        switch (type) {
            case WEEKLY:
                pattern = daysOfWeek;
                field = "scheduleDaysOfWeek";
                break;
            case MONTHLY:
                pattern = daysOfMonth;
                field = "scheduleDaysOfMonth";
                break;
            case QUARTERLY:
                pattern = daysOfQuarter;
                field = "scheduleDaysOfQuarter";
                break;
            case YEARLY:
                pattern = daysOfYear;
                field = "scheduleDaysOfYear";
                break;
            default:
                return;
        }
        if (pattern == null || pattern.trim().length() == 0) {
            return;
        }
        // The evaluator skips tokens it can't read, so check each one on its own.
        for (String token : pattern.split(",")) {
            String t = token.trim();
            if (t.length() == 0) {
                continue;
            }
            if (patternEvaluator.matchingDates(type, t, today, today.plusYears(3)).isEmpty()) {
                throw new TemplateEditException("invalid_arguments", field + ": \"" + t
                        + "\" isn't a schedule day Dandelion understands (e.g. MON for weekly, 15 or W1-MON or "
                        + "WL-FRI for monthly, 46 or W13-FRI for quarterly, 1225 for yearly).");
            }
        }
    }

    private static void checkLength(String value, int max, String field, String example) {
        if (value.length() > max) {
            throw new TemplateEditException("invalid_arguments",
                    field + " is limited to " + max + " characters" + example + ".");
        }
    }

    private static long toSeconds(Date date) {
        return date.getTime() / 1000L;
    }

    /** MySQL drops fractional seconds, so a change date echoed back must not carry them. */
    private static Date truncatedNow() {
        return new Date((System.currentTimeMillis() / 1000L) * 1000L);
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 ? null : value;
    }

    private static String n(String value) {
        return value == null ? "" : value;
    }
}
