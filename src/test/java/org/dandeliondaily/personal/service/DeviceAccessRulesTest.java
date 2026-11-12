package org.dandeliondaily.personal.service;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.WebApiClient;

public class DeviceAccessRulesTest {

    private static final String DEVICE = WebApiClient.TYPE_DEVICE;

    private static String refusal(String status, String path) {
        DeviceAccessRules.Refusal r = DeviceAccessRules.check(DEVICE, status, path);
        return r == null ? null : r.status + " " + r.code;
    }

    @Test
    public void agentsAreNotLimited() {
        Assert.assertNull(DeviceAccessRules.check(WebApiClient.TYPE_AGENT, null, "v1/actions"));
        Assert.assertNull(DeviceAccessRules.check(WebApiClient.TYPE_AGENT, null, "v1/mcp"));
    }

    @Test
    public void approvedDevice_personalEndpointsOnly() {
        String ok = WebApiClient.STATUS_APPROVED;
        Assert.assertNull(refusal(ok, "v1/personal/day"));
        Assert.assertNull(refusal(ok, "/v1/personal/actions/12"));
        Assert.assertNull(refusal(ok, "v1/me"));
        Assert.assertNull(refusal(ok, "v1/devices/me"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/actions"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/projects"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/sync/projects"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/mcp"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/personal"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/personalx/day"));
        Assert.assertEquals("403 device_scope", refusal(ok, "v1/personal/../actions"));
    }

    @Test
    public void pendingDevice_canOnlyAskItsStatus() {
        String pending = WebApiClient.STATUS_PENDING;
        Assert.assertNull(refusal(pending, "v1/devices/me"));
        Assert.assertEquals("403 device_pending", refusal(pending, "v1/personal/day"));
        Assert.assertEquals("403 device_pending", refusal(pending, "v1/me"));
    }

    @Test
    public void revokedDevice_isRefusedEverywhere() {
        String revoked = WebApiClient.STATUS_REVOKED;
        Assert.assertEquals("401 device_revoked", refusal(revoked, "v1/devices/me"));
        Assert.assertEquals("401 device_revoked", refusal(revoked, "v1/personal/day"));
    }

    @Test
    public void unknownStatusIsRefused() {
        Assert.assertEquals("401 device_revoked", refusal(null, "v1/personal/day"));
    }

    @Test
    public void onlySignInIsPublic() {
        Assert.assertTrue(DeviceAccessRules.isPublicPath("v1/devices/sign-in"));
        Assert.assertTrue(DeviceAccessRules.isPublicPath("/v1/devices/sign-in/"));
        Assert.assertFalse(DeviceAccessRules.isPublicPath("v1/devices/me"));
        Assert.assertFalse(DeviceAccessRules.isPublicPath("v1/personal/day"));
    }
}
