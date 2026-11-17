package org.koitharu.kotatsu.tsuki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.tsuki.model.TsukiPluginProvider

class VoraToonPackRoutingTest {
	private val legacy = VoraToonPackRouting.legacy
	private val global = VoraToonPackRouting.global

	@Test
	fun oldReferencePrefersGlobalWhenBothPacksAreUsable() {
		val installed = mapOf(legacy to "ID", global to "Global")
		assertEquals("Global", VoraToonPackRouting.resolve(legacy, installed::get))
		assertEquals("Global", VoraToonPackRouting.resolve(global, installed::get))
		assertTrue(VoraToonPackRouting.isShadowed(legacy, hasUsableGlobal = true))
		assertFalse(VoraToonPackRouting.isShadowed(global, hasUsableGlobal = true))
	}

	@Test
	fun oldReferenceResolvesWithEitherPackAndFallsBackIfGlobalIsUnusable() {
		assertEquals("Global", VoraToonPackRouting.resolve(legacy, mapOf(global to "Global")::get))
		val legacyOnly = mapOf(legacy to "ID")
		assertEquals("ID", VoraToonPackRouting.resolve(legacy, legacyOnly::get))
		assertEquals("ID", VoraToonPackRouting.resolve(global, legacyOnly::get))
		assertFalse(VoraToonPackRouting.isShadowed(legacy, hasUsableGlobal = false))
		assertNull(VoraToonPackRouting.resolve<String>(legacy) { null })
	}

	@Test
	fun keepsIdNamespaceAndDoesNotRouteOtherSources() {
		assertEquals(legacy, VoraToonPackRouting.persisted(global))
		assertEquals(legacy, VoraToonPackRouting.persisted(legacy))
		for (other in listOf(legacy.copy(sourceName = "OTHER"), legacy.copy(provider = TsukiPluginProvider.UMA))) {
			assertEquals(other, VoraToonPackRouting.persisted(other))
			assertFalse(VoraToonPackRouting.isShadowed(other, hasUsableGlobal = true))
			assertEquals(other, VoraToonPackRouting.resolve(other) { it })
		}
	}
}
