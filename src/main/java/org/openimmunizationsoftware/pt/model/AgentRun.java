package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;
import java.util.Date;

/**
 * One attempt of an unattended agent run: claim, lease and outcome. Table agent_run (src/db/v5.17.sql).
 */
public class AgentRun implements Serializable {

    private static final long serialVersionUID = 1L;

    private int runId;
    private String attemptToken;
    private int actionNextId;
    private int webUserId;
    private int clientId;
    private String runStatus;
    private Date planningDate;
    private Integer targetActionNextId;
    private String sessionId;
    private String rootKey;
    private String relativePath;
    private Date claimedDate;
    private Date leaseExpiresDate;
    private Date heartbeatDate;
    private Date finishedDate;
    private String resultJson;
    private String errorText;
    private Integer failureActionNextId;
    private Date retryRequestedDate;

    public int getRunId() {
        return runId;
    }

    public void setRunId(int runId) {
        this.runId = runId;
    }

    public String getAttemptToken() {
        return attemptToken;
    }

    public void setAttemptToken(String attemptToken) {
        this.attemptToken = attemptToken;
    }

    public int getActionNextId() {
        return actionNextId;
    }

    public void setActionNextId(int actionNextId) {
        this.actionNextId = actionNextId;
    }

    public int getWebUserId() {
        return webUserId;
    }

    public void setWebUserId(int webUserId) {
        this.webUserId = webUserId;
    }

    public int getClientId() {
        return clientId;
    }

    public void setClientId(int clientId) {
        this.clientId = clientId;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public void setRunStatus(String runStatus) {
        this.runStatus = runStatus;
    }

    public Date getPlanningDate() {
        return planningDate;
    }

    public void setPlanningDate(Date planningDate) {
        this.planningDate = planningDate;
    }

    public Integer getTargetActionNextId() {
        return targetActionNextId;
    }

    public void setTargetActionNextId(Integer targetActionNextId) {
        this.targetActionNextId = targetActionNextId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
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

    public Date getClaimedDate() {
        return claimedDate;
    }

    public void setClaimedDate(Date claimedDate) {
        this.claimedDate = claimedDate;
    }

    public Date getLeaseExpiresDate() {
        return leaseExpiresDate;
    }

    public void setLeaseExpiresDate(Date leaseExpiresDate) {
        this.leaseExpiresDate = leaseExpiresDate;
    }

    public Date getHeartbeatDate() {
        return heartbeatDate;
    }

    public void setHeartbeatDate(Date heartbeatDate) {
        this.heartbeatDate = heartbeatDate;
    }

    public Date getFinishedDate() {
        return finishedDate;
    }

    public void setFinishedDate(Date finishedDate) {
        this.finishedDate = finishedDate;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public String getErrorText() {
        return errorText;
    }

    public void setErrorText(String errorText) {
        this.errorText = errorText;
    }

    public Integer getFailureActionNextId() {
        return failureActionNextId;
    }

    public void setFailureActionNextId(Integer failureActionNextId) {
        this.failureActionNextId = failureActionNextId;
    }

    public Date getRetryRequestedDate() {
        return retryRequestedDate;
    }

    public void setRetryRequestedDate(Date retryRequestedDate) {
        this.retryRequestedDate = retryRequestedDate;
    }

}
