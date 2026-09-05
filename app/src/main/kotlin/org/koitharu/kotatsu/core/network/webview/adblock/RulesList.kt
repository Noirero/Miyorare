package org.koitharu.kotatsu.core.network.webview.adblock

import androidx.annotation.CheckResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Lightweight EasyList network-rule parser for the built-in browser.
 * Cosmetic rules are intentionally ignored because WebView request interception cannot apply them.
 */
class RulesList {

	private val blockRules = ArrayList<Rule>()
	private val allowRules = ArrayList<Rule>()
	private val blockDomains = HashMap<String, MutableList<Rule>>()
	private val allowDomains = HashMap<String, MutableList<Rule>>()

	operator fun get(url: HttpUrl, baseUrl: HttpUrl?, resourceType: ResourceType?): Rule? {
		val rule = findDomainRule(blockDomains, url, baseUrl, resourceType)
			?: blockRules.firstOrNull { it(url, baseUrl, resourceType) }
		return rule?.takeIf {
			findDomainRule(allowDomains, url, baseUrl, resourceType) == null &&
				allowRules.none { x -> x(url, baseUrl, resourceType) }
		}
	}

	fun add(line: String) {
		val normalized = line.trim()
		if (normalized.isEmpty() || "##" in normalized || "#@#" in normalized) return
		val parts = normalized.lowercase().split('$', limit = 2)
		parts.first().addImpl(isWhitelist = false, modifiers = parts.getOrNull(1))
	}

	fun trimToSize() {
		blockRules.trimToSize()
		allowRules.trimToSize()
		blockDomains.values.forEach { (it as? ArrayList<Rule>)?.trimToSize() }
		allowDomains.values.forEach { (it as? ArrayList<Rule>)?.trimToSize() }
	}

	private fun String.addImpl(isWhitelist: Boolean, modifiers: String?) {
		val list = if (isWhitelist) allowRules else blockRules

		when {
			startsWith('!') || startsWith('[') -> Unit

			startsWith("@@") -> {
				substring(2).addImpl(isWhitelist = true, modifiers = modifiers)
			}

			startsWith("||") -> {
				val domain = substring(2).substringBefore('^').trim()
				if (domain.isNotEmpty() && '*' !in domain && '/' !in domain) {
					val rule = Rule.Domain(domain).withModifiers(modifiers)
					val domains = if (isWhitelist) allowDomains else blockDomains
					domains.getOrPut(domain) { ArrayList(1) } += rule
				}
			}

			startsWith('|') -> {
				val url = substring(1).substringBefore('^').trim().toHttpUrlOrNull()
				if (url != null) {
					list += Rule.ExactUrl(url).withModifiers(modifiers)
				}
			}

			else -> {
				when {
					endsWith('*') && count { it == '*' } == 1 -> {
						list += Rule.Path(dropLast(1), contains = true).withModifiers(modifiers)
					}

					'*' !in this -> {
						list += Rule.Path(this, contains = true).withModifiers(modifiers)
					}
				}
			}
		}
	}

	@CheckResult
	private fun Rule.withModifiers(options: String?): Rule {
		if (options.isNullOrEmpty()) {
			return this
		}
		var thirdParty: Boolean? = null
		val domains = LinkedHashSet<String>()
		val domainsNot = LinkedHashSet<String>()
		val types = LinkedHashSet<ResourceType>()
		val typesNot = LinkedHashSet<ResourceType>()

		options.split(',').forEach { rawOption ->
			when {
				rawOption.startsWith("domain=") -> {
					rawOption.substringAfter('=').split('|').forEach { rawDomain ->
						val excluded = rawDomain.startsWith('~')
						val domain = rawDomain.removePrefix("~").trim().trimStart('.')
						if (domain.isNotEmpty()) {
							(if (excluded) domainsNot else domains) += domain
						}
					}
				}

				else -> {
					val isNot = rawOption.startsWith('~')
					val option = rawOption.removePrefix("~")
					when (option) {
						"third-party" -> thirdParty = !isNot
						"script" -> (if (isNot) typesNot else types) += ResourceType.SCRIPT
						"image" -> (if (isNot) typesNot else types) += ResourceType.IMAGE
						"stylesheet" -> (if (isNot) typesNot else types) += ResourceType.STYLESHEET
						"font" -> (if (isNot) typesNot else types) += ResourceType.FONT
						"media" -> (if (isNot) typesNot else types) += ResourceType.MEDIA
						"xmlhttprequest" -> (if (isNot) typesNot else types) += ResourceType.XHR
						"document", "subdocument" ->
							(if (isNot) typesNot else types) += ResourceType.DOCUMENT
						"other" -> (if (isNot) typesNot else types) += ResourceType.OTHER
					}
				}
			}
		}

		return Rule.WithModifiers(
			baseRule = this,
			thirdParty = thirdParty,
			domains = domains.takeIf { it.isNotEmpty() },
			domainsNot = domainsNot.takeIf { it.isNotEmpty() },
			types = types,
			typesNot = typesNot,
		)
	}

	private fun findDomainRule(
		rules: Map<String, List<Rule>>,
		url: HttpUrl,
		baseUrl: HttpUrl?,
		resourceType: ResourceType?,
	): Rule? {
		for (domain in domainCandidates(url.host)) {
			val match = rules[domain]?.firstOrNull { it(url, baseUrl, resourceType) }
			if (match != null) {
				return match
			}
		}
		return null
	}

	private fun domainCandidates(host: String): Sequence<String> = sequence {
		var current = host
		while (current.isNotEmpty()) {
			yield(current)
			val dot = current.indexOf('.')
			if (dot == -1) break
			current = current.substring(dot + 1)
		}
	}
}
