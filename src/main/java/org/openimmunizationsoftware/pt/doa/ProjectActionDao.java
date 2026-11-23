package org.openimmunizationsoftware.pt.doa;

import java.util.Date;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.api.common.HibernateRequestContext;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionTaken;
import org.openimmunizationsoftware.pt.model.ProjectNextActionStatus;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

/**
 * Data Access Object for ActionNext and ActionTaken.
 *
 * This centralizes common queries and persistence operations so servlets
 * and resources do not scatter raw HQL/Session usage.
 */
public class ProjectActionDao {

    private final Session session;

    public ProjectActionDao() {
        this.session = HibernateRequestContext.getCurrentSession();
    }

    public ProjectActionDao(Session session) {
        this.session = session;
    }

    public List<ActionNext> listNextActionsForProject(int workspaceId, int projectId) {
        Query query = session.createQuery(
                "from ActionNext an where an.projectId = :projectId and an.workspaceId = :workspaceId order by an.priorityLevel desc, an.nextActionDate");
        query.setInteger("projectId", projectId);
        query.setInteger("workspaceId", workspaceId);
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

    public List<ActionNext> listReadyNextActionsForContact(int workspaceId, int contactId) {
        Query query = session.createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "left join fetch an.contact "
                        + "left join fetch an.nextProjectContact "
                        + "where an.workspaceId = :workspaceId "
                        + "and (an.contactId = :contactId or an.nextContactId = :nextContactId) "
                        + "and an.nextDescription <> '' "
                        + "and an.nextActionStatusString = :nextActionStatus "
                        + "order by an.nextActionDate, an.priorityLevel DESC, an.nextTimeEstimate, an.nextChangeDate");
        query.setParameter("workspaceId", workspaceId);
        query.setParameter("contactId", contactId);
        query.setParameter("nextContactId", contactId);
        query.setParameter("nextActionStatus", ProjectNextActionStatus.READY.getId());
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

    public List<ActionNext> listNextActionsForProjectAndContact(int workspaceId, int projectId,
            int contactId) {
        Query query = session.createQuery(
                "from ActionNext an where an.projectId = :projectId and an.contactId = :contactId and an.workspaceId = :workspaceId order by an.nextActionDate");
        query.setInteger("projectId", projectId);
        query.setInteger("contactId", contactId);
        query.setInteger("workspaceId", workspaceId);
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

    public List<ActionNext> listTemplateActions(int workspaceId, int projectId) {
        Query query = session.createQuery(
                "from ActionNext an where an.projectId = :projectId and an.workspaceId = :workspaceId and an.nextDescription <> '' order by an.priorityLevel desc");
        query.setInteger("projectId", projectId);
        query.setInteger("workspaceId", workspaceId);
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

    public ActionNext getNextActionById(int workspaceId, int projectId, int actionNextId) {
        Query query = session.createQuery(
                "from ActionNext an where an.actionNextId = :actionNextId and an.projectId = :projectId and an.workspaceId = :workspaceId");
        query.setInteger("actionNextId", actionNextId);
        query.setInteger("projectId", projectId);
        query.setInteger("workspaceId", workspaceId);
        return (ActionNext) query.uniqueResult();
    }

    public ActionNext getNextActionById(int actionNextId) {
        return (ActionNext) session.get(ActionNext.class, actionNextId);
    }

    public ActionTaken getTakenActionById(int actionTakenId) {
        return (ActionTaken) session.get(ActionTaken.class, actionTakenId);
    }

    public ActionNext saveNextAction(ActionNext action) {
        validateNextActionStatus(action);
        action.setBillable(resolveBillable(action));
        session.save(action);
        return action;
    }

    public ActionTaken saveTakenAction(ActionTaken action) {
        session.save(action);
        return action;
    }

    public void updateNextAction(ActionNext action) {
        validateNextActionStatus(action);
        action.setBillable(resolveBillable(action));
        session.update(action);
    }

    private boolean resolveBillable(ActionNext action) {
        if (action == null) {
            return false;
        }
        Project project = action.getProject();
        if (project == null && action.getProjectId() > 0) {
            project = (Project) session.get(Project.class, action.getProjectId());
        }
        return isBillable(session, project);
    }

    /**
     * Whether a new action on this project is work ({@code billable = Y}). It is work only when the
     * project's bill code is itself marked billable. Having a bill code is not enough: "Personal" is a
     * bill code too, and the phone leaves out every billable action. Use this wherever an action is
     * created so every path agrees.
     */
    public static boolean isBillable(Session session, Project project) {
        if (project == null || project.getBillCode() == null || project.getBillCode().trim().equals("")) {
            return false;
        }
        BillCode billCode = ClientServlet.resolveBillCode(session, project);
        return billCode != null && "Y".equalsIgnoreCase(billCode.getBillable());
    }

    private void validateNextActionStatus(ActionNext action) {
        if (action == null) {
            throw new IllegalStateException("ActionNext is required.");
        }
        if (action.getNextActionStatus() == null) {
            throw new IllegalStateException("ActionNext status must be set.");
        }
    }

    public void updateTakenAction(ActionTaken action) {
        session.update(action);
    }

    public void deleteNextAction(ActionNext action) {
        session.delete(action);
    }

    public void deleteTakenAction(ActionTaken action) {
        session.delete(action);
    }

    public List<ActionTaken> listTakenActionsForProject(int projectId) {
        Query query = session.createQuery(
                "from ActionTaken atk where atk.projectId = :projectId order by atk.actionDate desc");
        query.setInteger("projectId", projectId);
        @SuppressWarnings("unchecked")
        List<ActionTaken> results = query.list();
        return results;
    }

    public List<ActionTaken> listTakenActionsForProjectAndContact(int projectId, int contactId) {
        Query query = session.createQuery(
                "from ActionTaken atk where atk.projectId = :projectId and atk.contactId = :contactId "
                        + "order by atk.actionDate desc");
        query.setInteger("projectId", projectId);
        query.setInteger("contactId", contactId);
        @SuppressWarnings("unchecked")
        List<ActionTaken> results = query.list();
        return results;
    }

    public boolean hasTakenActionsForProjectSince(int projectId, Date sinceDate) {
        Query query = session.createQuery(
                "select count(atk.actionTakenId) from ActionTaken atk where atk.projectId = :projectId and atk.actionDate >= :sinceDate");
        query.setInteger("projectId", projectId);
        query.setTimestamp("sinceDate", sinceDate);
        Long count = (Long) query.uniqueResult();
        return count != null && count.longValue() > 0;
    }

    public List<ActionNext> listGoalActions() {
        Query query = session.createQuery(
                "select distinct an from ActionNext an "
                        + "left join fetch an.project "
                        + "where an.nextDescription <> '' "
                        + "and an.nextActionType = :nextActionType "
                        + "order by an.priorityLevel desc, an.projectId, an.nextActionDate asc");
        query.setString("nextActionType", org.openimmunizationsoftware.pt.model.ProjectNextActionType.GOAL);
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

    public List<ActionNext> findNextActionsByTemplate(int templateActionNextId) {
        Query query = session
                .createQuery("from ActionNext an where an.templateActionNextId = :templateActionNextId");
        query.setInteger("templateActionNextId", templateActionNextId);
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

    public List<ActionNext> findNextActionsDueBetween(int projectId, Date start, Date end) {
        Query query = session.createQuery(
                "from ActionNext an where an.projectId = :projectId and an.nextActionDate between :start and :end order by an.nextActionDate");
        query.setInteger("projectId", projectId);
        query.setDate("start", start);
        query.setDate("end", end);
        @SuppressWarnings("unchecked")
        List<ActionNext> results = query.list();
        return results;
    }

}
