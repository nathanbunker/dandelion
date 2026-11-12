package org.dandeliondaily.personal.service;

import org.junit.Assert;
import org.junit.Test;

public class PersonalActionJsonTest {

    @Test
    public void plainTextDropsTheWebMarkup() {
        Assert.assertEquals("I will call Bob & Sue", PersonalActionJson.plainText("<i>I will</i> call Bob &amp; Sue"));
        Assert.assertEquals("Pay the water bill", PersonalActionJson.plainText("Pay the water bill"));
        Assert.assertNull(PersonalActionJson.plainText(null));
    }
}
