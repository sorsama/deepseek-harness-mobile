package com.labteto.dshmobile.data

/**
 * Which question request each session is currently holding.
 *
 * One session holds at most one `ask_user_question` batch at a time — a second request replaces
 * the first, because that is what the host does — so the map is by session, while the identity
 * that settles a request is its `eventId`. Both directions are needed: an answer knows its
 * session, and a `cancel` frame knows only its event.
 *
 * The rule worth having a name for is [forget]. A session can be asked again the instant the last
 * request settles, and the new waterfall arrives on `$events` while the answer that settled the old
 * one is still in flight over HTTP — so by the time the receipt lands, the session's registration
 * may already belong to the *next* request. Forgetting by session alone would then delete a card
 * whose request is still pending on the host, with nothing left on screen able to answer it: the
 * stuck-card bug of 0.11.3 reintroduced from the other end. So a settled answer may only take away
 * the request it actually answered.
 *
 * Not thread-safe by design: [SessionStore] holds one lock over this, the session rows and the
 * card it draws from them, and they have to move together or a reader sees half a change. File-
 * level and free of Android so the rule is testable without standing up the whole store.
 */
internal class PendingQuestionRegistry {

    private val bySession = HashMap<String, String>()

    /** Record [sessionId]'s current request, replacing whatever it held before. */
    fun install(sessionId: String, eventId: String) {
        bySession[sessionId] = eventId
    }

    /** The request [sessionId] is holding, or null when it holds none. */
    fun eventFor(sessionId: String): String? = bySession[sessionId]

    /** The session holding [eventId], or null — the reverse lookup a `cancel` frame needs. */
    fun sessionFor(eventId: String): String? =
        bySession.entries.firstOrNull { it.value == eventId }?.key

    /**
     * Forget [sessionId]'s request, but only while it is still [eventId]'s.
     *
     * A null [eventId] asks the opposite question — "is this session still holding nothing?" — and
     * is how a card that outlived its registration is taken away without racing a replacement into
     * the same gap.
     *
     * @return true when the registration was this one and has been dropped; false when the session
     *   has moved on, in which case the caller must leave its card alone.
     */
    fun forget(sessionId: String, eventId: String?): Boolean {
        if (bySession[sessionId] != eventId) return false
        bySession.remove(sessionId)
        return true
    }

    /** Drop [sessionId] entirely: the session itself is gone, so nothing about it can be answered. */
    fun discard(sessionId: String) {
        bySession.remove(sessionId)
    }
}
