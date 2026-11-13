package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder

/**
 * OPTIONAL Home Assistant hook for Ask. OFF unless the household types the address of its own Home Assistant and
 * a long-lived access token on the setup page and switches it on.
 *
 * What Ask may do is deliberately small, the same limit as the TV home's smart home card (AGENTS.md, 2.9):
 * lights, switches, fans, scenes, scripts, air conditioning and blinds. Locks, alarms and garage or gate doors
 * are never reachable from here, however the model asks. The token goes only to the address it was saved for,
 * is never sent back to the setup page, and redirects are not followed.
 */
object HomeAssistant {
    /** The kinds of things Ask may switch. Anything else (lock, alarm_control_panel, ...) is refused. */
    val DOMAINS = setOf("light", "switch", "fan", "scene", "script", "climate", "cover", "input_boolean")

    /** A blind is fine; a garage door, a gate or a door is not. */
    private val NEVER_CLASSES = setOf("garage", "gate", "door")

    fun allowed(entityId: String, deviceClass: String?): Boolean {
        val domain = entityId.substringBefore('.', "")
        if (domain !in DOMAINS) return false
        if (!Regex("[a-z_]+\\.[a-z0-9_]+").matches(entityId)) return false
        return domain != "cover" || deviceClass?.lowercase() !in NEVER_CLASSES
    }

    /** The Home Assistant service for what was asked, or null when that kind of thing cannot do it. */
    fun serviceFor(domain: String, action: String): String? = when (domain to action) {
        "light" to "on", "switch" to "on", "fan" to "on", "input_boolean" to "on", "climate" to "on", "scene" to "on", "script" to "on" -> "turn_on"
        "light" to "off", "switch" to "off", "fan" to "off", "input_boolean" to "off", "climate" to "off" -> "turn_off"
        "light" to "toggle", "switch" to "toggle", "fan" to "toggle", "input_boolean" to "toggle" -> "toggle"
        "cover" to "on" -> "open_cover"
        "cover" to "off" -> "close_cover"
        "light" to "set" -> "turn_on"
        "climate" to "set" -> "set_temperature"
        "cover" to "set" -> "set_cover_position"
        else -> null
    }

    /** Null when the address may be saved, else the sentence for the person. */
    fun checkUrl(url: String): String? {
        val u = try { java.net.URI(url.trim()) } catch (e: Exception) { return "That is not a web address." }
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "Start the address with http:// or https://"
        val host = u.host ?: return "That is not a web address."
        if (AskWire.isThisDevice(host)) return "That is the TV itself. Use the address of your Home Assistant."
        if (scheme == "http" && !AskWire.isLocalUrl(url)) return "A plain http address must be on your home network. For anything outside, use https."
        return null
    }

    private fun base() = Store.str("ha_url").orEmpty().trimEnd('/')
    private fun token() = Store.str("ha_token")?.takeIf { it.isNotEmpty() && Store.str("ha_token_origin") == origin(base()) }

    private fun origin(url: String): String {
        val u = try { java.net.URI(url) } catch (e: Exception) { return "?" }
        val s = u.scheme?.lowercase() ?: "?"
        return "$s://${u.host?.lowercase() ?: "?"}:${if (u.port != -1) u.port else if (s == "https") 443 else 80}"
    }

    val ready get() = Store.bool("ha_enabled") && base().isNotEmpty() && token() != null

    /** For the setup page. The token itself never leaves the TV. */
    fun status(): JSONObject = JSONObject().put("enabled", Store.bool("ha_enabled")).put("url", base())
        .put("has_token", token() != null).put("ready", ready)

    fun save(b: JSONObject) {
        if (b.has("ha_url")) {
            val url = b.optString("ha_url").trim().trimEnd('/')
            if (url.isNotEmpty()) checkUrl(url)?.let { throw ApiError(400, it) }
            val moved = origin(url) != origin(base())
            Store.putStr("ha_url", url.ifEmpty { null })
            if (moved && !b.has("ha_token")) { Store.putStr("ha_token", null); Store.putStr("ha_token_origin", null) }
        }
        if (b.has("ha_token")) {
            val typed = b.optString("ha_token").trim().ifEmpty { null }
            Store.putStr("ha_token_origin", typed?.let { origin(base()) })
            Store.putStr("ha_token", typed)
        }
        if (b.has("ha_enabled")) Store.putBool("ha_enabled", b.optBoolean("ha_enabled"))
    }

    /** What Ask can see: id, name and state of the things it may switch (at most 150). */
    fun list(): String {
        val arr = JSONArray(request("GET", "/api/states", null))
        val out = StringBuilder()
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("entity_id")
            val attrs = o.optJSONObject("attributes")
            if (!allowed(id, attrs?.optString("device_class"))) continue
            if (n++ >= 150) break
            out.append(id).append(" | ").append(attrs?.optString("friendly_name").orEmpty()).append(" | ").append(o.optString("state")).append('\n')
        }
        return out.toString().ifEmpty { "nothing switchable found" }
    }

    /** One action on one thing. [a] may carry brightness (0 to 100), temperature or position (0 to 100). */
    fun act(entityId: String, action: String, a: JSONObject): String {
        val domain = entityId.substringBefore('.', "")
        // look at the thing first: it must exist, and a garage door or gate is refused whatever it is called
        val state = try { JSONObject(request("GET", "/api/states/" + URLEncoder.encode(entityId, "UTF-8"), null)) } catch (e: ApiError) { throw ApiError(404, "$entityId was not found in Home Assistant.") }
        if (!allowed(entityId, state.optJSONObject("attributes")?.optString("device_class"))) throw ApiError(403, "Ask may not control $entityId.")
        val service = serviceFor(domain, action) ?: throw ApiError(400, "A $domain cannot do \"$action\".")
        val data = JSONObject().put("entity_id", entityId)
        when (domain to action) {
            "light" to "set" -> data.put("brightness_pct", a.optInt("value", a.optInt("brightness", 100)).coerceIn(0, 100))
            "climate" to "set" -> data.put("temperature", a.optDouble("value", a.optDouble("temperature", 22.0)).coerceIn(10.0, 32.0))
            "cover" to "set" -> data.put("position", a.optInt("value", a.optInt("position", 100)).coerceIn(0, 100))
        }
        request("POST", "/api/services/$domain/$service", data)
        return "done: $entityId $action"
    }

    private fun request(method: String, path: String, body: JSONObject?): String {
        val b = base(); val t = token() ?: throw ApiError(501, "Home Assistant is not set up.")
        val u = URL(b + path)
        // the same rule as the AI service: never the TV itself, whatever the address was spelled as
        val own = try { NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() } ?: emptyList() } catch (e: Exception) { emptyList() }
        val resolved = try { InetAddress.getAllByName(u.host).toList() } catch (e: Exception) { throw ApiError(502, "Could not find ${u.host}.") }
        if (AskWire.pointsAtThisDevice(resolved, own)) throw ApiError(502, "${u.host} is the TV itself.")
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 6_000; c.readTimeout = 15_000
        c.requestMethod = method; c.instanceFollowRedirects = false
        c.setRequestProperty("Authorization", "Bearer $t")
        c.setRequestProperty("Content-Type", "application/json")
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else (c.errorStream ?: "".byteInputStream())).bufferedReader().readText()
            if (code == 401) throw ApiError(502, "Home Assistant did not accept the token.")
            if (code == 404) throw ApiError(404, "Not found in Home Assistant.")
            if (code !in 200..299) throw ApiError(502, "Home Assistant answered $code.")
            return text
        } catch (e: IOException) {
            throw ApiError(502, "Could not reach Home Assistant: ${e.message ?: e.javaClass.simpleName}")
        } finally { c.disconnect() }
    }
}
