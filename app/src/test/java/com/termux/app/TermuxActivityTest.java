package com.termux.app;

import com.termux.app.event.SystemEventReceiver;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.data.TermuxUrlUtils;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashSet;

public class TermuxActivityTest {

    private void assertUrlsAre(String text, String... urls) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        Collections.addAll(expected, urls);
        Assert.assertEquals(expected, TermuxUrlUtils.extractUrls(text));
    }

    @Test
    public void testExtractUrls() {
        assertUrlsAre("hello http://example.com world", "http://example.com");

        assertUrlsAre("http://example.com\nhttp://another.com", "http://example.com", "http://another.com");

        assertUrlsAre("hello http://example.com world and http://more.example.com with secure https://more.example.com",
            "http://example.com", "http://more.example.com", "https://more.example.com");

        assertUrlsAre("hello https://example.com/#bar https://example.com/foo#bar",
            "https://example.com/#bar", "https://example.com/foo#bar");
    }

    @Test
    public void testSystemFeaturesConstants() {
        Assert.assertEquals("skyterm_system_features", QuickSettingsTileService.PREFERENCES_NAME);
        Assert.assertEquals("quick_tile_command", QuickSettingsTileService.KEY_QUICK_TILE_COMMAND);
        Assert.assertEquals("boot_tasks_enabled", QuickSettingsTileService.KEY_BOOT_TASKS_ENABLED);
        Assert.assertEquals(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termux/boot", SystemEventReceiver.BOOT_SCRIPTS_DIR_PATH);
    }

}
