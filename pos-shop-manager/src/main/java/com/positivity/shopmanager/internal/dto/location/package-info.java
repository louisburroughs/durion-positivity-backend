/**
 * Local mirrors of the pos-location bay and mobile-unit facts this module replicates (#1658).
 *
 * <p>These records belong in {@code pos-domain-events} alongside
 * {@code com.positivity.domainevents.location.LocationUpdatedV1}, where every other cross-domain
 * fact contract on {@code location.events.v1} lives. They are declared here only because
 * pos-location does not publish bay or mobile-unit facts yet, so there is no shared contract to
 * import: its {@code LocationFactPublisher} emits {@code location.location.*} and
 * {@code location.storage-location.updated} and nothing else.
 *
 * <p>They are therefore the <em>consumer's</em> statement of the contract it needs — a strict
 * subset of the owner's aggregates (identity, name, site scope, active flag), named and shaped to
 * the established {@code location.<entity>.<verb>} convention, and byte-identical to the mirror
 * pos-workorder declared for the same facts in #1656 so both modules move to the shared contract
 * in one step. When pos-location starts publishing, these records move to
 * {@code pos-domain-events} unchanged and the listener switches to importing them; until then the
 * listener simply never sees these event types, which is why it tolerates their absence rather
 * than treating an empty replica as an error.
 */
package com.positivity.shopmanager.internal.dto.location;
