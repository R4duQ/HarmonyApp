package com.harmony.core.model

/**
 * User-facing mood filters from the spec. Each maps to a target region of
 * perceptual space (see MoodProfiles in :domain:shuffle); membership is a
 * score, not a boolean, so "Happy" playlists rank the happiest songs first
 * instead of applying an arbitrary cutoff.
 */
enum class MoodFilter {
    HAPPY, SAD, CALM, DARK, AGGRESSIVE, RELAXING, EPIC, EMOTIONAL, FOCUS, WORKOUT, PARTY
}
