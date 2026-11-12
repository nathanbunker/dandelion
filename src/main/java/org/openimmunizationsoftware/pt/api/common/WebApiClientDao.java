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

    public void touchLastUsedDate(WebApiClient client, Date when) {
        if (client == null) {
            return;
        }
        client.setLastUsedDate(when);
        HibernateRequestContext.getCurrentSession().update(client);
    }
}
