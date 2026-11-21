package org.dandeliondaily.sessions.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Query;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.ProjectFolder;

/**
 * A project's folders: one primary and any number of ordered, labeled related folders. Read by
 * the Focused Action buttons, the launch resolver, the runner and get_project_context; written by
 * the project editor and update_project_folders. Callers own the transaction.
 */
public class ProjectFolderService {

    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_RELATED = "RELATED";
    public static final int MAX_RELATED = 20;

    /** A related folder as entered: label (optional) and "root:path". */
    public static class RelatedInput {
        public final String label;
        public final String spec;

        public RelatedInput(String label, String spec) {
            this.label = label;
            this.spec = spec;
        }
    }

    /** All folders of the project, primary first, then related in stored order. */
    @SuppressWarnings("unchecked")
    public List<ProjectFolder> list(Session session, int projectId) {
        Query query = session.createQuery("from ProjectFolder where projectId = :projectId "
                + "order by case when folderRole = 'PRIMARY' then 0 else 1 end, sortOrder, projectFolderId");
        query.setParameter("projectId", Integer.valueOf(projectId));
        return query.list();
    }

    public ProjectFolder primary(Session session, int projectId) {
        for (ProjectFolder folder : list(session, projectId)) {
            if (ROLE_PRIMARY.equals(folder.getFolderRole())) {
                return folder;
            }
        }
        return null;
    }

    public List<ProjectFolder> related(Session session, int projectId) {
        List<ProjectFolder> related = new ArrayList<ProjectFolder>();
        for (ProjectFolder folder : list(session, projectId)) {
            if (ROLE_RELATED.equals(folder.getFolderRole())) {
                related.add(folder);
            }
        }
        return related;
    }

    /**
     * Validates the whole set first, then replaces the project's folders. A blank primary removes
     * it. Throws IllegalArgumentException (nothing written) on any invalid entry.
     */
    public void replace(Session session, int projectId, String primarySpec, List<RelatedInput> relatedInputs) {
        FolderRoots.Folder primary = primarySpec == null || primarySpec.trim().length() == 0 ? null
                : FolderRoots.parse(primarySpec);
        if (primary != null && primary.path.length() == 0) {
            throw new IllegalArgumentException("The primary folder must be a folder under " + primary.root
                    + ":, not the whole root (agents work in the primary folder).");
        }
        List<FolderRoots.Folder> related = new ArrayList<FolderRoots.Folder>();
        List<String> labels = new ArrayList<String>();
        if (relatedInputs != null) {
            if (relatedInputs.size() > MAX_RELATED) {
                throw new IllegalArgumentException("A project can have at most " + MAX_RELATED + " related folders.");
            }
            for (RelatedInput input : relatedInputs) {
                if (input == null || input.spec == null || input.spec.trim().length() == 0) {
                    if (input != null && FolderRoots.normalizeLabel(input.label) != null) {
                        throw new IllegalArgumentException("Related folder \"" + input.label.trim() + "\" needs a folder.");
                    }
                    continue;
                }
                related.add(FolderRoots.parse(input.spec));
                labels.add(FolderRoots.normalizeLabel(input.label));
            }
        }
        for (ProjectFolder existing : list(session, projectId)) {
            session.delete(existing);
        }
        session.flush();
        if (primary != null) {
            ProjectFolder folder = new ProjectFolder();
            folder.setProjectId(projectId);
            folder.setFolderRole(ROLE_PRIMARY);
            folder.setPrimaryProjectId(Integer.valueOf(projectId));
            folder.setRootKey(primary.root);
            folder.setRelativePath(primary.path);
            folder.setSortOrder(0);
            session.save(folder);
        }
        for (int i = 0; i < related.size(); i++) {
            ProjectFolder folder = new ProjectFolder();
            folder.setProjectId(projectId);
            folder.setFolderRole(ROLE_RELATED);
            folder.setRootKey(related.get(i).root);
            folder.setRelativePath(related.get(i).path);
            folder.setLabel(labels.get(i));
            folder.setSortOrder(i + 1);
            session.save(folder);
        }
    }

    public static String spec(ProjectFolder folder) {
        return FolderRoots.format(folder.getRootKey(), folder.getRelativePath());
    }

    public static Map<String, Object> toMap(ProjectFolder folder) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("folderId", Integer.valueOf(folder.getProjectFolderId()));
        map.put("root", folder.getRootKey());
        map.put("path", folder.getRelativePath());
        map.put("folder", spec(folder));
        if (folder.getLabel() != null) {
            map.put("label", folder.getLabel());
        }
        return map;
    }

    /** {"primaryFolder": {...} or null, "relatedFolders": [...]} for project reads. */
    public Map<String, Object> describe(Session session, int projectId) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        ProjectFolder primary = null;
        List<Map<String, Object>> related = new ArrayList<Map<String, Object>>();
        for (ProjectFolder folder : list(session, projectId)) {
            if (ROLE_PRIMARY.equals(folder.getFolderRole())) {
                primary = folder;
            } else {
                related.add(toMap(folder));
            }
        }
        map.put("primaryFolder", primary == null ? null : toMap(primary));
        map.put("relatedFolders", related);
        return map;
    }
}
