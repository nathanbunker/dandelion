package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;
import java.util.Date;

/** An undo receipt for a phone change (Sundial). See src/db/v5.16.sql. */
public class ActionUndo implements Serializable {
    private static final long serialVersionUID = 1L;

    private int undoId;
    private int clientId;
    private String token;
    private String summary;
    private String snapshot;
    private Date createdDate;
    private Date expiresDate;
    private Date usedDate;

    public int getUndoId() { return undoId; }
    public void setUndoId(int undoId) { this.undoId = undoId; }
    public int getClientId() { return clientId; }
    public void setClientId(int clientId) { this.clientId = clientId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
    public Date getCreatedDate() { return createdDate; }
    public void setCreatedDate(Date createdDate) { this.createdDate = createdDate; }
    public Date getExpiresDate() { return expiresDate; }
    public void setExpiresDate(Date expiresDate) { this.expiresDate = expiresDate; }
    public Date getUsedDate() { return usedDate; }
    public void setUsedDate(Date usedDate) { this.usedDate = usedDate; }
}
