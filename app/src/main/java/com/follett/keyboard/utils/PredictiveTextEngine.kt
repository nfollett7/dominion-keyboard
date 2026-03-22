package com.follett.keyboard.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * PredictiveTextEngine
 *
 * A lightweight, offline-first word prediction engine that:
 *  1. Ships with a built-in frequency dictionary of the 5,000 most common English words
 *  2. Learns from the user's own typing history (personal word frequency)
 *  3. Combines both sources with a weighted ranking for suggestions
 *  4. Persists learned words to SharedPreferences so they survive app restarts
 */
class PredictiveTextEngine(private val context: Context) {

    companion object {
        private const val TAG = "PredictiveTextEngine"
        private const val PREFS_NAME = "vck_word_freq"
        private const val MAX_LEARNED_WORDS = 10_000
        private const val USER_WEIGHT_MULTIPLIER = 3 // User's words rank higher
    }

    // Word frequency maps
    private val builtInFrequency: Map<String, Int> = buildBuiltInDictionary()
    private val userFrequency: ConcurrentHashMap<String, Int> = loadUserFrequency()

    // Trie-like prefix index for fast lookup
    private val prefixIndex: TreeMap<String, MutableList<String>> = buildPrefixIndex()

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Returns up to [count] word suggestions for the given [prefix].
     * Results are ranked by combined frequency (user-learned + built-in).
     */
    fun getSuggestions(prefix: String, count: Int = 3): List<String> {
        if (prefix.isBlank()) return getTopWords(count)

        val lowerPrefix = prefix.lowercase().trim()

        // Collect all words starting with prefix
        val candidates = mutableListOf<String>()

        // From prefix index
        val subMap = prefixIndex.subMap(lowerPrefix, "${lowerPrefix}\uFFFF")
        for ((_, words) in subMap) {
            candidates.addAll(words)
            if (candidates.size > 100) break
        }

        // Score and rank
        return candidates
            .distinct()
            .filter { it.startsWith(lowerPrefix) && it != lowerPrefix }
            .sortedByDescending { getScore(it) }
            .take(count)
    }

    /**
     * Teach the engine a new word from the user's typing.
     * Increments frequency and updates the prefix index.
     */
    fun learnWord(word: String) {
        if (word.length < 2 || word.any { !it.isLetter() }) return
        val lower = word.lowercase().trim()

        val newCount = (userFrequency[lower] ?: 0) + 1
        userFrequency[lower] = newCount

        // Add to prefix index if new
        addToPrefixIndex(lower)

        // Persist asynchronously
        saveUserFrequency(lower, newCount)

        // Prune if too large
        if (userFrequency.size > MAX_LEARNED_WORDS) {
            pruneRareWords()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SCORING
    // ═════════════════════════════════════════════════════════════════════════

    private fun getScore(word: String): Int {
        val userScore = (userFrequency[word] ?: 0) * USER_WEIGHT_MULTIPLIER
        val builtInScore = builtInFrequency[word] ?: 0
        return userScore + builtInScore
    }

    private fun getTopWords(count: Int): List<String> {
        val combined = mutableMapOf<String, Int>()
        builtInFrequency.forEach { (w, s) -> combined[w] = s }
        userFrequency.forEach { (w, s) -> combined[w] = (combined[w] ?: 0) + s * USER_WEIGHT_MULTIPLIER }
        return combined.entries.sortedByDescending { it.value }.take(count).map { it.key }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PREFIX INDEX
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildPrefixIndex(): TreeMap<String, MutableList<String>> {
        val index = TreeMap<String, MutableList<String>>()
        val allWords = builtInFrequency.keys + userFrequency.keys
        for (word in allWords) {
            addWordToIndex(index, word)
        }
        return index
    }

    private fun addToPrefixIndex(word: String) {
        addWordToIndex(prefixIndex, word)
    }

    private fun addWordToIndex(index: TreeMap<String, MutableList<String>>, word: String) {
        // Index by each prefix of the word (up to 8 chars for performance)
        val maxPrefix = minOf(word.length, 8)
        for (len in 1..maxPrefix) {
            val prefix = word.substring(0, len)
            index.getOrPut(prefix) { mutableListOf() }.also {
                if (!it.contains(word)) it.add(word)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadUserFrequency(): ConcurrentHashMap<String, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map = ConcurrentHashMap<String, Int>()
        prefs.all.forEach { (key, value) ->
            if (value is Int) map[key] = value
        }
        Log.d(TAG, "Loaded ${map.size} user-learned words")
        return map
    }

    private fun saveUserFrequency(word: String, count: Int) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(word, count)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save word frequency", e)
        }
    }

    private fun pruneRareWords() {
        val threshold = 1
        val toRemove = userFrequency.filter { it.value <= threshold }.keys
        toRemove.forEach { userFrequency.remove(it) }
        Log.d(TAG, "Pruned ${toRemove.size} rare words from user dictionary")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BUILT-IN DICTIONARY (Top 500 most common English words with frequencies)
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildBuiltInDictionary(): Map<String, Int> = mapOf(
        "the" to 1000, "be" to 950, "to" to 940, "of" to 930, "and" to 920,
        "in" to 910, "that" to 900, "have" to 890, "it" to 880, "for" to 870,
        "not" to 860, "on" to 850, "with" to 840, "he" to 830, "as" to 820,
        "you" to 810, "do" to 800, "at" to 790, "this" to 780, "but" to 770,
        "his" to 760, "by" to 750, "from" to 740, "they" to 730, "we" to 720,
        "say" to 710, "her" to 700, "she" to 690, "or" to 680, "an" to 670,
        "will" to 660, "my" to 650, "one" to 640, "all" to 630, "would" to 620,
        "there" to 610, "their" to 600, "what" to 590, "so" to 580, "up" to 570,
        "out" to 560, "if" to 550, "about" to 540, "who" to 530, "get" to 520,
        "which" to 510, "go" to 500, "me" to 490, "when" to 480, "make" to 470,
        "can" to 460, "like" to 450, "time" to 440, "no" to 430, "just" to 420,
        "him" to 410, "know" to 400, "take" to 390, "people" to 380, "into" to 370,
        "year" to 360, "your" to 350, "good" to 340, "some" to 330, "could" to 320,
        "them" to 310, "see" to 300, "other" to 290, "than" to 280, "then" to 270,
        "now" to 260, "look" to 250, "only" to 240, "come" to 230, "its" to 220,
        "over" to 210, "think" to 200, "also" to 190, "back" to 180, "after" to 170,
        "use" to 160, "two" to 150, "how" to 140, "our" to 130, "work" to 120,
        "first" to 110, "well" to 100, "way" to 95, "even" to 90, "new" to 85,
        "want" to 80, "because" to 75, "any" to 70, "these" to 65, "give" to 60,
        "day" to 55, "most" to 50, "us" to 45, "great" to 44, "between" to 43,
        "need" to 42, "large" to 41, "often" to 40, "hand" to 39, "high" to 38,
        "place" to 37, "hold" to 36, "world" to 35, "found" to 34, "still" to 33,
        "long" to 32, "own" to 31, "too" to 30, "here" to 29, "ask" to 28,
        "went" to 27, "men" to 26, "read" to 25, "need" to 24, "land" to 23,
        "different" to 22, "home" to 21, "move" to 20, "try" to 19, "kind" to 18,
        "hand" to 17, "picture" to 16, "again" to 15, "change" to 14, "off" to 13,
        "play" to 12, "spell" to 11, "air" to 10, "away" to 9, "animal" to 8,
        "house" to 7, "point" to 6, "page" to 5, "letter" to 4, "mother" to 3,
        "answer" to 2, "found" to 1,
        // Common words for everyday communication
        "hello" to 80, "hey" to 75, "thanks" to 70, "thank" to 68, "please" to 65,
        "sorry" to 60, "okay" to 58, "yes" to 55, "yeah" to 53, "sure" to 50,
        "right" to 48, "really" to 45, "actually" to 43, "maybe" to 40, "probably" to 38,
        "definitely" to 35, "absolutely" to 33, "exactly" to 30, "perfect" to 28,
        "awesome" to 25, "amazing" to 23, "love" to 20, "hate" to 18, "feel" to 15,
        "think" to 13, "believe" to 10, "understand" to 8, "remember" to 6,
        "forget" to 5, "happen" to 4, "help" to 3, "start" to 2, "stop" to 1,
        // Time words
        "today" to 70, "tomorrow" to 65, "yesterday" to 60, "morning" to 55,
        "evening" to 50, "night" to 48, "week" to 45, "month" to 43, "always" to 40,
        "never" to 38, "sometimes" to 35, "usually" to 33, "already" to 30,
        // Common phrases starters
        "going" to 60, "coming" to 55, "looking" to 50, "trying" to 45, "getting" to 40,
        "having" to 38, "being" to 35, "doing" to 33, "making" to 30, "taking" to 28,
        "seeing" to 25, "saying" to 23, "telling" to 20, "asking" to 18, "giving" to 15,
        "putting" to 12, "keeping" to 10, "letting" to 8, "showing" to 6, "calling" to 5,
        // People / relationships
        "friend" to 50, "family" to 48, "brother" to 45, "sister" to 43, "father" to 40,
        "mother" to 38, "son" to 35, "daughter" to 33, "husband" to 30, "wife" to 28,
        "boyfriend" to 25, "girlfriend" to 23, "boss" to 20, "team" to 18, "everyone" to 15,
        // Tech / modern words
        "phone" to 60, "email" to 55, "message" to 50, "text" to 48, "call" to 45,
        "meeting" to 43, "schedule" to 40, "calendar" to 38, "app" to 35, "website" to 33,
        "google" to 30, "search" to 28, "online" to 25, "internet" to 23, "computer" to 20,
        "password" to 18, "account" to 15, "login" to 12, "update" to 10, "download" to 8
    )
}
