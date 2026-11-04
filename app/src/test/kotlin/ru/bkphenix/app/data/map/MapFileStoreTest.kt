package ru.bkphenix.app.data.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapFileStoreTest {
    @Test
    fun recognisesPmtilesV3Header() {
        val ok = File.createTempFile("valid", ".pmtiles").apply { writeBytes("PMTiles".toByteArray() + byteArrayOf(3) + ByteArray(200)) }
        val v2 = File.createTempFile("old", ".pmtiles").apply { writeBytes("PMTiles".toByteArray() + byteArrayOf(2) + ByteArray(200)) }
        val junk = File.createTempFile("junk", ".pmtiles").apply { writeText("<html>login</html>") }
        assertTrue(MapFileStore.isValid(ok))
        assertFalse(MapFileStore.isValid(v2))
        assertFalse(MapFileStore.isValid(junk))
    }
}
