package com.jeevavibeapp.spendwise.core

/**
 * The two ways into the ledger that are not automatic capture — typing a
 * transaction and pasting a bank message — and how loudly each is offered.
 *
 * How prominent pasting should be depends on the build. In the no-SMS build,
 * the one that installs where Play Protect refuses SMS permissions, pasting
 * is how bank data arrives at all, and a route three screens deep in Settings
 * is one most people never find. In the SMS build it is the exception — a
 * sender the app does not recognise, a message older than the inbox scan
 * reaches, one forwarded from another phone — so it is offered from the add
 * sheet, where someone already adding by hand is looking, rather than as one
 * more thing on a Home screen the ledger already fills.
 *
 * Nothing here reads anything. The screens say which state they are in and
 * draw what comes back, so the rule that no sentence on an empty ledger
 * promises capture that is not happening is checked on a JVM.
 */
object ManualEntry {

    /** Whether bank messages reach the ledger without the user. */
    enum class Capture {
        /** The SMS build, with the permission held. */
        AUTOMATIC,

        /** The SMS build, with the permission refused or since revoked. */
        OFF,

        /** The no-SMS build: there is no permission to grant. */
        NONE,
    }

    /**
     * A way in, with the words its button carries. One label per route on
     * every screen, so the button someone found on Home is recognisably the
     * one in Settings, and TalkBack reads the same thing in both places.
     */
    enum class Route(val label: String) {
        PASTE("Paste a bank message"),
        BY_HAND("Enter by hand"),
    }

    /**
     * What a ledger with nothing in it says, and the ways in it offers.
     * The first route is the primary one; the screen draws it as the filled
     * button and the rest as quieter ones under it.
     */
    data class Empty(val body: String, val routes: List<Route>)

    /**
     * Whether Home carries a paste control of its own once the ledger has
     * something in it. Only where pasting is the build's capture: elsewhere
     * the add sheet offers it, two taps from Home, without a permanent card.
     */
    fun pasteOnHome(capture: Capture): Boolean = capture == Capture.NONE

    /**
     * The empty ledger's sentence and buttons.
     *
     * Paste leads wherever nothing is captured automatically: one copied
     * message is less typing than the form, and it is read by the same
     * parser a captured message is.
     */
    fun emptyLedger(capture: Capture): Empty = when (capture) {
        Capture.AUTOMATIC -> Empty(
            "Bank messages are read on this device and filed here as they arrive. " +
                "You can also enter one yourself.",
            listOf(Route.BY_HAND),
        )
        Capture.OFF -> Empty(
            "SMS access is off, so nothing is filed by itself. Paste a bank message " +
                "in and SpendWise reads it for you to check, or enter one yourself.",
            listOf(Route.PASTE, Route.BY_HAND),
        )
        Capture.NONE -> Empty(
            "This copy of SpendWise doesn't read your messages. Paste one from your " +
                "bank and it reads the amount, payee and date for you to check, or " +
                "enter one yourself.",
            listOf(Route.PASTE, Route.BY_HAND),
        )
    }
}
