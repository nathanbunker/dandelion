package org.openimmunizationsoftware.pt.doa;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.LockMode;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.model.TrackerNarrative;
import org.openimmunizationsoftware.pt.model.TrackerNarrativeReviewStatus;

public class TrackerNarrativeDao {

    private final Session session;

    public TrackerNarrativeDao() {
        this.session = HibernateRequestContext.getCurrentSession();
    }

    public TrackerNarrativeDao(Session session) {
        this.session = session;
    }

    @SuppressWarnings("unchecked")
    public List<TrackerNarrative> listByContactUpdatedAfter(int contactId, Date updatedAfter) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and lastUpdated > :updatedAfter "
                        + "order by lastUpdated asc");
        query.setInteger("contactId", contactId);
        query.setTimestamp("updatedAfter", updatedAfter);
        return query.list();
    }

    @SuppressWarnings("unchecked")
    public List<TrackerNarrative> findByContactTypeAndPeriod(int contactId, String type, LocalDate start,
            LocalDate end) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and narrativeType = :type "
                        + "and periodStart = :start and periodEnd = :end order by narrativeId desc");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setDate("start", toSqlDate(start));
        query.setDate("end", toSqlDate(end));
        return query.list();
    }

    public TrackerNarrative findApprovedByContactTypeAndPeriod(int contactId, String type, LocalDate start,
            LocalDate end) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and narrativeType = :type "
                        + "and periodStart = :start and periodEnd = :end and reviewStatusString = :status "
                        + "order by dateApproved desc, dateGenerated desc");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setDate("start", toSqlDate(start));
        query.setDate("end", toSqlDate(end));
        query.setString("status", TrackerNarrativeReviewStatus.APPROVED.getId());
        query.setMaxResults(1);
        @SuppressWarnings("unchecked")
        List<TrackerNarrative> results = query.list();
        return results == null || results.isEmpty() ? null : results.get(0);
    }

    public TrackerNarrative findAdjacentByContactAndType(int contactId, String type, LocalDate periodStart,
            boolean next) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and narrativeType = :type "
                        + "and periodStart " + (next ? ">" : "<") + " :periodStart "
                        + "and reviewStatusString <> :deleted "
                        + "order by periodStart " + (next ? "asc" : "desc")
                        + ", case when reviewStatusString = :approved then 0 else 1 end, narrativeId desc");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setDate("periodStart", toSqlDate(periodStart));
        query.setString("deleted", TrackerNarrativeReviewStatus.DELETED.getId());
        query.setString("approved", TrackerNarrativeReviewStatus.APPROVED.getId());
        query.setMaxResults(1);
        @SuppressWarnings("unchecked")
        List<TrackerNarrative> results = query.list();
        return results.isEmpty() ? null : results.get(0);
    }

    @SuppressWarnings("unchecked")
    public List<TrackerNarrative> findByContactTypeAndPeriodForUpdate(int contactId, String type,
            LocalDate start, LocalDate end) {
        Query query = session.createQuery("from TrackerNarrative n where n.contactId = :contactId "
                + "and n.narrativeType = :type and n.periodStart = :start and n.periodEnd = :end "
                + "order by n.narrativeId asc");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setDate("start", toSqlDate(start));
        query.setDate("end", toSqlDate(end));
        query.setLockMode("n", LockMode.UPGRADE);
        return query.list();
    }

    @SuppressWarnings("unchecked")
    public List<TrackerNarrative> findApprovedByContactTypeAndPeriodRange(int contactId, String type,
            LocalDate startInclusive, LocalDate endInclusive) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and narrativeType = :type "
                        + "and periodStart >= :start and periodEnd <= :end and reviewStatusString = :status "
                        + "and markdownFinal is not null order by periodStart asc, dateApproved desc");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setDate("start", toSqlDate(startInclusive));
        query.setDate("end", toSqlDate(endInclusive));
        query.setString("status", TrackerNarrativeReviewStatus.APPROVED.getId());
        return query.list();
    }

    /**
     * All narratives (any review status) whose periodStart falls in an
     * inclusive range, most-recent first per period -- for the MCP
     * get_narratives read tool (docs/MCP-Feedback.md I-8), which needs to
     * show whatever's there (including not-yet-approved) rather than only
     * APPROVED like findApprovedByContactTypeAndPeriodRange.
     */
    @SuppressWarnings("unchecked")
    public List<TrackerNarrative> findByContactAndTypeInPeriodStartRange(int contactId, String type,
            LocalDate startInclusive, LocalDate endInclusive) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and narrativeType = :type "
                        + "and periodStart >= :start and periodStart <= :end "
                        + "order by periodStart desc, narrativeId desc");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setDate("start", toSqlDate(startInclusive));
        query.setDate("end", toSqlDate(endInclusive));
        return query.list();
    }

    public Map<LocalDate, Integer> sumBillableMinutesByDay(Integer webUserId, LocalDate startInclusive,
            LocalDate endExclusive) {
        Map<LocalDate, Integer> minutesByDay = new HashMap<LocalDate, Integer>();
        if (webUserId == null || webUserId.intValue() <= 0 || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            return minutesByDay;
        }

        Query query = session.createQuery(
                "select startTime, billMins from BillEntry "
                        + "where webUser.webUserId = :webUserId and billable = :billable and billMins > 0 "
                        + "and startTime >= :start and startTime < :end");
        query.setInteger("webUserId", webUserId.intValue());
        query.setString("billable", "Y");
        query.setTimestamp("start", toDate(startInclusive.atStartOfDay()));
        query.setTimestamp("end", toDate(endExclusive.atStartOfDay()));

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.list();
        for (Object[] row : rows) {
            if (row == null || row.length < 2) {
                continue;
            }
            Date startTime = (Date) row[0];
            Number billMinsNumber = (Number) row[1];
            if (startTime == null || billMinsNumber == null) {
                continue;
            }
            LocalDate day = toLocalDate(startTime);
            int billMins = billMinsNumber.intValue();
            Integer existing = minutesByDay.get(day);
            minutesByDay.put(day, (existing == null ? 0 : existing.intValue()) + billMins);
        }
        return minutesByDay;
    }

    public Set<LocalDate> findApprovedPeriodStarts(int contactId, String type, LocalDate startInclusive,
            LocalDate endInclusive) {
        Set<LocalDate> approvedStarts = new HashSet<LocalDate>();
        if (type == null || type.trim().length() == 0 || startInclusive == null || endInclusive == null
                || startInclusive.isAfter(endInclusive)) {
            return approvedStarts;
        }

        Query query = session.createQuery(
                "select periodStart from TrackerNarrative where contactId = :contactId and narrativeType = :type "
                        + "and reviewStatusString = :status and periodStart >= :start and periodStart <= :end");
        query.setInteger("contactId", contactId);
        query.setString("type", type);
        query.setString("status", TrackerNarrativeReviewStatus.APPROVED.getId());
        query.setDate("start", toSqlDate(startInclusive));
        query.setDate("end", toSqlDate(endInclusive));

        @SuppressWarnings("unchecked")
        List<Date> rows = query.list();
        for (Date date : rows) {
            LocalDate localDate = toLocalDate(date);
            if (localDate != null) {
                approvedStarts.add(localDate);
            }
        }
        return approvedStarts;
    }

    /**
     * Every narrative still waiting on the owner (generated drafts, generations in flight and failures),
     * across all periods and types, most recent period first. Feeds the review queue.
     */
    @SuppressWarnings("unchecked")
    public List<TrackerNarrative> findPendingByContact(int contactId) {
        Query query = session.createQuery(
                "from TrackerNarrative where contactId = :contactId and reviewStatusString in (:statuses) "
                        + "order by periodStart desc, narrativeId desc");
        query.setInteger("contactId", contactId);
        query.setParameterList("statuses", new String[] { TrackerNarrativeReviewStatus.GENERATED.getId(),
                TrackerNarrativeReviewStatus.GENERATING.getId(), TrackerNarrativeReviewStatus.FAILED.getId() });
        return query.list();
    }

    /**
     * Type, period start and approval time of every approved narrative for a contact, without loading the
     * report text. Rows are {narrativeType, periodStart, dateApproved}.
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> findApprovedSummaries(int contactId) {
        Query query = session.createQuery(
                "select narrativeType, periodStart, dateApproved from TrackerNarrative "
                        + "where contactId = :contactId and reviewStatusString = :status");
        query.setInteger("contactId", contactId);
        query.setString("status", TrackerNarrativeReviewStatus.APPROVED.getId());
        return query.list();
    }

    /** The calendar date a stored period date stands for. */
    public static LocalDate periodDate(Date date) {
        return toLocalDate(date);
    }

    public long insert(TrackerNarrative narrative) {
        if (narrative.getLastUpdated() == null) {
            narrative.setLastUpdated(new Date());
        }
        Serializable id = session.save(narrative);
        if (id instanceof Number) {
            return ((Number) id).longValue();
        }
        return narrative.getNarrativeId();
    }

    public void updateGeneratedText(long id, String markdownGenerated, String modelName, String promptVersion,
            LocalDateTime dateGenerated, String status) {
        TrackerNarrative narrative = getById(id);
        if (narrative == null) {
            return;
        }
        narrative.setMarkdownGenerated(markdownGenerated);
        narrative.setModelName(modelName);
        narrative.setPromptVersion(promptVersion);
        narrative.setDateGenerated(toDate(dateGenerated));
        narrative.setReviewStatusString(status);
        narrative.setLastUpdated(new Date());
        session.update(narrative);
    }

    public void updateFinalText(long id, int contactId, String markdownFinal) {
        TrackerNarrative narrative = getByIdForContact(id, contactId);
        if (narrative == null) {
            return;
        }
        narrative.setMarkdownFinal(markdownFinal);
        narrative.setLastUpdated(new Date());
        session.update(narrative);
    }

    public void approve(long id, int contactId, LocalDateTime dateApproved) {
        TrackerNarrative narrative = getByIdForContact(id, contactId);
        if (narrative == null) {
            return;
        }
        String type = narrative.getNarrativeType();
        LocalDate start = toLocalDate(narrative.getPeriodStart());
        LocalDate end = toLocalDate(narrative.getPeriodEnd());

        Transaction transaction = session.beginTransaction();
        try {
            if (type != null && start != null && end != null) {
                clearApprovedForPeriod(contactId, type, start, end);
            }
            narrative.setReviewStatus(TrackerNarrativeReviewStatus.APPROVED);
            narrative.setDateApproved(toDate(dateApproved));
            narrative.setLastUpdated(new Date());
            session.update(narrative);
            transaction.commit();
        } catch (RuntimeException exception) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw exception;
        }
    }

    public void reject(long id, int contactId) {
        TrackerNarrative narrative = getByIdForContact(id, contactId);
        if (narrative == null) {
            return;
        }
        narrative.setReviewStatus(TrackerNarrativeReviewStatus.REJECTED);
        narrative.setLastUpdated(new Date());
        session.update(narrative);
    }

    public void softDelete(long id, int contactId) {
        TrackerNarrative narrative = getByIdForContact(id, contactId);
        if (narrative == null) {
            return;
        }
        narrative.setReviewStatus(TrackerNarrativeReviewStatus.DELETED);
        narrative.setLastUpdated(new Date());
        session.update(narrative);
    }

    public void clearApprovedForPeriod(int contactId, String type, LocalDate start, LocalDate end) {
        Query query = session.createQuery(
                "update TrackerNarrative set reviewStatusString = :rejected, lastUpdated = :lastUpdated "
                        + "where contactId = :contactId and narrativeType = :type "
                        + "and periodStart = :start and periodEnd = :end "
                        + "and reviewStatusString = :approved");
        query.setInteger("contactId", contactId);
        query.setString("rejected", TrackerNarrativeReviewStatus.REJECTED.getId());
        query.setString("approved", TrackerNarrativeReviewStatus.APPROVED.getId());
        query.setString("type", type);
        query.setDate("start", toSqlDate(start));
        query.setDate("end", toSqlDate(end));
        query.setTimestamp("lastUpdated", new Date());
        query.executeUpdate();
    }

    private TrackerNarrative getById(long id) {
        return (TrackerNarrative) session.get(TrackerNarrative.class, (int) id);
    }

    public TrackerNarrative getByIdForContact(long id, int contactId) {
        TrackerNarrative narrative = getById(id);
        return narrative != null && narrative.getContactId() == contactId ? narrative : null;
    }

    private static java.sql.Date toSqlDate(LocalDate date) {
        return date == null ? null : java.sql.Date.valueOf(date);
    }

    private static Date toDate(LocalDateTime dateTime) {
        return dateTime == null ? null : Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

    private static LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
