package org.dandeliondaily.dashboard.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSet;
import org.openimmunizationsoftware.pt.model.ActionSetType;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

public class ActionSentenceImportService {

    private final QuickCaptureLinkedProjectService quickCaptureLinkedProjectService = new QuickCaptureLinkedProjectService();
    private final QuickCaptureProjectTokenService projectTokenService = new QuickCaptureProjectTokenService();

    public int importActionsFromText(WebUser webUser, Session dataSession, Project defaultProject,
            List<Project> projectList, String bulkImportText) {
        if (bulkImportText == null || bulkImportText.trim().length() == 0) {
            return 0;
        }
        int importedCount = 0;
        String[] lines = bulkImportText.split("\\r?\\n");
        for (String line : lines) {
            if (line == null || line.trim().length() == 0) {
                continue;
            }
            ActionNext created = saveNewActionFromSentence(webUser, dataSession, defaultProject, projectList,
                    line);
            if (created != null) {
                importedCount++;
            }
        }
        return importedCount;
    }

    public ActionNext saveNewActionFromSentence(WebUser webUser, Session dataSession,
            Project defaultProject, List<Project> projectList, String sentenceInput) {
        List<ActionNext> savedActions = saveNewActionsFromSentence(webUser, dataSession, defaultProject, projectList,
                sentenceInput);
        return savedActions.isEmpty() ? null : savedActions.get(0);
    }

    public List<ActionNext> saveNewActionsFromSentence(WebUser webUser, Session dataSession,
            Project defaultProject, List<Project> projectList, String sentenceInput) {
        List<ActionNext> savedActions = new ArrayList<ActionNext>();
        if (sentenceInput == null || sentenceInput.trim().length() == 0) {
            return savedActions;
        }

        String[] parts = sentenceInput.split(":", 2);
        String projectToken = parts.length == 2 ? parts[0].trim() : "";
        String actionPart = parts.length == 2 ? parts[1].trim() : sentenceInput.trim();

        QuickCaptureLinkedProjectService.LinkedAliasResolution aliasResolution = null;
        if (projectToken.length() > 0
                && (projectToken.contains("/") || projectToken.contains("-"))
                && projectList != null) {
            aliasResolution = quickCaptureLinkedProjectService.resolveAliasLabel(dataSession, projectList,
                    projectToken);
        }

        Transaction trans = dataSession.beginTransaction();
        if (aliasResolution != null) {
            ActionSet sharedActionSet = null;
            for (Project targetProject : aliasResolution.getTargetProjects()) {
                if (targetProject == null || targetProject.getWorkspaceId() == null) {
                    continue;
                }
                ActionNext nextAction = buildActionFromSentence(webUser, dataSession, targetProject, null,
                        actionPart, targetProject.getWorkspaceId(), false);
                if (nextAction == null) {
                    continue;
                }
                if (sharedActionSet == null) {
                    sharedActionSet = new ActionSetDao(dataSession).createActionSet(webUser, ActionSetType.SHARED);
                }
                nextAction.setActionSet(sharedActionSet);
                dataSession.saveOrUpdate(nextAction);
                savedActions.add(nextAction);
            }
            if (savedActions.isEmpty()) {
                trans.rollback();
                return savedActions;
            }
            trans.commit();
            return savedActions;
        }

        ActionNext nextAction = buildActionFromSentence(webUser, dataSession, defaultProject, projectList,
                sentenceInput, null);
        if (nextAction == null) {
            trans.rollback();
            return savedActions;
        }
        dataSession.saveOrUpdate(nextAction);
        trans.commit();
        savedActions.add(nextAction);
        return savedActions;
    }

    /**
     * True when the sentence starts with a "project: " prefix that names a
     * project in {@code projectList} or a linked-project alias, so
     * saveNewActionsFromSentence would not use the default project.
     */
    public boolean startsWithProjectToken(Session dataSession, List<Project> projectList, String sentenceInput) {
        if (sentenceInput == null || projectList == null) {
            return false;
        }
        String[] parts = sentenceInput.split(":", 2);
        if (parts.length != 2) {
            return false;
        }
        String projectToken = parts[0].trim();
        if (projectToken.length() == 0) {
            return false;
        }
        if ((projectToken.contains("/") || projectToken.contains("-"))
                && quickCaptureLinkedProjectService.resolveAliasLabel(dataSession, projectList, projectToken) != null) {
            return true;
        }
        return projectTokenService.findFirstByToken(projectList, projectToken) != null;
    }

    public ActionNext buildActionFromSentence(WebUser webUser, Session dataSession,
            Project defaultProject, List<Project> projectList, String sentenceInput, Integer workspaceIdOverride) {
        return buildActionFromSentence(webUser, dataSession, defaultProject, projectList, sentenceInput,
                workspaceIdOverride, true);
    }

    public ActionNext buildActionFromSentenceForProject(WebUser webUser, Session dataSession,
            Project defaultProject, String sentenceInput, Integer workspaceIdOverride) {
        return buildActionFromSentence(webUser, dataSession, defaultProject, null, sentenceInput,
                workspaceIdOverride, true, true);
    }

    public ActionNext buildActionFromSentenceForProject(WebUser webUser, Session dataSession,
            Project defaultProject, String sentenceInput, Integer workspaceIdOverride,
            boolean assignStandardActionSet) {
        return buildActionFromSentence(webUser, dataSession, defaultProject, null, sentenceInput,
                workspaceIdOverride, assignStandardActionSet, true);
    }

    public QuickCaptureActorResolution resolveProjectScopedActorForQuickCapture(WebUser webUser, Session dataSession,
            Project project, String actionPart) {
        return resolveProjectScopedActor(webUser, dataSession, project, actionPart);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private ActionNext buildActionFromSentence(WebUser webUser, Session dataSession,
            Project defaultProject, List<Project> projectList, String sentenceInput, Integer workspaceIdOverride,
            boolean assignStandardActionSet) {
        return buildActionFromSentence(webUser, dataSession, defaultProject, projectList, sentenceInput,
                workspaceIdOverride, assignStandardActionSet, false);
    }

    private ActionNext buildActionFromSentence(WebUser webUser, Session dataSession,
            Project defaultProject, List<Project> projectList, String sentenceInput, Integer workspaceIdOverride,
            boolean assignStandardActionSet, boolean projectScoped) {
        if (sentenceInput == null || sentenceInput.trim().length() == 0) {
            return null;
        }
        UrlExtractionResult urlResult = extractAndRemoveUrl(sentenceInput);
        String extractedUrl = urlResult.extractedUrl;
        sentenceInput = urlResult.cleanedText;

        String projectToken = "";
        String actionPart = sentenceInput;
        if (!projectScoped) {
            String[] parts = sentenceInput.split(":", 2);
            if (parts.length == 2) {
                projectToken = parts[0].trim();
                actionPart = parts[1].trim();
            }
        }

        Project foundProject = null;
        if (!projectScoped && projectToken.length() > 0) {
            foundProject = projectTokenService.findFirstByToken(projectList, projectToken);
        }
        if (foundProject == null) {
            if (defaultProject == null) {
                return null;
            }
            foundProject = defaultProject;
            if (!projectScoped && projectToken.length() > 0) {
                actionPart = projectToken + " " + actionPart;
            }
        }

        ProjectContact actorContact = webUser.getProjectContact();
        if (projectScoped) {
            QuickCaptureActorResolution actorResolution = resolveProjectScopedActor(webUser, dataSession,
                    foundProject, actionPart);
            if (actorResolution != null) {
                if (actorResolution.isUnknownNamedContact()) {
                    throw new IllegalArgumentException("Unknown workspace contact: "
                            + actorResolution.getUnknownNamedContact());
                }
                actionPart = actorResolution.getNormalizedActionPart();
                actorContact = actorResolution.getContact();
            }
        }

        String actionVerb = "I will";
        String actionToTake = actionPart;
        String whenToTakeAction = "";
        int nextTimeEstimate = 20;
        if (startsWithIgnoreCase(actionPart, "I will meet ")) {
            actionVerb = "I will meet";
            actionToTake = actionPart.substring("I will meet ".length()).trim();
            nextTimeEstimate = 60;
        } else if (startsWithIgnoreCase(actionPart, "I will ")) {
            actionVerb = "I will";
            actionToTake = actionPart.substring("I will ".length()).trim();
        } else if (startsWithIgnoreCase(actionPart, "I might ")) {
            actionVerb = "I might";
            actionToTake = actionPart.substring("I might ".length()).trim();
        } else if (startsWithIgnoreCase(actionPart, "I would like to ")
                || actionPart.equalsIgnoreCase("I would like to")
                || startsWithIgnoreCase(actionPart, "I would like to:")) {
            actionVerb = "I would like to";
            actionToTake = actionPart.substring("I would like to".length()).trim();
            if (actionToTake.startsWith(":")) {
                actionToTake = actionToTake.substring(1).trim();
            }
        } else if (startsWithIgnoreCase(actionPart, "I have committed ")) {
            actionVerb = "I have committed";
            actionToTake = actionPart.substring("I have committed ".length()).trim();
        } else if (startsWithIgnoreCase(actionPart, "I have set goal to")) {
            actionVerb = "I have set goal to";
            actionToTake = actionPart.substring("I have set goal to".length()).trim();
        } else if (startsWithIgnoreCase(actionPart, "I am waiting ") || actionPart.equalsIgnoreCase("I am waiting")
                || startsWithIgnoreCase(actionPart, "I am waiting:")) {
            actionVerb = "I am waiting";
            actionToTake = actionPart.substring("I am waiting".length()).trim();
            if (actionToTake.startsWith(":")) {
                actionToTake = actionToTake.substring(1).trim();
            }
            nextTimeEstimate = 5;
        }

        String[] tokens = actionToTake.trim().split("\\s+");
        if (tokens.length >= 1) {
            String lastToken = tokens[tokens.length - 1];
            String secondLastToken = tokens.length >= 2 ? tokens[tokens.length - 2] : "";
            if (tokens.length > 3) {
                String thirdLastToken = tokens.length >= 3 ? tokens[tokens.length - 3] : "";
                if (thirdLastToken.equals("for") && isNumeric(secondLastToken)) {
                    try {
                        nextTimeEstimate = Integer.parseInt(secondLastToken);
                    } catch (NumberFormatException e) {
                        nextTimeEstimate = 20;
                    }
                    if (lastToken.equals("hours") || lastToken.equals("hour")) {
                        nextTimeEstimate *= 60;
                    }
                    actionToTake = String.join(" ", Arrays.copyOf(tokens, tokens.length - 3)).trim();
                    tokens = actionToTake.trim().split("\\s+");
                    lastToken = tokens.length >= 1 ? tokens[tokens.length - 1] : "";
                    secondLastToken = tokens.length >= 2 ? tokens[tokens.length - 2] : "";
                }
            }

            boolean foundDate = false;
            if (lastToken.chars().filter(ch -> ch == '/').count() == 2) {
                whenToTakeAction = lastToken;
                foundDate = true;
            } else {
                String lower = lastToken.toLowerCase();
                if (lower.equals("today") || lower.equals("tomorrow")) {
                    whenToTakeAction = lastToken;
                    foundDate = true;
                } else {
                    String[] days = { "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday" };
                    for (String day : days) {
                        if (lower.equals(day)) {
                            if (secondLastToken.equalsIgnoreCase("next")) {
                                whenToTakeAction = "next " + lastToken;
                                actionToTake = String.join(" ", Arrays.copyOf(tokens, tokens.length - 2)).trim();
                            } else {
                                whenToTakeAction = lastToken;
                                actionToTake = String.join(" ", Arrays.copyOf(tokens, tokens.length - 1)).trim();
                            }
                            foundDate = true;
                            break;
                        }
                    }
                }
            }
            if (foundDate && whenToTakeAction != null && actionToTake.endsWith(whenToTakeAction)) {
                actionToTake = actionToTake.substring(0, actionToTake.length() - whenToTakeAction.length()).trim();
            }
        }

        ActionNext nextAction = new ActionNext();
        nextAction.setProject(foundProject);
        nextAction.setProjectId(foundProject.getProjectId());
        nextAction.setContact(actorContact);
        if (actorContact == null) {
            nextAction.setContactId(null);
        }
        Date actionDate = parseWhenToTakeAction(webUser, whenToTakeAction);
        if (actionVerb.equals("I will")) {
            nextAction.setNextActionType(ProjectNextActionType.WILL);
        } else if (actionVerb.equals("I might")) {
            nextAction.setNextActionType(ProjectNextActionType.MIGHT);
        } else if (actionVerb.equals("I would like to")) {
            nextAction.setNextActionType(ProjectNextActionType.WOULD_LIKE_TO);
        } else if (actionVerb.equals("I have committed")) {
            nextAction.setNextActionType(ProjectNextActionType.COMMITTED_TO);
        } else if (actionVerb.equals("I will meet")) {
            nextAction.setNextActionType(ProjectNextActionType.WILL_MEET);
        } else if (actionVerb.equals("I have set goal to")) {
            nextAction.setNextActionType(ProjectNextActionType.GOAL);
        } else if (actionVerb.equals("I am waiting")) {
            nextAction.setNextActionType(ProjectNextActionType.WAITING);
        } else {
            nextAction.setNextActionType(ProjectNextActionType.WILL);
        }
        if (ProjectNextActionType.WOULD_LIKE_TO.equals(nextAction.getNextActionType())
                && (whenToTakeAction == null || whenToTakeAction.isEmpty())) {
            actionDate = null;
        }
        nextAction.setNextActionDate(actionDate);
        nextAction.setNextDescription(actionToTake);
        nextAction.setNextTimeEstimate(nextTimeEstimate);
        nextAction.setNextChangeDate(new Date());
        Integer workspaceId = workspaceIdOverride != null ? workspaceIdOverride
                : WorkspaceRegistry.getWorkspaceIdForWebUserId(dataSession, webUser.getWebUserId());
        nextAction.setWorkspaceId(workspaceId);
        nextAction.setContact(actorContact);
        nextAction.setBillable(resolveBillable(dataSession, foundProject));
        if (extractedUrl != null && extractedUrl.length() > 0) {
            nextAction.setLinkUrl(extractedUrl);
        }
        defaultPersonalTimeSlot(nextAction);
        if (nextAction.getNextActionStatus() == null) {
            if (nextAction.hasNextDescription()) {
                if (nextAction.hasNextActionDate()) {
                    nextAction.setNextActionStatus(ProjectNextActionStatus.READY);
                } else {
                    nextAction.setNextActionStatus(ProjectNextActionStatus.PROPOSED);
                }
            }
        }
        if (assignStandardActionSet) {
            nextAction.setActionSet(new ActionSetDao(dataSession).createStandardActionSet(webUser));
        }
        return nextAction;
    }

    private QuickCaptureActorResolution resolveProjectScopedActor(WebUser webUser, Session dataSession,
            Project project, String actionPart) {
        if (webUser == null || dataSession == null || project == null || project.getWorkspaceId() == null
                || actionPart == null) {
            return null;
        }

        String trimmed = actionPart.trim();
        if (trimmed.length() == 0) {
            return null;
        }

        if (trimmed.equalsIgnoreCase("I") || trimmed.toLowerCase().startsWith("i ")) {
            return new QuickCaptureActorResolution(QuickCaptureActorKind.CURRENT_USER,
                    webUser.getProjectContact(), trimmed, null);
        }

        String[] tokens = trimmed.split("\\s+", 3);
        if (tokens.length < 2) {
            return new QuickCaptureActorResolution(QuickCaptureActorKind.IMPLICIT_CURRENT_USER,
                    webUser.getProjectContact(), trimmed, null);
        }

        String actorToken = tokens[0].trim();
        String verbToken = tokens[1].trim();
        if (!verbToken.equalsIgnoreCase("will") && !verbToken.equalsIgnoreCase("have")
                && !verbToken.equalsIgnoreCase("has") && !verbToken.equalsIgnoreCase("am")
                && !verbToken.equalsIgnoreCase("is") && !verbToken.equalsIgnoreCase("might")
                && !verbToken.equalsIgnoreCase("would")) {
            return new QuickCaptureActorResolution(QuickCaptureActorKind.IMPLICIT_CURRENT_USER,
                    webUser.getProjectContact(), trimmed, null);
        }

        if (actorToken.equalsIgnoreCase("Someone")) {
            String normalized = normalizeActorPrefixedAction(actorToken, trimmed);
            return new QuickCaptureActorResolution(QuickCaptureActorKind.SOMEONE,
                    null, normalized, null);
        }

        Query query = dataSession.createQuery(
                "from ProjectContact where workspaceId = :workspaceId and contactStatus = :contactStatus order by nameFirst, nameLast");
        query.setParameter("workspaceId", project.getWorkspaceId());
        query.setParameter("contactStatus", ProjectContact.STATUS_ACTIVE);
        @SuppressWarnings("unchecked")
        List<ProjectContact> contacts = query.list();
        for (ProjectContact contact : contacts) {
            if (contact != null && contact.getNameFirst() != null
                    && contact.getNameFirst().trim().equalsIgnoreCase(actorToken)) {
                String normalized = normalizeActorPrefixedAction(actorToken, trimmed);
                return new QuickCaptureActorResolution(QuickCaptureActorKind.NAMED_CONTACT,
                        contact, normalized, null);
            }
        }

        return new QuickCaptureActorResolution(QuickCaptureActorKind.UNKNOWN_NAMED_CONTACT,
                null, trimmed, actorToken);
    }

    private String normalizeActorPrefixedAction(String actorToken, String trimmed) {
        String afterActor = trimmed.substring(actorToken.length()).trim();
        String lowerAfterActor = afterActor.toLowerCase();
        if (lowerAfterActor.startsWith("has committed ")) {
            return "I have committed " + afterActor.substring("has committed ".length()).trim();
        }
        if (lowerAfterActor.startsWith("have committed ")) {
            return "I have committed " + afterActor.substring("have committed ".length()).trim();
        }
        if (lowerAfterActor.startsWith("has set goal to ")) {
            return "I have set goal to " + afterActor.substring("has set goal to ".length()).trim();
        }
        if (lowerAfterActor.startsWith("have set goal to ")) {
            return "I have set goal to " + afterActor.substring("have set goal to ".length()).trim();
        }
        if (lowerAfterActor.startsWith("is waiting")) {
            return "I am waiting" + afterActor.substring("is waiting".length());
        }
        if (lowerAfterActor.startsWith("am waiting")) {
            return "I am waiting" + afterActor.substring("am waiting".length());
        }
        if (lowerAfterActor.startsWith("would like to")) {
            return "I would like to" + afterActor.substring("would like to".length());
        }
        if (lowerAfterActor.startsWith("will ")) {
            return "I will " + afterActor.substring("will ".length()).trim();
        }
        if (lowerAfterActor.startsWith("might ")) {
            return "I might " + afterActor.substring("might ".length()).trim();
        }
        return "I " + afterActor;
    }

    public static enum QuickCaptureActorKind {
        IMPLICIT_CURRENT_USER,
        CURRENT_USER,
        SOMEONE,
        NAMED_CONTACT,
        UNKNOWN_NAMED_CONTACT
    }

    public static class QuickCaptureActorResolution {
        private final QuickCaptureActorKind actorKind;
        private final ProjectContact contact;
        private final String normalizedActionPart;
        private final String unknownNamedContact;

        public QuickCaptureActorResolution(QuickCaptureActorKind actorKind, ProjectContact contact,
                String normalizedActionPart,
                String unknownNamedContact) {
            this.actorKind = actorKind;
            this.contact = contact;
            this.normalizedActionPart = normalizedActionPart;
            this.unknownNamedContact = unknownNamedContact;
        }

        public QuickCaptureActorKind getActorKind() {
            return actorKind;
        }

        public ProjectContact getContact() {
            return contact;
        }

        public String getNormalizedActionPart() {
            return normalizedActionPart;
        }

        public boolean isUnknownNamedContact() {
            return unknownNamedContact != null && unknownNamedContact.trim().length() > 0;
        }

        public String getUnknownNamedContact() {
            return unknownNamedContact;
        }
    }

    private boolean resolveBillable(Session dataSession, Project project) {
        if (project == null || project.getBillCode() == null || project.getBillCode().equals("")) {
            return false;
        }
        BillCode billCode = ClientServlet.resolveBillCode(dataSession, project);
        return billCode != null && "Y".equalsIgnoreCase(billCode.getBillable());
    }

    private Date parseWhenToTakeAction(WebUser webUser, String whenToTakeAction) {
        Date actionDate = webUser.getCalendar().getTime();
        if (whenToTakeAction == null || whenToTakeAction.length() == 0) {
            return actionDate;
        }

        Calendar calendar = webUser.getCalendar();
        String lower = whenToTakeAction.trim().toLowerCase();
        String[] days = { "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday" };
        boolean isNext = lower.startsWith("next ");
        String dayName = isNext ? lower.substring(5).trim() : lower;
        int dayOfWeek = -1;
        for (int i = 0; i < days.length; i++) {
            if (days[i].equals(dayName)) {
                dayOfWeek = i + 1;
                break;
            }
        }
        if (lower.equals("today")) {
            // keep today
        } else if (lower.equals("tomorrow")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        } else if (dayOfWeek != -1) {
            int currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
            int daysUntil = dayOfWeek - currentDayOfWeek;
            if (isNext) {
                if (daysUntil <= 0) {
                    daysUntil += 7;
                }
                daysUntil += 7;
            } else if (daysUntil < 0) {
                daysUntil += 7;
            }
            calendar.add(Calendar.DAY_OF_YEAR, daysUntil);
        } else {
            try {
                Date parsedDate = webUser.getDateFormat().parse(whenToTakeAction);
                calendar.setTime(parsedDate);
            } catch (Exception e) {
                // leave current date as fallback
            }
        }
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        actionDate = calendar.getTime();
        return actionDate;
    }

    private void defaultPersonalTimeSlot(ActionNext projectAction) {
        if (projectAction != null && !projectAction.isBillable() && projectAction.getTimeSlot() == null) {
            projectAction.setTimeSlot(TimeSlot.AFTERNOON);
        }
    }

    private UrlExtractionResult extractAndRemoveUrl(String text) {
        if (text == null || text.isEmpty()) {
            return new UrlExtractionResult(text, null);
        }
        int urlStartIndex = text.indexOf("https://");
        if (urlStartIndex == -1) {
            return new UrlExtractionResult(text, null);
        }
        int urlEndIndex = text.indexOf(' ', urlStartIndex);
        if (urlEndIndex == -1) {
            urlEndIndex = text.length();
        }
        String extractedUrl = text.substring(urlStartIndex, urlEndIndex);
        String cleanedText = text.substring(0, urlStartIndex) + text.substring(urlEndIndex);
        cleanedText = cleanedText.trim().replaceAll("\\s+", " ");
        return new UrlExtractionResult(cleanedText, extractedUrl);
    }

    private static class UrlExtractionResult {
        private final String cleanedText;
        private final String extractedUrl;

        private UrlExtractionResult(String cleanedText, String extractedUrl) {
            this.cleanedText = cleanedText;
            this.extractedUrl = extractedUrl;
        }
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (char c : str.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }
}