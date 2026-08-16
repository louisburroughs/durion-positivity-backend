/**
 * Michelin S2S workorder authorization adapter (CAP-323 #1229) — the second protocol family.
 *
 * <h2>Why this package is the reusability proof</h2>
 *
 * Every other adapter in this module speaks EDIWheel. This one does not: it is JSON REST over
 * OAuth2 client credentials, with an asynchronous acceptance pattern no EDIWheel norm uses. If
 * adding it required changes to the orchestration, the registry or the consuming domains, then the
 * claim that a new vendor costs "an adapter plus configuration" was false. So the interesting output
 * of this package is not the code but the list of things outside it that had to change — which is
 * asserted, not asserted-to-be-empty, by {@code MichelinS2SReusabilityTest}.
 *
 * <h2>What the source spec does and does not define</h2>
 *
 * {@code durion/docs/ediwheel/ediwheel-workorder-michelin-implementation_1.json} is an
 * implementation note, not a contract. It defines the operations, the {@code partnerId} query
 * convention, the {@code API-Version} header and the 201/202 + {@code Location} acceptance pattern.
 * It defines the vehicle, contract and policy <em>response</em> shapes.
 *
 * <p>It does <strong>not</strong> define the workorder request body: the spec types it as
 * {@code JsonNode}, which is to say "some JSON". Nor does it give response schemas for the workorder
 * operations. The request shape built here is therefore derived from the EDIWheel party/vehicle/
 * reference vocabulary the same spec does define, and it is a best guess until a sandbox corrects
 * it. Every field that is a guess is marked as one at its declaration in {@code WorkorderS2SWire},
 * so a first sandbox run is a matter of fixing named fields rather than re-reading a vendor's mind.
 *
 * <h2>Decoding is defensive on purpose</h2>
 *
 * Because the response shapes are unspecified, the decoder reads what it recognises and reports what
 * it cannot rather than failing on an unexpected field. A vendor adding a field must not break an
 * authorization; a vendor omitting the authorization id must not be silently read as a grant with no
 * id, because that grant cannot be approved on completion later.
 */
package com.positivity.supplier.internal.adapter.michelins2s;
