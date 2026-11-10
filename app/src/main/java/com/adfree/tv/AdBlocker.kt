package com.adfree.tv

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object AdBlocker {

    // Ad tracking and serving domains
    private val blockedDomains = arrayOf(
        "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "ad.youtube.com",
        "static.doubleclick.net"
    )

    // Ad path segments
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

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream("".toByteArray())
        )
    }

    /**
     * Combined JavaScript injection:
     * 1. Trusted Types compatibility
     * 2. In-page Fetch interceptor to strip adPlacements from /youtubei/v1/player
     * 3. CSS hiding of ad banners using textContent (Trusted Types safe)
     * 4. Multi-level video ad detector, speed-up & auto-skipper
     */
    val INJECTION_SCRIPT: String = """
        (function() {
            if (window.__adBlockerLoaded) return;
            window.__adBlockerLoaded = true;

            // 1. Setup Trusted Types policy if enforced
            if (window.trustedTypes && window.trustedTypes.createPolicy && !window.trustedTypes.defaultPolicy) {
                try {
                    window.trustedTypes.createPolicy('default', {
                        createHTML: function(s) { return s; },
                        createScript: function(s) { return s; },
                        createScriptURL: function(s) { return s; }
                    });
                } catch (e) {}
            }

            // 2. Hide ad containers using textContent (Safe with Trusted Types)
            try {
                const style = document.createElement('style');
                style.textContent = `
                    .ytp-ad-overlay-container,
                    .ytp-ad-message-container,
                    .video-ads,
                    .ytp-ad-progress,
                    .ytp-ad-progress-list,
                    #player-ads,
                    ytd-promoted-sparkles-web-renderer,
                    ytd-display-ad-renderer,
                    ytd-banner-promo-renderer,
                    .ytd-in-feed-ad-layout-renderer,
                    .ad-showing .html5-video-player {
                        display: none !important;
                        visibility: hidden !important;
                    }
                `;
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}

            // 3. Hook fetch to strip adPlacements directly from YouTube player responses
            try {
                const originalFetch = window.fetch;
                window.fetch = async function(...args) {
                    const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                    
                    if (url.includes('/pagead/') || url.includes('doubleclick.net') || url.includes('/api/stats/ads') || url.includes('/youtubei/v1/player/ad_break')) {
                        return new Response(JSON.stringify({}), {
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

            // 4. Continuous ad scanner & instant skipper
            let wasAdPlaying = false;
            let previousVolume = 1.0;

            function processAds() {
                try {
                    const video = document.querySelector('video');
                    const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');

                    const isAdShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads, [class*="ad-showing"]') !== null;
                    const isPlayerAd = player && typeof player.getAdState === 'function' && player.getAdState() === 1;

                    if ((isAdShowing || isPlayerAd) && video) {
                        if (!wasAdPlaying) {
                            wasAdPlaying = true;
                            previousVolume = video.muted ? 0 : video.volume;
                        }

                        // Mute audio instantly
                        video.muted = true;

                        // Blast past the ad at maximum playback speed
                        try {
                            video.playbackRate = 16.0;
                        } catch (e) {}

                        // Try built-in player skip function
                        if (player && typeof player.skipAd === 'function') {
                            try { player.skipAd(); } catch (e) {}
                        }

                        // Fast forward to end of ad
                        if (video.duration && isFinite(video.duration) && video.duration > 0) {
                            video.currentTime = video.duration;
                        } else {
                            video.currentTime = 999999;
                        }

                        // Click any skip button that appears
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
                        // Content video resumed: restore speed and audio
                        wasAdPlaying = false;
                        try {
                            video.playbackRate = 1.0;
                        } catch (e) {}
                        if (previousVolume > 0) {
                            video.muted = false;
                            video.volume = previousVolume;
                        }
                    }
                } catch (e) {}
            }

            setInterval(processAds, 150);
        })();
    """.trimIndent()
}
