package org.dandeliondaily.personal.service;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;

public class ActionAccessServiceTest {

    private static final int ME = 7;
    private static final int OTHER = 9;
    private static final int WORKSPACE = 3;

    private static ActionNext action(Integer owner, Integer nextContact, Integer workspace, boolean billable) {
        ActionNext action = new ActionNext();
        action.setContactId(owner);
        action.setNextContactId(nextContact);
        action.setWorkspaceId(workspace);
        action.setBillable(billable);
        return action;
    }

    @Test
    public void ownPersonalAction() {
        Assert.assertTrue(ActionAccessService.isPersonalActionFor(action(ME, null, WORKSPACE, false), ME, WORKSPACE));
    }

    @Test
    public void personalActionWhereIAmTheNextContact() {
        Assert.assertTrue(ActionAccessService.isPersonalActionFor(action(OTHER, ME, WORKSPACE, false), ME, WORKSPACE));
    }

    @Test
    public void workActionIsNeverVisible() {
        Assert.assertFalse(ActionAccessService.isPersonalActionFor(action(ME, null, WORKSPACE, true), ME, WORKSPACE));
    }

    @Test
    public void someoneElsesActionIsNotVisible() {
        Assert.assertFalse(ActionAccessService.isPersonalActionFor(action(OTHER, OTHER, WORKSPACE, false), ME,
                WORKSPACE));
    }

    @Test
    public void otherWorkspaceIsNotVisible() {
        Assert.assertFalse(ActionAccessService.isPersonalActionFor(action(ME, null, 4, false), ME, WORKSPACE));
        Assert.assertFalse(ActionAccessService.isPersonalActionFor(action(ME, null, null, false), ME, WORKSPACE));
    }

    @Test
    public void missingActionOrContact() {
        Assert.assertFalse(ActionAccessService.isPersonalActionFor(null, ME, WORKSPACE));
        Assert.assertFalse(ActionAccessService.isPersonalActionFor(action(null, null, WORKSPACE, false), 0, WORKSPACE));
    }
}
