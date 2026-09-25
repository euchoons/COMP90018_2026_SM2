package au.edu.unimelb.floraguide.ui.components

import au.edu.unimelb.floraguide.domain.model.Observation

internal data class ObservationMapPoint(val latitude: Double, val longitude: Double)

/** Coarsened coordinates can coincide. Keep every observation accessible at that location. */
internal fun observationMapLocations(
    observations: List<Observation>,
): Map<ObservationMapPoint, List<Observation>> = observations
    .filter {
        it.coarseLocation.latitude in -90.0..90.0 &&
            it.coarseLocation.longitude in -180.0..180.0
    }
    .groupBy { ObservationMapPoint(it.coarseLocation.latitude, it.coarseLocation.longitude) }
