package com.westly.neribovault.feature.coder.preview

import android.net.Uri

/** One of the three libraries bundled in `assets/coder/vendor/`. [assetName] is the file name inside that folder. */
enum class CoderVendorAsset(val assetName: String) {
    React("react.development.js"),
    ReactDom("react-dom.development.js"),
    Babel("babel.min.js"),
}

/**
 * Decides which web addresses of React, ReactDOM and Babel the app answers from its own bundled copies,
 * so tutorial-style projects work offline. Only exact, known addresses on https match, so nothing else
 * is ever answered from the app. Always the development build is served, because it gives learners better
 * error messages. React and ReactDOM must be version 18 (React 19 has no browser build, so those requests
 * go to the network); Babel must be version 7 or have no version.
 */
object CoderCdnShim {
    private const val HOST_UNPKG = "unpkg.com"
    private const val HOST_JSDELIVR = "cdn.jsdelivr.net"
    private const val HOST_CDNJS = "cdnjs.cloudflare.com"

    private val UNPKG_REACT = Regex("^/react@([^/]+)/umd/react\\.(?:development|production\\.min)\\.js$")
    private val UNPKG_REACT_DOM = Regex("^/react-dom@([^/]+)/umd/react-dom\\.(?:development|production\\.min)\\.js$")
    private val UNPKG_BABEL = Regex("^/@babel/standalone(?:@([^/]+))?/(?:babel\\.min\\.js|babel\\.js)$")
    private val CDNJS_REACT = Regex("^/ajax/libs/react/([^/]+)/umd/react\\.(?:development|production\\.min)\\.js$")
    private val CDNJS_REACT_DOM = Regex("^/ajax/libs/react-dom/([^/]+)/umd/react-dom\\.(?:development|production\\.min)\\.js$")
    private val CDNJS_BABEL = Regex("^/ajax/libs/babel-standalone/([^/]+)/(?:babel\\.min\\.js|babel\\.js)$")

    /** The bundled file that answers [url], or null when the address must go to the network. Pure; uses only Uri getters. */
    fun match(url: Uri): CoderVendorAsset? {
        if (url.scheme?.lowercase() != "https") return null
        val port = url.port
        if (port != -1 && port != 443) return null
        val path = url.encodedPath ?: return null
        return when (url.host) {
            HOST_UNPKG -> matchNpmPath(path)
            HOST_JSDELIVR -> if (path.startsWith("/npm/")) matchNpmPath(path.removePrefix("/npm")) else null
            HOST_CDNJS -> matchCdnjsPath(path)
            else -> null
        }
    }

    private fun matchNpmPath(path: String): CoderVendorAsset? {
        UNPKG_REACT.matchEntire(path)?.let { m ->
            return if (majorIs(m.groupValues[1], "18")) CoderVendorAsset.React else null
        }
        UNPKG_REACT_DOM.matchEntire(path)?.let { m ->
            return if (majorIs(m.groupValues[1], "18")) CoderVendorAsset.ReactDom else null
        }
        UNPKG_BABEL.matchEntire(path)?.let { m ->
            val version = m.groupValues[1]
            return if (version.isEmpty() || majorIs(version, "7")) CoderVendorAsset.Babel else null
        }
        return null
    }

    private fun matchCdnjsPath(path: String): CoderVendorAsset? {
        CDNJS_REACT.matchEntire(path)?.let { m ->
            return if (majorIs(m.groupValues[1], "18")) CoderVendorAsset.React else null
        }
        CDNJS_REACT_DOM.matchEntire(path)?.let { m ->
            return if (majorIs(m.groupValues[1], "18")) CoderVendorAsset.ReactDom else null
        }
        CDNJS_BABEL.matchEntire(path)?.let { m ->
            return if (majorIs(m.groupValues[1], "7")) CoderVendorAsset.Babel else null
        }
        return null
    }

    /** True when [version] starts with [major] and the next character is not another digit ("18", "18.x", "18.2.0" yes; "180" no). */
    private fun majorIs(version: String, major: String): Boolean {
        if (!version.startsWith(major)) return false
        return version.length == major.length || !version[major.length].isDigit()
    }
}
