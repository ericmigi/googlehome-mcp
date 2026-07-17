package googlehome.mcp

import googlehome.mcp.foyer.Automation
import googlehome.mcp.foyer.Device
import googlehome.mcp.foyer.DeviceState
import googlehome.mcp.foyer.GoogleHomeFoyerClient
import googlehome.mcp.foyer.Home
import googlehome.mcp.foyer.Room
import googlehome.mcp.server.GoogleHomeMcpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A recording fake foyer client. Records every [setOnOff] call so tests can assert on control
 * targeting, and stubs the rest of the v2 control surface to satisfy the interface. Reads are backed
 * by the fixtures passed in.
 */
private class FakeFoyerClient(
    private val homes: List<Home> = emptyList(),
    private val devices: List<Device> = emptyList(),
    private val states: List<DeviceState> = emptyList(),
) : GoogleHomeFoyerClient {
    val setOnOffCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun getHomeGraph(): Pair<List<Home>, List<Device>> = homes to devices
    override suspend fun listRooms(): List<Room> = emptyList()
    override suspend fun getTraits(deviceIds: List<String>): List<DeviceState> =
        states.filter { it.id in deviceIds }

    override suspend fun setOnOff(deviceId: String, agentId: String, partnerDeviceId: String, on: Boolean): DeviceState {
        setOnOffCalls += deviceId to on
        return DeviceState(id = deviceId, online = true, onOff = on)
    }

    // v2 control surface — not exercised by these (legacy) server tests; stubbed to satisfy the interface.
    override suspend fun setBrightness(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int) =
        DeviceState(id = deviceId, online = true, brightnessPct = pct)
    override suspend fun setColorTemperature(deviceId: String, agentId: String, partnerDeviceId: String, kelvin: Int) =
        DeviceState(id = deviceId, online = true, colorTemperatureK = kelvin)
    override suspend fun setVolume(deviceId: String, agentId: String, partnerDeviceId: String, pct: Int) =
        DeviceState(id = deviceId, online = true, volumePct = pct)
    override suspend fun setMuted(deviceId: String, agentId: String, partnerDeviceId: String, muted: Boolean) =
        DeviceState(id = deviceId, online = true, muted = muted)
    override suspend fun setLocked(deviceId: String, agentId: String, partnerDeviceId: String, locked: Boolean, pin: String?) =
        DeviceState(id = deviceId, online = true, locked = locked)
    override suspend fun setThermostat(deviceId: String, agentId: String, partnerDeviceId: String, setpointC: Double?, mode: String?) =
        DeviceState(id = deviceId, online = true, setpointC = setpointC, thermostatMode = mode)
    override suspend fun mediaCommand(deviceId: String, agentId: String, partnerDeviceId: String, command: String) =
        DeviceState(id = deviceId, online = true)
    override suspend fun listAutomations(): List<Automation> = emptyList()
    override suspend fun runAutomation(automation: Automation): Boolean = true
}

class GoogleHomeMcpServerTest {

    private val deviceId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun exposes_the_read_only_tools() {
        val server = GoogleHomeMcpServer(FakeFoyerClient())
        val names = server.tools.map { it.name }.toSet()
        // Read surface is stable across v1/v2 (docs/CONTRACTS2.md); control tools are TOOLS-owned.
        assertTrue(names.containsAll(listOf("list_homes", "list_devices", "get_device_state")), "got $names")
    }

    @Test
    fun no_tool_creates_or_deletes_anything() {
        // The core v2 safety invariant: control is general, but nothing may add or remove a device.
        // No tool name may imply creation/deletion.
        val server = GoogleHomeMcpServer(FakeFoyerClient())
        val banned = listOf("create", "add", "delete", "remove", "new", "register", "unlink")
        for (name in server.tools.map { it.name }) {
            for (word in banned) {
                assertTrue(
                    !name.contains(word, ignoreCase = true),
                    "no tool may create/delete; offending tool '$name' contains '$word'",
                )
            }
        }
    }

    @Test
    fun list_homes_returns_home_ids_and_names() = runTest {
        val fake = FakeFoyerClient(homes = listOf(Home(id = "h1", name = "Home")))
        val server = GoogleHomeMcpServer(fake)
        val arr = server.call("list_homes", JsonObject(emptyMap())).jsonArray
        assertEquals(1, arr.size)
        assertEquals("h1", arr[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Home", arr[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun list_devices_merges_live_state() = runTest {
        val device = Device(
            id = deviceId,
            name = "Outside Lights",
            type = "action.devices.types.LIGHT",
            traits = listOf("action.devices.traits.OnOff"),
            roomName = "Outside",
            agentId = "acme-partner",
            partnerDeviceId = "AA11BB22CC33DD44EE55",
        )
        val fake = FakeFoyerClient(
            devices = listOf(device),
            states = listOf(DeviceState(id = deviceId, online = true, onOff = true)),
        )
        val server = GoogleHomeMcpServer(fake)
        val row = server.call("list_devices", JsonObject(emptyMap())).jsonArray.single().jsonObject
        assertEquals(deviceId, row["id"]!!.jsonPrimitive.content)
        assertEquals("Outside", row["room_name"]!!.jsonPrimitive.content)
        assertEquals(true, row["online"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, row["on_off"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun get_device_state_reads_requested_ids() = runTest {
        val fake = FakeFoyerClient(
            states = listOf(
                DeviceState(id = "a", online = true, onOff = false),
                DeviceState(id = "b", online = false, onOff = null),
            ),
        )
        val server = GoogleHomeMcpServer(fake)
        val args = buildJsonObject {
            put("device_ids", buildJsonArray { add(JsonPrimitive("a")) })
        }
        val arr = server.call("get_device_state", args).jsonArray
        assertEquals(1, arr.size)
        assertEquals("a", arr[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknown_tool_name_fails() = runTest {
        val server = GoogleHomeMcpServer(FakeFoyerClient())
        assertFailsWith<IllegalArgumentException> {
            server.call("set_device_onoff", buildJsonObject { put("on", JsonPrimitive(true)) })
        }
    }
}
