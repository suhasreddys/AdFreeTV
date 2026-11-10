package com.adfree.tv

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {

    private val blockedDomains = arrayOf(
        "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "ad.youtube.com",
        "static.doubleclick.net"
    )

    private val blockedPaths = arrayOf(
        "/pagead/",
        "/api/stats/ads",
        "/youtubei/v1/player/ad_break",
        "/get_midroll_info",
        "/ptracking",
        "/api/stats/qoe?adformat="
    )

    fun shouldBlock(request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString()?.lowercase() ?: return false

        for (domain in blockedDomains) {
            if (url.contains(domain)) return true
        }

        for (path in blockedPaths) {
            if (url.contains(path)) return true
        }

        return false
    }

    /**
     * Returns an empty response with valid CORS headers matching the caller's origin.
     * Prevents browser CORS errors from stalling the YouTube player state machine.
     */
    fun createEmptyResponse(request: WebResourceRequest?): WebResourceResponse {
        val origin = request?.requestHeaders?.get("Origin")
            ?: request?.requestHeaders?.get("origin")
            ?: "https://www.youtube.com"

        val headers = mapOf(
            "Access-Control-Allow-Origin" to origin,
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Headers" to "Content-Type, Authorization, X-YouTube-Client-Name, X-YouTube-Client-Version",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD"
        )

        return WebResourceResponse(
            "application/json",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream("{}".toByteArray())
        )
    }

    /**
     * Optimized injection:
     * 1. Eliminates default WebView video play button & poster overlays
     * 2. Non-blocking adPlacements stripper
     * 3. Precise ad detector (only triggers on ACTUAL active ads, never on the empty .video-ads container)
     * 4. Auto-kick for instant video playback start
     */
    val INJECTION_SCRIPT: String = """
        (function() {
            if (window.__adBlockerLoaded) return;
            window.__adBlockerLoaded = true;

            // 1. Trusted Types compatibility
            if (window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy) {
                try {
                    window.trustedTypes.createPolicy('default', {
                        createHTML: function(s) { return s; },
                        createScript: function(s) { return s; },
                        createScriptURL: function(s) { return s; }
                    });
                } catch (e) {}
            }

            // 2. Hide ad banners, overlays, and suppress the default gray media play button
            try {
                const style = document.createElement('style');
                style.textContent = `
                    video::-webkit-media-controls-start-playback-button,
                    video::-webkit-media-controls-play-button {
                        display: none !important;
                        -webkit-appearance: none !important;
                        opacity: 0 !important;
                        visibility: hidden !important;
                    }
                    .ytp-ad-overlay-container,
                    .ytp-ad-message-container,
                    .ytp-ad-progress,
                    .ytp-ad-progress-list,
                    #player-ads,
                    ytd-promoted-sparkles-web-renderer,
                    ytd-display-ad-renderer,
                    ytd-banner-promo-renderer,
                    .ytd-in-feed-ad-layout-renderer {
                        display: none !important;
                        visibility: hidden !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}

            // 3. Intercept fetch to strip adPlacements before playback starts
            try {
                const originalFetch = window.fetch;
                window.fetch = async function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                    
                    if (url.includes('/pagead/') || url.includes('doubleclick.net') || url.includes('/api/stats/ads') || url.includes('/youtubei/v1/player/ad_break')) {
                        return new Response('{}', {
                            status: 200,
                            headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' }
                        });
                    }

                    const response = await originalFetch.apply(this, args);

                    if (url.includes('/youtubei/v1/player')) {
                        try {
                            const clone = response.clone();
                            const data = await clone.json();
                            if (data && (data.adPlacements || data.adSlots || data.playerAds)) {
                                delete data.adPlacements;
                                delete data.adSlots;
                                delete data.playerAds;
                                return new Response(JSON.stringify(data), {
                                    status: response.status,
                                    statusText: response.statusText,
                                    headers: response.headers
                                });
                            }
                        } catch (err) {}
                    }

                    return response;
                };
            } catch (e) {}

            // 4. Precision ad skipper - ONLY triggers when an ad is ACTUALLY playing
            let wasAdPlaying = false;
            let previousVolume = 1.0;

            function processAds() {
                try {
                    const video = document.querySelector('video');
                    const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');

                    // Check for active ads using specific active selectors (never match empty .video-ads)
                    const isAdShowing = document.querySelector(
                        '#movie_player.ad-showing, #movie_player.ad-interrupting, .ad-showing, .ad-interrupting, .video-ads > *'
                    ) !== null;
                    const isPlayerAd = player && typeof player.getAdState === 'function' && player.getAdState() === 1;

                    if ((isAdShowing || isPlayerAd) && video) {
                        if (!wasAdPlaying) {
                            wasAdPlaying = true;
                            previousVolume = video.muted ? 0 : video.volume;
                        }

                        video.muted = true;
                        try { video.playbackRate = 16.0; } catch (e) {}

                        if (player && typeof player.skipAd === 'function') {
                            try { player.skipAd(); } catch (e) {}
                        }

                        if (video.duration && isFinite(video.duration) && video.duration > 0) {
                            video.currentTime = video.duration;
                        } else {
                            video.currentTime = 999999;
                        }

                        const skipSelectors = [
                            '.ytp-ad-skip-button',
                            '.ytp-skip-ad-button',
                            '.ytp-ad-skip-button-modern',
                            '.videoAdUiSkipButton',
                            'button[class*="skip-button"]',
                            'button[id*="skip"]',
                            '[aria-label*="Skip"]',
                            '[aria-label*="skip"]'
                        ];

                        for (let i = 0; i < skipSelectors.length; i++) {
                            const btn = document.querySelector(skipSelectors[i]);
                            if (btn) {
                                btn.click();
                                break;
                            }
                        }
                    } else if (wasAdPlaying && video) {
                        wasAdPlaying = false;
                        try { video.playbackRate = 1.0; } catch (e) {}
                        if (previousVolume > 0) {
                            video.muted = false;
                            video.volume = previousVolume;
                        }
                    }
                } catch (e) {}
            }

            setInterval(processAds, 100);
        })();
    """.trimIndent()
}
