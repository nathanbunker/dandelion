package org.dandeliondaily.personal.service;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class DeviceServiceTest {

    @After
    public void tearDown() {
        DeviceService.clearFailures();
    }

    @Test
    public void deviceKeysAreLongRandomAndUrlSafe() {
        String a = DeviceService.newDeviceKey();
        String b = DeviceService.newDeviceKey();
        Assert.assertEquals(43, a.length());
        Assert.assertTrue(a.matches("[A-Za-z0-9_-]+"));
        Assert.assertNotEquals(a, b);
    }

    @Test
    public void hashIsStableAndFitsTheColumn() {
        String hash = DeviceService.hashKey("abc");
        Assert.assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash);
        Assert.assertTrue(DeviceService.hashKey(DeviceService.newDeviceKey()).length() <= 80);
    }

    @Test
    public void deviceNameIsCleaned() {
        Assert.assertEquals("Sundial on Nathan's S23", DeviceService.cleanDeviceName("  Sundial on\tNathan's\nS23 "));
        Assert.assertEquals("", DeviceService.cleanDeviceName(null));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('x');
        }
        Assert.assertEquals(DeviceService.MAX_DEVICE_NAME, DeviceService.cleanDeviceName(longName.toString()).length());
    }

    @Test
    public void failedSignInsAreThrottled() {
        long now = 1_000_000L;
        for (int i = 0; i < DeviceService.MAX_FAILURES - 1; i++) {
            DeviceService.recordFailure("nathan", now);
        }
        Assert.assertFalse(DeviceService.tooManyFailures("nathan", now));
        DeviceService.recordFailure("nathan", now);
        Assert.assertTrue(DeviceService.tooManyFailures("nathan", now));
        Assert.assertFalse(DeviceService.tooManyFailures("someone", now));
        Assert.assertFalse(DeviceService.tooManyFailures("nathan", now + DeviceService.FAILURE_WINDOW_MS + 1));
    }
}
