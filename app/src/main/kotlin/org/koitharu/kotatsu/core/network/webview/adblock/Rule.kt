package org.koitharu.kotatsu.core.network.webview.adblock

import okhttp3.HttpUrl

enum class ResourceType {
	DOCUMENT,
	SCRIPT,
	IMAGE,
	STYLESHEET,
	FONT,
	MEDIA,
	XHR,
	OTHER,
}

sealed interface Rule {

	operator fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType?): Boolean

	data class Domain(val domain: String) : Rule {

		override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType?): Boolean =
			url.host.matchesDomain(domain)
	}

	data class ExactUrl(private val url: HttpUrl) : Rule {

		override operator fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType?): Boolean =
			url == this.url
	}

	data class Path(private val path: String, private val contains: Boolean) : Rule {

		override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType?): Boolean {
			val fullPath = buildString {
				append(url.host)
				append(url.encodedPath)
				url.encodedQuery?.let {
					append('?')
					append(it)
				}
			}
			return if (contains) {
				fullPath.contains(path)
			} else {
				fullPath.endsWith(path)
			}
		}
	}

	data class WithModifiers(
		private val baseRule: Rule,
		private val thirdParty: Boolean?,
		private val domains: Set<String>?,
		private val domainsNot: Set<String>?,
		private val types: Set<ResourceType>,
		private val typesNot: Set<ResourceType>,
	) : Rule {

		override fun invoke(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType?): Boolean {
			if (!baseRule.invoke(url, baseUrl, resourceType)) {
				return false
			}
			if (types.isNotEmpty() && (resourceType == null || resourceType !in types)) {
				return false
			}
			if (resourceType != null && resourceType in typesNot) {
				return false
			}
			if (thirdParty != null) {
				val pageUrl = baseUrl ?: return false
				val isThirdPartyRequest =
					(url.topPrivateDomain() ?: url.host) != (pageUrl.topPrivateDomain() ?: pageUrl.host)
				if (isThirdPartyRequest != thirdParty) {
					return false
				}
			}
			if (!domains.isNullOrEmpty()) {
				val pageHost = baseUrl?.host ?: return false
				if (domains.none { pageHost.matchesDomain(it) }) {
					return false
				}
			}
			if (!domainsNot.isNullOrEmpty()) {
				val pageHost = baseUrl?.host ?: return false
				if (domainsNot.any { pageHost.matchesDomain(it) }) {
					return false
				}
			}
			return true
		}
	}
}

private fun String.matchesDomain(domain: String): Boolean = this == domain || endsWith(".$domain")
