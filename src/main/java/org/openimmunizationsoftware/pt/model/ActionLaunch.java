package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;
import java.util.Date;

/**
 * One launch from a Focused Action button: minted by the signed-in page, redeemed once by the PC. Table action_launch (src/db/v5.17.sql).
 */
public class ActionLaunch implements Serializable {

    private static final long serialVersionUID = 1L;

    private int launchId;
    private String nonceHash;
    private int webUserId;
    private int workspaceId;
    private int actionNextId;
    private String verb;
    private String agentKey;
    private String agentModel;
    private Integer projectFolderId;
    private String envKey;
    private Date issuedDate;
    private Date expiresDate;
    private Date redeemedDate;
    private Integer redeemedClientId;
    private String folderRootKey;
    private String folderRelativePath;
    private String pendingSessionId;
    private String boundSessionId;
    private Date boundDate;
    private Date endedDate;

    public int getLaunchId() {
        return launchId;
    }

    public void setLaunchId(int launchId) {
        this.launchId = launchId;
    }

    public String getNonceHash() {
        return nonceHash;
    }

    public void setNonceHash(String nonceHash) {
        this.nonceHash = nonceHash;
    }

    public int getWebUserId() {
        return webUserId;
    }

    public void setWebUserId(int webUserId) {
        this.webUserId = webUserId;
    }

    public int getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(int workspaceId) {
        this.workspaceId = workspaceId;
    }

    public int getActionNextId() {
        return actionNextId;
    }

    public void setActionNextId(int actionNextId) {
        this.actionNextId = actionNextId;
    }

    public String getVerb() {
        return verb;
    }

    public void setVerb(String verb) {
        this.verb = verb;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public void setAgentModel(String agentModel) {
        this.agentModel = agentModel;
    }

    public Integer getProjectFolderId() {
        return projectFolderId;
    }

    public void setProjectFolderId(Integer projectFolderId) {
        this.projectFolderId = projectFolderId;
    }

    public String getEnvKey() {
        return envKey;
    }

    public void setEnvKey(String envKey) {
        this.envKey = envKey;
    }

    public Date getIssuedDate() {
        return issuedDate;
    }

    public void setIssuedDate(Date issuedDate) {
        this.issuedDate = issuedDate;
    }

    public Date getExpiresDate() {
        return expiresDate;
    }

    public void setExpiresDate(Date expiresDate) {
        this.expiresDate = expiresDate;
    }

    public Date getRedeemedDate() {
        return redeemedDate;
    }

    public void setRedeemedDate(Date redeemedDate) {
        this.redeemedDate = redeemedDate;
    }

    public Integer getRedeemedClientId() {
        return redeemedClientId;
    }

    public void setRedeemedClientId(Integer redeemedClientId) {
        this.redeemedClientId = redeemedClientId;
    }

    public String getFolderRootKey() {
        return folderRootKey;
    }

    public void setFolderRootKey(String folderRootKey) {
        this.folderRootKey = folderRootKey;
    }

    public String getFolderRelativePath() {
        return folderRelativePath;
    }

    public void setFolderRelativePath(String folderRelativePath) {
        this.folderRelativePath = folderRelativePath;
    }

    public String getPendingSessionId() {
        return pendingSessionId;
    }

    public void setPendingSessionId(String pendingSessionId) {
        this.pendingSessionId = pendingSessionId;
    }

    public String getBoundSessionId() {
        return boundSessionId;
    }

    public void setBoundSessionId(String boundSessionId) {
        this.boundSessionId = boundSessionId;
    }

    public Date getBoundDate() {
        return boundDate;
    }

    public void setBoundDate(Date boundDate) {
        this.boundDate = boundDate;
    }

    public Date getEndedDate() {
        return endedDate;
    }

    public void setEndedDate(Date endedDate) {
        this.endedDate = endedDate;
    }

}
