package `in`.nulltheory.waypoint.ui

import `in`.nulltheory.waypoint.net.Place
import `in`.nulltheory.waypoint.net.Route

/** Which of the two points the single search box is standing for right now. */
enum class Field { ORIGIN, DESTINATION }

/**
 * The Activity's screen state machine. One search box, used twice: pick a start point, press
 * Directions, pick a destination, and the route is fetched on selection.
 *
 * IDLE -> ORIGIN_SET -> PICKING_DESTINATION -> ROUTING -> ROUTED -> RUNNING/PAUSED -> FINISHED
 *
 * Every transition is driven by a user action or a network result; nothing auto-advances
 * except RUNNING to FINISHED when the route is used up.
 */
sealed interface SimState {

    /** Nothing picked yet. The box searches for the start point. */
    data object Idle : SimState

    /** Start point chosen; the sheet offers Directions. */
    data object OriginSet : SimState

    /** The box has been handed over to the destination. */
    data object PickingDestination : SimState

    data object Routing : SimState

    data class Routed(val route: Route) : SimState

    /** Screen 3. The service owns the actual simulation; this is just which screen is up. */
    data class Live(val route: Route) : SimState

    data class Finished(val route: Route) : SimState

    /**
     * Which point the search box is standing for. Once the origin is locked in, the box and
     * the collapsed crumb above it keep describing the destination for the rest of the flow.
     */
    val field: Field
        get() = when (this) {
            Idle, OriginSet -> Field.ORIGIN
            else -> Field.DESTINATION
        }
}

/** The two endpoints, chosen by search or by long-pressing the map. */
data class Endpoints(val from: Place? = null, val to: Place? = null) {
    val complete: Boolean get() = from != null && to != null
}
