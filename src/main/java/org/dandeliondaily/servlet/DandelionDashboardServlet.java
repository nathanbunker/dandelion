package org.dandeliondaily.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.dandeliondaily.dashboard.model.DashboardNowColumnModel;
import org.dandeliondaily.dashboard.model.DashboardNextColumnModel;
import org.dandeliondaily.dashboard.model.ProjectDashboardChatMessage;
import org.dandeliondaily.dashboard.model.ProjectDashboardChatState;
import org.dandeliondaily.dashboard.model.ProjectLanguageReviewChatState;
import org.dandeliondaily.dashboard.model.ProjectNextActionsChatState;
import org.dandeliondaily.dashboard.model.ProjectDashboardSuggestedAction;
import org.dandeliondaily.dashboard.model.ProjectDashboardSuggestedIssue;
import org.dandeliondaily.dashboard.model.ProjectDashboardSuggestedNarrative;
import org.dandeliondaily.dashboard.model.DashboardTodayColumnModel;
import org.dandeliondaily.dashboard.model.TimeGaugeModel;
import org.dandeliondaily.dashboard.render.DashboardPageRenderer.DashboardLayoutMode;
import org.dandeliondaily.dashboard.render.DashboardPageRenderer;
import org.dandeliondaily.dashboard.render.TimeGaugeRenderer;
import org.dandeliondaily.dashboard.service.DashboardCurrentActionService;
import org.dandeliondaily.dashboard.service.ActionRecoveryService;
import org.dandeliondaily.dashboard.service.DashboardNowColumnService;
import org.dandeliondaily.dashboard.service.DashboardNextColumnService;
import org.dandeliondaily.dashboard.service.ProjectDisplayLabelService;
import org.dandeliondaily.dashboard.service.DashboardTimeGaugeService;
import org.dandeliondaily.dashboard.service.DashboardTodayColumnService;
import org.dandeliondaily.dashboard.service.ProjectDashboardAiContextService;
import org.dandeliondaily.planahead.service.PlanAheadDayCapacityService;
import org.dandeliondaily.projecthealth.service.ProjectHealthPageService;
import org.dandeliondaily.projectnarrative.model.ProjectNarrativeEntry;
import org.dandeliondaily.projectnarrative.service.ProjectNarrativeService;
import org.dandeliondaily.shared.service.ActionCompletionService;
import org.dandeliondaily.shared.service.ActionNotesService;
import org.dandeliondaily.shared.service.LinkedMeetingService;
import org.dandeliondaily.shared.service.SharedActionSiblings;
import org.dandeliondaily.shared.service.ProjectIconService;
import org.openimmunizationsoftware.pt.AppReq;
import org.openimmunizationsoftware.pt.WorkspaceRegistry;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ProjectContactAssigned;
import org.openimmunizationsoftware.pt.model.ProjectContactAssignedId;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.ProjectTag;
import org.openimmunizationsoftware.pt.model.ProjectTagMap;
import org.openimmunizationsoftware.pt.model.ProjectIssueType;
import org.openimmunizationsoftware.pt.model.ProjectNarrativeVerb;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.model.Workspace;
import org.openimmunizationsoftware.pt.doa.ActionSetDao;
import org.openimmunizationsoftware.pt.manager.ProjectReviewChatResponse;
import org.openimmunizationsoftware.pt.manager.ProjectReviewChatService;
import org.openimmunizationsoftware.pt.manager.ProjectReviewChatService.ChatMode;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;
import org.openimmunizationsoftware.pt.servlet.HandleValidationSupport;

public class DandelionDashboardServlet extends ClientServlet {

    private static final long serialVersionUID = 6049052526445852256L;

    private final DashboardPageRenderer dashboardPageRenderer = new DashboardPageRenderer();
    private final DashboardNowColumnService dashboardNowColumnService = new DashboardNowColumnService();
    private final DashboardTodayColumnService dashboardTodayColumnService = new DashboardTodayColumnService();
    private final DashboardCurrentActionService dashboardCurrentActionService = new DashboardCurrentActionService();
    private final ActionCompletionService actionCompletionService = new ActionCompletionService();
    private final ActionNotesService actionNotesService = new ActionNotesService();
    private final ActionRecoveryService actionRecoveryService = new ActionRecoveryService();
    private final DashboardTimeGaugeService dashboardTimeGaugeService = new DashboardTimeGaugeService();
    private final DashboardNextColumnService dashboardNextColumnService = new DashboardNextColumnService();
    private final ProjectDisplayLabelService projectDisplayLabelService = new ProjectDisplayLabelService();
    private final PlanAheadDayCapacityService dayCapacityService = new PlanAheadDayCapacityService();
    private final ProjectNarrativeService projectNarrativeService = new ProjectNarrativeService();
    private final ProjectHealthPageService projectHealthPageService = new ProjectHealthPageService();
    private final ProjectDashboardAiContextService projectDashboardAiContextService = new ProjectDashboardAiContextService();
    private ProjectReviewChatService projectReviewChatService;
    private static final ExecutorService AI_CHAT_EXECUTOR = Executors.newCachedThreadPool();

    private static final String ACTION_PROJECT_CHAT_SEND = "projectChatSend";
    private static final String ACTION_PROJECT_CHAT_QUICK_PROMPT = "projectChatQuickPrompt";
    private static final String ACTION_PROJECT_CHAT_APPLY_ISSUE = "projectChatApplyIssue";
    private static final String ACTION_PROJECT_CHAT_APPLY_NARRATIVE = "projectChatApplyNarrative";
    private static final String ACTION_PROJECT_CHAT_DISMISS = "projectChatDismissSuggestions";

    private static final String ACTION_LANGUAGE_REVIEW_START = "languageReviewStart";
    private static final String ACTION_LANGUAGE_REVIEW_SEND = "languageReviewSend";
    private static final String ACTION_LANGUAGE_REVIEW_APPLY = "languageReviewApply";
    private static final String ACTION_LANGUAGE_REVIEW_DISMISS = "languageReviewDismissSuggestions";
    private static final String ACTION_LANGUAGE_REVIEW_STATUS = "languageReviewStatus";
    private static final String LANGUAGE_REVIEW_START_PROMPT = "I want to review and improve the language for "
            + "this project. Interview me about it first — ask no more than five questions at a time. Focus "
            + "on the project's boundary, why it matters, my work during approximately the next month, the "
            + "intended outcome, and observable evidence of success. Use the project context already provided "
            + "and make reasonable inferences instead of asking unnecessary questions. After I answer, propose "
            + "updated values for description, current focus, project outcome, and success criteria, and let me "
            + "react before treating the proposal as final. Do not propose renaming the project unless I "
            + "specifically ask.";

    private static final String ACTION_NEXT_ACTIONS_START = "nextActionsStart";
    private static final String ACTION_NEXT_ACTIONS_SEND = "nextActionsSend";
    private static final String ACTION_NEXT_ACTIONS_STATUS = "nextActionsStatus";
    private static final String ACTION_NEXT_ACTIONS_ADOPT = "nextActionsAdopt";
    private static final String ACTION_NEXT_ACTIONS_DISMISS = "nextActionsDismissSuggestions";
    private static final String NEXT_ACTIONS_START_PROMPT = "Suggest a batch of concrete next actions for this "
            + "project. Most of these won't be adopted, so it's fine to propose more than I'll use — favor "
            + "distinct, non-overlapping options over one exhaustive list. For each one, write a notes field "
            + "with enough detail that I can pick it up later and know exactly what to do, even if I've "
            + "forgotten the context by then.";

    private static final int MAX_PROJECT_CHAT_MESSAGES = 24;

    protected void processRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        AppReq appReq = new AppReq(request, response);
        try {
            if (appReq.isLoggedOut()) {
                forwardToHome(request, response);
                return;
            }

            String action = request.getParameter("action");
            String dashboardPath = resolveDashboardPath(request);
            boolean projectExpandedLayout = "ProjectDashboardServlet".equals(dashboardPath);
            if (projectExpandedLayout) {
                applyProjectOverride(appReq);
            }

            // Language review and next-actions AI calls run on a background thread so the
            // request returns
            // immediately; the browser polls status and swaps in the fragment without a
            // page reload.
            if (projectExpandedLayout) {
                if (ACTION_LANGUAGE_REVIEW_START.equals(action) || ACTION_LANGUAGE_REVIEW_SEND.equals(action)) {
                    handleLanguageReviewSendAsync(appReq, action);
                    return;
                }
                if (ACTION_LANGUAGE_REVIEW_STATUS.equals(action)) {
                    handleLanguageReviewStatus(appReq);
                    return;
                }
                if (ACTION_NEXT_ACTIONS_START.equals(action) || ACTION_NEXT_ACTIONS_SEND.equals(action)) {
                    handleNextActionsSendAsync(appReq, action);
                    return;
                }
                if (ACTION_NEXT_ACTIONS_STATUS.equals(action)) {
                    handleNextActionsStatus(appReq);
                    return;
                }
            }

            // Handle AJAX requests for today column modals
            if ("loadActionData".equals(action)) {
                handleLoadActionData(appReq);
                return;
            }
            if ("editAction".equals(action)) {
                handleEditAction(appReq);
                return;
            }
            if ("CompleteActionEdit".equals(action)) {
                handleCompleteAction(appReq);
                return;
            }
            if ("deleteAction".equals(action)) {
                handleDeleteAction(appReq);
                return;
            }
            if ("restoreRecentAction".equals(action)) {
                handleRestoreRecentAction(appReq);
                return;
            }
            if ("loadReprioritizeData".equals(action)) {
                handleLoadReprioritizeData(appReq);
                return;
            }
            if ("reprioritizeAction".equals(action)) {
                handleReprioritizeAction(appReq);
                return;
            }
            if ("loadRescheduleData".equals(action)) {
                handleLoadRescheduleData(appReq);
                return;
            }
            if ("rescheduleAction".equals(action)) {
                handleRescheduleAction(appReq);
                return;
            }
            if ("startActionNow".equals(action)) {
                handleStartActionNow(appReq);
                return;
            }
            if ("saveProjectEdit".equals(action)) {
                handleSaveProjectEdit(appReq);
                return;
            }
            if ("saveProjectCreate".equals(action)) {
                handleSaveProjectCreate(appReq);
                return;
            }
            if ("refreshHeaderGauges".equals(action)) {
                handleRefreshHeaderGauges(appReq);
                return;
            }
            if ("addCurrentActionNote".equals(action)) {
                handleAddCurrentActionNote(appReq);
                return;
            }
            if ("saveWorkdayProjectReview".equals(action)) {
                handleSaveWorkdayProjectReview(appReq);
            }

            if ("addIssue".equals(action)) {
                handleAddIssue(appReq);
                return;
            }
            if ("editIssue".equals(action)) {
                handleEditIssue(appReq);
                return;
            }

            if ("addAiThought".equals(action)) {
                handleAddAiThought(appReq);
                return;
            }
            if ("editAiThought".equals(action)) {
                handleEditAiThought(appReq);
                return;
            }
            if ("deleteAiThought".equals(action)) {
                handleDeleteAiThought(appReq);
                return;
            }

            if ("StartTimer".equals(action)) {
                handleStartTimer(appReq);
            }

            if (projectExpandedLayout) {
                if (ACTION_PROJECT_CHAT_SEND.equals(action) || ACTION_PROJECT_CHAT_QUICK_PROMPT.equals(action)) {
                    handleProjectChatSend(appReq, action);
                } else if (ACTION_PROJECT_CHAT_APPLY_ISSUE.equals(action)) {
                    handleProjectChatApplyIssue(appReq);
                } else if (ACTION_PROJECT_CHAT_APPLY_NARRATIVE.equals(action)) {
                    handleProjectChatApplyNarrative(appReq);
                } else if (ACTION_PROJECT_CHAT_DISMISS.equals(action)) {
                    handleProjectChatDismiss(appReq);
                } else if (ACTION_LANGUAGE_REVIEW_APPLY.equals(action)) {
                    handleLanguageReviewApply(appReq);
                } else if (ACTION_LANGUAGE_REVIEW_DISMISS.equals(action)) {
                    handleLanguageReviewDismiss(appReq);
                } else if (ACTION_NEXT_ACTIONS_ADOPT.equals(action)) {
                    handleNextActionsAdopt(appReq);
                } else if (ACTION_NEXT_ACTIONS_DISMISS.equals(action)) {
                    handleNextActionsDismiss(appReq);
                }
            }

            dashboardCurrentActionService.handleSelectAction(appReq);
            dashboardCurrentActionService.handleCurrentActionWork(appReq);
            ActionNext capturedAction = dashboardTodayColumnService.handleQuickCapture(appReq);
            dashboardCurrentActionService.ensureCurrentActionSelected(appReq);

            appReq.setTitle(projectExpandedLayout ? "Project Dashboard" : "Dandelion Dashboard");
            DashboardNowColumnModel nowColumnModel = dashboardNowColumnService.buildModel(appReq);
            DashboardTodayColumnModel todayColumnModel = dashboardTodayColumnService.buildModel(appReq);
            todayColumnModel.getQuickCapture().setFormAction(dashboardPath);
            if (capturedAction != null
                    && DashboardTodayColumnService.ACTION_SCHEDULE_AND_EDIT.equals(action)) {
                todayColumnModel.getQuickCapture().setEditActionNextId(capturedAction.getActionNextId());
            }
            ProjectDashboardChatState chatState = projectExpandedLayout ? getProjectDashboardChatState(appReq) : null;
            ProjectLanguageReviewChatState languageReviewChatState = projectExpandedLayout
                    ? getProjectLanguageReviewChatState(appReq)
                    : null;
            ProjectNextActionsChatState nextActionsChatState = projectExpandedLayout
                    ? getProjectNextActionsChatState(appReq)
                    : null;
            TimeGaugeModel nowGaugeModel = dashboardTimeGaugeService.buildNowGauge(appReq);
            int todayTargetMinutes = dayCapacityService.loadTargetMinutesForDay(appReq,
                    appReq.getWebUser().toDate(appReq.getWebUser().getLocalDateToday()));
            TimeGaugeModel todayGaugeModel = dashboardTimeGaugeService.buildTodayGauge(appReq, todayTargetMinutes);
            dashboardTimeGaugeService.updateTodayGaugePlanned(todayGaugeModel,
                    todayColumnModel.getTotals().getPlannedMinutes(), todayTargetMinutes);
            DashboardNextColumnModel nextColumnModel = dashboardNextColumnService.buildModel(appReq,
                    dashboardTimeGaugeService);
            printHtmlHead(appReq);
            dashboardPageRenderer.render(appReq, nowColumnModel, todayColumnModel, nextColumnModel,
                    nowGaugeModel, todayGaugeModel,
                    projectExpandedLayout ? DashboardLayoutMode.PROJECT_EXPANDED : DashboardLayoutMode.DEFAULT,
                    dashboardPath,
                    chatState,
                    languageReviewChatState,
                    nextActionsChatState,
                    projectExpandedLayout && !ProjectReviewChatService.isConfigured()
                            ? ProjectReviewChatService.getMissingConfigurationMessage()
                            : "");
            printHtmlFoot(appReq);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            appReq.close();
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        processRequest(request, response);
    }

    private void handleLoadActionData(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);

        WebUser webUser = appReq.getWebUser();

        ActionNext action = (ActionNext) appReq.getDataSession()
                .get(ActionNext.class, actionNextId);

        if (action == null) {
            sendJsonResponse(appReq, false, "Action not found", null);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Project actionProject = action.getProject();
        Integer privateWorkspaceId = WorkspaceRegistry.getWorkspaceIdForWebUserId(appReq.getDataSession(),
                webUser.getWebUserId());
        data.put("success", true);
        data.put("nextActionDate", formatUserDate(webUser, action.getNextActionDate()));
        data.put("nextActionDateIso", toIsoDate(action.getNextActionDate(), webUser));
        data.put("nextActionType", action.getNextActionType() != null ? action.getNextActionType() : "");
        data.put("projectName", action.getProject() != null && action.getProject().getProjectName() != null
                ? action.getProject().getProjectName()
                : "");
        data.put("displayProjectName", actionProject == null
                ? ""
                : projectDisplayLabelService.buildActionDisplayName(appReq.getDataSession(), action,
                        privateWorkspaceId));
        Integer nextContactId = action.getNextContactId();
        data.put("nextContactId", nextContactId != null && nextContactId.intValue() > 0 ? nextContactId : "");
        data.put("nextDescription", action.getNextDescription() != null ? action.getNextDescription() : "");
        data.put("nextTimeEstimate", action.getNextTimeEstimate() != null ? action.getNextTimeEstimate() : 0);
        // Return target/deadline in ISO format for native date picker inputs
        data.put("nextTargetDate", toIsoDate(action.getNextTargetDate(), webUser));
        data.put("nextDeadlineDate", toIsoDate(action.getNextDeadlineDate(), webUser));
        data.put("linkUrl", action.getLinkUrl() != null ? action.getLinkUrl() : "");
        data.put("nextNote", actionNotesService.read(action));
        // Mode and time slot for mode-aware UI
        BillCode billCode = actionProject != null ? resolveBillCode(appReq.getDataSession(), actionProject) : null;
        boolean isPersonal = billCode == null || !"Y".equalsIgnoreCase(billCode.getBillable());
        data.put("isPersonal", isPersonal);
        TimeSlot timeSlot = action.getTimeSlot();
        data.put("timeSlot", timeSlot != null ? timeSlot.getId() : "");

        sendJsonResponse(appReq, true, "OK", data);
    }

    private String resolveDashboardPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath == null || servletPath.trim().length() == 0) {
            return "DandelionDashboardServlet";
        }
        String normalized = servletPath.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.length() == 0 ? "DandelionDashboardServlet" : normalized;
    }

    private void applyProjectOverride(AppReq appReq) {
        String projectIdParam = appReq.getRequest().getParameter("projectId");
        if (projectIdParam == null || projectIdParam.trim().length() == 0) {
            return;
        }
        int projectId;
        try {
            projectId = Integer.parseInt(projectIdParam.trim());
        } catch (NumberFormatException nfe) {
            return;
        }
        Project project = (Project) appReq.getDataSession().get(Project.class, projectId);
        if (project == null) {
            return;
        }
        Integer activeWorkspaceId = appReq.getActiveWorkspaceId();
        if (activeWorkspaceId != null && project.getWorkspaceId() != null
                && activeWorkspaceId.intValue() != project.getWorkspaceId().intValue()) {
            return;
        }
        appReq.setProject(project);
        appReq.setProjectSelected(project);
    }

    private void handleProjectChatSend(AppReq appReq, String action) {
        Project project = appReq.getProject();
        if (project == null) {
            appReq.addWarningMessage("Select a project to use chat.");
            return;
        }
        if (!ProjectReviewChatService.isConfigured()) {
            appReq.addWarningMessage(ProjectReviewChatService.getMissingConfigurationMessage());
            return;
        }

        String prompt = ACTION_PROJECT_CHAT_QUICK_PROMPT.equals(action)
                ? n(appReq.getRequest().getParameter("quickPrompt")).trim()
                : n(appReq.getRequest().getParameter("chatPrompt")).trim();
        if (prompt.length() == 0) {
            appReq.addWarningMessage("Enter a prompt before sending.");
            return;
        }

        ProjectDashboardChatState chatState = getProjectDashboardChatState(appReq);
        chatState.getMessages().add(new ProjectDashboardChatMessage("user", prompt));

        try {
            String contextText = projectDashboardAiContextService.buildContextText(appReq, project);
            String modelId = resolveConfiguredModel(appReq);
            ProjectReviewChatResponse aiResponse = getProjectReviewChatService().chat(chatState.getMessages(), prompt,
                    contextText, modelId, ChatMode.GENERAL);
            String assistantText = n(aiResponse.getAssistantMessage()).trim();
            if (assistantText.length() == 0) {
                assistantText = "I don't have a response yet. Please try again.";
            }
            chatState.getMessages().add(new ProjectDashboardChatMessage("assistant", assistantText));
            trimChatMessages(chatState.getMessages());

            chatState.setFollowUpQuestions(aiResponse.getFollowUpQuestions() == null
                    ? new ArrayList<String>()
                    : aiResponse.getFollowUpQuestions());
            chatState.setProposedIssues(aiResponse.getProposedIssues() == null
                    ? new ArrayList<ProjectDashboardSuggestedIssue>()
                    : aiResponse.getProposedIssues());
            chatState.setProposedNarratives(aiResponse.getProposedNarratives() == null
                    ? new ArrayList<ProjectDashboardSuggestedNarrative>()
                    : aiResponse.getProposedNarratives());
        } catch (Exception e) {
            chatState.getMessages().add(new ProjectDashboardChatMessage("assistant",
                    "I ran into an error while generating a response. Please try again."));
            trimChatMessages(chatState.getMessages());
            appReq.addErrorMessage("Project chat request failed.");
        }
    }

    private void handleLanguageReviewSendAsync(AppReq appReq, String action) {
        Project project = appReq.getProject();
        if (project == null) {
            sendJsonResponseQuiet(appReq, false, "Select a project to use the language review.", null);
            return;
        }
        if (!ProjectReviewChatService.isConfigured()) {
            sendJsonResponseQuiet(appReq, false, ProjectReviewChatService.getMissingConfigurationMessage(), null);
            return;
        }

        ProjectLanguageReviewChatState reviewState = getProjectLanguageReviewChatState(appReq);
        if (reviewState.isPending()) {
            sendLanguageReviewStatusResponse(appReq, reviewState, false,
                    "The assistant is still working on the previous message.");
            return;
        }

        String prompt = ACTION_LANGUAGE_REVIEW_START.equals(action)
                ? LANGUAGE_REVIEW_START_PROMPT
                : n(appReq.getRequest().getParameter("chatPrompt")).trim();
        if (prompt.length() == 0) {
            sendJsonResponseQuiet(appReq, false, "Enter a message before sending.", null);
            return;
        }

        String contextText = projectDashboardAiContextService.buildContextText(appReq, project);
        String modelId = resolveConfiguredModel(appReq);

        reviewState.appendUserMessageAndBeginPending(prompt);
        runLanguageReviewInBackground(reviewState, contextText, modelId, prompt);

        sendLanguageReviewStatusResponse(appReq, reviewState, true, "");
    }

    private void runLanguageReviewInBackground(ProjectLanguageReviewChatState reviewState, String contextText,
            String modelId, String prompt) {
        List<ProjectDashboardChatMessage> historySnapshot = reviewState.snapshotMessages();
        AI_CHAT_EXECUTOR.submit(new Runnable() {
            public void run() {
                try {
                    ProjectReviewChatResponse aiResponse = getProjectReviewChatService().chat(historySnapshot,
                            prompt, contextText, modelId, ChatMode.LANGUAGE_REVIEW);
                    String assistantText = n(aiResponse.getAssistantMessage()).trim();
                    if (assistantText.length() == 0) {
                        assistantText = "I don't have a response yet. Please try again.";
                    }
                    reviewState.completePending(assistantText,
                            n(aiResponse.getProposedDescription()).trim(),
                            n(aiResponse.getProposedCurrentFocus()).trim(),
                            n(aiResponse.getProposedOutcome()).trim(),
                            n(aiResponse.getProposedSuccessCriteria()).trim(),
                            aiResponse.getFollowUpQuestions(),
                            MAX_PROJECT_CHAT_MESSAGES);
                } catch (Exception e) {
                    reviewState.failPending("I ran into an error while generating a response. Please try again.",
                            MAX_PROJECT_CHAT_MESSAGES);
                }
            }
        });
    }

    private void handleLanguageReviewStatus(AppReq appReq) {
        ProjectLanguageReviewChatState reviewState = getProjectLanguageReviewChatState(appReq);
        sendLanguageReviewStatusResponse(appReq, reviewState, true, "");
    }

    private void sendLanguageReviewStatusResponse(AppReq appReq, ProjectLanguageReviewChatState reviewState,
            boolean success, String message) {
        String dashboardPath = resolveDashboardPath(appReq.getRequest());
        String contentHtml = dashboardPageRenderer.renderLanguageReviewContentFragment(reviewState, dashboardPath);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("pending", Boolean.valueOf(reviewState.isPending()));
        data.put("contentHtml", contentHtml);
        sendJsonResponseQuiet(appReq, success, message, data);
    }

    private void sendJsonResponseQuiet(AppReq appReq, boolean success, String message, Map<String, Object> data) {
        try {
            sendJsonResponse(appReq, success, message, data);
        } catch (Exception e) {
            // best-effort; the client will retry on its next poll if the response stream
            // failed
        }
    }

    private String resolveConfiguredModel(AppReq appReq) {
        return TrackerKeysManager.getApplicationKeyValue(
                TrackerKeysManager.KEY_AI_PROJECT_REVIEW_MODEL, ProjectReviewChatService.DEFAULT_MODEL,
                appReq.getDataSession());
    }

    private void handleNextActionsSendAsync(AppReq appReq, String action) {
        Project project = appReq.getProject();
        if (project == null) {
            sendJsonResponseQuiet(appReq, false, "Select a project to suggest next actions.", null);
            return;
        }
        if (!ProjectReviewChatService.isConfigured()) {
            sendJsonResponseQuiet(appReq, false, ProjectReviewChatService.getMissingConfigurationMessage(), null);
            return;
        }

        ProjectNextActionsChatState nextActionsState = getProjectNextActionsChatState(appReq);
        if (nextActionsState.isPending()) {
            sendNextActionsStatusResponse(appReq, nextActionsState, false,
                    "The assistant is still working on the previous message.");
            return;
        }

        String prompt = ACTION_NEXT_ACTIONS_START.equals(action)
                ? NEXT_ACTIONS_START_PROMPT
                : n(appReq.getRequest().getParameter("chatPrompt")).trim();
        if (prompt.length() == 0) {
            sendJsonResponseQuiet(appReq, false, "Enter a message before sending.", null);
            return;
        }

        String contextText = projectDashboardAiContextService.buildContextText(appReq, project);
        String modelId = resolveConfiguredModel(appReq);

        nextActionsState.appendUserMessageAndBeginPending(prompt);
        runNextActionsInBackground(nextActionsState, contextText, modelId, prompt);

        sendNextActionsStatusResponse(appReq, nextActionsState, true, "");
    }

    private void runNextActionsInBackground(ProjectNextActionsChatState nextActionsState, String contextText,
            String modelId, String prompt) {
        List<ProjectDashboardChatMessage> historySnapshot = nextActionsState.snapshotMessages();
        AI_CHAT_EXECUTOR.submit(new Runnable() {
            public void run() {
                try {
                    ProjectReviewChatResponse aiResponse = getProjectReviewChatService().chat(historySnapshot,
                            prompt, contextText, modelId, ChatMode.NEXT_ACTIONS);
                    String assistantText = n(aiResponse.getAssistantMessage()).trim();
                    if (assistantText.length() == 0) {
                        assistantText = "I don't have a response yet. Please try again.";
                    }
                    nextActionsState.completePending(assistantText, aiResponse.getProposedActions(),
                            aiResponse.getFollowUpQuestions(), MAX_PROJECT_CHAT_MESSAGES);
                } catch (Exception e) {
                    nextActionsState.failPending("I ran into an error while generating a response. Please try again.",
                            MAX_PROJECT_CHAT_MESSAGES);
                }
            }
        });
    }

    private void handleNextActionsStatus(AppReq appReq) {
        ProjectNextActionsChatState nextActionsState = getProjectNextActionsChatState(appReq);
        sendNextActionsStatusResponse(appReq, nextActionsState, true, "");
    }

    private void sendNextActionsStatusResponse(AppReq appReq, ProjectNextActionsChatState nextActionsState,
            boolean success, String message) {
        String dashboardPath = resolveDashboardPath(appReq.getRequest());
        String contentHtml = dashboardPageRenderer.renderNextActionsContentFragment(nextActionsState, dashboardPath);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("pending", Boolean.valueOf(nextActionsState.isPending()));
        data.put("contentHtml", contentHtml);
        sendJsonResponseQuiet(appReq, success, message, data);
    }

    private void handleNextActionsAdopt(AppReq appReq) {
        Project project = appReq.getProject();
        if (project == null) {
            appReq.addWarningMessage("Select a project before adopting a suggested action.");
            return;
        }

        ProjectNextActionsChatState nextActionsState = getProjectNextActionsChatState(appReq);
        if (nextActionsState.isPending()) {
            appReq.addWarningMessage("Wait for the current suggestions to finish generating before adopting one.");
            return;
        }

        int suggestionIndex;
        try {
            suggestionIndex = Integer.parseInt(n(appReq.getRequest().getParameter("suggestionIndex")).trim());
        } catch (Exception e) {
            appReq.addWarningMessage("Suggested action is not valid.");
            return;
        }

        ProjectDashboardSuggestedAction suggestion = nextActionsState.removeProposedAction(suggestionIndex);
        if (suggestion == null) {
            appReq.addWarningMessage("Suggested action is no longer available.");
            return;
        }

        String title = clip(n(appReq.getRequest().getParameter("editedTitle")).trim(), 240);
        String notes = clip(n(appReq.getRequest().getParameter("editedNotes")).trim(), 4000);
        String description = clip(n(suggestion.getDescription()), 1200);
        String actionText = title.length() > 0 ? title : description;
        if (actionText.length() == 0) {
            appReq.addWarningMessage("Suggested action is empty.");
            return;
        }

        Session dataSession = appReq.getDataSession();
        WebUser webUser = appReq.getWebUser();
        Transaction transaction = dataSession.beginTransaction();
        try {
            Project managedProject = (Project) dataSession.get(Project.class, project.getProjectId());
            if (managedProject == null) {
                transaction.rollback();
                appReq.addErrorMessage("Project was not found.");
                return;
            }

            ActionNext action = new ActionNext();
            action.setProject(managedProject);
            action.setProjectId(managedProject.getProjectId());
            action.setWorkspaceId(managedProject.getWorkspaceId());
            action.setContact(webUser.getProjectContact());
            action.setContactId(webUser.getContactId());
            action.setNextActionType(resolveAiSuggestedActionType(suggestion.getSuggestedType()));
            action.setNextDescription(actionText);
            action.setNextSummary(description.length() > 0 ? description : actionText);
            action.setNextTimeEstimate(normalizeEstimateMinutes(suggestion.getEstimateMinutes()));
            action.setNextActionStatus(ProjectNextActionStatus.READY);
            action.setNextActionDate(java.sql.Date.valueOf(webUser.getLocalDateToday()));
            action.setNextChangeDate(new Date());
            action.setPriorityLevel(ProjectNextActionType.defaultPriority(action.getNextActionType()));
            action.setBillable(
                    managedProject.getBillCode() != null && managedProject.getBillCode().trim().length() > 0);
            actionNotesService.initialize(action, notes);
            dataSession.save(action);
            transaction.commit();

            appReq.setCompletingAction(action);
            appReq.setProject(managedProject);
            appReq.addSuccessMessage("Adopted: " + actionText);
        } catch (Exception e) {
            if (transaction != null && transaction.isActive()) {
                transaction.rollback();
            }
            appReq.addErrorMessage("Unable to adopt suggested action.");
        }
    }

    private void handleNextActionsDismiss(AppReq appReq) {
        ProjectNextActionsChatState nextActionsState = getProjectNextActionsChatState(appReq);
        nextActionsState.clearProposals();
        appReq.addSuccessMessage("Suggested actions dismissed.");
    }

    private void handleLanguageReviewApply(AppReq appReq) {
        Project project = appReq.getProject();
        if (project == null) {
            appReq.addWarningMessage("Select a project before applying the language update.");
            return;
        }

        ProjectLanguageReviewChatState reviewState = getProjectLanguageReviewChatState(appReq);
        String editedDescription = n(appReq.getRequest().getParameter("editedDescription")).trim();
        String editedCurrentFocus = n(appReq.getRequest().getParameter("editedCurrentFocus")).trim();
        String editedOutcome = n(appReq.getRequest().getParameter("editedOutcome")).trim();
        String editedSuccessCriteria = n(appReq.getRequest().getParameter("editedSuccessCriteria")).trim();
        boolean hasDescription = editedDescription.length() > 0;
        boolean hasCurrentFocus = editedCurrentFocus.length() > 0;
        boolean hasOutcome = editedOutcome.length() > 0;
        boolean hasSuccessCriteria = editedSuccessCriteria.length() > 0;

        if (!hasDescription && !hasCurrentFocus && !hasOutcome && !hasSuccessCriteria) {
            appReq.addWarningMessage("No language review proposal is available to apply yet.");
            return;
        }

        Session dataSession = appReq.getDataSession();
        WebUser webUser = appReq.getWebUser();
        if (webUser == null || project.getWorkspaceId() == null
                || !WorkspaceRegistry.canAdministerWorkspace(dataSession, project.getWorkspaceId(),
                        webUser.getWebUserId())) {
            appReq.addErrorMessage("Project is not available for this user.");
            return;
        }

        Transaction transaction = dataSession.beginTransaction();
        try {
            Project managedProject = (Project) dataSession.get(Project.class, project.getProjectId());
            if (managedProject == null) {
                transaction.rollback();
                appReq.addErrorMessage("Project was not found.");
                return;
            }

            if (hasDescription) {
                managedProject.setDescription(clip(editedDescription, 1200));
            }
            if (hasCurrentFocus) {
                managedProject.setCurrentFocusText(clip(editedCurrentFocus, 12000));
            }
            if (hasOutcome) {
                managedProject.setOutcomeText(clip(editedOutcome, 12000));
            }
            if (hasSuccessCriteria) {
                managedProject.setSuccessCriteriaText(clip(editedSuccessCriteria, 12000));
            }

            managedProject.setLastModifiedByWebUserId(webUser.getWebUserId());
            managedProject.setLastModifiedDate(new Date());
            dataSession.saveOrUpdate(managedProject);
            transaction.commit();

            reviewState.clearProposals();
            appReq.setProject(managedProject);
            appReq.setProjectSelected(managedProject);
            appReq.addSuccessMessage("Language update applied.");
        } catch (Exception e) {
            if (transaction != null && transaction.isActive()) {
                transaction.rollback();
            }
            appReq.addErrorMessage("Unable to apply language update.");
        }
    }

    private void handleLanguageReviewDismiss(AppReq appReq) {
        ProjectLanguageReviewChatState reviewState = getProjectLanguageReviewChatState(appReq);
        reviewState.clearProposals();
        appReq.addSuccessMessage("Language review suggestions dismissed.");
    }

    private void handleProjectChatApplyIssue(AppReq appReq) {
        Project project = appReq.getProject();
        if (project == null) {
            appReq.addWarningMessage("Select a project before applying issue suggestions.");
            return;
        }

        int suggestionIndex;
        try {
            suggestionIndex = Integer.parseInt(n(appReq.getRequest().getParameter("suggestionIndex")).trim());
        } catch (Exception e) {
            appReq.addWarningMessage("Issue suggestion is not valid.");
            return;
        }

        ProjectDashboardChatState chatState = getProjectDashboardChatState(appReq);
        if (chatState.getProposedIssues() == null
                || suggestionIndex < 0
                || suggestionIndex >= chatState.getProposedIssues().size()) {
            appReq.addWarningMessage("Issue suggestion is no longer available.");
            return;
        }

        ProjectDashboardSuggestedIssue suggestedIssue = chatState.getProposedIssues().get(suggestionIndex);
        String issueText = clip(n(suggestedIssue.getIssueText()), 1200);
        if (issueText.length() == 0) {
            appReq.addWarningMessage("Issue suggestion is empty.");
            return;
        }
        ProjectIssueType issueType = ProjectIssueType.fromString(n(suggestedIssue.getIssueType()));

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            Project managedProject = (Project) dataSession.get(Project.class, project.getProjectId());
            if (managedProject == null) {
                transaction.rollback();
                appReq.addErrorMessage("Project was not found.");
                return;
            }

            org.openimmunizationsoftware.pt.doa.ProjectIssueDao projectIssueDao = new org.openimmunizationsoftware.pt.doa.ProjectIssueDao(
                    dataSession);
            projectIssueDao.createIssue(managedProject, issueText, issueType);
            transaction.commit();

            chatState.getProposedIssues().remove(suggestionIndex);
            appReq.addSuccessMessage("AI issue suggestion created.");
        } catch (Exception e) {
            if (transaction != null && transaction.isActive()) {
                transaction.rollback();
            }
            appReq.addErrorMessage("Unable to create AI issue suggestion.");
        }
    }

    private void handleProjectChatApplyNarrative(AppReq appReq) {
        Project project = appReq.getProject();
        if (project == null) {
            appReq.addWarningMessage("Select a project before applying narrative suggestions.");
            return;
        }

        int suggestionIndex;
        try {
            suggestionIndex = Integer.parseInt(n(appReq.getRequest().getParameter("suggestionIndex")).trim());
        } catch (Exception e) {
            appReq.addWarningMessage("Narrative suggestion is not valid.");
            return;
        }

        ProjectDashboardChatState chatState = getProjectDashboardChatState(appReq);
        if (chatState.getProposedNarratives() == null
                || suggestionIndex < 0
                || suggestionIndex >= chatState.getProposedNarratives().size()) {
            appReq.addWarningMessage("Narrative suggestion is no longer available.");
            return;
        }

        ProjectDashboardSuggestedNarrative suggestedNarrative = chatState.getProposedNarratives().get(suggestionIndex);
        String narrativeText = clip(n(suggestedNarrative.getText()), 4000);
        if (narrativeText.length() == 0) {
            appReq.addWarningMessage("Narrative suggestion is empty.");
            return;
        }

        ProjectNarrativeVerb narrativeVerb = ProjectNarrativeVerb.fromId(n(suggestedNarrative.getVerb()).toUpperCase());
        if (narrativeVerb == null) {
            narrativeVerb = ProjectNarrativeVerb.NOTE;
        }

        try {
            projectNarrativeService.saveSingleNarrativeForProjectDate(appReq,
                    project.getProjectId(),
                    appReq.getWebUser().getLocalDateToday(),
                    narrativeVerb,
                    narrativeText);
            chatState.getProposedNarratives().remove(suggestionIndex);
            appReq.addSuccessMessage("AI narrative suggestion saved.");
        } catch (Exception e) {
            appReq.addErrorMessage("Unable to save AI narrative suggestion.");
        }
    }

    private void handleProjectChatDismiss(AppReq appReq) {
        ProjectDashboardChatState chatState = getProjectDashboardChatState(appReq);
        chatState.clearSuggestions();
        appReq.addSuccessMessage("AI suggestions dismissed.");
    }

    private String resolveAiSuggestedActionType(String suggestedType) {
        String normalized = n(suggestedType).toUpperCase();
        if (normalized.equals(ProjectNextActionType.WILL)
                || normalized.equals(ProjectNextActionType.WILL_CONTACT)
                || normalized.equals(ProjectNextActionType.WILL_MEET)
                || normalized.equals(ProjectNextActionType.WILL_REVIEW)
                || normalized.equals(ProjectNextActionType.WILL_DOCUMENT)
                || normalized.equals(ProjectNextActionType.WILL_FOLLOW_UP)
                || normalized.equals(ProjectNextActionType.MIGHT)
                || normalized.equals(ProjectNextActionType.WOULD_LIKE_TO)
                || normalized.equals(ProjectNextActionType.COMMITTED_TO)
                || normalized.equals(ProjectNextActionType.GOAL)
                || normalized.equals(ProjectNextActionType.WAITING)
                || normalized.equals(ProjectNextActionType.OVERDUE_TO)) {
            return normalized;
        }
        return ProjectNextActionType.WILL;
    }

    private Integer normalizeEstimateMinutes(Integer estimateMinutes) {
        if (estimateMinutes == null) {
            return Integer.valueOf(15);
        }
        int value = estimateMinutes.intValue();
        if (value < 0) {
            value = 0;
        }
        if (value > 480) {
            value = 480;
        }
        return Integer.valueOf(value);
    }

    private synchronized ProjectReviewChatService getProjectReviewChatService() {
        if (projectReviewChatService == null) {
            projectReviewChatService = new ProjectReviewChatService();
        }
        return projectReviewChatService;
    }

    private ProjectDashboardChatState getProjectDashboardChatState(AppReq appReq) {
        Integer workspaceId = appReq.getActiveWorkspaceId();
        Project project = appReq.getProject();
        String key = "PROJECT_DASHBOARD_CHAT_STATE_" + (workspaceId == null ? "0" : workspaceId.intValue()) + "_"
                + (project == null ? "0" : project.getProjectId());
        Object stateObject = appReq.getWebSession().getAttribute(key);
        if (stateObject instanceof ProjectDashboardChatState) {
            return (ProjectDashboardChatState) stateObject;
        }
        ProjectDashboardChatState state = new ProjectDashboardChatState();
        appReq.getWebSession().setAttribute(key, state);
        return state;
    }

    private ProjectLanguageReviewChatState getProjectLanguageReviewChatState(AppReq appReq) {
        Integer workspaceId = appReq.getActiveWorkspaceId();
        Project project = appReq.getProject();
        String key = "PROJECT_LANGUAGE_REVIEW_CHAT_STATE_" + (workspaceId == null ? "0" : workspaceId.intValue())
                + "_" + (project == null ? "0" : project.getProjectId());
        Object stateObject = appReq.getWebSession().getAttribute(key);
        if (stateObject instanceof ProjectLanguageReviewChatState) {
            return (ProjectLanguageReviewChatState) stateObject;
        }
        ProjectLanguageReviewChatState state = new ProjectLanguageReviewChatState();
        appReq.getWebSession().setAttribute(key, state);
        return state;
    }

    private ProjectNextActionsChatState getProjectNextActionsChatState(AppReq appReq) {
        Integer workspaceId = appReq.getActiveWorkspaceId();
        Project project = appReq.getProject();
        String key = "PROJECT_NEXT_ACTIONS_CHAT_STATE_" + (workspaceId == null ? "0" : workspaceId.intValue())
                + "_" + (project == null ? "0" : project.getProjectId());
        Object stateObject = appReq.getWebSession().getAttribute(key);
        if (stateObject instanceof ProjectNextActionsChatState) {
            return (ProjectNextActionsChatState) stateObject;
        }
        ProjectNextActionsChatState state = new ProjectNextActionsChatState();
        appReq.getWebSession().setAttribute(key, state);
        return state;
    }

    private void trimChatMessages(List<ProjectDashboardChatMessage> messages) {
        if (messages == null) {
            return;
        }
        while (messages.size() > MAX_PROJECT_CHAT_MESSAGES) {
            messages.remove(0);
        }
    }

    private void handleSaveWorkdayProjectReview(AppReq appReq) throws Exception {
        String projectIdString = appReq.getRequest().getParameter("projectId");
        if (projectIdString == null || projectIdString.trim().length() == 0) {
            appReq.addErrorMessage("Project is required for review.");
            return;
        }

        long projectId;
        try {
            projectId = Long.parseLong(projectIdString.trim());
        } catch (NumberFormatException nfe) {
            appReq.addErrorMessage("Project is not available.");
            return;
        }

        LocalDate reviewDate = appReq.getWebUser().getLocalDateToday();
        ProjectNarrativeEntry entry = new ProjectNarrativeEntry();
        entry.setNote(n(appReq.getRequest().getParameter("note")).trim());
        entry.setDecision(n(appReq.getRequest().getParameter("decision")).trim());
        entry.setInsight(n(appReq.getRequest().getParameter("insight")).trim());
        entry.setRisk(n(appReq.getRequest().getParameter("risk")).trim());
        entry.setOpportunity(n(appReq.getRequest().getParameter("opportunity")).trim());

        try {
            projectNarrativeService.saveNarrativeForProjectDate(appReq, projectId, reviewDate, entry);
            appReq.addSuccessMessage("Project review saved.");
        } catch (IllegalArgumentException iae) {
            appReq.addErrorMessage(iae.getMessage());
        }
    }

    private void handleEditAction(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();

        try {
            ActionNext action = (ActionNext) dataSession
                    .get(ActionNext.class, actionNextId);

            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            ActionNext currentAction = appReq.getCompletingAction();
            Integer currentActionId = currentAction == null
                    ? null
                    : Integer.valueOf(currentAction.getActionNextId());
            LocalDate previousActionDate = toStoredLocalDate(action.getNextActionDate(), appReq.getWebUser());

            // Update action fields from request
            String nextActionDate = appReq.getRequest().getParameter("nextActionDate");
            String nextActionType = appReq.getRequest().getParameter("nextActionType");
            String nextContactIdStr = appReq.getRequest().getParameter("nextContactId");
            String nextDescription = appReq.getRequest().getParameter("nextDescription");
            String nextTimeEstimateStr = appReq.getRequest().getParameter("nextTimeEstimate");
            String nextTargetDate = appReq.getRequest().getParameter("nextTargetDate");
            String nextDeadlineDate = appReq.getRequest().getParameter("nextDeadlineDate");
            String timeSlotParam = appReq.getRequest().getParameter("timeSlot");
            String linkUrl = appReq.getRequest().getParameter("linkUrl");
            String nextNote = appReq.getRequest().getParameter("nextNote");
            String baseNote = appReq.getRequest().getParameter("baseNote");
            String saveMode = appReq.getRequest().getParameter("saveMode");
            boolean saveAndStart = "saveAndStart".equals(saveMode);

            if (nextActionDate != null) {
                nextActionDate = nextActionDate.trim();
            }

            Date normalizedNextActionDate = null;
            boolean hasNextActionDateValue = nextActionDate != null && nextActionDate.length() > 0;
            if (hasNextActionDateValue) {
                Date parsedDate = parseIsoOrUserDate(appReq.getWebUser(), nextActionDate);
                if (parsedDate != null) {
                    normalizedNextActionDate = normalizeUserDate(appReq.getWebUser(), parsedDate);
                }
            }

            Integer parsedNextContactId = null;
            boolean hasNextContactIdValue = nextContactIdStr != null && nextContactIdStr.length() > 0;
            if (hasNextContactIdValue) {
                try {
                    parsedNextContactId = Integer.valueOf(Integer.parseInt(nextContactIdStr));
                } catch (Exception e) {
                    parsedNextContactId = null;
                }
            }

            Integer parsedNextTimeEstimate = null;
            boolean hasNextTimeEstimateValue = nextTimeEstimateStr != null && nextTimeEstimateStr.length() > 0;
            if (hasNextTimeEstimateValue) {
                try {
                    parsedNextTimeEstimate = Integer.valueOf(Integer.parseInt(nextTimeEstimateStr));
                } catch (Exception e) {
                    parsedNextTimeEstimate = null;
                }
            }

            Date normalizedNextTargetDate = null;
            boolean clearNextTargetDate = nextTargetDate != null && nextTargetDate.length() == 0;
            if (nextTargetDate != null && nextTargetDate.length() > 0) {
                Date parsedDate = parseIsoOrUserDate(appReq.getWebUser(), nextTargetDate);
                if (parsedDate != null) {
                    normalizedNextTargetDate = normalizeUserDate(appReq.getWebUser(), parsedDate);
                }
            }

            Date normalizedNextDeadlineDate = null;
            boolean clearNextDeadlineDate = nextDeadlineDate != null && nextDeadlineDate.length() == 0;
            if (nextDeadlineDate != null && nextDeadlineDate.length() > 0) {
                Date parsedDate = parseIsoOrUserDate(appReq.getWebUser(), nextDeadlineDate);
                if (parsedDate != null) {
                    normalizedNextDeadlineDate = normalizeUserDate(appReq.getWebUser(), parsedDate);
                }
            }

            TimeSlot parsedTimeSlot = null;
            if (timeSlotParam != null && timeSlotParam.trim().length() > 0) {
                parsedTimeSlot = TimeSlot.getTimeSlot(timeSlotParam.trim());
            }

            if (nextActionType != null && nextActionType.length() > 0) {
                for (ActionNext sibling : SharedActionSiblings.resolve(dataSession, action)) {
                    String meetingError = LinkedMeetingService.actionTypeError(dataSession, sibling, nextActionType);
                    if (meetingError != null) {
                        transaction.rollback();
                        sendJsonResponse(appReq, false, meetingError, null);
                        return;
                    }
                }
            }
            if (nextNote != null) {
                ActionNotesService.Result notesResult = actionNotesService.replace(dataSession, action, nextNote,
                        baseNote, null);
                if (!notesResult.isOk()) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, notesResult.getErrorMessage(), null);
                    return;
                }
            }
            applyEditFieldsToSiblings(appReq, dataSession, action, saveAndStart, nextActionDate,
                    normalizedNextActionDate, hasNextActionDateValue, nextActionType, hasNextContactIdValue,
                    parsedNextContactId, nextDescription, hasNextTimeEstimateValue, parsedNextTimeEstimate,
                    normalizedNextTargetDate, clearNextTargetDate, normalizedNextDeadlineDate, clearNextDeadlineDate,
                    parsedTimeSlot, linkUrl);
            transaction.commit();

            LocalDate savedActionDate = toStoredLocalDate(action.getNextActionDate(), appReq.getWebUser());
            boolean requiresActionRefresh = dashboardCurrentActionService.shouldHandoffCurrentAction(
                    currentActionId, Integer.valueOf(actionNextId), previousActionDate, savedActionDate,
                    appReq.getWebUser().getLocalDateToday())
                    && dashboardCurrentActionService.handoffCurrentAction(appReq);

            if (saveAndStart) {
                appReq.setCompletingAction(action);
                if (action.getProject() != null) {
                    appReq.setProject(action.getProject());
                }
                requiresActionRefresh = false;
            }

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("requiresActionRefresh", Boolean.valueOf(requiresActionRefresh));
            sendJsonResponse(appReq, true, "Action saved successfully", data);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Unable to save action. See the server log for details.", null);
        }
    }

    private void applyEditFieldsToSiblings(AppReq appReq, Session dataSession, ActionNext action,
            boolean saveAndStart, String nextActionDate, Date normalizedNextActionDate,
            boolean hasNextActionDateValue, String nextActionType, boolean hasNextContactIdValue,
            Integer parsedNextContactId, String nextDescription, boolean hasNextTimeEstimateValue,
            Integer parsedNextTimeEstimate, Date normalizedNextTargetDate, boolean clearNextTargetDate,
            Date normalizedNextDeadlineDate, boolean clearNextDeadlineDate, TimeSlot parsedTimeSlot,
            String linkUrl) {
        Date now = new Date();
        List<ActionNext> actionSiblings = SharedActionSiblings.resolve(dataSession, action);
        for (ActionNext sibling : actionSiblings) {
            Date siblingOriginalNextActionDate = sibling.getNextActionDate();
            boolean nextActionDateChanged = false;

            if (hasNextActionDateValue && normalizedNextActionDate != null) {
                if (!sameDate(siblingOriginalNextActionDate, normalizedNextActionDate)) {
                    sibling.setNextActionDate(normalizedNextActionDate);
                    nextActionDateChanged = true;
                }
            } else if (nextActionDate != null && siblingOriginalNextActionDate != null) {
                sibling.setNextActionDate(null);
                nextActionDateChanged = true;
            }

            if (nextActionType != null && nextActionType.length() > 0) {
                sibling.setNextActionType(nextActionType);
            }

            if (hasNextContactIdValue && parsedNextContactId != null) {
                sibling.setNextContactId(parsedNextContactId.intValue());
            }

            if (nextDescription != null) {
                sibling.setNextDescription(nextDescription);
            }

            if (hasNextTimeEstimateValue && parsedNextTimeEstimate != null) {
                sibling.setNextTimeEstimate(parsedNextTimeEstimate.intValue());
            }

            if (normalizedNextTargetDate != null) {
                sibling.setNextTargetDate(normalizedNextTargetDate);
            } else if (clearNextTargetDate) {
                sibling.setNextTargetDate(null);
            }

            if (normalizedNextDeadlineDate != null) {
                sibling.setNextDeadlineDate(normalizedNextDeadlineDate);
            } else if (clearNextDeadlineDate) {
                sibling.setNextDeadlineDate(null);
            }

            if (parsedTimeSlot != null) {
                sibling.setTimeSlot(parsedTimeSlot);
            }

            if (linkUrl != null) {
                sibling.setLinkUrl(linkUrl);
            }

            if (saveAndStart) {
                WebUser webUser = appReq.getWebUser();
                sibling.setNextActionDate(java.sql.Date.valueOf(webUser.getLocalDateToday()));
                nextActionDateChanged = !sameDate(siblingOriginalNextActionDate, sibling.getNextActionDate());
            }

            if (nextActionDateChanged) {
                sibling.setCompletionOrder(0);
            }

            sibling.setNextChangeDate(now);
            dataSession.update(sibling);
        }
    }

    /**
     * Backdated "Complete" from the Edit Action modal: validates the completion
     * time/duration before writing anything, then applies the same field edits
     * as {@link #handleEditAction} and hands off to
     * {@link ActionCompletionService} to mark the action (and SHARED siblings)
     * completed and optionally record a bill entry.
     */
    private void handleCompleteAction(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);

        Session dataSession = appReq.getDataSession();
        ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
        if (action == null) {
            sendJsonResponse(appReq, false, "Action not found", null);
            return;
        }

        ActionCompletionService.CompletionTime completionTime = actionCompletionService.parseCompletionTime(
                appReq.getWebUser(), appReq.getRequest().getParameter("completeDate"),
                appReq.getRequest().getParameter("completeTime"),
                appReq.getRequest().getParameter("completeDuration"));
        String validationError = actionCompletionService.validateCompletion(appReq, action, completionTime);
        String completeNote = appReq.getRequest().getParameter("completeNote");
        String followUpSentence = appReq.getRequest().getParameter("followUp");
        String followUpNotes = appReq.getRequest().getParameter("followUpNotes");
        if (validationError == null) {
            validationError = actionCompletionService.validateHandoff(completeNote, followUpNotes);
        }
        if (validationError != null) {
            sendJsonResponse(appReq, false, validationError, null);
            return;
        }

        String nextActionDate = appReq.getRequest().getParameter("nextActionDate");
        String nextActionType = appReq.getRequest().getParameter("nextActionType");
        String nextContactIdStr = appReq.getRequest().getParameter("nextContactId");
        String nextDescription = appReq.getRequest().getParameter("nextDescription");
        String nextTimeEstimateStr = appReq.getRequest().getParameter("nextTimeEstimate");
        String nextTargetDate = appReq.getRequest().getParameter("nextTargetDate");
        String nextDeadlineDate = appReq.getRequest().getParameter("nextDeadlineDate");
        String timeSlotParam = appReq.getRequest().getParameter("timeSlot");
        String linkUrl = appReq.getRequest().getParameter("linkUrl");
        String nextNote = appReq.getRequest().getParameter("nextNote");
        String baseNote = appReq.getRequest().getParameter("baseNote");

        if (nextActionDate != null) {
            nextActionDate = nextActionDate.trim();
        }

        Date normalizedNextActionDate = null;
        boolean hasNextActionDateValue = nextActionDate != null && nextActionDate.length() > 0;
        if (hasNextActionDateValue) {
            Date parsedDate = parseIsoOrUserDate(appReq.getWebUser(), nextActionDate);
            if (parsedDate != null) {
                normalizedNextActionDate = normalizeUserDate(appReq.getWebUser(), parsedDate);
            }
        }

        Integer parsedNextContactId = null;
        boolean hasNextContactIdValue = nextContactIdStr != null && nextContactIdStr.length() > 0;
        if (hasNextContactIdValue) {
            try {
                parsedNextContactId = Integer.valueOf(Integer.parseInt(nextContactIdStr));
            } catch (Exception e) {
                parsedNextContactId = null;
            }
        }

        Integer parsedNextTimeEstimate = null;
        boolean hasNextTimeEstimateValue = nextTimeEstimateStr != null && nextTimeEstimateStr.length() > 0;
        if (hasNextTimeEstimateValue) {
            try {
                parsedNextTimeEstimate = Integer.valueOf(Integer.parseInt(nextTimeEstimateStr));
            } catch (Exception e) {
                parsedNextTimeEstimate = null;
            }
        }

        Date normalizedNextTargetDate = null;
        boolean clearNextTargetDate = nextTargetDate != null && nextTargetDate.length() == 0;
        if (nextTargetDate != null && nextTargetDate.length() > 0) {
            Date parsedDate = parseIsoOrUserDate(appReq.getWebUser(), nextTargetDate);
            if (parsedDate != null) {
                normalizedNextTargetDate = normalizeUserDate(appReq.getWebUser(), parsedDate);
            }
        }

        Date normalizedNextDeadlineDate = null;
        boolean clearNextDeadlineDate = nextDeadlineDate != null && nextDeadlineDate.length() == 0;
        if (nextDeadlineDate != null && nextDeadlineDate.length() > 0) {
            Date parsedDate = parseIsoOrUserDate(appReq.getWebUser(), nextDeadlineDate);
            if (parsedDate != null) {
                normalizedNextDeadlineDate = normalizeUserDate(appReq.getWebUser(), parsedDate);
            }
        }

        TimeSlot parsedTimeSlot = null;
        if (timeSlotParam != null && timeSlotParam.trim().length() > 0) {
            parsedTimeSlot = TimeSlot.getTimeSlot(timeSlotParam.trim());
        }

        Transaction transaction = dataSession.beginTransaction();
        try {
            if (nextNote != null) {
                ActionNotesService.Result notesResult = actionNotesService.replace(dataSession, action, nextNote,
                        baseNote, null);
                if (!notesResult.isOk()) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, notesResult.getErrorMessage(), null);
                    return;
                }
            }
            applyEditFieldsToSiblings(appReq, dataSession, action, false, nextActionDate, normalizedNextActionDate,
                    hasNextActionDateValue, nextActionType, hasNextContactIdValue, parsedNextContactId,
                    nextDescription, hasNextTimeEstimateValue, parsedNextTimeEstimate, normalizedNextTargetDate,
                    clearNextTargetDate, normalizedNextDeadlineDate, clearNextDeadlineDate, parsedTimeSlot, linkUrl);
            transaction.commit();
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }

        ActionNext currentAction = appReq.getCompletingAction();
        boolean wasCurrentAction = currentAction != null && currentAction.getActionNextId() == actionNextId;

        String completeDescription = appReq.getRequest().getParameter("completeDescription");
        ActionNext followUp;
        try {
            followUp = actionCompletionService.closeActionWithHandoff(appReq, action, completeDescription,
                    completeNote, ProjectNextActionStatus.COMPLETED, completionTime.getMoment(),
                    completionTime.getDurationMins(), followUpSentence, followUpNotes);
        } catch (IllegalArgumentException iae) {
            sendJsonResponse(appReq, false, iae.getMessage(), null);
            return;
        }

        if (wasCurrentAction) {
            dashboardCurrentActionService.handoffCurrentAction(appReq);
        }

        Map<String, Object> data = null;
        if (followUp != null) {
            data = new LinkedHashMap<String, Object>();
            data.put("followUpActionNextId", Integer.valueOf(followUp.getActionNextId()));
        }
        sendJsonResponse(appReq, true, followUp == null ? "Action completed" : "Action completed; next action added",
                data);
    }

    private void handleDeleteAction(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        if (actionNextIdStr == null || actionNextIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "actionNextId is required", null);
            return;
        }
        int actionNextId;
        try {
            actionNextId = Integer.parseInt(actionNextIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "actionNextId must be a whole number", null);
            return;
        }
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }
            ActionNext currentAction = appReq.getCompletingAction();
            boolean deletingCurrentAction = currentAction != null
                    && currentAction.getActionNextId() == actionNextId;
            Integer activeWorkspaceId = appReq.getActiveWorkspaceId();
            if (activeWorkspaceId != null && action.getWorkspaceId() != null
                    && activeWorkspaceId.intValue() != action.getWorkspaceId().intValue()) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action is not available for this workspace", null);
                return;
            }
            List<ActionNext> actionSiblings = SharedActionSiblings.resolve(dataSession, action);
            Date now = new Date();
            for (ActionNext sibling : actionSiblings) {
                sibling.setNextActionStatus(ProjectNextActionStatus.CANCELLED);
                sibling.setNextChangeDate(now);
                dataSession.update(sibling);
            }
            transaction.commit();
            actionRecoveryService.remember(appReq, action, ProjectNextActionStatus.CANCELLED);
            boolean requiresActionRefresh = deletingCurrentAction
                    && dashboardCurrentActionService.handoffCurrentAction(appReq);
            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("requiresActionRefresh", Boolean.valueOf(requiresActionRefresh));
            sendJsonResponse(appReq, true, "Action deleted", data);
        } catch (RuntimeException re) {
            transaction.rollback();
            throw re;
        }
    }

    private void handleRestoreRecentAction(AppReq appReq) throws Exception {
        String actionNextIdString = appReq.getRequest().getParameter("actionNextId");
        int actionNextId;
        try {
            actionNextId = Integer.parseInt(n(actionNextIdString).trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid action", null);
            return;
        }
        try {
            actionRecoveryService.restore(appReq, actionNextId);
            sendJsonResponse(appReq, true, "Action restored for today", null);
        } catch (IllegalArgumentException iae) {
            sendJsonResponse(appReq, false, iae.getMessage(), null);
        }
    }

    private void sendJsonResponse(AppReq appReq, boolean success, String message, Map<String, Object> data)
            throws Exception {
        appReq.getResponse().setContentType("application/json; charset=UTF-8");
        PrintWriter out = appReq.getResponse().getWriter();

        // Build JSON manually
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\":").append(success).append(",");
        json.append("\"message\":\"").append(escapeJson(message)).append("\"");

        if (data != null) {
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                json.append(",\"").append(entry.getKey()).append("\":");
                appendJsonValue(json, entry.getValue());
            }
        }

        json.append("}");
        out.println(json.toString());
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private void appendJsonValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof String) {
            json.append("\"").append(escapeJson((String) value)).append("\"");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else if (value instanceof Map) {
            json.append("{");
            boolean first = true;
            for (Map.Entry<Object, Object> entry : ((Map<Object, Object>) value).entrySet()) {
                if (!first) {
                    json.append(",");
                }
                json.append("\"").append(escapeJson(String.valueOf(entry.getKey()))).append("\":");
                appendJsonValue(json, entry.getValue());
                first = false;
            }
            json.append("}");
        } else if (value instanceof Iterable) {
            json.append("[");
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    json.append(",");
                }
                appendJsonValue(json, item);
                first = false;
            }
            json.append("]");
        } else if (value.getClass().isArray()) {
            json.append("[");
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    json.append(",");
                }
                appendJsonValue(json, java.lang.reflect.Array.get(value, i));
            }
            json.append("]");
        } else {
            json.append("\"").append(escapeJson(value.toString())).append("\"");
        }
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20 || ch == '\u2028' || ch == '\u2029') {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
    }

    private void handleLoadReprioritizeData(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);

        Session dataSession = appReq.getDataSession();
        ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);

        if (action == null) {
            sendJsonResponse(appReq, false, "Action not found", null);
            return;
        }

        // Build the today model to get all actions in the same section as this one
        DashboardTodayColumnModel todayModel = dashboardTodayColumnService.buildModel(appReq);

        // Find which section/table this action belongs to
        String currentActionSection = null;
        List<DashboardTodayColumnModel.TodayActionItemModel> sectionItems = null;

        for (DashboardTodayColumnModel.TodayActionGroupModel group : todayModel.getActionGroups()) {
            for (DashboardTodayColumnModel.TodayActionItemModel item : group.getItems()) {
                if (item.getActionNextId() == actionNextId) {
                    currentActionSection = group.getTitle();
                    sectionItems = group.getItems();
                    break;
                }
            }
            if (sectionItems != null) {
                break;
            }
        }

        if (sectionItems == null) {
            for (DashboardTodayColumnModel.TodayActionItemModel item : todayModel.getCompletedToday()) {
                if (item.getActionNextId() == actionNextId) {
                    currentActionSection = "Completed";
                    sectionItems = todayModel.getCompletedToday();
                    break;
                }
            }
        }

        if (sectionItems == null || currentActionSection == null) {
            sendJsonResponse(appReq, false, "Action is not available in a today section", null);
            return;
        }

        if (!isReprioritizableTodaySection(currentActionSection)) {
            sendJsonResponse(appReq, false, "This section cannot be reprioritized", null);
            return;
        }

        // Load all actions in the section (excluding current one)
        List<Map<String, Object>> actionList = new ArrayList<>();
        for (DashboardTodayColumnModel.TodayActionItemModel itemModel : sectionItems) {
            int sectionActionId = itemModel.getActionNextId();
            if (sectionActionId != actionNextId) {
                ActionNext sectionAction = (ActionNext) dataSession.get(ActionNext.class,
                        sectionActionId);
                if (sectionAction != null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", sectionAction.getActionNextId());
                    item.put("description",
                            sectionAction.getNextDescription() != null ? sectionAction.getNextDescription() : "");
                    item.put("order", sectionAction.getCompletionOrder());
                    actionList.add(item);
                }
            }
        }

        // Sort by completion order
        actionList.sort((a, b) -> {
            int orderA = ((Number) a.get("order")).intValue();
            int orderB = ((Number) b.get("order")).intValue();
            return orderA - orderB;
        });

        // Build response
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("description", action.getNextDescription() != null ? action.getNextDescription() : "");
        data.put("actions", actionList);

        sendJsonResponse(appReq, true, "OK", data);
    }

    private boolean isReprioritizableTodaySection(String sectionTitle) {
        return !"Completed".equals(sectionTitle)
                && !"Overdue".equals(sectionTitle)
                && !"Personal (Wake)".equals(sectionTitle)
                && !"Personal (Morning)".equals(sectionTitle)
                && !"Personal (Afternoon & Evening)".equals(sectionTitle)
                && !"Other".equals(sectionTitle);
    }

    private void handleReprioritizeAction(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);
        String moveType = appReq.getRequest().getParameter("moveType");

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();

        try {
            ActionNext currentAction = (ActionNext) dataSession.get(ActionNext.class,
                    actionNextId);
            if (currentAction == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            // Build the today model to get all actions in the same section
            DashboardTodayColumnModel todayModel = dashboardTodayColumnService.buildModel(appReq);

            // Find which section this action belongs to and get all section actions
            List<ActionNext> sectionActions = new ArrayList<>();
            int actionIndex = -1;

            for (DashboardTodayColumnModel.TodayActionGroupModel group : todayModel.getActionGroups()) {
                List<Integer> groupActionIds = new ArrayList<>();
                int currentIndex = -1;
                for (DashboardTodayColumnModel.TodayActionItemModel item : group.getItems()) {
                    groupActionIds.add(item.getActionNextId());
                    if (item.getActionNextId() == actionNextId) {
                        currentIndex = groupActionIds.size() - 1;
                    }
                }

                if (currentIndex >= 0) {
                    // This is the correct section
                    for (Integer id : groupActionIds) {
                        ActionNext action = (ActionNext) dataSession.get(ActionNext.class, id);
                        if (action != null) {
                            sectionActions.add(action);
                        }
                    }
                    actionIndex = currentIndex;
                    break;
                }
            }

            // Handle completed section if needed
            if (actionIndex < 0) {
                List<Integer> completedIds = new ArrayList<>();
                int currentIndex = -1;
                for (DashboardTodayColumnModel.TodayActionItemModel item : todayModel.getCompletedToday()) {
                    completedIds.add(item.getActionNextId());
                    if (item.getActionNextId() == actionNextId) {
                        currentIndex = completedIds.size() - 1;
                    }
                }

                if (currentIndex >= 0) {
                    for (Integer id : completedIds) {
                        ActionNext action = (ActionNext) dataSession.get(ActionNext.class, id);
                        if (action != null) {
                            sectionActions.add(action);
                        }
                    }
                    actionIndex = currentIndex;
                }
            }

            if (actionIndex < 0 || sectionActions.isEmpty()) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action section not found", null);
                return;
            }

            // Sort section actions by completion order
            sectionActions.sort((a, b) -> a.getCompletionOrder() - b.getCompletionOrder());

            // Find the action again after sorting (it may have moved)
            actionIndex = -1;
            for (int i = 0; i < sectionActions.size(); i++) {
                if (sectionActions.get(i).getActionNextId() == actionNextId) {
                    actionIndex = i;
                    break;
                }
            }

            if (actionIndex < 0) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found in section", null);
                return;
            }

            // Perform the move operation
            int targetIndex = -1;

            if ("first".equals(moveType)) {
                targetIndex = 0;
            } else if ("up".equals(moveType)) {
                targetIndex = actionIndex - 1;
            } else if ("down".equals(moveType)) {
                targetIndex = actionIndex + 1;
            } else if ("last".equals(moveType)) {
                targetIndex = sectionActions.size() - 1;
            } else if ("before".equals(moveType)) {
                String targetActionIdStr = appReq.getRequest().getParameter("targetActionId");
                int targetActionId = Integer.parseInt(targetActionIdStr);
                for (int i = 0; i < sectionActions.size(); i++) {
                    if (sectionActions.get(i).getActionNextId() == targetActionId) {
                        targetIndex = i;
                        break;
                    }
                }
            }

            // Validate target index
            if (targetIndex < 0 || targetIndex >= sectionActions.size() || targetIndex == actionIndex) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Invalid move operation", null);
                return;
            }

            // Perform the swap
            ActionNext targetAction = sectionActions.get(targetIndex);
            int currentOrder = currentAction.getCompletionOrder();
            int targetOrder = targetAction.getCompletionOrder();

            currentAction.setCompletionOrder(targetOrder);
            currentAction.setNextChangeDate(new Date());
            targetAction.setCompletionOrder(currentOrder);
            targetAction.setNextChangeDate(new Date());

            dataSession.update(currentAction);
            dataSession.update(targetAction);
            transaction.commit();

            sendJsonResponse(appReq, true, "Action moved successfully", null);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error moving action: " + e.getMessage(), null);
        }
    }

    private void handleLoadRescheduleData(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);

        WebUser webUser = appReq.getWebUser();

        ActionNext action = (ActionNext) appReq.getDataSession()
                .get(ActionNext.class, actionNextId);

        if (action == null) {
            sendJsonResponse(appReq, false, "Action not found", null);
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("success", true);
        data.put("description", action.getNextDescription() != null ? action.getNextDescription() : "");
        data.put("nextActionDate", formatUserDate(webUser, action.getNextActionDate()));

        sendJsonResponse(appReq, true, "OK", data);
    }

    private void handleRescheduleAction(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        int actionNextId = Integer.parseInt(actionNextIdStr);
        String nextActionDateStr = appReq.getRequest().getParameter("nextActionDate");

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();

        try {
            ActionNext action = (ActionNext) dataSession
                    .get(ActionNext.class, actionNextId);

            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            ActionNext currentAction = appReq.getCompletingAction();
            Integer currentActionId = currentAction == null
                    ? null
                    : Integer.valueOf(currentAction.getActionNextId());
            LocalDate previousActionDate = toStoredLocalDate(action.getNextActionDate(), appReq.getWebUser());

            if (nextActionDateStr != null && nextActionDateStr.length() > 0) {
                Date parsedDate = appReq.getWebUser().parseDate(nextActionDateStr);
                if (parsedDate == null) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Invalid date format", null);
                    return;
                }

                LocalDate newDate = toStoredLocalDate(parsedDate, appReq.getWebUser());
                if (newDate != null && newDate.isBefore(appReq.getWebUser().getLocalDateToday())) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Cannot schedule action to a past date", null);
                    return;
                }

                Date updatedDate = java.sql.Date.valueOf(newDate);
                if (!sameDate(action.getNextActionDate(), updatedDate)) {
                    action.setNextActionDate(updatedDate);
                    action.setCompletionOrder(0);
                    action.setNextChangeDate(new Date());
                }
            }

            // Save the updated action
            dataSession.update(action);
            transaction.commit();

            LocalDate savedActionDate = toStoredLocalDate(action.getNextActionDate(), appReq.getWebUser());
            boolean requiresActionRefresh = dashboardCurrentActionService.shouldHandoffCurrentAction(
                    currentActionId, Integer.valueOf(actionNextId), previousActionDate, savedActionDate,
                    appReq.getWebUser().getLocalDateToday())
                    && dashboardCurrentActionService.handoffCurrentAction(appReq);

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("requiresActionRefresh", Boolean.valueOf(requiresActionRefresh));
            sendJsonResponse(appReq, true, "Action rescheduled successfully", data);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error rescheduling action: " + e.getMessage(), null);
        }
    }

    private void handleStartActionNow(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        if (actionNextIdStr == null || actionNextIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Action is required", null);
            return;
        }

        int actionNextId;
        try {
            actionNextId = Integer.parseInt(actionNextIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid action", null);
            return;
        }

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            WebUser webUser = appReq.getWebUser();
            Date now = new Date();
            List<ActionNext> actionSiblings = SharedActionSiblings.resolve(dataSession, action);
            for (ActionNext sibling : actionSiblings) {
                sibling.setNextActionDate(java.sql.Date.valueOf(webUser.getLocalDateToday()));
                sibling.setNextChangeDate(now);
                dataSession.update(sibling);
            }
            transaction.commit();

            appReq.setCompletingAction(action);
            if (action.getProject() != null) {
                appReq.setProject(action.getProject());
            }

            sendJsonResponse(appReq, true, "Action started", null);
        } catch (Exception e) {
            transaction.rollback();
            sendJsonResponse(appReq, false, "Unable to start action: " + e.getMessage(), null);
        }
    }

    private void handleRefreshHeaderGauges(AppReq appReq) throws Exception {
        Session dataSession = appReq.getDataSession();
        ActionNext completingAction = appReq.getCompletingAction();

        // Keep the running billing entry synchronized with real elapsed time.
        if (completingAction != null) {
            ActionNext persistedAction = (ActionNext) dataSession.get(ActionNext.class,
                    completingAction.getActionNextId());
            if (persistedAction != null) {
                appReq.setCompletingAction(persistedAction);
                if (appReq.getTimeTracker() != null && appReq.getTimeTracker().isRunningClock()) {
                    appReq.getTimeTracker().update(persistedAction, dataSession);
                }
            }
        }

        TimeGaugeModel nowGaugeModel = dashboardTimeGaugeService.buildNowGauge(appReq);
        DashboardTodayColumnModel todayColumnModel = dashboardTodayColumnService.buildModel(appReq);
        int todayTargetMinutes = dayCapacityService.loadTargetMinutesForDay(appReq,
                appReq.getWebUser().toDate(appReq.getWebUser().getLocalDateToday()));
        TimeGaugeModel todayGaugeModel = dashboardTimeGaugeService.buildTodayGauge(appReq, todayTargetMinutes);
        dashboardTimeGaugeService.updateTodayGaugePlanned(todayGaugeModel,
                todayColumnModel.getTotals().getPlannedMinutes(), todayTargetMinutes);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nowGaugeHtml", renderGaugeHtml(nowGaugeModel));
        data.put("todayGaugeHtml", renderGaugeHtml(todayGaugeModel));
        data.put("todayCurrentTime", formatCurrentUserTime(appReq.getWebUser()));
        sendJsonResponse(appReq, true, "OK", data);
    }

    private void handleAddIssue(AppReq appReq) throws Exception {
        String projectIdStr = appReq.getRequest().getParameter("projectId");
        String issueText = clip(appReq.getRequest().getParameter("issueText"), 1200);
        String issueTypeStr = clip(appReq.getRequest().getParameter("issueType"), 20);

        if (projectIdStr == null || projectIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Project id is required", null);
            return;
        }
        if (issueText.length() == 0) {
            sendJsonResponse(appReq, false, "Issue text is required", null);
            return;
        }
        if (issueTypeStr.length() == 0) {
            sendJsonResponse(appReq, false, "Issue type is required", null);
            return;
        }

        int projectId;
        try {
            projectId = Integer.parseInt(projectIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid project id", null);
            return;
        }

        Session dataSession = appReq.getDataSession();
        Project project = (Project) dataSession.get(Project.class, projectId);
        if (project == null) {
            sendJsonResponse(appReq, false, "Project not found", null);
            return;
        }

        org.openimmunizationsoftware.pt.model.ProjectIssueType issueType = org.openimmunizationsoftware.pt.model.ProjectIssueType
                .fromString(issueTypeStr);

        Transaction transaction = dataSession.beginTransaction();
        try {
            org.openimmunizationsoftware.pt.doa.ProjectIssueDao dao = new org.openimmunizationsoftware.pt.doa.ProjectIssueDao(
                    dataSession);
            dao.createIssue(project, issueText, issueType);
            transaction.commit();
            sendJsonResponse(appReq, true, "Issue created", null);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error creating issue: " + e.getMessage(), null);
        }
    }

    private void handleEditIssue(AppReq appReq) throws Exception {
        String issueIdStr = appReq.getRequest().getParameter("projectIssueId");
        String issueText = clip(appReq.getRequest().getParameter("issueText"), 1200);
        String issueTypeStr = clip(appReq.getRequest().getParameter("issueType"), 20);
        String resolvedStr = appReq.getRequest().getParameter("resolved");

        if (issueIdStr == null || issueIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Issue id is required", null);
            return;
        }
        if (issueText.length() == 0) {
            sendJsonResponse(appReq, false, "Issue text is required", null);
            return;
        }
        if (issueTypeStr.length() == 0) {
            sendJsonResponse(appReq, false, "Issue type is required", null);
            return;
        }

        int issueId;
        try {
            issueId = Integer.parseInt(issueIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid issue id", null);
            return;
        }

        boolean resolve = "1".equals(resolvedStr);

        Session dataSession = appReq.getDataSession();
        org.openimmunizationsoftware.pt.doa.ProjectIssueDao dao = new org.openimmunizationsoftware.pt.doa.ProjectIssueDao(
                dataSession);
        org.openimmunizationsoftware.pt.model.ProjectIssue issue = dao.getById(issueId);
        if (issue == null) {
            sendJsonResponse(appReq, false, "Issue not found", null);
            return;
        }

        org.openimmunizationsoftware.pt.model.ProjectIssueType issueType = org.openimmunizationsoftware.pt.model.ProjectIssueType
                .fromString(issueTypeStr);

        Transaction transaction = dataSession.beginTransaction();
        try {
            dao.updateIssue(issue, issueText, issueType, resolve);
            transaction.commit();
            sendJsonResponse(appReq, true, "Issue updated", null);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error updating issue: " + e.getMessage(), null);
        }
    }

    private void handleAddAiThought(AppReq appReq) throws Exception {
        String projectIdStr = appReq.getRequest().getParameter("projectId");
        String noteText = clip(appReq.getRequest().getParameter("noteText"), 4000);

        if (projectIdStr == null || projectIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Project id is required", null);
            return;
        }
        if (noteText.length() == 0) {
            sendJsonResponse(appReq, false, "Note text is required", null);
            return;
        }

        int projectId;
        try {
            projectId = Integer.parseInt(projectIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid project id", null);
            return;
        }

        Integer workspaceId = appReq.getActiveWorkspaceId();
        if (workspaceId == null) {
            sendJsonResponse(appReq, false, "No active workspace", null);
            return;
        }
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            new org.dandeliondaily.projectainote.service.ProjectAiNoteService().create(dataSession,
                    workspaceId.intValue(), projectId, noteText, "manual");
            transaction.commit();
            sendJsonResponse(appReq, true, "AI thought added", null);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error adding AI thought: " + e.getMessage(), null);
        }
    }

    private void handleEditAiThought(AppReq appReq) throws Exception {
        String noteIdStr = appReq.getRequest().getParameter("noteId");
        String noteText = clip(appReq.getRequest().getParameter("noteText"), 4000);

        if (noteIdStr == null || noteIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Note id is required", null);
            return;
        }
        if (noteText.length() == 0) {
            sendJsonResponse(appReq, false, "Note text is required", null);
            return;
        }

        int noteId;
        try {
            noteId = Integer.parseInt(noteIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid note id", null);
            return;
        }

        Integer workspaceId = appReq.getActiveWorkspaceId();
        if (workspaceId == null) {
            sendJsonResponse(appReq, false, "No active workspace", null);
            return;
        }
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            new org.dandeliondaily.projectainote.service.ProjectAiNoteService().update(dataSession,
                    workspaceId.intValue(), noteId, noteText);
            transaction.commit();
            sendJsonResponse(appReq, true, "AI thought updated", null);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error updating AI thought: " + e.getMessage(), null);
        }
    }

    private void handleDeleteAiThought(AppReq appReq) throws Exception {
        String noteIdStr = appReq.getRequest().getParameter("noteId");
        if (noteIdStr == null || noteIdStr.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Note id is required", null);
            return;
        }

        int noteId;
        try {
            noteId = Integer.parseInt(noteIdStr.trim());
        } catch (NumberFormatException nfe) {
            sendJsonResponse(appReq, false, "Invalid note id", null);
            return;
        }

        Integer workspaceId = appReq.getActiveWorkspaceId();
        if (workspaceId == null) {
            sendJsonResponse(appReq, false, "No active workspace", null);
            return;
        }
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            new org.dandeliondaily.projectainote.service.ProjectAiNoteService().delete(dataSession,
                    workspaceId.intValue(), noteId);
            transaction.commit();
            sendJsonResponse(appReq, true, "AI thought deleted", null);
        } catch (Exception e) {
            transaction.rollback();
            e.printStackTrace();
            sendJsonResponse(appReq, false, "Error deleting AI thought: " + e.getMessage(), null);
        }
    }

    private void handleSaveProjectEdit(AppReq appReq) throws Exception {
        handleSaveProject(appReq, false);
    }

    private void handleSaveProjectCreate(AppReq appReq) throws Exception {
        handleSaveProject(appReq, true);
    }

    private void handleSaveProject(AppReq appReq, boolean createMode) throws Exception {
        String projectIdStr = appReq.getRequest().getParameter("projectId");
        if (!createMode && (projectIdStr == null || projectIdStr.trim().length() == 0)) {
            sendJsonResponse(appReq, false, "Project id is required", null);
            return;
        }

        int projectId = 0;
        if (!createMode) {
            try {
                projectId = Integer.parseInt(projectIdStr.trim());
            } catch (NumberFormatException nfe) {
                sendJsonResponse(appReq, false, "Invalid project id", null);
                return;
            }
        }

        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            WebUser webUser = appReq.getWebUser();
            Integer activeWorkspaceId = resolveWorkspaceContextIdForProjectEdit(appReq, webUser, dataSession);
            if (webUser == null || activeWorkspaceId == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Workspace is not available for this user", null);
                return;
            }
            if (!WorkspaceRegistry.canAdministerWorkspace(dataSession, activeWorkspaceId, webUser.getWebUserId())) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Workspace is not available for this user", null);
                return;
            }
            Project project;
            if (createMode) {
                project = new Project();
                project.setWorkspaceId(activeWorkspaceId);
                project.setCreatedByWebUserId(webUser.getWebUserId());
            } else {
                project = (Project) dataSession.get(Project.class, projectId);
                if (project == null) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Project not found", null);
                    return;
                }
                if (project.getWorkspaceId() == null || !project.getWorkspaceId().equals(activeWorkspaceId)) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Project is not available for this user", null);
                    return;
                }
            }

            String projectName = clip(appReq.getRequest().getParameter("projectName"), 100);
            String projectHandle = HandleValidationSupport.resolveHandle(
                    appReq.getRequest().getParameter("projectHandle"), projectName, 60);
            String projectIconInput = appReq.getRequest().getParameter("projectIcon");
            String description = clip(appReq.getRequest().getParameter("description"), 1200);
            String currentFocusText = clip(appReq.getRequest().getParameter("currentFocusText"), 12000);
            String outcomeText = clip(appReq.getRequest().getParameter("outcomeText"), 12000);
            String successCriteriaText = clip(appReq.getRequest().getParameter("successCriteriaText"), 12000);
            String projectStatus = clip(appReq.getRequest().getParameter("projectStatus"), 20);
            projectStatus = normalizeProjectStatus(projectStatus);
            List<Integer> requestedProjectTagIds = parseProjectTagIds(
                    appReq.getRequest().getParameterValues("projectTagIds"));
            String billCode = clip(appReq.getRequest().getParameter("billCode"), 15);
            String updateEveryStr = clip(appReq.getRequest().getParameter("updateEvery"), 8);
            String linkedPatchWorkspaceIdStr = appReq.getRequest().getParameter("linkedPatchWorkspaceId");
            Integer newLinkedPatchWorkspaceId = null;
            if (linkedPatchWorkspaceIdStr != null && linkedPatchWorkspaceIdStr.trim().length() > 0) {
                try {
                    newLinkedPatchWorkspaceId = Integer.valueOf(Integer.parseInt(linkedPatchWorkspaceIdStr.trim()));
                } catch (NumberFormatException nfe) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Invalid linked patch workspace id", null);
                    return;
                }
            }

            if (!createMode && project.isExternalManaged()) {
                String existingStatus = normalizeProjectStatus(project.getProjectStatus());
                List<Integer> existingProjectTagIds = loadProjectTagIds(dataSession, project.getProjectId());
                if (!sameString(project.getProjectName(), projectName)
                        || !sameString(project.getDescription(), description)
                        || !sameString(project.getProjectHandle(), projectHandle)
                        || !sameString(existingStatus, projectStatus)
                        || !sameIntegerContent(existingProjectTagIds, requestedProjectTagIds)) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false,
                            "Project name, handle, description, status, and tags are externally managed and cannot be edited here.",
                            null);
                    return;
                }
            }

            if (projectName.length() == 0) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Project name is required", null);
                return;
            }

            boolean closedPhase = ProjectStatus.CLOSED.getDatabaseValue().equalsIgnoreCase(projectStatus);
            if (!closedPhase && projectHandle.length() == 0) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Project handle is required for active projects", null);
                return;
            }

            String handleValidationMessage = HandleValidationSupport.validateHandleCharacters("Project handle",
                    projectHandle);
            if (handleValidationMessage != null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, handleValidationMessage, null);
                return;
            }

            Query uniqueQuery = dataSession.createQuery(
                    "select count(*) from Project where workspaceId = :workspaceId and lower(projectHandle) = :projectHandle and projectId <> :projectId and projectStatus <> :closedStatus");
            uniqueQuery.setParameter("workspaceId", activeWorkspaceId);
            uniqueQuery.setParameter("projectHandle", projectHandle.toLowerCase());
            uniqueQuery.setParameter("projectId", createMode ? -1 : projectId);
            uniqueQuery.setParameter("closedStatus", ProjectStatus.CLOSED.getDatabaseValue());
            Number duplicateCount = (Number) uniqueQuery.uniqueResult();
            if (duplicateCount != null && duplicateCount.intValue() > 0) {
                transaction.rollback();
                sendJsonResponse(appReq, false,
                        "Project handle must be unique among active projects in this workspace", null);
                return;
            }

            int updateEvery = 0;
            if (updateEveryStr.length() > 0) {
                try {
                    updateEvery = Integer.parseInt(updateEveryStr);
                } catch (NumberFormatException nfe) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Update Every must be a number", null);
                    return;
                }
            }
            if (updateEvery < 0) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Update Every must be zero or greater", null);
                return;
            }

            String projectIcon;
            try {
                projectIcon = ProjectIconService.normalize(projectIconInput);
            } catch (ProjectIconService.InvalidIconException invalidIcon) {
                transaction.rollback();
                sendJsonResponse(appReq, false, invalidIcon.getMessage(), null);
                return;
            }
            if (billCode.length() == 0) {
                billCode = null;
            }

            project.setProjectName(projectName);
            project.setProjectHandle(projectHandle.length() > 0 ? projectHandle : null);
            if (closedPhase) {
                project.setPriorityLevel(0);
            }
            project.setProjectIcon(projectIcon);
            project.setDescription(description);
            project.setCurrentFocusText(currentFocusText.length() > 0 ? currentFocusText : null);
            // Collected now for future Project Health reporting and project briefing use.
            project.setOutcomeText(outcomeText.length() > 0 ? outcomeText : null);
            // Intentionally store raw newline-separated criteria text for now.
            project.setSuccessCriteriaText(successCriteriaText.length() > 0 ? successCriteriaText : null);
            project.setProjectStatus(projectStatus);
            if (webUser.isTrackTime()) {
                project.setBillCode(billCode);
            }
            project.setWebUser(webUser);
            project.setWorkspaceId(activeWorkspaceId);
            project.setLastModifiedByWebUserId(webUser.getWebUserId());
            project.setLastModifiedDate(new Date());
            if (createMode && project.getCreatedByWebUserId() == null) {
                project.setCreatedByWebUserId(webUser.getWebUserId());
            }

            Workspace projectWorkspace = (Workspace) dataSession.get(Workspace.class, activeWorkspaceId);
            boolean isPrivateWorkspace = projectWorkspace != null
                    && Workspace.TYPE_PRIVATE.equals(projectWorkspace.getWorkspaceType());
            if (isPrivateWorkspace) {
                Integer currentLinkedPatchWorkspaceId = createMode ? null : project.getLinkedPatchWorkspaceId();
                boolean changing = (newLinkedPatchWorkspaceId == null && currentLinkedPatchWorkspaceId != null)
                        || (newLinkedPatchWorkspaceId != null
                                && !newLinkedPatchWorkspaceId.equals(currentLinkedPatchWorkspaceId));
                if (changing && !createMode
                        && new org.openimmunizationsoftware.pt.doa.ProjectPatchLinkDao(dataSession)
                                .hasLinksForProject(project.getProjectId())) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false,
                            "Remove all project links before changing the linked patch workspace", null);
                    return;
                }
                if (changing && newLinkedPatchWorkspaceId != null
                        && !WorkspaceRegistry.hasActiveMembership(dataSession,
                                newLinkedPatchWorkspaceId.intValue(), webUser.getWebUserId())) {
                    transaction.rollback();
                    sendJsonResponse(appReq, false, "Patch workspace is not accessible", null);
                    return;
                }
                project.setLinkedPatchWorkspaceId(newLinkedPatchWorkspaceId);
            }
            dataSession.saveOrUpdate(project);
            if (createMode) {
                dataSession.flush();
            }
            syncProjectTags(dataSession, project.getProjectId(), activeWorkspaceId.intValue(), requestedProjectTagIds);

            ProjectContactAssigned projectContactAssigned = loadProjectContactAssigned(webUser, dataSession, project);
            if (projectContactAssigned == null) {
                projectContactAssigned = new ProjectContactAssigned();
                projectContactAssigned.setId(new ProjectContactAssignedId());
                projectContactAssigned.getId().setContactId(webUser.getContactId());
                projectContactAssigned.getId().setProjectId(project.getProjectId());
                projectContactAssigned.setEmailAlert("Y");
                dataSession.save(projectContactAssigned);
            }
            projectContactAssigned.setUpdateDue(updateEvery);
            dataSession.saveOrUpdate(projectContactAssigned);

            if (!closedPhase) {
                projectHealthPageService.normalizeOpenProjectPriorities(webUser, dataSession);
            }

            ActionNext setupAction = null;
            if (createMode) {
                setupAction = new ActionNext();
                setupAction.setProject(project);
                setupAction.setProjectId(project.getProjectId());
                setupAction.setContactId(webUser.getContactId());
                setupAction.setContact(webUser.getProjectContact());
                setupAction.setWorkspaceId(activeWorkspaceId);
                setupAction.setNextActionType(ProjectNextActionType.WILL);
                setupAction.setNextActionDate(java.sql.Date.valueOf(webUser.getLocalDateToday()));
                setupAction.setNextDescription("setup new project");
                setupAction.setNextTimeEstimate(5);
                setupAction.setNextActionStatus(ProjectNextActionStatus.READY);
                setupAction.setNextChangeDate(new Date());
                setupAction.setBillable(project.getBillCode() != null && project.getBillCode().trim().length() > 0);
                setupAction.setActionSet(new ActionSetDao(dataSession).createStandardActionSet(webUser));
                dataSession.save(setupAction);
            }

            transaction.commit();

            appReq.setProject(project);
            if (createMode && setupAction != null) {
                appReq.setCompletingAction(setupAction);
            }
            sendJsonResponse(appReq, true, createMode ? "Project created" : "Project saved", null);
        } catch (Exception e) {
            transaction.rollback();
            sendJsonResponse(appReq, false,
                    (createMode ? "Unable to create project: " : "Unable to save project: ") + e.getMessage(),
                    null);
        }
    }

    private Integer resolveWorkspaceContextIdForProjectEdit(AppReq appReq, WebUser webUser, Session dataSession) {
        if (webUser == null) {
            return null;
        }
        Integer workspaceId = appReq.getActiveWorkspaceId();
        String workspaceContextIdStr = appReq.getRequest().getParameter("workspaceContextId");
        if (workspaceContextIdStr == null || workspaceContextIdStr.trim().length() == 0) {
            return workspaceId;
        }
        try {
            Integer requestedWorkspaceId = Integer.valueOf(Integer.parseInt(workspaceContextIdStr.trim()));
            if (WorkspaceRegistry.hasActiveMembership(dataSession, requestedWorkspaceId.intValue(),
                    webUser.getWebUserId())) {
                return requestedWorkspaceId;
            }
        } catch (Exception e) {
            return workspaceId;
        }
        return workspaceId;
    }

    private void syncProjectTags(Session dataSession, int projectId, int workspaceId, List<Integer> requestedTagIds) {
        Query deleteQuery = dataSession.createQuery("delete from ProjectTagMap where projectId = :projectId");
        deleteQuery.setParameter("projectId", projectId);
        deleteQuery.executeUpdate();

        if (requestedTagIds == null || requestedTagIds.isEmpty()) {
            return;
        }

        Query validTagQuery = dataSession.createQuery(
                "select projectTagId from ProjectTag where workspaceId = :workspaceId and tagStatus = :tagStatus and projectTagId in (:tagIds)");
        validTagQuery.setParameter("workspaceId", workspaceId);
        validTagQuery.setParameter("tagStatus", ProjectTag.STATUS_ACTIVE);
        validTagQuery.setParameterList("tagIds", requestedTagIds);
        @SuppressWarnings("unchecked")
        List<Integer> validTagIds = validTagQuery.list();

        for (Integer tagId : validTagIds) {
            ProjectTagMap map = new ProjectTagMap();
            map.setProjectId(projectId);
            map.setProjectTagId(tagId.intValue());
            map.setCreatedDate(new Date());
            dataSession.save(map);
        }
    }

    private List<Integer> parseProjectTagIds(String[] values) {
        List<Integer> tagIds = new ArrayList<Integer>();
        if (values == null) {
            return tagIds;
        }
        for (String value : values) {
            if (value == null || value.trim().length() == 0) {
                continue;
            }
            try {
                tagIds.add(Integer.valueOf(Integer.parseInt(value.trim())));
            } catch (NumberFormatException nfe) {
                // Ignore invalid values from request payload.
            }
        }
        return tagIds;
    }

    private List<Integer> loadProjectTagIds(Session dataSession, int projectId) {
        Query query = dataSession.createQuery(
                "select projectTagId from ProjectTagMap where projectId = :projectId");
        query.setParameter("projectId", projectId);
        @SuppressWarnings("unchecked")
        List<Integer> ids = query.list();
        return ids == null ? new ArrayList<Integer>() : ids;
    }

    private boolean sameIntegerContent(List<Integer> left, List<Integer> right) {
        List<Integer> normalizedLeft = normalizeIntegerList(left);
        List<Integer> normalizedRight = normalizeIntegerList(right);
        if (normalizedLeft.size() != normalizedRight.size()) {
            return false;
        }
        for (Integer value : normalizedLeft) {
            if (!normalizedRight.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> normalizeIntegerList(List<Integer> source) {
        List<Integer> normalized = new ArrayList<Integer>();
        if (source == null) {
            return normalized;
        }
        for (Integer value : source) {
            if (value == null || normalized.contains(value)) {
                continue;
            }
            normalized.add(value);
        }
        return normalized;
    }

    private String normalizeProjectStatus(String projectStatus) {
        return ProjectStatus.fromDatabaseValue(projectStatus).getDatabaseValue();
    }

    private String clip(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen);
    }

    private ProjectContactAssigned loadProjectContactAssigned(WebUser webUser, Session dataSession, Project project) {
        if (webUser == null || project == null) {
            return null;
        }
        ProjectContactAssignedId id = new ProjectContactAssignedId();
        id.setContactId(webUser.getContactId());
        id.setProjectId(project.getProjectId());
        return (ProjectContactAssigned) dataSession.get(ProjectContactAssigned.class, id);
    }

    private String formatUserDate(WebUser webUser, Date date) {
        LocalDate localDate = toStoredLocalDate(date, webUser);
        if (localDate == null) {
            return "";
        }
        return localDate.format(DateTimeFormatter.ofPattern(webUser.getDateEntryPattern()));
    }

    private Date normalizeUserDate(WebUser webUser, Date date) {
        LocalDate localDate = toStoredLocalDate(date, webUser);
        if (localDate == null) {
            return null;
        }
        return java.sql.Date.valueOf(localDate);
    }

    /**
     * Returns ISO YYYY-MM-DD string for a date, for use with native date picker
     * inputs.
     */
    private String toIsoDate(Date date, WebUser webUser) {
        LocalDate localDate = toStoredLocalDate(date, webUser);
        return localDate != null ? localDate.toString() : "";
    }

    /**
     * Parses a date string that may be in ISO (YYYY-MM-DD) format or the user's
     * entry format.
     * Returns null if the string cannot be parsed.
     */
    private Date parseIsoOrUserDate(WebUser webUser, String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }
        String trimmed = dateString.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return java.sql.Date.valueOf(trimmed);
            } catch (IllegalArgumentException e) {
                // fall through to user format
            }
        }
        return webUser.parseDate(trimmed);
    }

    private boolean sameDate(Date left, Date right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.equals(right);
    }

    private boolean sameString(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.equals(safeRight);
    }

    private LocalDate toStoredLocalDate(Date date, WebUser webUser) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return LocalDate.parse(sdf.format(date));
    }

    private String formatCurrentUserTime(WebUser webUser) {
        return webUser.getDateFormatService().formatPattern(new Date(), "hh:mm:ss aaa z", webUser.getTimeZone());
    }

    private String renderGaugeHtml(TimeGaugeModel model) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        new TimeGaugeRenderer().render(printWriter, model);
        printWriter.flush();
        return stringWriter.toString();
    }

    private void handleAddCurrentActionNote(AppReq appReq) throws Exception {
        String actionNextIdStr = appReq.getRequest().getParameter("actionNextId");
        String nextNote = appReq.getRequest().getParameter("nextNote");
        if (actionNextIdStr == null || nextNote == null || nextNote.trim().length() == 0) {
            sendJsonResponse(appReq, false, "Note is required", null);
            return;
        }

        int actionNextId = Integer.parseInt(actionNextIdStr);
        Session dataSession = appReq.getDataSession();
        Transaction transaction = dataSession.beginTransaction();
        try {
            ActionNext action = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
            if (action == null) {
                transaction.rollback();
                sendJsonResponse(appReq, false, "Action not found", null);
                return;
            }

            ActionNotesService.Result notesResult = actionNotesService.append(dataSession, action, nextNote,
                    new Date());
            if (!notesResult.isOk()) {
                transaction.rollback();
                sendJsonResponse(appReq, false, notesResult.getErrorMessage(), null);
                return;
            }
            transaction.commit();
            sendJsonResponse(appReq, true, "Note added", null);
        } catch (Exception e) {
            transaction.rollback();
            sendJsonResponse(appReq, false, "Unable to add note", null);
        }
    }

    private void handleStartTimer(AppReq appReq) {
        TimeTracker timeTracker = appReq.getTimeTracker();
        if (timeTracker == null) {
            return;
        }

        Session dataSession = appReq.getDataSession();
        Project project = appReq.getProject();
        ActionNext completingAction = appReq.getCompletingAction();

        String actionIdStr = appReq.getRequest().getParameter("completingActionNextId");
        if (actionIdStr != null && actionIdStr.trim().length() > 0) {
            try {
                int actionNextId = Integer.parseInt(actionIdStr.trim());
                ActionNext selected = (ActionNext) dataSession.get(ActionNext.class, actionNextId);
                if (selected != null) {
                    completingAction = selected;
                    appReq.setCompletingAction(selected);
                    if (selected.getProject() != null) {
                        project = selected.getProject();
                        appReq.setProject(project);
                    }
                }
            } catch (NumberFormatException nfe) {
                // Ignore invalid action id and continue with session state.
            }
        }

        if (project == null) {
            String projectIdStr = appReq.getRequest().getParameter("projectId");
            if (projectIdStr != null && projectIdStr.trim().length() > 0) {
                try {
                    int projectId = Integer.parseInt(projectIdStr.trim());
                    project = (Project) dataSession.get(Project.class, projectId);
                    if (project != null) {
                        appReq.setProject(project);
                    }
                } catch (NumberFormatException nfe) {
                    // Ignore invalid project id and continue with session state.
                }
            }
        }

        if (project != null) {
            timeTracker.startClock(project, completingAction, dataSession);
        }
    }
}
