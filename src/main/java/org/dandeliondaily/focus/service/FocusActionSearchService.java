package org.dandeliondaily.focus.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.manager.TimeTracker;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSetType;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * Finds the user's actions by words in the action text or project name, for the Focused Action
 * search. Every word must appear in one of the two. Open actions come first (overdue, today, then
 * by date, undated last), then actions completed in the last few days, newest first. Notes are not
 * searched.
 */
public class FocusActionSearchService {

    /** Shortest query that is searched; shorter ones return nothing. */
    public static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_WORDS = 6;
    private static final int OPEN_LIMIT = 25;
    private static final int DONE_LIMIT = 10;
    private static final int DONE_DAYS = 14;
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    public static final String GROUP_OVERDUE = "Overdue";
    public static final String GROUP_TODAY = "Today";
    public static final String GROUP_UPCOMING = "Upcoming";
    public static final String GROUP_UNSCHEDULED = "No date";
    public static final String GROUP_DONE = "Done recently";

    public static class Result {
        private final ActionNext action;
        private final String group;
        private final String whenLabel;
        private final boolean done;

        Result(ActionNext action, String group, String whenLabel, boolean done) {
            this.action = action;
            this.group = group;
            this.whenLabel = whenLabel;
            this.done = done;
        }

        public ActionNext getAction() {
            return action;
        }

        public String getGroup() {
            return group;
        }

        public String getWhenLabel() {
            return whenLabel;
        }

        public boolean isDone() {
            return done;
        }
    }

    public List<Result> search(Session dataSession, WebUser webUser, Integer workspaceId, String queryText) {
        List<String> words = words(queryText);
        if (words.isEmpty() || workspaceId == null) {
            return Collections.emptyList();
        }
        LocalDate today = webUser.getLocalDateToday();
        ZoneId zoneId = webUser.getZoneId();

        List<ActionNext> open = query(dataSession, webUser, workspaceId, words,
                "an.nextActionStatusString = :status", ProjectNextActionStatus.READY, null, OPEN_LIMIT * 2);
        List<ActionNext> done = query(dataSession, webUser, workspaceId, words,
                "an.nextActionStatusString = :status and an.nextChangeDate >= :doneSince",
                ProjectNextActionStatus.COMPLETED,
                TimeTracker.createToday(webUser).getTime().getTime() - DONE_DAYS * 24L * 60 * 60 * 1000,
                DONE_LIMIT * 2);

        List<Result> results = new ArrayList<Result>();
        Set<Integer> seenSharedSets = new HashSet<Integer>();
        List<ActionNext> sortedOpen = new ArrayList<ActionNext>(open);
        Collections.sort(sortedOpen, (a, b) -> {
            int byDate = compareDates(toLocalDate(a.getNextActionDate(), zoneId), toLocalDate(b.getNextActionDate(), zoneId));
            return byDate != 0 ? byDate : Integer.compare(b.getPriorityLevel(), a.getPriorityLevel());
        });
        int openCount = 0;
        for (ActionNext action : sortedOpen) {
            if (openCount >= OPEN_LIMIT || !firstOfSharedSet(action, seenSharedSets)) {
                continue;
            }
            LocalDate date = toLocalDate(action.getNextActionDate(), zoneId);
            String group;
            if (date == null) {
                group = GROUP_UNSCHEDULED;
            } else if (date.isBefore(today)) {
                group = GROUP_OVERDUE;
            } else if (date.equals(today)) {
                group = GROUP_TODAY;
            } else {
                group = GROUP_UPCOMING;
            }
            results.add(new Result(action, group, openWhenLabel(date, today), false));
            openCount++;
        }
        int doneCount = 0;
        for (ActionNext action : done) {
            if (doneCount >= DONE_LIMIT || !firstOfSharedSet(action, seenSharedSets)) {
                continue;
            }
            LocalDate doneDate = action.getNextChangeDate() == null ? null
                    : action.getNextChangeDate().toInstant().atZone(zoneId).toLocalDate();
            results.add(new Result(action, GROUP_DONE, doneWhenLabel(doneDate, today), true));
            doneCount++;
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private List<ActionNext> query(Session dataSession, WebUser webUser, Integer workspaceId, List<String> words,
            String statusClause, ProjectNextActionStatus status, Long doneSinceMs, int limit) {
        StringBuilder hql = new StringBuilder("select an from ActionNext an join fetch an.project p "
                + "where an.workspaceId = :workspaceId "
                + "and (an.contactId = :contactId or an.nextContactId = :contactId) "
                + "and an.nextDescription <> '' "
                + "and (an.templateTypeString is null or an.templateTypeString = '') and ");
        hql.append(statusClause);
        for (int i = 0; i < words.size(); i++) {
            hql.append(" and (lower(an.nextDescription) like :w").append(i).append(" escape '!'")
                    .append(" or lower(p.projectName) like :w").append(i).append(" escape '!')");
        }
        hql.append(doneSinceMs == null ? " order by an.nextActionDate, an.actionNextId"
                : " order by an.nextChangeDate desc");
        Query query = dataSession.createQuery(hql.toString());
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
        query.setParameter("status", status.getId());
        if (doneSinceMs != null) {
            query.setParameter("doneSince", new Date(doneSinceMs.longValue()));
        }
        for (int i = 0; i < words.size(); i++) {
            query.setParameter("w" + i, contains(words.get(i)));
        }
        query.setMaxResults(limit);
        return query.list();
    }

    /** Lowercased words of the query, or none when it is shorter than {@link #MIN_QUERY_LENGTH}. */
    static List<String> words(String queryText) {
        String trimmed = queryText == null ? "" : queryText.trim().toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<String>();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return words;
        }
        for (String word : trimmed.split("\\s+")) {
            if (word.length() > 0 && !words.contains(word) && words.size() < MAX_WORDS) {
                words.add(word.length() > 100 ? word.substring(0, 100) : word);
            }
        }
        return words;
    }

    private static String contains(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    /** A linked-project action is one action stored once per project; list it once. */
    private static boolean firstOfSharedSet(ActionNext action, Set<Integer> seenSharedSets) {
        if (action.getActionSet() == null || action.getActionSet().getActionSetType() != ActionSetType.SHARED) {
            return true;
        }
        return seenSharedSets.add(Integer.valueOf(action.getActionSet().getActionSetId()));
    }

    static String openWhenLabel(LocalDate date, LocalDate today) {
        if (date == null) {
            return "No date";
        }
        if (date.equals(today)) {
            return "Today";
        }
        if (date.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        if (date.isAfter(today) && date.isBefore(today.plusDays(7))) {
            return WEEKDAY.format(date);
        }
        return MONTH_DAY.format(date);
    }

    static String doneWhenLabel(LocalDate date, LocalDate today) {
        if (date == null) {
            return "Done";
        }
        if (date.equals(today)) {
            return "Done today";
        }
        if (date.equals(today.minusDays(1))) {
            return "Done yesterday";
        }
        return "Done " + MONTH_DAY.format(date);
    }

    private static int compareDates(LocalDate a, LocalDate b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : 1) : -1;
        }
        return a.compareTo(b);
    }

    private static LocalDate toLocalDate(Date date, ZoneId zoneId) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return date.toInstant().atZone(zoneId).toLocalDate();
    }
}
