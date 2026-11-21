package org.dandeliondaily.focus.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class FocusLauncherRendererTest {

    private static FocusLauncherRenderer.LauncherData data(boolean folder) {
        FocusLauncherRenderer.LauncherData data = new FocusLauncherRenderer.LauncherData();
        data.actionNextId = 797616;
        data.hasPrimaryFolder = folder;
        data.primaryFolderLabel = "brain:projects/work-hygiene";
        data.defaultAgentKey = "claude-code";
        data.agents.add(new String[] { "claude-code", "Claude Code", "Y", "Y" });
        data.agents.add(new String[] { "codex", "Codex", "Y", "Y" });
        if (folder) {
            data.relatedFolders.add(new String[] { "12", "Reports (dropbox:Work/Reports)" });
        }
        return data;
    }

    private static String buttons(FocusLauncherRenderer.LauncherData data) {
        StringWriter sw = new StringWriter();
        new FocusLauncherRenderer().renderButtons(new PrintWriter(sw), data);
        return sw.toString();
    }

    @Test
    public void launcherControlsStartHiddenAndCarryOnlyIds() {
        String html = buttons(data(true));
        assertTrue(html.contains("<span class=\"fa-launch\" hidden>"));
        assertTrue(html.contains("faLaunch('folder')"));
        assertTrue(html.contains("faLaunch('folder', null, 12)"));
        assertTrue(html.contains("faLaunch('terminal')"));
        assertTrue(html.contains("faLaunch('start', 'claude-code')"));
        assertTrue("other agent in the chooser", html.contains("faLaunch('start', 'codex')"));
        assertTrue(html.contains("faLaunch('new', 'claude-code')"));
        assertTrue(html.contains("faLaunch('new', 'codex')"));
        assertTrue("close out always available", html.contains("CloseOutServlet?actionNextId=797616"));
        assertFalse("no paths in handlers", html.contains("faLaunch('folder', 'dropbox"));
        assertFalse(html.contains("onclick=\"faLaunch('folder', null, 'dropbox"));
    }

    @Test
    public void noFolderDisablesWithAReason() {
        String html = buttons(data(false));
        assertTrue(html.contains("Set a primary folder on the project first."));
        assertTrue(html.contains("disabled>Terminal</button>"));
        assertFalse(html.contains("faLaunch('terminal')"));
        assertTrue(html.contains("CloseOutServlet?actionNextId=797616"));
    }

    @Test
    public void resumeShowsAgentAndAgeAndIsProminentOnAReview() {
        FocusLauncherRenderer.LauncherData data = data(true);
        Map<String, Object> session = new LinkedHashMap<String, Object>();
        session.put("agentKey", "claude-code");
        session.put("ageDays", Long.valueOf(0));
        session.put("resumable", Boolean.TRUE);
        session.put("state", "ACTIVE");
        session.put("source", "LAUNCH");
        data.session = session;
        String html = buttons(data);
        assertTrue(html, html.contains("Resume Claude &middot; 0d"));
        assertTrue(html.contains("class=\"fa-small-btn\" title=\"Reopen"));
        session.put("source", "HANDOFF");
        assertTrue(buttons(data).contains("fa-primary-btn fa-resume-main"));
        session.put("resumable", Boolean.FALSE);
        assertFalse("hidden from 30 days", buttons(data).contains("Resume"));
    }

    @Test
    public void scriptRevealsOnlyForOptedInWindowsDesktop() {
        StringWriter sw = new StringWriter();
        new FocusLauncherRenderer().renderScript(new PrintWriter(sw), data(true));
        String js = sw.toString();
        assertTrue(js.contains("/Windows/.test(ua) && !/Mobi|Android|iPhone|iPad/.test(ua)"));
        assertTrue(js.contains("localStorage.getItem('dandelion.launcher.optIn') === '1'"));
        assertTrue(js.contains("'X-Dandelion-Launch': '1'"));
    }
}
