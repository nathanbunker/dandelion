package org.dandeliondaily.shared.service;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.hibernate.Query;
import org.hibernate.Session;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ActionNext;
import org.openimmunizationsoftware.pt.model.ActionSet;
import org.openimmunizationsoftware.pt.model.ActionSetType;

public class ActionNotesServiceTest {

    private final ActionNotesService service = new ActionNotesService();
    private final List<ActionNext> siblings = new ArrayList<ActionNext>();
    private final List<ActionNext> updated = new ArrayList<ActionNext>();
    private Session session;

    @Before
    public void setUp() {
        session = (Session) Proxy.newProxyInstance(Session.class.getClassLoader(), new Class<?>[] {Session.class},
                (proxy, method, args) -> {
                    if ("createQuery".equals(method.getName())) {
                        return Proxy.newProxyInstance(Query.class.getClassLoader(), new Class<?>[] {Query.class},
                                (q, m, a) -> {
                                    if (m.getName().startsWith("set")) {
                                        return q;
                                    }
                                    if ("list".equals(m.getName())) {
                                        return siblings;
                                    }
                                    throw new UnsupportedOperationException(m.getName());
                                });
                    }
                    if ("update".equals(method.getName())) {
                        updated.add((ActionNext) args[0]);
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    public void appendToEmptyNotesIsJustTheText() {
        ActionNext action = action(1, null);

        ActionNotesService.Result result = service.append(session, action, "First line  \n", null);

        Assert.assertTrue(result.isOk());
        Assert.assertEquals("First line", action.getNextNotes());
    }

    @Test
    public void appendAddsANewLineWithoutPrefixesOrDates() {
        ActionNext action = action(1, "- one\n  two");

        service.append(session, action, "three", null);

        Assert.assertEquals("- one\n  two\nthree", action.getNextNotes());
    }

    @Test
    public void appendDoesNotDoubleATrailingNewline() {
        ActionNext action = action(1, "one\n");

        service.append(session, action, "two", null);

        Assert.assertEquals("one\ntwo", action.getNextNotes());
    }

    @Test
    public void appendOfBlankTextIsRefused() {
        ActionNext action = action(1, "one");

        ActionNotesService.Result result = service.append(session, action, "  \n ", null);

        Assert.assertFalse(result.isOk());
        Assert.assertEquals("one", action.getNextNotes());
    }

    @Test
    public void replaceWithUnchangedBaseKeepsTextAsTyped() {
        ActionNext action = action(1, "old");
        Date changeDate = new Date(5000L);
        String typed = "- first\n\n    indented\n";

        ActionNotesService.Result result = service.replace(session, action, typed, "old", changeDate);

        Assert.assertTrue(result.isOk());
        Assert.assertTrue(result.isChanged());
        Assert.assertFalse(result.isTailReattached());
        Assert.assertEquals(typed, action.getNextNotes());
        Assert.assertEquals(changeDate, action.getNextChangeDate());
        Assert.assertEquals(Arrays.asList(action), updated);
    }

    @Test
    public void replaceReattachesTextAppendedSinceTheEditorOpened() {
        ActionNext action = action(1, "one\ntwo\nadded by MCP");

        ActionNotesService.Result result = service.replace(session, action, "one\nTWO", "one\ntwo", null);

        Assert.assertTrue(result.isOk());
        Assert.assertTrue(result.isTailReattached());
        Assert.assertEquals("one\nTWO\nadded by MCP", action.getNextNotes());
    }

    @Test
    public void replaceFromEmptyBaseReattachesEverythingAppended() {
        ActionNext action = action(1, "added");

        service.replace(session, action, "mine", "", null);

        Assert.assertEquals("mine\nadded", action.getNextNotes());
    }

    @Test
    public void replaceIsRefusedWhenTheTextChangedOtherwise() {
        ActionNext action = action(1, "one\nchanged elsewhere");

        ActionNotesService.Result result = service.replace(session, action, "mine", "one\ntwo", new Date());

        Assert.assertFalse(result.isOk());
        Assert.assertEquals(ActionNotesService.CHANGED_MESSAGE, result.getErrorMessage());
        Assert.assertEquals("one\nchanged elsewhere", action.getNextNotes());
        Assert.assertNull(action.getNextChangeDate());
        Assert.assertTrue(updated.isEmpty());
    }

    @Test
    public void untouchedNotesNeverBlockASaveEvenIfChangedElsewhere() {
        ActionNext action = action(1, "rewritten elsewhere");

        ActionNotesService.Result result = service.replace(session, action, "one\ntwo", "one\ntwo", new Date());

        Assert.assertTrue(result.isOk());
        Assert.assertFalse(result.isChanged());
        Assert.assertEquals("rewritten elsewhere", action.getNextNotes());
        Assert.assertTrue(updated.isEmpty());
    }

    @Test
    public void replaceWithoutBaseSkipsTheCheck() {
        ActionNext action = action(1, "anything");

        service.replace(session, action, "new", null, null);

        Assert.assertEquals("new", action.getNextNotes());
    }

    @Test
    public void clearingNotesStoresNull() {
        ActionNext action = action(1, "one");

        ActionNotesService.Result result = service.replace(session, action, "  ", "one", null);

        Assert.assertTrue(result.isOk());
        Assert.assertNull(action.getNextNotes());
        Assert.assertEquals("", result.getText());
    }

    @Test
    public void unchangedTextIsNotWritten() {
        ActionNext action = action(1, "same");

        ActionNotesService.Result result = service.replace(session, action, "same", "same", new Date());

        Assert.assertTrue(result.isOk());
        Assert.assertFalse(result.isChanged());
        Assert.assertNull(action.getNextChangeDate());
        Assert.assertTrue(updated.isEmpty());
    }

    @Test
    public void writesReachEverySharedTwin() {
        ActionSet shared = new ActionSet();
        shared.setActionSetId(9);
        shared.setActionSetType(ActionSetType.SHARED);
        ActionNext privateTwin = action(1, "note");
        ActionNext sharedTwin = action(2, "note");
        privateTwin.setActionSet(shared);
        sharedTwin.setActionSet(shared);
        siblings.add(privateTwin);
        siblings.add(sharedTwin);
        Date changeDate = new Date(7000L);

        service.append(session, sharedTwin, "more", changeDate);
        Assert.assertEquals("note\nmore", privateTwin.getNextNotes());
        Assert.assertEquals("note\nmore", sharedTwin.getNextNotes());
        Assert.assertEquals(changeDate, privateTwin.getNextChangeDate());

        service.replace(session, privateTwin, "rewritten", "note\nmore", null);
        Assert.assertEquals("rewritten", privateTwin.getNextNotes());
        Assert.assertEquals("rewritten", sharedTwin.getNextNotes());
    }

    @Test
    public void lineEndingsAreNormalized() {
        ActionNext action = action(1, "one\r\ntwo");

        ActionNotesService.Result result = service.replace(session, action, "one\r\ntwo\rthree", "one\ntwo",
                null);

        Assert.assertTrue(result.isOk());
        Assert.assertEquals("one\ntwo\nthree", action.getNextNotes());
    }

    @Test
    public void textOverTheLengthCapIsRejectedNotTruncated() {
        ActionNext action = action(1, "keep");
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= ActionNotesService.MAX_LENGTH; i++) {
            tooLong.append('x');
        }

        ActionNotesService.Result replaced = service.replace(session, action, tooLong.toString(), "keep", null);
        Assert.assertFalse(replaced.isOk());
        Assert.assertEquals("Notes are limited to 20,000 characters.", replaced.getErrorMessage());

        ActionNotesService.Result appended = service.append(session, action,
                tooLong.substring(0, ActionNotesService.MAX_LENGTH), null);
        Assert.assertFalse(appended.isOk());
        Assert.assertEquals("keep", action.getNextNotes());

        try {
            service.initialize(new ActionNext(), tooLong.toString());
            Assert.fail("Expected the cap to reject new-action notes too");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals("Notes are limited to 20,000 characters.", expected.getMessage());
        }
    }

    @Test
    public void displayLinesSkipBlankLinesAndKeepIndentation() {
        ActionNext action = action(1, "one\n\n   \n  two  \n- three");

        Assert.assertEquals(Arrays.asList("one", "  two", "- three"), service.displayLines(action));
        Assert.assertTrue(service.displayLines(action(2, null)).isEmpty());
    }

    private static ActionNext action(int id, String notes) {
        ActionNext action = new ActionNext();
        action.setActionNextId(id);
        action.setNextNotes(notes);
        return action;
    }
}
