package org.openimmunizationsoftware.pt.model;

import java.io.Serializable;
import java.util.Date;

/** The stored response to one phone write, by its Idempotency-Key. See src/db/v5.16.sql. */
public class ApiIdempotency implements Serializable {
    private static final long serialVersionUID = 1L;

    private int idempotencyId;
    private int clientId;
    private String idemKey;
    private String requestPath;
    private int responseStatus;
    private String responseBody;
    private Date createdDate;

    public int getIdempotencyId() { return idempotencyId; }
    public void setIdempotencyId(int idempotencyId) { this.idempotencyId = idempotencyId; }
    public int getClientId() { return clientId; }
    public void setClientId(int clientId) { this.clientId = clientId; }
    public String getIdemKey() { return idemKey; }
    public void setIdemKey(String idemKey) { this.idemKey = idemKey; }
    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String requestPath) { this.requestPath = requestPath; }
    public int getResponseStatus() { return responseStatus; }
    public void setResponseStatus(int responseStatus) { this.responseStatus = responseStatus; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public Date getCreatedDate() { return createdDate; }
    public void setCreatedDate(Date createdDate) { this.createdDate = createdDate; }
}
