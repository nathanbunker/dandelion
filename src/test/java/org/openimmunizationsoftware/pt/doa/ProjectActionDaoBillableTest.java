package org.openimmunizationsoftware.pt.doa;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.BillCode;
import org.openimmunizationsoftware.pt.model.Project;

/**
 * The one rule for whether a new action is work: the project's bill code must itself be billable.
 * A nonbillable bill code such as "Personal" must not make an action billable, or the phone hides it.
 */
public class ProjectActionDaoBillableTest {

    @Test
    public void personalBillCodeIsNotBillable() {
        Assert.assertFalse(ProjectActionDao.isBillable(session(code("Personal", "N")), project("Personal")));
    }

    @Test
    public void billableBillCodeIsBillable() {
        Assert.assertTrue(ProjectActionDao.isBillable(session(code("OPS", "Y")), project("OPS")));
    }

    @Test
    public void unknownOrMissingBillCodeIsNotBillable() {
        Assert.assertFalse(ProjectActionDao.isBillable(session(null), project("Gone")));
        Assert.assertFalse(ProjectActionDao.isBillable(session(code("OPS", "Y")), project("")));
        Assert.assertFalse(ProjectActionDao.isBillable(session(code("OPS", "Y")), project(null)));
        Assert.assertFalse(ProjectActionDao.isBillable(session(code("OPS", "Y")), null));
    }

    private static BillCode code(String billCode, String billable) {
        BillCode code = new BillCode();
        code.setWorkspaceId(Integer.valueOf(1));
        code.setBillCode(billCode);
        code.setBillable(billable);
        return code;
    }

    private static Project project(String billCode) {
        Project project = new Project();
        project.setWorkspaceId(Integer.valueOf(1));
        project.setBillCode(billCode);
        return project;
    }

    private static Session session(BillCode code) {
        final List<BillCode> codes = code == null ? Collections.<BillCode>emptyList()
                : Collections.singletonList(code);
        final Query query = proxy(Query.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("list".equals(method.getName())) {
                    return codes;
                }
                return Query.class.isAssignableFrom(method.getReturnType()) ? proxy : null;
            }
        });
        return proxy(Session.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return "createQuery".equals(method.getName()) ? query : null;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }
}
