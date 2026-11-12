package org.dandeliondaily.personal.service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectContact;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.model.TimeSlot;
import org.openimmunizationsoftware.pt.model.WebUser;

/**
 * A day's personal actions: overdue (only when the day is today) and the day's actions by
 * part of the day. Shared by the mobile Todo page ({@code /m/todo}) and the phone API
 * ({@code GET /api/v1/personal/day}), so both show the same list.
 *
 * <p>Work actions ({@code billable = true}) are always left out. The phone never sees work
 * (Sundial Plan.md, change 3); this is where that is enforced for reading.
 *
 * <p>Moved from {@code TodoServlet}; the behavior is pinned by PersonalDayServiceTest.
 */
public class PersonalDayService {

    /** The four parts of the day, in order. */
    public static final TimeSlot[] PARTS = { TimeSlot.WAKE, TimeSlot.MORNING, TimeSlot.AFTERNOON,
            TimeSlot.EVENING };

    public static final class Day {
        private final List<ActionNext> overdue = new ArrayList<ActionNext>();
        private final Map<TimeSlot, List<ActionNext>> parts = new EnumMap<TimeSlot, List<ActionNext>>(
                TimeSlot.class);

        Day() {
            for (TimeSlot part : PARTS) {
                parts.put(part, new ArrayList<ActionNext>());
            }
        }

        public List<ActionNext> getOverdue() {
            return overdue;
        }

        /** Actions for one part of the day. An action with no part counts as Afternoon. */
        public List<ActionNext> getPart(TimeSlot part) {
            List<ActionNext> list = parts.get(part);
            return list == null ? Collections.<ActionNext>emptyList() : list;
        }
    }

    /**
     * READY actions with a description, due on or before the date, where the user is the
     * owner or the next contact, in the workspace. Ordered by date, priority (high first),
     * change date, then description. Includes work actions; {@link #organize} drops them.
     */
    public List<ActionNext> fetchReadyActions(Session dataSession, WebUser webUser, Integer workspaceId,
            String selectedDateKey) {
        java.sql.Date selectedSqlDate = java.sql.Date.valueOf(selectedDateKey);

        Query query = dataSession.createQuery(
                "select distinct an from ActionNext an " +
                        "left join fetch an.project " +
                        "left join fetch an.contact " +
                        "left join fetch an.nextProjectContact " +
                        "where an.workspaceId = :workspaceId " +
                        "and (an.contactId = :contactId or an.nextContactId = :contactId) " +
                        "and an.nextActionStatusString = :status " +
                        "and an.nextDescription <> '' " +
                        "and an.nextActionDate <= :selectedDate " +
                        "order by an.nextActionDate, an.priorityLevel DESC, an.nextChangeDate, lower(an.nextDescription)");

        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", webUser.getContactId());
        query.setParameter("status", ProjectNextActionStatus.READY.getId());
        query.setParameter("selectedDate", selectedSqlDate);

        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();

        // Keep a defensive date-only guard in case DB mapping includes time-of-day.
        List<ActionNext> filtered = new ArrayList<ActionNext>();
        for (ActionNext action : results) {
            String actionDateKey = databaseDateKey(action.getNextActionDate());
            if (actionDateKey != null && actionDateKey.compareTo(selectedDateKey) <= 0) {
                if (action.getProject() == null && action.getProjectId() > 0) {
                    action.setProject((Project) dataSession.get(Project.class, action.getProjectId()));
                }
                if (action.getContact() == null && action.getContactId() != null
                        && action.getContactId().intValue() > 0) {
                    action.setContact((ProjectContact) dataSession.get(ProjectContact.class,
                            action.getContactId()));
                }
                if (action.getNextProjectContact() == null && action.getNextContactId() != null
                        && action.getNextContactId() > 0) {
                    action.setNextProjectContact(
                            (ProjectContact) dataSession.get(ProjectContact.class, action.getNextContactId()));
                }
                filtered.add(action);
            }
        }
        return filtered;
    }

    /**
     * Sorts fetched actions into a {@link Day}, keeping their order.
     *
     * @param selectedDateKeyDb   the selected day as a UTC yyyy-MM-dd key (the database key)
     * @param selectedDateKeyUser the selected day as a yyyy-MM-dd key in the user's time zone
     * @param isToday             whether the selected day is the user's today
     */
    public static Day organize(List<ActionNext> actions, String selectedDateKeyDb, String selectedDateKeyUser,
            boolean isToday) {
        Day day = new Day();
        for (ActionNext action : actions) {
            if (action.isBillable()) {
                continue;
            }
            String actionDateKey = databaseDateKey(action.getNextActionDate());
            if (actionDateKey == null) {
                continue;
            }
            if (isToday) {
                if (actionDateKey.compareTo(selectedDateKeyDb) < 0) {
                    day.overdue.add(action);
                } else if (actionDateKey.equals(selectedDateKeyDb)) {
                    day.parts.get(partOf(action)).add(action);
                }
            } else if (actionDateKey.equals(selectedDateKeyUser)) {
                // Other days: only that day's actions, no overdue.
                day.parts.get(partOf(action)).add(action);
            }
        }
        return day;
    }

    /** The part of the day an action belongs to; none means Afternoon. */
    public static TimeSlot partOf(ActionNext action) {
        TimeSlot slot = action.getTimeSlot();
        if (slot == TimeSlot.WAKE || slot == TimeSlot.MORNING || slot == TimeSlot.AFTERNOON
                || slot == TimeSlot.EVENING) {
            return slot;
        }
        return TimeSlot.AFTERNOON;
    }

    /** A date-only value as its UTC yyyy-MM-dd key, the way it is stored. */
    public static String databaseDateKey(Date date) {
        if (date == null) {
            return null;
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(date);
    }
}
