package org.koitharu.kotatsu.scrobbling.discord.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PublicDiscordCoverTest {

	@Test
	fun acceptsStableHttpsUrlsOnly() {
		assertNotNull(discordPublicUrl("https://cdn.example.com/cover.jpg"))
		listOf(
			"http://cdn.example.com/cover.jpg",
			"https://cdn.example.com:8443/cover.jpg",
			"https://user:password@cdn.example.com/cover.jpg",
			"https://cdn.example.com/cover.jpg?access_token=sample",
			"https://cdn.example.com/cover.jpg#private",
			"https://127.0.0.1/cover.jpg",
			"https://[::1]/cover.jpg",
			"file:///sdcard/cover.jpg",
		).forEach { assertNull(it, discordPublicUrl(it)) }
	}

	@Test
	fun rejectsLocalAndSpecialUseAddresses() {
		listOf("127.0.0.1", "10.0.0.2", "169.254.169.254", "100.64.0.2", "192.168.1.1", "198.18.0.1",
			"192.0.2.1", "::1", "fd00::1", "fe80::1", "2001:db8::1").forEach {
			assertFalse(it, isPublicDiscordAddress(InetAddress.getByName(it)))
		}
		assertTrue(isPublicDiscordAddress(InetAddress.getByName("1.1.1.1")))
		assertTrue(isPublicDiscordAddress(InetAddress.getByName("2606:4700:4700::1111")))
	}
}
