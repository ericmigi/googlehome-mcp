package googlehome.mcp.foyer

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Unit tests for [FoyerCodec], driven off the *real* captured payloads under `captures/`
 * (GetHomeGraph embedded via [GET_HOME_GRAPH_SAMPLE]; the small GetTraits/UpdateTraits bodies
 * inlined below verbatim from captures/UpdateTraits.sample.json).
 */
class FoyerCodecTest {

    private val json = Json

    private companion object {
        const val OUTSIDE_LIGHTS = "22222222-2222-4222-8222-222222222222"

        // Verbatim from captures/UpdateTraits.sample.json ("request": turning Outside Lights OFF).
        const val CAPTURED_UPDATE_OFF_REQUEST =
            """[[[["22222222-2222-4222-8222-222222222222",["acme-partner","AA11BB22CC33DD44EE55"]],[["onOff",[["onOff",[null,null,null,0]]]]]]]]"""

        // Verbatim from captures/UpdateTraits.sample.json ("GetTraits_readback": onOff back ON).
        const val CAPTURED_GET_TRAITS_READBACK =
            """[[[["22222222-2222-4222-8222-222222222222"],[["deviceStatus",[["online",[null,null,null,1]],["onlineStateDetails",[null,null,"stateOnline"]]]],["onOff",[["onOff",[null,null,null,1]]],[[["commandOnlyOnOff",[null,null,null,0]]]]]]]]]"""

        // Verbatim from captures/UpdateTraits.sample.json ("UpdateTraits.response": echo after OFF).
        const val CAPTURED_UPDATE_OFF_RESPONSE =
            """[[[["22222222-2222-4222-8222-222222222222"],[["partnerDeviceId",[["partnerDeviceId",[null,null,"AA11BB22CC33DD44EE55"]]]],["deviceStatus",[["online",[null,null,null,1]],["onlineStateDetails",[null,null,"stateOnline"]]]],["onOff",[["onOff",[null,null,null,0]]],[[["commandOnlyOnOff",[null,null,null,0]]]]]]]]]"""
    }

    // --------------------------------------------------------------------------------------------
    // parseGetHomeGraph against the real 102 KB sample
    // --------------------------------------------------------------------------------------------

    @Test
    fun parsesHomeGraphAndFindsOutsideLightsDevice() {
        val graph = FoyerCodec.parseGetHomeGraph(json.parseToJsonElement(GET_HOME_GRAPH_SAMPLE))

        // Four structures were enumerated in this account.
        assertEquals(4, graph.homes.size, "home count")
        assertEquals("Home", graph.homes.first().name)
        assertEquals("11111111-1111-4111-8111-111111111111", graph.homes.first().id)

        // The synthetic control-target device in the fixture.
        val outside = graph.devices.firstOrNull { it.id == OUTSIDE_LIGHTS }
        assertNotNull(outside, "target device must be present")

        assertEquals("Patio Light", outside.name, "device name")
        assertEquals("Outside", outside.roomName, "room membership")
        assertEquals("acme-partner", outside.agentId, "agent id")
        assertEquals("AA11BB22CC33DD44EE55", outside.partnerDeviceId, "partner device id")
        assertEquals("action.devices.types.OUTLET", outside.type, "hardware device type")
        // 22222222 is an OUTLET the user assigned to LIGHT in the Home app, so its EFFECTIVE type is
        // LIGHT — this is how "turn off all the lights" reaches switch/outlet-driven lights.
        assertEquals("action.devices.types.LIGHT", outside.assignedType, "user-assigned type (field 20)")
        assertEquals("action.devices.types.LIGHT", outside.effectiveType, "effective type")
        assertTrue(
            outside.traits.contains("action.devices.traits.OnOff"),
            "must expose OnOff trait, got ${outside.traits}",
        )
    }

    @Test
    fun parsesRoomsIncludingOutside() {
        val graph = FoyerCodec.parseGetHomeGraph(json.parseToJsonElement(GET_HOME_GRAPH_SAMPLE))
        val outsideRoom = graph.rooms.firstOrNull { it.name == "Outside" }
        assertNotNull(outsideRoom, "Outside room must be parsed")
        assertTrue(
            outsideRoom.deviceIds.contains(OUTSIDE_LIGHTS),
            "Outside room must contain device 22222222, got ${outsideRoom.deviceIds}",
        )
    }

    // --------------------------------------------------------------------------------------------
    // buildUpdateOnOff must reproduce the captured request byte-structure exactly
    // --------------------------------------------------------------------------------------------

    @Test
    fun buildUpdateOnOffMatchesCapturedRequestExactly() {
        val built = FoyerCodec.buildUpdateOnOff(
            deviceId = OUTSIDE_LIGHTS,
            agentId = "acme-partner",
            partnerDeviceId = "AA11BB22CC33DD44EE55",
            on = false, // OFF
        )
        val expected = json.parseToJsonElement(CAPTURED_UPDATE_OFF_REQUEST)
        assertEquals(expected, built, "UpdateTraits OFF request must equal the captured array")
        // Also assert exact serialized form is identical.
        assertEquals(CAPTURED_UPDATE_OFF_REQUEST, built.toString(), "serialized form")
    }

    @Test
    fun buildUpdateOnOffOnUsesValueOne() {
        val built = FoyerCodec.buildUpdateOnOff(
            deviceId = OUTSIDE_LIGHTS,
            agentId = "acme-partner",
            partnerDeviceId = "AA11BB22CC33DD44EE55",
            on = true, // ON
        )
        val expectedOn =
            """[[[["22222222-2222-4222-8222-222222222222",["acme-partner","AA11BB22CC33DD44EE55"]],[["onOff",[["onOff",[null,null,null,1]]]]]]]]"""
        assertEquals(expectedOn, built.toString())
    }

    // --------------------------------------------------------------------------------------------
    // parseGetTraits reads onOff + online
    // --------------------------------------------------------------------------------------------

    @Test
    fun parseGetTraitsReadsOnOffOn() {
        val states = FoyerCodec.parseGetTraits(json.parseToJsonElement(CAPTURED_GET_TRAITS_READBACK))
        assertEquals(1, states.size)
        val s = states.single()
        assertEquals(OUTSIDE_LIGHTS, s.id)
        assertTrue(s.online, "device reported online")
        assertEquals(true, s.onOff, "onOff must read back as ON (1)")
    }

    @Test
    fun parseGetTraitsReadsOnOffOffFromUpdateEcho() {
        // The UpdateTraits response echoes the same per-device shape GetTraits uses.
        val states = FoyerCodec.parseGetTraits(json.parseToJsonElement(CAPTURED_UPDATE_OFF_RESPONSE))
        val s = states.single()
        assertEquals(OUTSIDE_LIGHTS, s.id)
        assertTrue(s.online)
        assertEquals(false, s.onOff, "onOff must read back as OFF (0)")
    }

    @Test
    fun buildGetTraitsRequestShape() {
        val body = FoyerCodec.buildGetTraitsRequest(listOf(OUTSIDE_LIGHTS))
        assertEquals("""[[["22222222-2222-4222-8222-222222222222"]]]""", body.toString())
    }

    // --------------------------------------------------------------------------------------------
    // v2 typed builders must match the payloads in docs/TRAITS.md exactly (value at the
    // type-specific wrapper index: int/float->idx1, string->idx2, bool->idx3).
    // --------------------------------------------------------------------------------------------

    private val AGENT = "acme-partner"
    private val PARTNER = "AA11BB22CC33DD44EE55"

    // Common prefix of every UpdateTraits command up to the trait block.
    private val P =
        """[[[["22222222-2222-4222-8222-222222222222",["acme-partner","AA11BB22CC33DD44EE55"]],"""

    private fun agent() = AGENT
    private fun partner() = PARTNER

    @Test
    fun buildOnOffMatchesTraitsDoc() {
        val on = FoyerCodec.buildOnOff(OUTSIDE_LIGHTS, agent(), partner(), true)
        assertEquals("""$P[["onOff",[["onOff",[null,null,null,1]]]]]]]]""", on.toString())
        val off = FoyerCodec.buildOnOff(OUTSIDE_LIGHTS, agent(), partner(), false)
        assertEquals("""$P[["onOff",[["onOff",[null,null,null,0]]]]]]]]""", off.toString())
    }

    @Test
    fun buildBrightnessMatchesTraitsDoc() {
        val b = FoyerCodec.buildBrightness(OUTSIDE_LIGHTS, agent(), partner(), 42)
        // Brightness: value (int) sits at wrapper index 1 -> [null,42].
        assertEquals("""$P[["brightness",[["brightness",[null,42]]]]]]]]""", b.toString())
    }

    @Test
    fun buildColorTemperatureMatchesTraitsDoc() {
        val c = FoyerCodec.buildColorTemperature(OUTSIDE_LIGHTS, agent(), partner(), 4000)
        // color.colorTemperature: int at index 1 -> [null,4000].
        assertEquals("""$P[["color",[["colorTemperature",[null,4000]]]]]]]]""", c.toString())
    }

    @Test
    fun buildVolumeMatchesTraitsDoc() {
        val v = FoyerCodec.buildVolume(OUTSIDE_LIGHTS, agent(), partner(), 55)
        assertEquals("""$P[["volume",[["currentVolume",[null,55]]]]]]]]""", v.toString())
    }

    @Test
    fun buildMutedMatchesTraitsDoc() {
        val m = FoyerCodec.buildMuted(OUTSIDE_LIGHTS, agent(), partner(), true)
        // Bool at index 3 -> [null,null,null,1].
        assertEquals("""$P[["volume",[["isMuted",[null,null,null,1]]]]]]]]""", m.toString())
    }

    @Test
    fun buildLockMatchesTraitsDoc() {
        val locked = FoyerCodec.buildLock(OUTSIDE_LIGHTS, agent(), partner(), true)
        assertEquals("""$P[["lockUnlock",[["isLocked",[null,null,null,1]]]]]]]]""", locked.toString())
        val unlocked = FoyerCodec.buildLock(OUTSIDE_LIGHTS, agent(), partner(), false)
        assertEquals("""$P[["lockUnlock",[["isLocked",[null,null,null,0]]]]]]]]""", unlocked.toString())
    }

    @Test
    fun genericBuildUpdateEqualsTypedHelper() {
        // The generic builder + a bool wrapper must reproduce the onOff helper byte-for-byte.
        val wrapper = json.parseToJsonElement("""[null,null,null,1]""")
        val generic = FoyerCodec.buildUpdate(
            OUTSIDE_LIGHTS, agent(), partner(), "onOff", "onOff", wrapper as kotlinx.serialization.json.JsonArray,
        )
        assertEquals(FoyerCodec.buildOnOff(OUTSIDE_LIGHTS, agent(), partner(), true).toString(), generic.toString())
    }

    // --------------------------------------------------------------------------------------------
    // Best-effort builders — payload shapes need live confirmation (docs/TRAITS.md). Wired but the
    // exact bytes are unverified, so these assertions are @Ignore'd pending a real web-app capture.
    // --------------------------------------------------------------------------------------------

    @Test
    fun buildThermostatSetpointMatchesCapturedNestPayload() {
        // Live-verified 2026-07-16: field is `thermostatTemperatureSetpoint` in the device DISPLAY
        // unit (here °F, a whole number), with `mode` sent alongside. Captured from the web app.
        val f = FoyerCodec.buildThermostatSetpoint(OUTSIDE_LIGHTS, agent(), partner(), 66.0, "heat")
        assertEquals(
            """$P[["temperatureSetting",[["mode",[null,null,"heat"]],["thermostatTemperatureSetpoint",[null,66]]]]]]]]""",
            f.toString(),
        )
        // Non-whole (°C) setpoints keep their decimal.
        val c = FoyerCodec.buildThermostatSetpoint(OUTSIDE_LIGHTS, agent(), partner(), 19.5, "eco")
        assertEquals(
            """$P[["temperatureSetting",[["mode",[null,null,"eco"]],["thermostatTemperatureSetpoint",[null,19.5]]]]]]]]""",
            c.toString(),
        )
    }

    @Test
    fun automationRequestsAndParse() {
        assertEquals("""["S"]""", FoyerCodec.buildListAutomationsRequest("S").toString())
        // Live-verified ExecuteAutomation body shape.
        assertEquals("""["S","A",null,2]""", FoyerCodec.buildExecuteAutomationRequest("S", "A").toString())

        val resp =
            """[[""" +
                """["id1",null,1,"Runnable","1 starter","2 actions","edit",null,2,"icon","BLOB1","0 errors"],""" +
                """["id2",null,3,"AutoOnly","1 starter","1 action","edit",null,2,"icon","BLOB2","0 errors"]""" +
                """],[],[]]"""
        val autos = FoyerCodec.parseListAutomations(json.parseToJsonElement(resp))
        assertEquals(2, autos.size)
        assertEquals("Runnable", autos[0].name)
        assertTrue(autos[0].manuallyRunnable, "flag 1 -> manually runnable")
        assertEquals("2 actions", autos[0].actions)
        assertEquals("BLOB1", autos[0].triggerInput)
        assertTrue(!autos[1].manuallyRunnable, "flag 3 -> not manually runnable")
    }

    @Test
    fun buildMediaCommandMatchesCapturedPlaybackState() {
        // Live-verified 2026-07-16: play/pause drive mediaState.playbackState (playing/paused),
        // NOT the transportControl trait. Captured from the web app (hub device).
        assertEquals(
            """$P[["mediaState",[["playbackState",[null,null,"paused"]]]]]]]]""",
            FoyerCodec.buildMediaCommand(OUTSIDE_LIGHTS, agent(), partner(), "pause").toString(),
        )
        assertEquals(
            """$P[["mediaState",[["playbackState",[null,null,"playing"]]]]]]]]""",
            FoyerCodec.buildMediaCommand(OUTSIDE_LIGHTS, agent(), partner(), "play").toString(),
        )
        assertFailsWith<IllegalArgumentException> {
            FoyerCodec.buildMediaCommand(OUTSIDE_LIGHTS, agent(), partner(), "next")
        }
    }

    @Test
    fun buildLockWithPinMatchesCapturedUnlock() {
        // Live-verified 2026-07-16: PIN rides as a `pin` string field on lockUnlock (placeholder here).
        val u = FoyerCodec.buildLockWithPin(OUTSIDE_LIGHTS, agent(), partner(), false, "1234")
        assertEquals(
            """$P[["lockUnlock",[["isLocked",[null,null,null,0]],["pin",[null,null,"1234"]]]]]]]]""",
            u.toString(),
        )
    }

    // --------------------------------------------------------------------------------------------
    // parseGetTraits reads the richer DeviceState; parseChallenge detects a pinNeeded challenge.
    // --------------------------------------------------------------------------------------------

    @Test
    fun parseGetTraitsReadsRichState() {
        val payload =
            """[[[["dev"],[""" +
                """["deviceStatus",[["online",[null,null,null,1]]]],""" +
                """["onOff",[["onOff",[null,null,null,1]]]],""" +
                """["brightness",[["brightness",[null,42]]]],""" +
                """["color",[["colorTemperature",[null,4000]]]],""" +
                """["volume",[["currentVolume",[null,55]],["isMuted",[null,null,null,1]]]],""" +
                """["lockUnlock",[["isLocked",[null,null,null,1]],["isJammed",[null,null,null,0]]]],""" +
                """["temperatureSetting",[["mode",[null,null,"heat"]],""" +
                """["thermostatTemperatureSetpointC",[null,19.5]],["ambientAirTemperatureC",[null,21.0]]]]""" +
                """]]]]"""
        val s = FoyerCodec.parseGetTraits(json.parseToJsonElement(payload)).single()
        assertEquals("dev", s.id)
        assertTrue(s.online)
        assertEquals(true, s.onOff)
        assertEquals(42, s.brightnessPct)
        assertEquals(4000, s.colorTemperatureK)
        assertEquals(55, s.volumePct)
        assertEquals(true, s.muted)
        assertEquals(true, s.locked)
        assertEquals(false, s.jammed)
        assertEquals("heat", s.thermostatMode)
        assertEquals(19.5, s.setpointC)
        assertEquals(21.0, s.ambientC)
    }

    @Test
    fun parseGetTraitsLeavesAbsentTraitsNull() {
        // Only onOff present: every other field must stay null (absent), not default to false/0.
        val s = FoyerCodec.parseGetTraits(json.parseToJsonElement(CAPTURED_GET_TRAITS_READBACK)).single()
        assertEquals(true, s.onOff)
        assertNull(s.brightnessPct)
        assertNull(s.muted)
        assertNull(s.locked)
        assertNull(s.setpointC)
    }

    @Test
    fun parseChallengeDetectsPinNeeded() {
        val payload =
            """[[[["dev"],[["deviceStatus",[["challenge",[null,null,"pinNeeded"]]]]]]]]"""
        assertEquals("pinNeeded", FoyerCodec.parseChallenge(json.parseToJsonElement(payload)))
        // A normal readback carries no challenge.
        assertNull(FoyerCodec.parseChallenge(json.parseToJsonElement(CAPTURED_GET_TRAITS_READBACK)))
    }

    @Test
    fun deriveCapabilitiesMapsTraits() {
        val caps = FoyerCodec.deriveCapabilities(
            listOf(
                "action.devices.traits.OnOff",
                "action.devices.traits.Brightness",
                "action.devices.traits.ColorSetting",
                "action.devices.traits.Volume",
                "action.devices.traits.LockUnlock",
                "action.devices.traits.TemperatureSetting",
                "action.devices.traits.TransportControl",
            ),
        )
        assertEquals(
            setOf(
                Capability.ON_OFF, Capability.BRIGHTNESS, Capability.COLOR_TEMPERATURE, Capability.COLOR,
                Capability.VOLUME, Capability.LOCK, Capability.THERMOSTAT, Capability.MEDIA_TRANSPORT,
            ),
            caps,
        )
    }
}
