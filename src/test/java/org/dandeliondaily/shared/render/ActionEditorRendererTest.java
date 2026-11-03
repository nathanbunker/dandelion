package org.dandeliondaily.shared.render;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;
import org.openimmunizationsoftware.pt.model.ProjectContact;

public class ActionEditorRendererTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    private String render(int openActionNextId, String reloadUrl, ProjectContact... contacts) {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);
        new ActionEditorRenderer().renderMarkup(out, TODAY,
                contacts.length == 0 ? Collections.<ProjectContact>emptyList() : Arrays.asList(contacts),
                openActionNextId, reloadUrl);
        out.flush();
        return sw.toString();
    }

    @Test
    public void render_hasAllElevenVerbsInTheMenu() {
        String html = render(0, null);
        String[] types = { "WILL", "MIGHT", "WOULD_LIKE_TO", "WILL_CONTACT", "WILL_MEET", "REVIEW", "DOCUMENT",
                "WILL_FOLLOW_UP", "COMMITTED_TO", "GOAL", "WAITING" };
        for (String type : types) {
            Assert.assertTrue(type, html.contains("class=\"ae-verb-option\" role=\"menuitem\" data-type=\"" + type + "\""));
        }
    }

    @Test
    public void render_usesDatePickersAndShowsNoteUpFront() {
        String html = render(0, null);
        Assert.assertTrue(html.contains("<input type=\"date\" id=\"aeDate\" />"));
        Assert.assertTrue(html.contains("<input type=\"date\" id=\"aeTarget\" />"));
        Assert.assertTrue(html.contains("<input type=\"date\" id=\"aeDeadline\" />"));
        Assert.assertTrue(html.contains("<textarea id=\"aeNote\""));
        int details = html.indexOf("<details");
        Assert.assertTrue("note is no longer tucked under More options",
                details < 0 || html.indexOf("<textarea id=\"aeNote\"") < details);
        Assert.assertTrue("the only collapsed section is the thread history",
                details < 0 || html.indexOf("<details", details + 1) < 0 && html.contains("<details id=\"aeHistory\""));
        Assert.assertTrue(html.contains("data-today=\"2026-10-31\""));
    }

    @Test
    public void render_loadsTheSharedScriptAndStyles() {
        String html = render(0, null);
        Assert.assertTrue(html.contains("href=\"css/action-editor.css?v="));
        Assert.assertTrue(html.contains("<script src=\"js/action-editor.js?v="));
    }

    @Test
    public void render_opensOnCapturedActionOnlyWhenAsked() {
        Assert.assertFalse(render(0, null).contains("data-open-action"));
        String html = render(4321, "PlanAheadServlet?windowStart=2026-11-02&mode=WORK");
        Assert.assertTrue(html.contains("data-open-action=\"4321\""));
        Assert.assertTrue(html.contains("data-reload-url=\"PlanAheadServlet?windowStart=2026-11-02&amp;mode=WORK\""));
    }

    @Test
    public void render_listsContactsEscaped() {
        ProjectContact contact = new ProjectContact();
        contact.setContactId(12);
        contact.setNameFirst("Erin");
        contact.setNameLast("<O'Neil>");
        String html = render(0, null, contact);
        Assert.assertTrue(html.contains("<option value=\"12\">Erin &lt;O&#39;Neil&gt;</option>"));
    }
}
