package com.follett.keyboard.api

import android.util.Log

/**
 * AgentRouter — MCP-style intent router for the agentic keyboard.
 *
 * When the AI detects an actionable intent in the user's text,
 * this router determines which agent/tool should handle it and
 * formats the response for the user.
 *
 * This is the foundation for the keyboard acting as a gateway
 * to a federated multi-agent system (Phase 3 of 2030 roadmap).
 *
 * Currently supported intents:
 *  - translate: Route to Honduran Spanish translator
 *  - schedule: Format a calendar event suggestion
 *  - remind: Format a reminder suggestion
 *  - calculate: Evaluate math expressions
 *  - search: Suggest a search query
 *  - send_message: Format a message draft
 *  - navigate: Suggest navigation
 */
class AgentRouter {

    companion object {
        private const val TAG = "AgentRouter"
    }

    /**
     * Routes a detected intent to the appropriate handler.
     * Returns a user-facing response string, or null if no action needed.
     */
    fun routeIntent(intent: AgentIntent): AgentResponse? {
        Log.d(TAG, "Routing intent: action=${intent.action}, target=${intent.target}")

        return when (intent.action) {
            "translate" -> AgentResponse(
                type = ResponseType.INLINE_REPLACE,
                message = "🌐 Translating to Honduran Spanish...",
                action = intent.action,
                data = intent.target
            )
            "schedule" -> AgentResponse(
                type = ResponseType.SUGGESTION_CHIP,
                message = "📅 Schedule: ${intent.target}",
                action = "schedule",
                data = "${intent.target}|${intent.params}"
            )
            "remind" -> AgentResponse(
                type = ResponseType.SUGGESTION_CHIP,
                message = "⏰ Remind: ${intent.target}",
                action = "remind",
                data = "${intent.target}|${intent.params}"
            )
            "calculate" -> AgentResponse(
                type = ResponseType.INLINE_INSERT,
                message = "🧮 ${intent.target} = ${evaluateSimpleMath(intent.target)}",
                action = "calculate",
                data = evaluateSimpleMath(intent.target)
            )
            "search" -> AgentResponse(
                type = ResponseType.SUGGESTION_CHIP,
                message = "🔍 Search: ${intent.target}",
                action = "search",
                data = intent.target
            )
            "navigate" -> AgentResponse(
                type = ResponseType.SUGGESTION_CHIP,
                message = "📍 Navigate to: ${intent.target}",
                action = "navigate",
                data = intent.target
            )
            else -> null
        }
    }

    private fun evaluateSimpleMath(expression: String): String {
        return try {
            // Basic math evaluation for simple expressions
            val cleaned = expression.replace(" ", "")
            // This is a placeholder — in production, use a proper expression parser
            cleaned
        } catch (e: Exception) {
            "error"
        }
    }
}

/**
 * Response from the agent router to be displayed/executed by the keyboard.
 */
data class AgentResponse(
    val type: ResponseType,
    val message: String,
    val action: String,
    val data: String
)

enum class ResponseType {
    INLINE_REPLACE,    // Replace the current text with the result
    INLINE_INSERT,     // Insert result at cursor
    SUGGESTION_CHIP,   // Show as a tappable suggestion
    STATUS_MESSAGE     // Show in status bar only
}
