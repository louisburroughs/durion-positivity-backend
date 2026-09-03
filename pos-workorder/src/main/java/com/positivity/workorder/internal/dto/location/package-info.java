/**
 * Local mirrors of the pos-location bay and mobile-unit facts this module replicates (#1656).
 *
 * <p>These records belong in {@code pos-domain-events} alongside
 * {@code com.positivity.domainevents.location.LocationUpdatedV1}, where every other cross-domain
 * fact contract on {@code location.events.v1} lives. They are declared here only because
 * pos-location does not publish bay or mobile-unit facts yet, so there is no shared contract to
 * import: {@code LocationFactPublisher} emits {@code location.location.*} and
 * {@code location.storage-location.updated} and nothing else.
 *
 * <p>They are therefore the <em>consumer's</em> statement of the contract it needs — deliberately
 * a strict subset of the owner's aggregates (identity, name, site scope, lifecycle status), named and
 * shaped to the established {@code location.<entity>.<verb>} convention. When pos-location starts
 * publishing, these records move to {@code pos-domain-events} unchanged and the listener switches
 * to importing them; until then the listener simply never sees these event types, which is why it
 * tolerates their absence rather than treating an empty replica as an error.
 *
 * <p><strong>Provisional — traceable to
 * <a href="https://github.com/louisburroughs/durion-positivity-backend/issues/1668">issue #1668</a>,
 * the producer story.</strong> Event names and field names here are this module's guess at what
 * pos-location will publish; nothing has agreed them. That makes a wrong guess the likeliest way
 * this replica fails, so the guess is made falsifiable rather than silent: a payload missing the
 * identifier throws out of the record's compact constructor, and one missing the site scope is
 * refused by {@code LocationEventsListener#requireSiteScope}. Both are counted on
 * {@code replica.payload.rejected} and logged at ERROR, so a shape mismatch is distinguishable from
 * the expected "pos-location publishes nothing yet" silence instead of leaving a half-populated row
 * the roster query can never return. #1668 is where the shape gets agreed and these records move to
 * {@code pos-domain-events}.
 */
package com.positivity.workorder.internal.dto.location;
