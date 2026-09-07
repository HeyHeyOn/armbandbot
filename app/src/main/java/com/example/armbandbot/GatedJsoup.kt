package com.heyheyon.armbandbot

import java.io.IOException
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.UnsupportedMimeTypeException
import org.jsoup.internal.StringUtil

/** Jsoup 1.17 redirect semantics, with a gate before every actual HTTP hop. */
internal fun executeGatedJsoup(connection: Connection, beforeRequest: () -> Unit): Connection.Response {
    val request = connection.request()
    if (!request.followRedirects()) {
        beforeRequest()
        return connection.execute()
    }
    val ignoreErrors = request.ignoreHttpErrors()
    val ignoreType = request.ignoreContentType()
    val cookies = linkedMapOf<String, String>()
    request.followRedirects(false).ignoreHttpErrors(true).ignoreContentType(true)
    try {
        var redirects = 0
        while (true) {
            beforeRequest()
            val response = connection.execute()
            cookies.putAll(response.cookies())
            cookies.forEach { (key, value) -> response.cookies().putIfAbsent(key, value) }
            val location = response.header("Location")
            if (location != null) {
                response.bodyStream().close()
                if (redirects++ >= 20) throw IOException("Too many redirects occurred trying to load URL ${request.url()}")
                if (response.statusCode() != 307) {
                    request.method(Connection.Method.GET)
                    request.data().clear()
                    request.requestBody(null)
                    request.removeHeader("Content-Type")
                }
                val fixedLocation = if (location.startsWith("http:/") && location.length > 6 && location[6] != '/') location.substring(6) else location
                request.url(StringUtil.resolve(request.url(), fixedLocation))
                continue
            }
            val status = response.statusCode()
            if (!ignoreErrors && (status < 200 || status >= 400)) {
                response.bodyStream().close()
                throw HttpStatusException("HTTP error fetching URL", status, request.url().toString())
            }
            val type = response.contentType()
            if (!ignoreType && type != null && !type.startsWith("text/") && !Regex("(\\w+)/\\w*\\+?xml.*").matches(type)) {
                response.bodyStream().close()
                throw UnsupportedMimeTypeException("Unhandled content type. Must be text/*, */xml, or */*+xml", type, request.url().toString())
            }
            return response
        }
    } finally {
        request.followRedirects(true).ignoreHttpErrors(ignoreErrors).ignoreContentType(ignoreType)
    }
}
