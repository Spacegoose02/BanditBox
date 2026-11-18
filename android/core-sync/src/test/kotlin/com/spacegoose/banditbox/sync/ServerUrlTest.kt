package com.spacegoose.banditbox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun addsSchemeAndDefaultPort() {
        assertEquals("http://192.168.1.20:8686", ServerUrl.normalize("192.168.1.20"))
        assertEquals("http://nas:8686", ServerUrl.normalize(" nas "))
    }

    @Test
    fun keepsGivenPortAndDropsPath() {
        assertEquals("http://nas:9000", ServerUrl.normalize("nas:9000"))
        assertEquals("http://nas", ServerUrl.normalize("http://nas:80/devices"))
        assertEquals("https://box.example.com", ServerUrl.normalize("https://box.example.com/"))
    }

    @Test
    fun rejectsJunk() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("not an address"))
        assertNull(ServerUrl.normalize("ftp://nas"))
    }
}
