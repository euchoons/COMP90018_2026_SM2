package au.edu.unimelb.floraguide.ui.components

import au.edu.unimelb.floraguide.domain.model.Observation

internal data class ObservationMapPoint(val latitude: Double, val longitude: Double)

/**
 * Coarsened coordinates can coincide. Keep every observation accessible at that location.
 * Re-coarsening keeps pins on the storage grid even for imported or synced records with finer coordinates.
 */
internal fun observationMapLocations(
    observations: List<Observation>,
): Map<ObservationMapPoint, List<Observation>> = observations
    .filter {
        it.coarseLocation.latitude in -90.0..90.0 &&
            it.coarseLocation.longitude in -180.0..180.0
    }
    .groupBy { observation ->
        observation.coarseLocation.coarsened().let { ObservationMapPoint(it.latitude, it.longitude) }
    }
