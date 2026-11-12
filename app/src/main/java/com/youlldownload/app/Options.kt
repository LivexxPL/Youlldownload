package com.youlldownload.app

enum class OutFormat(val label: String, val ext: String, val mime: String, val isAudio: Boolean) {
    MP4("MP4 (video)", "mp4", "video/mp4", false),
    WEBM("WEBM (video)", "webm", "video/webm", false),
    MKV("MKV (video)", "mkv", "video/x-matroska", false),
    MP3("MP3 (audio only)", "mp3", "audio/mpeg", true),
    M4A("M4A (audio only)", "m4a", "audio/mp4", true),
}

/** Max height ("up to"). Sites that don't offer the exact height get the closest lower one. */
enum class Resolution(val label: String, val height: Int?) {
    BEST("Best available", null),
    P2160("2160p (4K)", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
}

/** "Under 10 MB": 10,000,000 bytes, which also fits 10 MiB limits. */
const val SIZE_LIMIT_BYTES = 10_000_000L
