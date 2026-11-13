package dev.couchtv.ontv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantTest {
    @Test fun lightsAndScenesAreAllowed() {
        assertTrue(HomeAssistant.allowed("light.living_room", null))
        assertTrue(HomeAssistant.allowed("scene.movie_night", null))
        assertTrue(HomeAssistant.allowed("climate.bedroom", null))
    }

    @Test fun locksAlarmsAndOtherKindsAreNeverAllowed() {
        assertFalse(HomeAssistant.allowed("lock.front_door", null))
        assertFalse(HomeAssistant.allowed("alarm_control_panel.house", null))
        assertFalse(HomeAssistant.allowed("camera.front_door", null))
        assertFalse(HomeAssistant.allowed("shell_command.reboot", null))
    }

    @Test fun blindsAreAllowedButGarageDoorsAndGatesAreNot() {
        assertTrue(HomeAssistant.allowed("cover.bedroom_blind", "blind"))
        assertTrue(HomeAssistant.allowed("cover.bedroom_blind", null))
        assertFalse(HomeAssistant.allowed("cover.garage", "garage"))
        assertFalse(HomeAssistant.allowed("cover.driveway", "gate"))
        assertFalse(HomeAssistant.allowed("cover.back", "door"))
    }

    @Test fun oddIdsAreRefused() {
        assertFalse(HomeAssistant.allowed("light.a/../lock", null))
        assertFalse(HomeAssistant.allowed("light", null))
        assertFalse(HomeAssistant.allowed("light.Living Room", null))
    }

    @Test fun servicesFollowWhatWasAsked() {
        assertEquals("turn_on", HomeAssistant.serviceFor("light", "on"))
        assertEquals("turn_off", HomeAssistant.serviceFor("climate", "off"))
        assertEquals("turn_on", HomeAssistant.serviceFor("scene", "on"))
        assertEquals("open_cover", HomeAssistant.serviceFor("cover", "on"))
        assertEquals("set_temperature", HomeAssistant.serviceFor("climate", "set"))
        assertNull(HomeAssistant.serviceFor("scene", "off"))
        assertNull(HomeAssistant.serviceFor("script", "toggle"))
    }

    @Test fun addressesMustBeSensible() {
        assertNull(HomeAssistant.checkUrl("http://192.168.1.50:8123"))
        assertNull(HomeAssistant.checkUrl("http://homeassistant.local:8123"))
        assertNull(HomeAssistant.checkUrl("https://example.ui.nabu.casa"))
        assertNotNull(HomeAssistant.checkUrl("http://example.com"))
        assertNotNull(HomeAssistant.checkUrl("http://127.0.0.1:5400"))
        assertNotNull(HomeAssistant.checkUrl("ftp://192.168.1.50"))
        assertNotNull(HomeAssistant.checkUrl("homeassistant"))
    }
}
