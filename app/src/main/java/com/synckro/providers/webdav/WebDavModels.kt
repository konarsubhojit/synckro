package com.synckro.providers.webdav

import com.synckro.domain.provider.RemoteFile
import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.w3c.dom.Node
import timber.log.Timber
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** XML namespace of the WebDAV core vocabulary (RFC 4918). */
internal const val DAV_NAMESPACE = "DAV:"

/** MIME type used by the provider to mark collections (directories). */
internal const val WEBDAV_COLLECTION_MIME_TYPE = "httpd/unix-directory"

/**
 * A single `<D:response>` entry from a WebDAV `PROPFIND` multistatus document,
 * reduced to the properties Synckro needs.
 *
 * @property path Server-absolute, percent-decoded path of the resource with any
 *   trailing slash removed (e.g. `/remote.php/dav/files/alice/Photos/a.jpg`).
 *   Synckro uses this path as the provider-specific item id: WebDAV has no
 *   stable, rename-independent resource id, so a move is observed as a
 *   delete + add of two different ids.
 * @property isCollection `true` when `<D:resourcetype>` contains `<D:collection/>`.
 * @property contentLength `D:getcontentlength`, when reported.
 * @property lastModifiedMs `D:getlastmodified` (RFC 1123) as epoch millis, when reported.
 * @property eTag `D:getetag` with surrounding quotes and any weak-validator
 *   prefix removed. Opaque version tag — not a content hash (see [RemoteFile]).
 * @property contentType `D:getcontenttype`, when reported.
 */
internal data class WebDavResource(
    val path: String,
    val isCollection: Boolean,
    val contentLength: Long? = null,
    val lastModifiedMs: Long? = null,
    val eTag: String? = null,
    val contentType: String? = null,
) {
    /** Last path segment, i.e. the file or folder name. */
    val name: String get() = path.substringAfterLast('/')

    /** Server-absolute path of the parent collection, or `null` for the server root. */
    val parentPath: String?
        get() {
            val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
            return parent.ifEmpty { null }
        }
}

/**
 * Quota reported by `PROPFIND` on a collection using the RFC 4331 properties
 * `D:quota-used-bytes` / `D:quota-available-bytes`. Nextcloud, ownCloud and most
 * other WebDAV servers implement these, which avoids depending on Nextcloud's
 * proprietary OCS endpoint.
 */
internal data class WebDavQuota(
    val usedBytes: Long,
    val availableBytes: Long,
)

/**
 * Maps a [WebDavResource] onto the provider-agnostic [RemoteFile].
 *
 * [RemoteFile.contentHash] is always `null`: WebDAV's `getetag` is an opaque
 * version tag rather than a hash of the file bytes, and the `CloudProvider`
 * contract forbids substituting one for the other.
 */
internal fun WebDavResource.toRemoteFile(): RemoteFile =
    RemoteFile(
        id = path,
        name = name,
        parentId = parentPath,
        isFolder = isCollection,
        size = contentLength,
        lastModifiedMs = lastModifiedMs,
        eTag = eTag,
        mimeType = contentType ?: if (isCollection) WEBDAV_COLLECTION_MIME_TYPE else null,
        contentHash = null,
    )

/**
 * Parses a `207 Multi-Status` body into [WebDavResource] entries.
 *
 * Only `propstat` blocks whose `D:status` carries a 2xx code are considered, so
 * the `404 Not Found` propstat blocks that servers emit for properties they do
 * not support are ignored instead of being read as empty values.
 *
 * @param baseUrl Absolute URL the request was sent to; used to resolve `href`
 *   values that are returned as full URLs rather than absolute paths.
 */
internal fun parseMultiStatus(
    body: InputStream,
    baseUrl: HttpUrl,
): List<WebDavResource> {
    val document =
        body.use { stream ->
            newSecureDocumentBuilderFactory().newDocumentBuilder().parse(stream)
        }
    val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
    return buildList {
        for (i in 0 until responses.length) {
            val response = responses.item(i) as? Element ?: continue
            parseResponse(response, baseUrl)?.let(::add)
        }
    }
}

/**
 * Extracts the RFC 4331 quota properties from a `207 Multi-Status` body.
 *
 * Returns `null` when the server did not report both properties (the
 * properties are optional, and `quota-available-bytes` is negative for servers
 * that advertise unlimited storage).
 */
internal fun parseQuota(
    body: InputStream,
    baseUrl: HttpUrl,
): WebDavQuota? {
    val document =
        body.use { stream ->
            newSecureDocumentBuilderFactory().newDocumentBuilder().parse(stream)
        }
    val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
    for (i in 0 until responses.length) {
        val response = responses.item(i) as? Element ?: continue
        val props = successfulProps(response)
        val used = props.firstNotNullOfOrNull { propText(it, "quota-used-bytes") }?.toLongOrNull()
        val available = props.firstNotNullOfOrNull { propText(it, "quota-available-bytes") }?.toLongOrNull()
        if (used != null && available != null && available >= 0) {
            return WebDavQuota(usedBytes = used, availableBytes = available)
        }
    }
    Timber.d("WebDAV: PROPFIND response from %s carried no usable quota properties", baseUrl.encodedPath)
    return null
}

private fun parseResponse(
    response: Element,
    baseUrl: HttpUrl,
): WebDavResource? {
    val href = childText(response, "href") ?: return null
    val path = resolveHrefPath(href, baseUrl) ?: return null
    val props = successfulProps(response)
    val resourceType = props.firstNotNullOfOrNull { childElement(it, "resourcetype") }
    val isCollection = resourceType != null && childElement(resourceType, "collection") != null
    return WebDavResource(
        path = path,
        isCollection = isCollection,
        contentLength = props.firstNotNullOfOrNull { propText(it, "getcontentlength") }?.toLongOrNull(),
        lastModifiedMs = props.firstNotNullOfOrNull { propText(it, "getlastmodified") }?.let(::parseRfc1123),
        eTag = props.firstNotNullOfOrNull { propText(it, "getetag") }?.let(::normalizeETag),
        contentType = props.firstNotNullOfOrNull { propText(it, "getcontenttype") },
    )
}

/**
 * Returns the `D:prop` elements of every `D:propstat` block whose status is 2xx.
 * Blocks without a status element are kept: a malformed-but-usable response is
 * preferable to dropping the resource entirely.
 */
private fun successfulProps(response: Element): List<Element> {
    val propstats = response.getElementsByTagNameNS(DAV_NAMESPACE, "propstat")
    return buildList {
        for (i in 0 until propstats.length) {
            val propstat = propstats.item(i) as? Element ?: continue
            val status = childText(propstat, "status")
            if (status != null && !isSuccessStatus(status)) continue
            childElement(propstat, "prop")?.let(::add)
        }
    }
}

/** Parses the 2xx-ness of a `D:status` line such as `HTTP/1.1 200 OK`. */
private fun isSuccessStatus(status: String): Boolean {
    val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
    return code in 200..299
}

/**
 * Converts an `href` (either a full URL or a server-absolute path) into a
 * percent-decoded, slash-normalized absolute path. Returns `null` when the
 * href cannot be resolved against [baseUrl].
 */
internal fun resolveHrefPath(
    href: String,
    baseUrl: HttpUrl,
): String? {
    val resolved = baseUrl.resolve(href.trim()) ?: return null
    val decoded = "/" + resolved.pathSegments.joinToString("/")
    return normalizePath(decoded)
}

/** Strips trailing slashes (collections are returned with one) and collapses an empty path to "/". */
internal fun normalizePath(path: String): String {
    val trimmed = path.trimEnd('/')
    return trimmed.ifEmpty { "/" }
}

/** Removes the weak-validator prefix and surrounding quotes from an ETag. */
internal fun normalizeETag(raw: String): String? =
    raw
        .trim()
        .removePrefix("W/")
        .trim('"')
        .ifEmpty { null }

/** Parses an RFC 1123 HTTP-date (`Wed, 21 Oct 2015 07:28:00 GMT`) into epoch millis. */
internal fun parseRfc1123(value: String): Long? =
    runCatching {
        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

private fun childElement(
    parent: Element,
    localName: String,
): Element? {
    val children = parent.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType != Node.ELEMENT_NODE) continue
        val element = node as Element
        if (element.localName == localName && element.namespaceURI == DAV_NAMESPACE) return element
    }
    return null
}

private fun childText(
    parent: Element,
    localName: String,
): String? = childElement(parent, localName)?.textContent?.takeIf { it.isNotBlank() }

private fun propText(
    prop: Element,
    localName: String,
): String? = childText(prop, localName)

/**
 * Creates a [DocumentBuilderFactory] with DTDs and external entity resolution
 * disabled, so a hostile or compromised WebDAV server cannot use the PROPFIND
 * response to mount an XXE / billion-laughs attack against the app.
 */
private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory =
    DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = true
    }
