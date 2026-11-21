package org.openimmunizationsoftware.pt.api.common;

import java.util.Date;
import org.hibernate.Query;
import org.dandeliondaily.personal.service.DeviceService;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.WebApiClient;

public class WebApiClientDao {

    public WebApiClient findByApiKey(String apiKey) {
        Session session = HibernateRequestContext.getCurrentSession();
        Query query = session.createQuery("from WebApiClient where apiKey = :apiKey");
        query.setString("apiKey", apiKey);
        WebApiClient client = (WebApiClient) query.uniqueResult();
        if (client == null) {
            // Device keys are stored hashed (DeviceService).
            query.setString("apiKey", DeviceService.hashKey(apiKey));
            client = (WebApiClient) query.uniqueResult();
        }
        return client;
    }

    static final long TOUCH_EVERY_MS = 60L * 1000L;

    /**
     * Stamps the key's last use in its own short transaction, at most once a minute. Updating it in
     * the request's transaction held that key's row lock for the whole request, so every request
     * with the same key (parallel runner calls, the MCP) queued behind the slowest one.
     */
    public void touchLastUsedDate(WebApiClient client, Date when) {
        if (client == null) {
            return;
        }
        if (client.getLastUsedDate() != null && when.getTime() - client.getLastUsedDate().getTime() < TOUCH_EVERY_MS) {
            return;
        }
        org.hibernate.Session own = null;
        org.hibernate.Transaction tx = null;
        try {
            own = org.openimmunizationsoftware.pt.CentralControl.getSessionFactory().openSession();
            tx = own.beginTransaction();
            own.createSQLQuery("update web_api_client set last_used_date = :when where client_id = :id "
                    + "and (last_used_date is null or last_used_date < :cutoff)")
                    .setTimestamp("when", when).setInteger("id", client.getClientId())
                    .setTimestamp("cutoff", new Date(when.getTime() - TOUCH_EVERY_MS)).executeUpdate();
            tx.commit();
        } catch (RuntimeException e) {
            // A missed stamp is harmless; never fail or slow the request for it.
            if (tx != null && tx.isActive()) {
                try {
                    tx.rollback();
                } catch (RuntimeException ignored) {
                    // keep going
                }
            }
        } finally {
            if (own != null) {
                own.close();
            }
        }
    }
}
