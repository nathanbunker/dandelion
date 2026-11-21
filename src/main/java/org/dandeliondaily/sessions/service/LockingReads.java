package org.dandeliondaily.sessions.service;

import java.util.List;

import org.hibernate.LockMode;
import org.hibernate.SQLQuery;
import org.hibernate.Session;
import org.openimmunizationsoftware.pt.model.AgentPrepTarget;
import org.openimmunizationsoftware.pt.model.AgentRun;

/**
 * Reads that decide who wins a race. Under MySQL's REPEATABLE READ a plain SELECT sees the
 * snapshot taken at the request's first read (the API key check), so after waiting on a lock it
 * can still miss rows another request just committed. These use explicit {@code FOR UPDATE}
 * (a locking read sees the latest committed rows and holds them) or a refresh with an UPGRADE
 * lock. Hibernate HQL lock modes are not used: on this stack they are silently dropped.
 */
final class LockingReads {

    private LockingReads() {
    }

    /** Reloads the entity's current committed state and locks its row. */
    static <T> T refresh(Session session, T entity) {
        if (entity != null) {
            session.refresh(entity, LockMode.UPGRADE);
        }
        return entity;
    }

    static AgentRun latestRun(Session session, int actionNextId) {
        SQLQuery query = session.createSQLQuery(
                "select * from agent_run where action_next_id = :id order by run_id desc limit 1 for update");
        query.addEntity(AgentRun.class);
        query.setParameter("id", Integer.valueOf(actionNextId));
        @SuppressWarnings("unchecked")
        List<AgentRun> runs = query.list();
        return runs.isEmpty() ? null : refresh(session, runs.get(0));
    }

    static AgentRun runByToken(Session session, String token) {
        SQLQuery query = session.createSQLQuery("select * from agent_run where attempt_token = :token for update");
        query.addEntity(AgentRun.class);
        query.setParameter("token", token);
        @SuppressWarnings("unchecked")
        List<AgentRun> runs = query.list();
        return runs.isEmpty() ? null : refresh(session, runs.get(0));
    }

    @SuppressWarnings("unchecked")
    static List<AgentRun> staleRuns(Session session, int webUserId, java.util.Date now) {
        SQLQuery query = session.createSQLQuery("select * from agent_run where web_user_id = :userId "
                + "and run_status in ('CLAIMED', 'RUNNING') and lease_expires_date < :now for update");
        query.addEntity(AgentRun.class);
        query.setParameter("userId", Integer.valueOf(webUserId));
        query.setTimestamp("now", now);
        List<AgentRun> runs = query.list();
        for (AgentRun run : runs) {
            refresh(session, run);
        }
        return runs;
    }

    @SuppressWarnings("unchecked")
    static List<AgentPrepTarget> prepTargets(Session session, int prepTemplateId) {
        SQLQuery query = session.createSQLQuery(
                "select * from agent_prep_target where prep_template_id = :templateId for update");
        query.addEntity(AgentPrepTarget.class);
        query.setParameter("templateId", Integer.valueOf(prepTemplateId));
        List<AgentPrepTarget> links = query.list();
        for (AgentPrepTarget link : links) {
            refresh(session, link);
        }
        return links;
    }

    /** Ids of open target instances whose prep link exists, read with the latest commits. */
    @SuppressWarnings("unchecked")
    static List<Number> targetsWithPrep(Session session, int prepTemplateId) {
        return session.createSQLQuery("select target_action_next_id from agent_prep_target "
                + "where prep_template_id = :templateId for update")
                .setParameter("templateId", Integer.valueOf(prepTemplateId)).list();
    }
}
