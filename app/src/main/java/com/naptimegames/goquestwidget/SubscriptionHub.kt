package com.naptimegames.goquestwidget

import com.naptimegames.goquestwidget.protocol.Protocol.Api
import org.json.JSONObject

/**
 * Lets several [SpectatorController]s share one connection. Each controller "sends" through
 * [sender]; the hub counts who wants each game and each live list, so one controller
 * stopping can't cut off another:
 *
 *  - `game` is always forwarded (it also re-requests the full state for a newcomer);
 *    `game_stop` only goes out when the last owner lets go.
 *  - `live` is always forwarded (a fresh list); `live_stop` only when no one else wants
 *    that board type's list.
 */
class SubscriptionHub(private val send: (Api, JSONObject) -> Unit) {
    private val gameOwners = HashMap<String, MutableSet<Any>>()
    private val liveOwners = HashMap<String, MutableSet<Any>>()

    /** The send function to hand to the controller identified by [owner]. */
    fun sender(owner: Any): (Api, JSONObject) -> Unit = { api, body -> route(owner, api, body) }

    private fun route(owner: Any, api: Api, body: JSONObject) {
        when (api) {
            Api.GAME -> {
                gameOwners.getOrPut(body.optString("id")) { HashSet() }.add(owner)
                send(api, body)
            }
            Api.GAME_STOP -> release(gameOwners, body.optString("id"), owner) { send(api, body) }
            Api.LIVE -> {
                liveOwners.getOrPut(body.optString("gtype")) { HashSet() }.add(owner)
                send(api, body)
            }
            Api.LIVE_STOP -> release(liveOwners, body.optString("gtype"), owner) { send(api, body) }
            else -> send(api, body)
        }
    }

    private fun release(map: HashMap<String, MutableSet<Any>>, key: String, owner: Any, sendStop: () -> Unit) {
        val owners = map[key] ?: return
        if (!owners.remove(owner)) return
        if (owners.isEmpty()) {
            map.remove(key)
            sendStop()
        }
    }

    /** The server forgets everything when the connection drops. */
    fun reset() {
        gameOwners.clear()
        liveOwners.clear()
    }
}
