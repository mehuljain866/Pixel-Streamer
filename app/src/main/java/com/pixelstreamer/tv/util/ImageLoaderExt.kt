package com.pixelstreamer.tv.util

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import coil.load

fun ImageView.loadUrlWithFallback(
    primaryUrl: String,
    fallbackUrl: String? = null,
    placeholderRes: Int? = null
) {
    val first = primaryUrl.ifBlank { fallbackUrl.orEmpty() }
    if (first.isBlank()) {
        if (placeholderRes != null) setImageResource(placeholderRes)
        return
    }
    load(first) {
        crossfade(true)
        if (placeholderRes != null) placeholder(placeholderRes)
        val fb = fallbackUrl?.takeIf { it.isNotBlank() && it != first }
        if (fb != null) {
            listener(
                onError = { _, _ ->
                    post {
                        load(fb) {
                            crossfade(true)
                            if (placeholderRes != null) placeholder(placeholderRes)
                        }
                    }
                }
            )
        }
    }
}

/**
 * Loads an official transparent PNG title logo into [this] ImageView, falling back
 * cleanly to [fallbackTitleView] before load or if the logo fails.
 */
fun ImageView.bindTitleLogo(logoUrl: String?, fallbackTitleView: TextView) {
    val cleanUrl = logoUrl?.trim().orEmpty()
    if (cleanUrl.isBlank()) {
        visibility = View.GONE
        fallbackTitleView.visibility = View.VISIBLE
        return
    }
    load(cleanUrl) {
        crossfade(true)
        listener(
            onStart = {
                visibility = View.GONE
                fallbackTitleView.visibility = View.VISIBLE
            },
            onSuccess = { _, _ ->
                visibility = View.VISIBLE
                fallbackTitleView.visibility = View.GONE
            },
            onError = { _, _ ->
                visibility = View.GONE
                fallbackTitleView.visibility = View.VISIBLE
            }
        )
    }
}
