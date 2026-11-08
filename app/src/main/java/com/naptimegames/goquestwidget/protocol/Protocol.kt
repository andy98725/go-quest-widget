package com.naptimegames.goquestwidget.protocol

import org.json.JSONObject

/**
 * GoQuest's game server protocol, as used by the current Android app (captured Nov 2026):
 *
 *  - Plain TCP to [DEFAULT_HOST]:[DEFAULT_PORT]; one message per line: "<name> <json>\n".
 *  - The client opens with `start {"gtype":"go9",...}`; the server answers with
 *    `start {"api":{<obfuscated name>: <8-hex id>, ...}, "minVersion": ...}`. Every later
 *    message uses those ids as its name.
 *  - The api keys are the real names under a fixed letter substitution ("Q40A" is "live").
 *    Ids are looked up from the table at runtime, with the captured ids as fallbacks.
 */
object Protocol {
    const val DEFAULT_HOST = "ec2-54-229-59-237.eu-west-1.compute.amazonaws.com"
    const val DEFAULT_PORT = 4000

    const val START = "start"

    /** The messages the widget uses: obfuscated api key and the id it mapped to when captured. */
    enum class Api(val key: String, val fallbackId: String) {
        /** C->S {"gtype","page"}: subscribe to the live-games list; S->C list updates. */
        LIVE("Q40A", "b878053d"),
        /** C->S {"gtype","page"}: unsubscribe from the live-games list. */
        LIVE_STOP("Q40AFwDIa", "a054e65d"),
        /** C->S {"id"}: start spectating; S->C full game state. */
        GAME("JlMA", "ba276087"),
        /** C->S {"id"}: stop spectating. */
        GAME_STOP("JlMAFwDIa", "35fb6f17"),
        /** S->C full game state after each move. */
        GAME_POSITION("JlMAvaIw4D4Ii", "8d7a2124"),
        /** S->C final result with rating changes. */
        GAME_RESULT("JlMAvKAwfQD", "1f566a1a"),
        /** S->C {"id","num"} spectator count. */
        GAME_WATCHERS("JlMAvslDk9AKw", "d40a7297"),
        /** S->C lobby stats every few seconds. */
        SERVER_STATUS("wAK0AKvwDlDfw", "f19683a8"),
    }

    /** Resolves message ids from the server's api table. */
    class ApiTable(private val ids: Map<Api, String> = Api.entries.associateWith { it.fallbackId }) {
        private val byId = ids.entries.associate { (k, v) -> v to k }
        fun id(api: Api): String = ids.getValue(api)
        fun lookup(id: String): Api? = byId[id]

        companion object {
            fun fromStart(start: JSONObject): ApiTable {
                val api = start.optJSONObject("api") ?: return ApiTable()
                return ApiTable(Api.entries.associateWith { api.optString(it.key).ifEmpty { it.fallbackId } })
            }
        }
    }

    data class Message(val name: String, val body: JSONObject)

    fun parseLine(line: String): Message? {
        val trimmed = line.trimEnd('\r')
        if (trimmed.isBlank()) return null
        val space = trimmed.indexOf(' ')
        val name = if (space < 0) trimmed else trimmed.substring(0, space)
        val body = if (space < 0) JSONObject() else runCatching { JSONObject(trimmed.substring(space + 1)) }.getOrNull() ?: return null
        return Message(name, body)
    }

    fun line(name: String, body: JSONObject): String = "$name $body\n"

    /** Empty line: the official client sends one as a keep-alive. */
    const val KEEPALIVE = "\n"
}
