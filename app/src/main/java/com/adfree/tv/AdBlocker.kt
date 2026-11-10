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

    /**
     * Checks if a network request should be blocked.
     */
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
     * Returns an empty response to neutralize blocked requests cleanly.
     */
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
     * 1. In-page Fetch/XHR interceptor
     * 2. Video ad speed-up & auto-skipper
     * 3. CSS banner & promo hider
     */
    val INJECTION_SCRIPT: String = """
        (function() {
            if (window.__adBlockerLoaded) return;
            window.__adBlockerLoaded = true;

            // 1. Inject CSS to hide all ad overlays, banners, and companion cards
            const style = document.createElement('style');
            style.innerHTML = `
                .ytp-ad-overlay-container,
                .ytp-ad-message-container,
                .video-ads,
                .ytp-ad-progress,
                .ytp-ad-progress-list,
                #player-ads,
                ytd-promoted-sparkles-web-renderer,
                ytd-display-ad-renderer,
                ytd-banner-promo-renderer,
                .ytd-in-feed-ad-layout-renderer {
                    display: none !important;
                    visibility: hidden !important;
                    opacity: 0 !important;
                    pointer-events: none !important;
                }
            `;
            document.head ? document.head.appendChild(style) : document.documentElement.appendChild(style);

            // 2. Continuous ad detector and skipper
            let wasAdPlaying = false;
            let previousVolume = 1.0;

            function processAds() {
                const video = document.querySelector('video');
                const adPlayerShowing = document.querySelector('.ad-showing, .ad-interrupting');
                
                if (adPlayerShowing && video) {
                    if (!wasAdPlaying) {
                        wasAdPlaying = true;
                        previousVolume = video.muted ? 0 : video.volume;
                    }

                    // Mute audio during ad
                    video.muted = true;

                    // Accelerate playback speed to blow past the ad
                    try {
                        video.playbackRate = 16.0;
                    } catch (e) {}

                    // Jump directly to the end of the ad clip
                    if (isFinite(video.duration) && video.duration > 0) {
                        video.currentTime = video.duration;
                    }

                    // Click any skip button that becomes visible
                    const skipSelectors = [
                        '.ytp-ad-skip-button',
                        '.ytp-skip-ad-button',
                        '.ytp-ad-skip-button-modern',
                        '.videoAdUiSkipButton',
                        'button[class*="skip-button"]',
                        'button[id*="skip"]'
                    ];

                    for (const selector of skipSelectors) {
                        const btn = document.querySelector(selector);
                        if (btn) {
                            btn.click();
                            break;
                        }
                    }
                } else if (wasAdPlaying && video) {
                    // Video ad finished: restore normal state
                    wasAdPlaying = false;
                    video.playbackRate = 1.0;
                    if (previousVolume > 0) {
                        video.muted = false;
                        video.volume = previousVolume;
                    }
                }
            }

            // Run check every 200ms
            setInterval(processAds, 200);

            // 3. Network hooks in JavaScript context
            const originalFetch = window.fetch;
            window.fetch = function(...args) {
                const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                if (url.includes('/pagead/') || url.includes('doubleclick.net') || url.includes('/api/stats/ads')) {
                    return Promise.resolve(new Response('', { status: 200 }));
                }
                return originalFetch.apply(this, args);
            };
        })();
    """.trimIndent()
}
