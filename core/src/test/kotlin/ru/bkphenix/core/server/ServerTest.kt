package ru.bkphenix.core.server

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.bkphenix.core.geo.GeoPoint
import ru.bkphenix.core.track.TrackPoint
import ru.bkphenix.core.track.TrackPrivacy
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class ServerTest {
    private val sha = "a".repeat(64)

    @Test
    fun `разбор JSON`() {
        val v = MiniJson.parse("""{"a":[1,2.5,-3e2],"b":"x\"yA","c":true,"d":null,"e":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"yA", v["b"])
        assertEquals(true, v["c"])
        assertTrue(v.containsKey("d") && v["d"] == null)
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("{\"a\":1,}") }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("[1] x") }
        assertEquals("\"a\\\"b\\n\"", MiniJson.quote("a\"b\n"))
    }

    @Test
    fun `адрес сервера только https`() {
        assertEquals("https://example.org/nc", ServerFormats.normalizeServerUrl("example.org/nc/"))
        assertEquals("https://example.org/nc", ServerFormats.normalizeServerUrl(" https://example.org/nc "))
        assertNull(ServerFormats.normalizeServerUrl("http://example.org/nc"))
        assertNull(ServerFormats.normalizeServerUrl("https://user:pw@example.org"))
        assertNull(ServerFormats.normalizeServerUrl("https://example.org/nc?x=1"))
    }

    @Test
    fun `манифест ресурсов проверяет имена размеры и хэши`() {
        val m = ServerFormats.parseResourceManifest(
            """{"format":1,"generated":"2026-11-14","files":[{"kind":"map","name":"m.pmtiles","version":"v1","size":10,"sha256":"${sha.uppercase()}"}]}""",
        )
        assertEquals(1, m.files.size)
        assertEquals(sha, m.entry("map")!!.sha256)
        assertNull(m.entry("roads"))
        assertThrows(IllegalArgumentException::class.java) {
            ServerFormats.parseResourceManifest("""{"files":[{"kind":"map","name":"../x","size":10,"sha256":"$sha"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerFormats.parseResourceManifest("""{"files":[{"kind":"map","name":"x","size":10,"sha256":"abc"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerFormats.parseResourceManifest("""{"format":2,"files":[]}""")
        }
    }

    @Test
    fun `манифест обновления`() {
        val u = ServerFormats.parseUpdateManifest("""{"versionCode":16,"versionName":"0.8.2","apk":"a.apk","size":5,"sha256":"$sha","notes":"n"}""")
        assertEquals(16, u.versionCode)
        assertThrows(IllegalArgumentException::class.java) {
            ServerFormats.parseUpdateManifest("""{"versionCode":16,"apk":"a.exe","size":5,"sha256":"$sha"}""")
        }
    }

    @Test
    fun `метка устройства путь выгрузки и отпечаток`() {
        assertEquals("DOOGEE-V-Max-ab12", ServerFormats.deviceLabel("DOOGEE V Max", "AB12"))
        assertEquals("device", ServerFormats.deviceLabel("  ", ""))
        assertEquals("upload/dev-1_phenix.jsonl", ServerFormats.uploadPath("dev-1", "phenix.jsonl"))
        assertThrows(IllegalArgumentException::class.java) { ServerFormats.uploadPath("d", "../x") }
        assertEquals("F7F1B3", ServerFormats.normalizeFingerprint("f7:f1:b3"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ServerFormats.sha256Hex(ByteArray(0)))
    }

    @Test
    fun `учётные данные не попадают в toString`() {
        val c = ServerCredentials("https://s", "u", "secret-pass")
        assertFalse(c.toString().contains("secret-pass"))
        assertFalse(LoginFlowStart("https://s/l", "https://s/p", "tok-123").toString().contains("tok-123"))
    }

    @Test
    fun `обрезка начала и конца трека`() {
        val start = GeoPoint(55.70, 37.50)
        val pts = (0..40).map { TrackPoint(it * 1000L, start.offset(it * 50.0, 90.0), 5.0) }
        val trimmed = TrackPrivacy.trimEnds(pts, 300.0)
        assertTrue(trimmed.isNotEmpty())
        assertTrue(trimmed.all { it.point.distanceTo(pts.first().point) > 300.0 && it.point.distanceTo(pts.last().point) > 300.0 })
        assertTrue(trimmed.size in 25..29, "осталось ${trimmed.size}")
    }

    // ---- клиент против локального тестового сервера ----

    private class FakeNextcloud {
        val files = ConcurrentHashMap<String, ByteArray>()
        var polls = 0
        val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base get() = "http://127.0.0.1:${http.address.port}/nc"
        private val auth = "Basic " + Base64.getEncoder().encodeToString("bkphenix:app-pass".toByteArray())

        init {
            http.createContext("/nc/index.php/login/v2") { ex ->
                val body = if (ex.requestURI.path.endsWith("/poll")) {
                    polls++
                    if (polls < 2) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
                    """{"server":"$base","loginName":"bkphenix","appPassword":"app-pass"}"""
                } else {
                    """{"poll":{"token":"t1","endpoint":"$base/index.php/login/v2/poll"},"login":"$base/index.php/login/v2/flow/x"}"""
                }
                val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            http.createContext("/nc/ocs/v2.php/cloud/user") { ex ->
                val ok = ex.requestHeaders.getFirst("Authorization") == auth
                ex.sendResponseHeaders(if (ok) 200 else 401, -1); ex.close()
            }
            http.createContext("/nc/remote.php/dav/files/bkphenix/") { ex ->
                if (ex.requestHeaders.getFirst("Authorization") != auth) { ex.sendResponseHeaders(401, -1); ex.close(); return@createContext }
                val path = ex.requestURI.path.removePrefix("/nc/remote.php/dav/files/bkphenix/")
                when (ex.requestMethod) {
                    "PUT" -> {
                        if (files.containsKey(path)) { ex.sendResponseHeaders(403, -1); ex.close(); return@createContext }
                        files[path] = ex.requestBody.readBytes(); ex.sendResponseHeaders(201, -1); ex.close()
                    }
                    "HEAD" -> {
                        val f = files[path]
                        if (f == null) ex.sendResponseHeaders(404, -1) else { ex.responseHeaders.add("Content-Length", f.size.toString()); ex.sendResponseHeaders(200, -1) }
                        ex.close()
                    }
                    "GET" -> {
                        val f = files[path]
                        if (f == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
                        val range = ex.requestHeaders.getFirst("Range")
                        if (range != null) {
                            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                            val part = f.copyOfRange(from, f.size)
                            ex.sendResponseHeaders(206, part.size.toLong()); ex.responseBody.use { it.write(part) }
                        } else {
                            ex.sendResponseHeaders(200, f.size.toLong()); ex.responseBody.use { it.write(f) }
                        }
                    }
                    else -> { ex.sendResponseHeaders(405, -1); ex.close() }
                }
            }
            http.start()
        }
    }

    @Test
    fun `вход Login Flow v2 выгрузка и докачка`() {
        val fake = FakeNextcloud()
        try {
            val anon = NextcloudClient(fake.base)
            val start = anon.startLoginFlow()
            assertEquals("t1", start.pollToken)
            assertNull(anon.pollLoginFlow(start), "до подтверждения — null")
            val cred = anon.pollLoginFlow(start)!!
            assertEquals("bkphenix", cred.loginName)

            val client = NextcloudClient(fake.base, cred)
            assertTrue(client.checkAccess())
            assertFalse(NextcloudClient(fake.base, cred.copy(appPassword = "wrong")).checkAccess())

            val tmp = Files.createTempDirectory("nc").toFile()
            val local = File(tmp, "phenix.jsonl").apply { writeText("{\"type\":\"x\"}\n".repeat(1000)) }
            val path = ServerFormats.uploadPath("dev", local.name)
            client.upload(path, local)
            client.upload(path, local) // повтор после обрыва: тот же размер — не ошибка
            assertEquals(local.length(), client.size(path))
            assertNull(client.size("upload/none"))

            // докачка: частичный файл продолжается с места обрыва
            val data = ByteArray(3_000_000) { (it % 251).toByte() }
            fake.files["resources/map.pmtiles"] = data
            val dest = File(tmp, "map.part").apply { writeBytes(data.copyOfRange(0, 1_000_000)) }
            var lastProgress = 0L
            client.download("resources/map.pmtiles", dest, data.size.toLong()) { d, _ -> lastProgress = d }
            assertEquals(ServerFormats.sha256Hex(data), ServerFormats.sha256Hex(dest))
            assertEquals(data.size.toLong(), lastProgress)

            fake.files["resources/manifest.json"] = """{"files":[]}""".toByteArray()
            assertEquals("""{"files":[]}""", client.getText("resources/manifest.json"))
            tmp.deleteRecursively()
        } finally {
            fake.http.stop(0)
        }
    }

    @Test
    fun `ссылки входа на чужой адрес отклоняются`() {
        assertThrows(IllegalArgumentException::class.java) {
            ServerFormats.parseLoginFlowStart(
                """{"poll":{"token":"t","endpoint":"https://evil.example/poll"},"login":"https://example.org/nc/login"}""",
                "https://example.org/nc",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerFormats.parseLoginCredentials("""{"server":"https://evil.example","loginName":"u","appPassword":"p"}""", "https://example.org/nc")
        }
    }
}
