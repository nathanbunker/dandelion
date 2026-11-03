package org.openimmunizationsoftware.pt.doa;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.ProjectNarrative;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.ProjectNarrativeVerb;

public class ProjectNarrativeDao {

    public static final int MINUTES_REVIEW_THRESHOLD = 5;

    private final Session session;

    public ProjectNarrativeDao() {
        this.session = HibernateRequestContext.getCurrentSession();
    }

    public ProjectNarrativeDao(Session session) {
        this.session = session;
    }

    @SuppressWarnings("unchecked")
    public List<ProjectNarrative> findByProjectAndDateRange(long projectId, LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "from ProjectNarrative where projectId = :projectId and narrativeDate >= :start and narrativeDate < :end order by narrativeDate asc");
        query.setLong("projectId", projectId);
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        return query.list();
    }

    public boolean hasNarrativeForProjectOnDate(long projectId, LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "select 1 from ProjectNarrative where projectId = :projectId and narrativeDate >= :start and narrativeDate < :end");
        query.setLong("projectId", projectId);
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        query.setMaxResults(1);
        return !query.list().isEmpty();
    }

    public void insert(ProjectNarrative narrative) {
        if (narrative.getLastUpdated() == null) {
            narrative.setLastUpdated(new Date());
        }
        session.save(narrative);
    }

    public void update(ProjectNarrative narrative) {
        narrative.setLastUpdated(new Date());
        session.update(narrative);
    }

    public void delete(ProjectNarrative narrative) {
        session.delete(narrative);
    }

    public ProjectNarrative findById(long narrativeId) {
        return (ProjectNarrative) session.get(ProjectNarrative.class, (int) narrativeId);
    }

    @SuppressWarnings("unchecked")
    public List<ProjectNarrative> listByWorkspaceAndContactUpdatedAfter(int workspaceId, int contactId,
            Date updatedAfter) {
        Query query = session.createQuery(
                "from ProjectNarrative where workspaceId = :workspaceId and contactId = :contactId "
                        + "and lastUpdated > :updatedAfter order by lastUpdated asc");
        query.setInteger("workspaceId", workspaceId);
        query.setInteger("contactId", contactId);
        query.setTimestamp("updatedAfter", updatedAfter);
        return query.list();
    }

    public boolean updateNarrativeTextIfChanged(ProjectNarrative narrative, String newText, Date narrativeDate) {
        if (narrative == null) {
            return false;
        }
        String currentText = normalizeText(narrative.getNarrativeText());
        String incomingText = normalizeText(newText);
        if (currentText.equals(incomingText)) {
            return false;
        }
        narrative.setNarrativeText(newText);
        if (narrativeDate != null) {
            narrative.setNarrativeDate(narrativeDate);
        }
        narrative.setLastUpdated(new Date());
        session.update(narrative);
        return true;
    }

    public ProjectNarrative findNarrativeForProjectVerbOnDate(long projectId, ProjectNarrativeVerb verb,
            LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "from ProjectNarrative where projectId = :projectId and narrativeVerbString = :verb "
                        + "and narrativeDate >= :start and narrativeDate < :end order by narrativeDate asc");
        query.setLong("projectId", projectId);
        query.setParameter("verb", verb == null ? null : verb.getId());
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        query.setMaxResults(1);
        @SuppressWarnings("unchecked")
        List<ProjectNarrative> results = query.list();
        if (results == null || results.isEmpty()) {
            return null;
        }
        return results.get(0);
    }

    public Map<Long, Integer> getMinutesSpentByProjectOnDate(LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "select be.projectId, sum(be.billMins) from BillEntry be where be.startTime >= :start and be.startTime < :end group by be.projectId");
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.list();
        Map<Long, Integer> results = new LinkedHashMap<Long, Integer>();
        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            Number projectId = (Number) row[0];
            Number minutes = (Number) row[1];
            int minuteValue = minutes == null ? 0 : minutes.intValue();
            results.put(projectId.longValue(), minuteValue);
        }
        return results;
    }

    public List<Action> getCompletedActionsForProjectOnDate(long projectId, LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "from ActionTaken where projectId = :projectId and actionDate >= :start and actionDate < :end "
                        + "and actionDescription is not null and actionDescription <> '' "
                        + "order by actionDate asc");
        query.setLong("projectId", projectId);
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        @SuppressWarnings("unchecked")
        List<ActionTaken> actions = query.list();
        List<Action> results = new ArrayList<Action>();
        for (ActionTaken action : actions) {
            results.add(new Action(
                    action.getActionTakenId(),
                    action.getActionDescription(),
                    action.getActionDate(),
                    action.getActionNote()));
        }
        return results;
    }

    public List<ActionWithMinutes> getDeletedActionsWithTimeForProjectOnDate(long projectId, LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "from ActionNext where projectId = :projectId and nextActionStatusString = :status "
                        + "and nextChangeDate >= :start and nextChangeDate < :end "
                        + "and nextTimeActual > 0 "
                        + "order by nextChangeDate asc");
        query.setLong("projectId", projectId);
        query.setString("status", ProjectNextActionStatus.CANCELLED.getId());
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        @SuppressWarnings("unchecked")
        List<ActionNext> rows = query.list();
        List<ActionWithMinutes> results = new ArrayList<ActionWithMinutes>();
        for (ActionNext action : rows) {
            if (action == null || action.getNextTimeActual() == null || action.getNextTimeActual() <= 0) {
                continue;
            }
            results.add(new ActionWithMinutes(
                    action.getActionNextId(),
                    action.getNextDescription(),
                    action.getNextChangeDate(),
                    action.getNextTimeActual()));
        }
        return results;
    }

    public List<Action> getDeletedActionsWithoutTimeForProjectOnDate(long projectId, LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query actionsQuery = session.createQuery(
                "from ActionNext where projectId = :projectId and nextActionStatusString = :status "
                        + "and nextChangeDate >= :start and nextChangeDate < :end "
                        + "and (nextTimeActual is null or nextTimeActual = 0) "
                        + "order by nextChangeDate asc");
        actionsQuery.setLong("projectId", projectId);
        actionsQuery.setString("status", ProjectNextActionStatus.CANCELLED.getId());
        actionsQuery.setTimestamp("start", start);
        actionsQuery.setTimestamp("end", end);
        @SuppressWarnings("unchecked")
        List<ActionNext> actions = actionsQuery.list();

        List<Action> results = new ArrayList<Action>();
        for (ActionNext action : actions) {
            results.add(new Action(
                    action.getActionNextId(),
                    action.getNextDescription(),
                    action.getNextChangeDate(),
                    null));
        }
        return results;
    }

    public List<ReviewItem> listReviewItemsForDate(LocalDate date, int contactId) {
        List<ReviewItem> results = new ArrayList<ReviewItem>();
        for (ReviewItemDetail detail : listReviewItemsForDateDetailed(date, contactId, false)) {
            results.add(new ReviewItem(detail.getProjectId(), detail.getProjectName(), detail.getMinutesSpent(),
                    detail.isReviewed()));
        }
        return results;
    }

    /**
     * Richer version of listReviewItemsForDate for the MCP work-day-review tool
     * (docs/MCP-Feedback.md item 2, I-10): same underlying query, but also reports
     * *why* a project isn't reviewed yet (no narrative vs. which setup pieces are
     * missing), and can optionally include projects below the minutes threshold.
     */
    public List<ReviewItemDetail> listReviewItemsForDateDetailed(LocalDate date, int contactId,
            boolean includeBelowThreshold) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);

        Query minutesQuery = session.createQuery(
                "select be.projectId, p.projectName, sum(be.billMins), p.outcomeText, p.successCriteriaText "
                        + "from BillEntry be, Project p "
                        + "where be.projectId = p.projectId and be.startTime >= :start and be.startTime < :end "
                        + "group by be.projectId, p.projectName, p.outcomeText, p.successCriteriaText "
                        + (includeBelowThreshold ? "" : "having sum(be.billMins) >= :minMinutes ")
                        + "order by sum(be.billMins) desc");
        minutesQuery.setTimestamp("start", start);
        minutesQuery.setTimestamp("end", end);
        if (!includeBelowThreshold) {
            minutesQuery.setInteger("minMinutes", MINUTES_REVIEW_THRESHOLD);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = minutesQuery.list();

        Query reviewedQuery = session.createQuery(
                "select distinct pn.projectId from ProjectNarrative pn "
                        + "where pn.narrativeDate >= :start and pn.narrativeDate < :end");
        reviewedQuery.setTimestamp("start", start);
        reviewedQuery.setTimestamp("end", end);
        @SuppressWarnings("unchecked")
        List<Number> reviewedProjectIds = reviewedQuery.list();
        Set<Long> hasNarrativeLookup = new HashSet<Long>();
        for (Number projectId : reviewedProjectIds) {
            if (projectId != null) {
                hasNarrativeLookup.add(projectId.longValue());
            }
        }

        Query updateDueQuery = session.createQuery(
                "select pca.id.projectId, pca.updateDue from ProjectContactAssigned pca where pca.id.contactId = :contactId");
        updateDueQuery.setInteger("contactId", contactId);
        @SuppressWarnings("unchecked")
        List<Object[]> updateDueRows = updateDueQuery.list();
        Map<Long, Integer> updateDueByProject = new LinkedHashMap<Long, Integer>();
        for (Object[] row : updateDueRows) {
            if (row == null || row.length < 2 || row[0] == null) {
                continue;
            }
            Number projectId = (Number) row[0];
            Number updateDue = (Number) row[1];
            updateDueByProject.put(projectId.longValue(), updateDue == null ? 0 : updateDue.intValue());
        }

        List<ReviewItemDetail> results = new ArrayList<ReviewItemDetail>();
        for (Object[] row : rows) {
            if (row == null || row.length < 5) {
                continue;
            }
            Number projectId = (Number) row[0];
            String projectName = (String) row[1];
            Number minutes = (Number) row[2];
            String outcomeText = (String) row[3];
            String successCriteriaText = (String) row[4];
            if (projectId == null) {
                continue;
            }
            int minuteValue = minutes == null ? 0 : minutes.intValue();
            int updateDue = updateDueByProject.containsKey(projectId.longValue())
                    ? updateDueByProject.get(projectId.longValue()).intValue()
                    : 0;
            List<String> missingSetup = new ArrayList<String>();
            if (updateDue <= 0) {
                missingSetup.add("reviewCadence");
            }
            if (!hasText(outcomeText)) {
                missingSetup.add("outcome");
            }
            if (!hasText(successCriteriaText)) {
                missingSetup.add("successCriteria");
            }
            boolean setupComplete = missingSetup.isEmpty();
            boolean hasNarrative = hasNarrativeLookup.contains(projectId.longValue());
            boolean reviewed = hasNarrative && setupComplete;
            results.add(new ReviewItemDetail(projectId.longValue(), projectName, minuteValue, reviewed, hasNarrative,
                    missingSetup));
        }
        return results;
    }

    public int getTotalMinutesForDate(LocalDate date) {
        Date start = startOfDay(date);
        Date end = startOfNextDay(date);
        Query query = session.createQuery(
                "select sum(be.billMins) from BillEntry be where be.startTime >= :start and be.startTime < :end");
        query.setTimestamp("start", start);
        query.setTimestamp("end", end);
        Number total = (Number) query.uniqueResult();
        return total == null ? 0 : total.intValue();
    }

    private static Date startOfDay(LocalDate date) {
        return Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static Date startOfNextDay(LocalDate date) {
        return Date.from(date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    public static class Action {
        private final int actionTakenId;
        private final String description;
        private final Date completedDate;
        private final String completionNote;

        public Action(int actionTakenId, String description, Date completedDate, String completionNote) {
            this.actionTakenId = actionTakenId;
            this.description = description;
            this.completedDate = completedDate;
            this.completionNote = completionNote;
        }

        public int getActionTakenId() {
            return actionTakenId;
        }

        public String getDescription() {
            return description;
        }

        public Date getCompletedDate() {
            return completedDate;
        }

        public String getCompletionNote() {
            return completionNote;
        }
    }

    public static class ActionWithMinutes {
        private final int actionTakenId;
        private final String description;
        private final Date changeDate;
        private final int minutes;

        public ActionWithMinutes(int actionTakenId, String description, Date changeDate, int minutes) {
            this.actionTakenId = actionTakenId;
            this.description = description;
            this.changeDate = changeDate;
            this.minutes = minutes;
        }

        public int getActionTakenId() {
            return actionTakenId;
        }

        public String getDescription() {
            return description;
        }

        public Date getChangeDate() {
            return changeDate;
        }

        public int getMinutes() {
            return minutes;
        }
    }

    public static class ReviewItemDetail {
        private final long projectId;
        private final String projectName;
        private final int minutesSpent;
        private final boolean reviewed;
        private final boolean hasNarrative;
        private final List<String> missingSetup;

        public ReviewItemDetail(long projectId, String projectName, int minutesSpent, boolean reviewed,
                boolean hasNarrative, List<String> missingSetup) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.minutesSpent = minutesSpent;
            this.reviewed = reviewed;
            this.hasNarrative = hasNarrative;
            this.missingSetup = missingSetup;
        }

        public long getProjectId() {
            return projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public int getMinutesSpent() {
            return minutesSpent;
        }

        public boolean isReviewed() {
            return reviewed;
        }

        public boolean isHasNarrative() {
            return hasNarrative;
        }

        public List<String> getMissingSetup() {
            return missingSetup;
        }
    }

    public static class ReviewItem {
        private final long projectId;
        private final String projectName;
        private final int minutesSpent;
        private final boolean reviewed;

        public ReviewItem(long projectId, String projectName, int minutesSpent, boolean reviewed) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.minutesSpent = minutesSpent;
            this.reviewed = reviewed;
        }

        public long getProjectId() {
            return projectId;
        }

        public String getProjectName() {
            return projectName;
        }

        public int getMinutesSpent() {
            return minutesSpent;
        }

        public boolean isReviewed() {
            return reviewed;
        }
    }
}
