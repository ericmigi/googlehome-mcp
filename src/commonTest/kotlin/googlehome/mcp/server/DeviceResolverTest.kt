package googlehome.mcp.server

import googlehome.mcp.foyer.Automation
import googlehome.mcp.foyer.Device
import googlehome.mcp.foyer.DeviceState
import googlehome.mcp.foyer.GoogleHomeFoyerClient
import googlehome.mcp.foyer.Home
import googlehome.mcp.foyer.Room
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Tests for selector-based device resolution (docs/CONTRACTS2.md §"Selector / resolution").
 *
 * Codes to the v2 resolver API:
 * ```kotlin
 * data class Selector(val name: String? = null, val room: String? = null, val type: String? = null)
 * class DeviceResolver(foyer: GoogleHomeFoyerClient) { suspend fun resolve(sel: Selector): List<Device> }
 * ```
 * Resolution is case-insensitive + trimmed; `name` is a fuzzy contains, `type` matches the
 * `action.devices.types.*` suffix, and multiple selector fields are AND-ed. Group operations
 * ("all lights") fall out of a bare `type` selector. An empty match set is a helpful *error*
 * (it does not return an empty list).
 *
 * NOTE: `DeviceResolver`/`Selector` live in main sources owned by the TOOLS agent; if they are not
 * present yet these tests will not compile until that lands (expected per the task brief).
 */
class DeviceResolverTest {

    // A foyer client whose home graph is a fixed device fixture. Control methods must never be
    // touched by resolution, so they throw if called.
    private class FakeGraphClient(private val devices: List<Device>) : GoogleHomeFoyerClient {
        override suspend fun getHomeGraph(): Pair<List<Home>, List<Device>> = emptyList<Home>() to devices
        override suspend fun listRooms(): List<Room> = emptyList()
        override suspend fun getTraits(deviceIds: List<String>): List<DeviceState> = emptyList()

        private fun nope(): Nothing = throw AssertionError("resolution must not issue control calls")
        override suspend fun setOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean) = nope()
        override suspend fun setBrightness(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int) = nope()
        override suspend fun setColorTemperature(deviceId: String, agentId: String, partnerDeviceId: String, kelvin: Int) = nope()
        override suspend fun setVolume(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int) = nope()
        override suspend fun setMuted(deviceId: String, agentId: String, partnerDeviceId: String, muted: Boolean) = nope()
        override suspend fun setLocked(deviceId: String, agentId: String, partnerDeviceId: String, locked: Boolean, pin: String?) = nope()
        override suspend fun setThermostat(deviceId: String, agentId: String, partnerDeviceId: String, setpointC: Double?, mode: String?) = nope()
        override suspend fun mediaCommand(deviceId: String, agentId: String, partnerDeviceId: String, command: String) = nope()
        override suspend fun listAutomations(): List<Automation> = emptyList()
        override suspend fun runAutomation(automation: Automation): Boolean = nope()
    }

    private fun dev(id: String, name: String, type: String, room: String, assigned: String? = null) = Device(
        id = id,
        name = name,
        type = "action.devices.types.$type",
        assignedType = assigned?.let { "action.devices.types.$it" },
        traits = emptyList(),
        roomName = room,
        agentId = "ag",
        partnerDeviceId = "p-$id",
    )

    // Living Room: two lights. Kitchen: a light + a speaker. Bedroom: a speaker. Entryway: a lock.
    private val ceiling = dev("d1", "Ceiling Light", "LIGHT", "Living Room")
    private val lamp = dev("d2", "Floor Lamp", "LIGHT", "Living Room")
    private val kitchenLight = dev("d3", "Kitchen Light", "LIGHT", "Kitchen")
    private val kitchenSpeaker = dev("d4", "Kitchen Speaker", "SPEAKER", "Kitchen")
    private val bedroomSpeaker = dev("d5", "Guest Speaker", "SPEAKER", "Guest Room")
    private val frontDoor = dev("d6", "Front Door", "LOCK", "Entryway")

    private val allDevices = listOf(ceiling, lamp, kitchenLight, kitchenSpeaker, bedroomSpeaker, frontDoor)
    private fun resolver() = DeviceResolver(FakeGraphClient(allDevices))

    private suspend fun ids(sel: Selector): Set<String> = resolver().resolve(sel).map { it.id }.toSet()

    // --- name selection ---------------------------------------------------------------------------

    @Test
    fun resolvesByExactName() = runTest {
        assertEquals(setOf("d6"), ids(Selector(name = "Front Door")))
    }

    @Test
    fun resolvesByFuzzyNameContains() = runTest {
        // "speaker" is a contains-match against both speakers, case-insensitively.
        assertEquals(setOf("d4", "d5"), ids(Selector(name = "speaker")))
    }

    // --- room selection ---------------------------------------------------------------------------

    @Test
    fun resolvesByRoom() = runTest {
        assertEquals(setOf("d3", "d4"), ids(Selector(room = "Kitchen")))
    }

    // --- type selection (group ops) ---------------------------------------------------------------

    @Test
    fun resolvesByTypeReturnsAllOfThatType() = runTest {
        // "all lights" = a bare type selector -> every LIGHT across every room.
        assertEquals(setOf("d1", "d2", "d3"), ids(Selector(type = "light")))
    }

    @Test
    fun typeMatchesTheDevicesTypeSuffix() = runTest {
        assertEquals(setOf("d6"), ids(Selector(type = "lock")))
        assertEquals(setOf("d4", "d5"), ids(Selector(type = "speaker")))
    }

    // --- combined selectors (AND) -----------------------------------------------------------------

    @Test
    fun combinesRoomAndType() = runTest {
        // "living room lights" -> only the two Living Room LIGHTs, not the Kitchen lights.
        assertEquals(setOf("d1", "d2"), ids(Selector(room = "Living Room", type = "light")))
    }

    @Test
    fun combinesNameAndTypeAsAnd() = runTest {
        // name "kitchen" alone matches both Kitchen devices; adding type=speaker narrows to one.
        assertEquals(setOf("d4"), ids(Selector(name = "kitchen", type = "speaker")))
    }

    // --- normalization ----------------------------------------------------------------------------

    @Test
    fun matchingIsCaseInsensitiveAndTrimmed() = runTest {
        assertEquals(setOf("d3"), ids(Selector(room = "  kitchen  ", type = " LIGHT ")))
        assertEquals(setOf("d6"), ids(Selector(name = "  FRONT door ")))
    }

    // --- no match ---------------------------------------------------------------------------------

    @Test
    fun noMatchIsAHelpfulError() = runTest {
        // An empty match set must be surfaced as an error (not an empty list), per CONTRACTS2.
        val ex = assertFails { resolver().resolve(Selector(name = "Garage Door")) }
        assertTrue((ex.message ?: "").isNotBlank(), "no-match error should carry a helpful message: ${ex.message}")
    }

    @Test
    fun noMatchOnRoomPlusTypeThatNeverCoexist() = runTest {
        // Bedroom has no light; the AND of room+type is empty -> error.
        assertFails { resolver().resolve(Selector(room = "Bedroom", type = "light")) }
    }

    // --- over-match guards (wrong-device hazard) --------------------------------------------------

    @Test
    fun roomMatchesExactlyNotSubstring() = runTest {
        // "Bedroom" must NOT also select "Master Bedroom" / "Guest Bedroom" — else a room selector
        // silently controls the wrong rooms.
        val bedLight = dev("b1", "Bed Light", "LIGHT", "Bedroom")
        val masterLight = dev("b2", "Master Light", "LIGHT", "Master Bedroom")
        val guestLight = dev("b3", "Guest Light", "LIGHT", "Guest Bedroom")
        val r = DeviceResolver(FakeGraphClient(listOf(bedLight, masterLight, guestLight)))
        val got = r.resolve(Selector(room = "Bedroom")).map { it.id }.toSet()
        assertEquals(setOf("b1"), got, "room=Bedroom must resolve ONLY the exact Bedroom, not Master/Guest Bedroom")
    }

    @Test
    fun namePrefersExactMatchOverSubstringFanout() = runTest {
        // A device literally named "Light" must not drag in every "* Light" when queried by that
        // exact name; exact match wins.
        val plainLight = dev("n1", "Light", "LIGHT", "Hall")
        val ceilingLight = dev("n2", "Ceiling Light", "LIGHT", "Hall")
        val deskLight = dev("n3", "Desk Light", "LIGHT", "Office")
        val r = DeviceResolver(FakeGraphClient(listOf(plainLight, ceilingLight, deskLight)))
        assertEquals(setOf("n1"), r.resolve(Selector(name = "Light")).map { it.id }.toSet(),
            "exact name 'Light' must win over substring fan-out")
    }

    @Test
    fun typeMatchesUserAssignedTypeOverHardware() = runTest {
        // "turn off all the lights": a SWITCH and an OUTLET the user marked as Light must be found by
        // type="light", while an unassigned OUTLET (a plug running a server) must NOT.
        val kitchenSwitch = dev("k1", "Kitchen light", "SWITCH", "Kitchen", assigned = "LIGHT")
        val outletLamp = dev("k2", "Top Lights", "OUTLET", "Hall", assigned = "LIGHT")
        val nativeLight = dev("k3", "Bulb", "LIGHT", "Hall")
        val serverPlug = dev("k4", "Server Plug", "OUTLET", "Office")   // no assignment -> not a light
        val fan = dev("k5", "Fan", "OUTLET", "Office", assigned = "FAN")
        val r = DeviceResolver(FakeGraphClient(listOf(kitchenSwitch, outletLamp, nativeLight, serverPlug, fan)))
        assertEquals(
            setOf("k1", "k2", "k3"),
            r.resolve(Selector(type = "light")).map { it.id }.toSet(),
            "type=light must include user-assigned lights (switch/outlet) but exclude plain outlets/fans",
        )
    }

    @Test
    fun nameFallsBackToSubstringWhenNoExactMatch() = runTest {
        // "lamp" has no exact device, so substring finds "Corner Lamp".
        val corner = dev("c1", "Corner Lamp", "LIGHT", "Den")
        val r = DeviceResolver(FakeGraphClient(listOf(corner, ceiling)))
        assertEquals(setOf("c1"), r.resolve(Selector(name = "lamp")).map { it.id }.toSet())
    }
}
