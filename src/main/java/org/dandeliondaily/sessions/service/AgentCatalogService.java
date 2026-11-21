package org.dandeliondaily.sessions.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.manager.TrackerKeysManager;
import org.openimmunizationsoftware.pt.model.Agent;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * The agent list (the agent table) and each user's agent preferences: a default agent and an
 * optional model per agent, kept with the user's other settings in tracker_keys. The default
 * interactive agent is Claude Code until the user picks another; the model is unset by default,
 * which means the PC passes no model flag at all.
 */
public class AgentCatalogService {

    public static final String CLAUDE_CODE = "claude-code";
    public static final String CODEX = "codex";
    public static final String DEFAULT_AGENT = CLAUDE_CODE;

    public static final String CAP_NEW = "new";
    public static final String CAP_START = "start-with-prompt";
    public static final String CAP_SESSION_ID_AT_LAUNCH = "session-id-at-launch";
    public static final String CAP_RESUME = "resume-by-id";
    public static final String CAP_HEADLESS = "headless";
    public static final String CAP_PULL = "pull";

    static final String KEY_DEFAULT_AGENT = "agent.default";
    static final String KEY_MODEL_PREFIX = "agent.model.";
    static final String KEY_LAUNCHER_OPT_IN = "agent.launcher.opt_in";
    public static final int MAX_MODEL_LENGTH = 80;

    @SuppressWarnings("unchecked")
    public List<Agent> listActive(Session session) {
        Query query = session.createQuery("from Agent where activeFlag = 'Y' order by sortOrder, agentKey");
        return query.list();
    }

    /** The active agent with this key, or null. */
    public Agent findActive(Session session, String agentKey) {
        if (agentKey == null) {
            return null;
        }
        Agent agent = (Agent) session.get(Agent.class, agentKey);
        return agent != null && "Y".equals(agent.getActiveFlag()) ? agent : null;
    }

    public static boolean hasCapability(Agent agent, String capability) {
        if (agent == null || agent.getCapabilities() == null) {
            return false;
        }
        return Arrays.asList(agent.getCapabilities().replace(" ", "").split(",")).contains(capability);
    }

    /** The user's default agent if it is still active, otherwise Claude Code. */
    public String defaultAgent(Session session, WebUser webUser) {
        String key = TrackerKeysManager.getUserKeyValueNoFallback(KEY_DEFAULT_AGENT, DEFAULT_AGENT, webUser, session);
        return findActive(session, key) != null ? key : DEFAULT_AGENT;
    }

    /** The user's model for this agent, or null when unset (no model flag is passed). */
    public String model(Session session, WebUser webUser, String agentKey) {
        String value = TrackerKeysManager.getUserKeyValueNoFallback(KEY_MODEL_PREFIX + agentKey, "", webUser, session);
        return value == null || value.trim().length() == 0 ? null : value.trim();
    }

    /** Model names are passed to the PC as data; keep them to a safe, flag-free shape. */
    public static String validateModel(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) {
            return null;
        }
        if (value.length() > MAX_MODEL_LENGTH || !value.matches("[A-Za-z0-9][A-Za-z0-9._:\\[\\]-]*")) {
            throw new IllegalArgumentException("A model name may use letters, digits and . _ - : [ ] only.");
        }
        return value;
    }

    /** Saves the default agent and per-agent models. Each save runs in its own small transaction. */
    public void savePreferences(Session session, WebUser webUser, String defaultAgent, Map<String, String> models) {
        if (defaultAgent != null) {
            if (findActive(session, defaultAgent) == null) {
                throw new IllegalArgumentException("Unknown agent \"" + defaultAgent + "\".");
            }
        }
        Map<String, String> validated = new LinkedHashMap<String, String>();
        if (models != null) {
            for (Map.Entry<String, String> entry : models.entrySet()) {
                if (findActive(session, entry.getKey()) == null) {
                    throw new IllegalArgumentException("Unknown agent \"" + entry.getKey() + "\".");
                }
                String model = validateModel(entry.getValue());
                validated.put(entry.getKey(), model == null ? "" : model);
            }
        }
        if (defaultAgent != null) {
            TrackerKeysManager.saveKeyValue(KEY_DEFAULT_AGENT, webUser, defaultAgent, session);
        }
        for (Map.Entry<String, String> entry : validated.entrySet()) {
            TrackerKeysManager.saveKeyValue(KEY_MODEL_PREFIX + entry.getKey(), webUser, entry.getValue(), session);
        }
    }

    public static Map<String, Object> toMap(Agent agent) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("agentKey", agent.getAgentKey());
        map.put("displayName", agent.getDisplayName());
        map.put("integrationStyle", agent.getIntegrationStyle());
        List<String> capabilities = new ArrayList<String>();
        for (String cap : agent.getCapabilities().split(",")) {
            if (cap.trim().length() > 0) {
                capabilities.add(cap.trim());
            }
        }
        map.put("capabilities", capabilities);
        return map;
    }

    /** "Claude" / "Codex" for short button labels. */
    public static String shortName(String agentKey) {
        if (CLAUDE_CODE.equals(agentKey)) {
            return "Claude";
        }
        if (CODEX.equals(agentKey)) {
            return "Codex";
        }
        return agentKey;
    }
}
