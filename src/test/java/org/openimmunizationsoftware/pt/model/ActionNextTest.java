package org.openimmunizationsoftware.pt.model;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Assert;
import org.junit.Test;

public class ActionNextTest {

    @Test
    public void notesAreStoredExactlyAsSet() {
        ActionNext action = new ActionNext();
        String notes = "- first\n\n    indented\n - second";

        action.setNextNotes(notes);

        Assert.assertEquals(notes, action.getNextNotes());
    }

    @Test
    public void notesMapToAPlainTextColumn() throws Exception {
        String xml = readMapping();
        Assert.assertTrue(xml.contains("<property name=\"nextNotes\" type=\"text\">"));
        Assert.assertTrue(xml.contains("<column name=\"next_notes\" />"));
        Assert.assertFalse(xml.contains("nextNoteEntries"));
    }

    private String readMapping() throws Exception {
        InputStream mapping = ActionNext.class
                .getResourceAsStream("/org/openimmunizationsoftware/pt/model/ActionNext.hbm.xml");
        Assert.assertNotNull(mapping);
        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = mapping.read(buffer)) != -1) {
                content.write(buffer, 0, read);
            }
            return new String(content.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            mapping.close();
        }
    }
}
