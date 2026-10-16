package com.vayro.app.marketplace

import org.json.JSONArray
import org.json.JSONObject

/**
 * A listing submission before it reaches the registry.
 * Validated locally by [validateListing] so malformed drafts are refused
 * before any network call is made.
 */
data class ListingDraft(
    val name: String,
    val category: MarketplaceCategory?,
    val description: String,
    val version: String,
    val artifactUrl: String,
    val sha256: String = "",
    val tags: List<String> = emptyList(),
)

/** Outcome of listing validation: valid, or the list of problems found. */
data class ListingValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)

private val SEMVER_REGEX = Regex("\\d+(\\.\\d+)*(-[A-Za-z0-9.]+)?")
private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")
private val TAG_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")

/**
 * Validates a listing draft (Milestone 13). Pure JVM.
 *
 * Rules:
 * - name: at least 3 characters
 * - category: required
 * - description: at least 20 characters (buyers need real detail)
 * - version: numeric dotted semver (e.g. 1.2.0, 2.0.0-beta.1)
 * - artifactUrl: required, must be http(s)
 * - sha256: required when artifactUrl is set, must be 64 hex chars
 * - tags: at most 10, each 1-32 chars of [A-Za-z0-9._-]
 */
fun validateListing(draft: ListingDraft): ListingValidationResult {
    val errors = mutableListOf<String>()

    if (draft.name.trim().length < 3) {
        errors += "name must be at least 3 characters"
    }
    if (draft.category == null) {
        errors += "category is required"
    }
    if (draft.description.trim().length < 20) {
        errors += "description must be at least 20 characters"
    }
    if (!SEMVER_REGEX.matches(draft.version.trim())) {
        errors += "version must be numeric dotted (e.g. 1.2.0), got '${draft.version}'"
    }
    val url = draft.artifactUrl.trim()
    if (url.isBlank()) {
        errors += "artifactUrl is required"
    } else if (!(url.startsWith("http://") || url.startsWith("https://"))) {
        errors += "artifactUrl must be http(s), got '$url'"
    }
    if (url.isNotBlank() && !SHA256_REGEX.matches(draft.sha256.trim())) {
        errors += "sha256 must be 64 hex characters when an artifactUrl is set"
    }
    if (draft.tags.size > 10) {
        errors += "at most 10 tags allowed, got ${draft.tags.size}"
    }
    draft.tags.forEach { tag ->
        if (!TAG_REGEX.matches(tag)) {
            errors += "tag '$tag' must be 1-32 chars of letters, digits, '.', '_' or '-'"
        }
    }

    return ListingValidationResult(isValid = errors.isEmpty(), errors = errors)
}

/** Converts a validated draft into the registry's listing JSON body. */
fun ListingDraft.toListingJson(): JSONObject = JSONObject().apply {
    put("name", name.trim())
    put("category", category!!.name)
    put("description", description.trim())
    put("version", version.trim())
    put("artifactUrl", artifactUrl.trim())
    put("sha256", sha256.trim().lowercase())
    put("tags", JSONArray(tags))
}

/**
 * Validates a marketplace item the user publishes from the Marketplace
 * screen. Same name/description thresholds as [validateListing]; the
 * install command/URL must be non-blank. ListingDraft's artifactUrl
 * http(s) rule does NOT apply here: an MCP item's install value is a
 * command such as `npx -y ...`, not a URL.
 */
fun validateMarketplaceItem(item: MarketplaceItem): ListingValidationResult {
    val errors = mutableListOf<String>()
    if (item.name.trim().length < 3) {
        errors += "name must be at least 3 characters"
    }
    if (item.description.trim().length < 20) {
        errors += "description must be at least 20 characters"
    }
    if (item.installUrl.isBlank()) {
        errors += "install command or URL is required"
    }
    if (item.sha256.isNotBlank() && !SHA256_REGEX.matches(item.sha256.trim())) {
        errors += "sha256 must be 64 hex characters"
    }
    if (item.tags.size > 10) {
        errors += "at most 10 tags allowed, got ${item.tags.size}"
    }
    item.tags.forEach { tag ->
        if (!TAG_REGEX.matches(tag)) {
            errors += "tag '$tag' must be 1-32 chars of letters, digits, '.', '_' or '-'"
        }
    }
    return ListingValidationResult(isValid = errors.isEmpty(), errors = errors)
}
