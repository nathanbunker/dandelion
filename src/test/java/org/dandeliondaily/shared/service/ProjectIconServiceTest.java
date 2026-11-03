package org.dandeliondaily.shared.service;

import static org.junit.Assert.*;

import org.junit.Test;
import org.openimmunizationsoftware.pt.model.Project;

public class ProjectIconServiceTest {

    private static final String SEEDLING = "🌱"; // one code point, two Java chars
    // man, ZWJ, woman, ZWJ, girl, ZWJ, boy: 7 code points, 11 Java chars
    private static final String FAMILY = "👨‍👩‍👧‍👦";

    @Test
    public void blankMeansNoIcon() {
        assertNull(ProjectIconService.normalize(null));
        assertNull(ProjectIconService.normalize("   "));
        assertNull(ProjectIconService.normalizeLenient(""));
    }

    @Test
    public void trimsAndKeepsEmoji() {
        assertEquals(SEEDLING, ProjectIconService.normalize("  " + SEEDLING + " "));
    }

    @Test
    public void countsCodePointsNotJavaChars() {
        assertEquals(11, FAMILY.length());
        assertEquals(FAMILY, ProjectIconService.normalize(FAMILY));
    }

    @Test(expected = ProjectIconService.InvalidIconException.class)
    public void rejectsTooLong() {
        ProjectIconService.normalize("123456789");
    }

    @Test
    public void lenientCutsOnCodePointBoundary() {
        String nineSeedlings = SEEDLING + SEEDLING + SEEDLING + SEEDLING + SEEDLING + SEEDLING + SEEDLING
                + SEEDLING + SEEDLING;
        String cut = ProjectIconService.normalizeLenient(nineSeedlings);
        assertEquals(8, cut.codePointCount(0, cut.length()));
        assertEquals(16, cut.length());
    }

    @Test
    public void setIconReturnsPrevious() {
        Project project = new Project();
        project.setProjectIcon(SEEDLING);
        assertEquals(SEEDLING, new ProjectIconService().setIcon(project, ""));
        assertNull(project.getProjectIcon());
    }
}
