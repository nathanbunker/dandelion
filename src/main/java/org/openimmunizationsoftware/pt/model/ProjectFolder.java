package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;

/**
 * A project folder: a known root (brain, dropbox, dev, aira) plus a relative path. One PRIMARY per
 * project, any number of ordered RELATED folders. Table project_folder (src/db/v5.17.sql).
 */
public class ProjectFolder implements Serializable {

    private static final long serialVersionUID = 1L;

    private int projectFolderId;
    private int projectId;
    private String folderRole;
    private Integer primaryProjectId;
    private String rootKey;
    private String relativePath;
    private String label;
    private int sortOrder;

    public int getProjectFolderId() {
        return projectFolderId;
    }

    public void setProjectFolderId(int projectFolderId) {
        this.projectFolderId = projectFolderId;
    }

    public int getProjectId() {
        return projectId;
    }

    public void setProjectId(int projectId) {
        this.projectId = projectId;
    }

    public String getFolderRole() {
        return folderRole;
    }

    public void setFolderRole(String folderRole) {
        this.folderRole = folderRole;
    }

    public Integer getPrimaryProjectId() {
        return primaryProjectId;
    }

    public void setPrimaryProjectId(Integer primaryProjectId) {
        this.primaryProjectId = primaryProjectId;
    }

    public String getRootKey() {
        return rootKey;
    }

    public void setRootKey(String rootKey) {
        this.rootKey = rootKey;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

}
