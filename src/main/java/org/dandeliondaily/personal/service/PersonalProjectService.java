package org.dandeliondaily.personal.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;
import org.openimmunizationsoftware.pt.model.ProjectStatus;
import org.openimmunizationsoftware.pt.model.WebUser;
import org.openimmunizationsoftware.pt.servlet.ClientServlet;

/**
 * The user's personal projects: open projects in the workspace whose bill code isn't billable, the
 * same rule /m/project uses. Work projects never reach the phone, so they can't be picked for capture
 * or an edit, and a "project:" prefix can't name one.
 */
public class PersonalProjectService {

    /** Open personal projects, most recently used first (then by name). */
    public List<Project> listPersonalProjects(Session session, WebUser webUser, int workspaceId) {
        Query query = session.createQuery("from Project where workspaceId = :workspaceId"
                + " and (projectStatus is null or projectStatus <> :closedStatus) order by projectName");
        query.setParameter("workspaceId", Integer.valueOf(workspaceId));
        query.setParameter("closedStatus", ProjectStatus.CLOSED.getDatabaseValue());
        @SuppressWarnings("unchecked")
        List<Project> all = query.list();
        List<Project> personal = new ArrayList<Project>();
        for (Project project : all) {
            if (!isBillable(session, project)) {
                personal.add(project);
            }
        }
        final Map<Integer, Date> lastUsed = lastUsed(session, webUser, workspaceId);
        Collections.sort(personal, new Comparator<Project>() {
            public int compare(Project a, Project b) {
                Date da = lastUsed.get(Integer.valueOf(a.getProjectId()));
                Date db = lastUsed.get(Integer.valueOf(b.getProjectId()));
                if (da == null && db == null) {
                    return 0; // keep name order
                }
                if (da == null) {
                    return 1;
                }
                if (db == null) {
                    return -1;
                }
                return db.compareTo(da);
            }
        });
        return personal;
    }

    /** The project if it is an open personal project in the workspace, otherwise null. */
    public Project findPersonalProject(Session session, int workspaceId, int projectId) {
        Project project = (Project) session.get(Project.class, Integer.valueOf(projectId));
        if (project == null || project.getWorkspaceId() == null || project.getWorkspaceId().intValue() != workspaceId) {
            return null;
        }
        if (ProjectStatus.CLOSED.getDatabaseValue().equals(project.getProjectStatus())) {
            return null;
        }
        return isBillable(session, project) ? null : project;
    }

    public static boolean isBillable(Session session, Project project) {
        if (project == null || project.getBillCode() == null || project.getBillCode().equals("")) {
            return false;
        }
        BillCode billCode = ClientServlet.resolveBillCode(session, project);
        return billCode != null && "Y".equalsIgnoreCase(billCode.getBillable());
    }

    private static Map<Integer, Date> lastUsed(Session session, WebUser webUser, int workspaceId) {
        Query query = session.createQuery("select projectId, max(nextChangeDate) from ActionNext"
                + " where workspaceId = :workspaceId and contactId = :contactId group by projectId");
        query.setParameter("workspaceId", Integer.valueOf(workspaceId));
        query.setParameter("contactId", Integer.valueOf(webUser.getContactId()));
        Map<Integer, Date> result = new HashMap<Integer, Date>();
        for (Object row : query.list()) {
            Object[] cols = (Object[]) row;
            if (cols[0] != null && cols[1] != null) {
                result.put((Integer) cols[0], (Date) cols[1]);
            }
        }
        return result;
    }
}
