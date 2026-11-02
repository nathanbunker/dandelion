package org.dandeliondaily.focus.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.LinkedMeeting;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNextActionType;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Today's meetings for the Focused Action page, with their linked meeting times. The Meetings
 * row, the Meetings drawer and the clock arcs all read this list so they always agree.
 *
 * Scope: READY WILL_MEET actions dated today. Meetings whose linked occurrence is CANCELLED at the
 * source are skipped. Meetings created by hand have no linked row and so no time.
 */
public class FocusMeetingService {

    public static final String SOURCE_STATUS_CANCELLED = "CANCELLED";

    /** Meeting-start sounds; the browser synthesizes each one. "off" is the default. */
    public static final String CHIME_OFF = "off";
    public static final List<String> CHIMES = java.util.Arrays.asList(CHIME_OFF, "chime", "bell", "rise");

    /** The stored value when it is a known sound, otherwise null. */
    public static String normalizeChime(String value) {
        String chime = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        return CHIMES.contains(chime) ? chime : null;
    }

    public static class TodayMeeting {
        private final ActionNext action;
        private final Long startEpochMs;
        private final Long endEpochMs;
        private final String joinUrl;

        public TodayMeeting(ActionNext action, Long startEpochMs, Long endEpochMs, String joinUrl) {
            this.action = action;
            this.startEpochMs = startEpochMs;
            this.endEpochMs = endEpochMs;
            this.joinUrl = joinUrl;
        }

        public ActionNext getAction() {
            return action;
        }

        public Long getStartEpochMs() {
            return startEpochMs;
        }

        public Long getEndEpochMs() {
            return endEpochMs;
        }

        public String getJoinUrl() {
            return joinUrl;
        }

        public boolean isTimed() {
            return startEpochMs != null && endEpochMs != null;
        }
    }

    public List<TodayMeeting> loadTodayMeetings(Session dataSession, WebUser webUser, Integer workspaceId) {
        LocalDate today = webUser.getLocalDateToday();
        Query query = dataSession.createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "where an.workspaceId = :workspaceId and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                        + "and an.nextActionStatusString = :nextActionStatus "
                        + "and an.nextActionDate = :targetDate "
                        + "and an.nextActionType = :meetingType "
                        + "order by an.completionOrder, an.priorityLevel desc, an.nextChangeDate");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("nextContactId", webUser.getContactId());
        query.setParameter("nextActionStatus", ProjectNextActionStatus.READY.getId());
        query.setParameter("targetDate", java.sql.Date.valueOf(today));
        query.setParameter("meetingType", ProjectNextActionType.WILL_MEET);
        @SuppressWarnings("unchecked")
        List<ActionNext> actions = query.list();
        if (actions.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> actionIds = new ArrayList<Integer>();
        for (ActionNext action : actions) {
            actionIds.add(Integer.valueOf(action.getActionNextId()));
        }
        Query linkedQuery = dataSession.createQuery(
                "from LinkedMeeting where workspaceId = :workspaceId and actionNextId in (:actionNextIds)");
        linkedQuery.setParameter("workspaceId", workspaceId);
        linkedQuery.setParameterList("actionNextIds", actionIds);
        @SuppressWarnings("unchecked")
        List<LinkedMeeting> linkedMeetings = linkedQuery.list();
        Map<Integer, LinkedMeeting> linkedByAction = new HashMap<Integer, LinkedMeeting>();
        for (LinkedMeeting linked : linkedMeetings) {
            linkedByAction.put(Integer.valueOf(linked.getActionNextId()), linked);
        }
        return combine(actions, linkedByAction);
    }

    /**
     * Pairs actions with their linked meetings, drops cancelled occurrences, and orders timed
     * meetings by start time with untimed meetings after them in their original order.
     */
    public List<TodayMeeting> combine(List<ActionNext> actions, Map<Integer, LinkedMeeting> linkedByAction) {
        List<TodayMeeting> timed = new ArrayList<TodayMeeting>();
        List<TodayMeeting> untimed = new ArrayList<TodayMeeting>();
        for (ActionNext action : actions) {
            LinkedMeeting linked = linkedByAction.get(Integer.valueOf(action.getActionNextId()));
            if (linked == null) {
                untimed.add(new TodayMeeting(action, null, null, null));
                continue;
            }
            if (SOURCE_STATUS_CANCELLED.equalsIgnoreCase(linked.getSourceStatus())) {
                continue;
            }
            String joinUrl = linked.getJoinUrl() == null || linked.getJoinUrl().trim().length() == 0 ? null
                    : linked.getJoinUrl().trim();
            timed.add(new TodayMeeting(action, Long.valueOf(linked.getScheduledStartEpochMs()),
                    Long.valueOf(linked.getScheduledEndEpochMs()), joinUrl));
        }
        Collections.sort(timed, (a, b) -> {
            int byStart = Long.compare(a.getStartEpochMs().longValue(), b.getStartEpochMs().longValue());
            return byStart != 0 ? byStart : Long.compare(a.getEndEpochMs().longValue(), b.getEndEpochMs().longValue());
        });
        List<TodayMeeting> ordered = new ArrayList<TodayMeeting>(timed);
        ordered.addAll(untimed);
        return ordered;
    }
}
