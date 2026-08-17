/**
 * Capability ports — the SPI protocol adapters implement (ADR-0051, architecture doc §6).
 *
 * <h2>What a port is</h2>
 *
 * One narrow interface per business capability, taking a {@code SupplierRef} and canonical request
 * records, returning canonical result records. A port is the seam between "what this platform wants
 * from a supplier" and "how a particular vendor's protocol says it". Codecs
 * ({@link com.positivity.supplier.internal.spi.SupplierAdapterCodec}) are the <em>registration
 * unit</em> of the adapter registry, keyed by {@code (capability, protocolFamily, version)}
 * (ADR-0051 §3); ports are the <em>calling</em> unit.
 *
 * <h2>Adopted, after six capabilities went round them (#1349)</h2>
 *
 * These ports were declared by the CAP-317 foundation and then not used: stock inquiry, orders,
 * price catalogue, stock report, invoices and the marketing catalogue each routed through their own
 * {@code *Runner} and never referenced their port. CAP-323 was the first to implement one, and only
 * after discovering that the declared signature could not express its operation.
 *
 * <p>That is the pattern worth naming. <strong>An unimplemented signature is never tested against
 * reality, so it drifts into being unimplementable.</strong> Two of these had done so:
 *
 * <ul>
 *   <li>{@code SupplierWorkorderAuthorizationPort} took a {@code PartyContext} and a workorder id,
 *       with nowhere to say how a fleet vehicle is identified. Implementing it as written meant
 *       inventing a vehicle identifier out of a billing account number — which compiles, passes a
 *       unit test, and cannot work against a vendor.
 *   <li>Every port returned {@code SupplierExchange<T>}, whose {@code ExchangeMetadata} required a
 *       non-null exchange audit id. The audit write is best-effort by design — {@code
 *       ExchangeAuditObserver} deliberately swallows its own failures so a gap in the trail cannot
 *       fail an exchange — so that id may not exist. The wrapper was unimplementable in principle,
 *       not merely unimplemented.
 * </ul>
 *
 * <h2>So ports return canonical results directly</h2>
 *
 * The two things the wrapper was for are still available, and better placed:
 *
 * <ul>
 *   <li><strong>Finding the audit trail.</strong> Every exchange is stamped with a correlation id by
 *       {@code SupplierBaseClient} and every audit row carries it. A correlation id is always
 *       present, which is more than the audit id could promise.
 *   <li><strong>Unmapped vendor fields (ADR-0051 §4).</strong> Nothing ever populated the wrapper's
 *       copy of these, so removing it ends nothing that worked. The discipline itself is alive
 *       where it is actually used: {@code EdiwheelC11OrderStatusCodec} records the values it could
 *       not read on its own decoded result, and the caller logs them — which is what lets an
 *       operator diagnosing a missing delivery date learn the vendor did state one. Carrying a
 *       second, always-empty copy on every port's return value would have made that look solved
 *       everywhere while being solved in one place.
 * </ul>
 *
 * <h2>Rules</h2>
 *
 * Framework-clean by mandate: no Spring, JPA, or web types in this package, and no entities —
 * enforced by the module {@code ArchitectureTest}. A port signature that needs an entity is a port
 * describing bookkeeping rather than a capability. Adapters may depend on this package and on
 * {@code internal.domain.model}, never the reverse (ADR-0051 §2).
 *
 * <h2>Two things adoption changed, beyond adding {@code implements}</h2>
 *
 * <p><strong>Order creation has no port.</strong> There was one, and it was removed rather than
 * implemented. {@code OrderTransmissionDispatcher} claims a transmission intent
 * ({@code markDispatching}) <em>after</em> building the document and <em>before</em> sending it —
 * so that an intent is only claimed once its document is known to be buildable. A port covering
 * build-and-send would have to move that claim to one side or the other: claim earlier and a
 * document that cannot be built leaves an intent stuck in DISPATCHING; claim later and two instances
 * can send the same order. Order creation in this platform is not "send an order", it is "advance an
 * intent through its ledger", and that is orchestration rather than a protocol capability. A port
 * describing it would be fiction, which is the defect this whole exercise existed to end.
 *
 * <p><strong>{@code SupplierPriceCatalogPort} takes an import manifest id</strong>, which it should
 * not. {@code SupplierPriceCatalogEntry.importManifestId} is non-null, so an entry cannot exist
 * without one — this platform's import bookkeeping is baked into the canonical model of what a
 * vendor published. The parameter is documented as the leak it is rather than hidden by inventing an
 * id here, which would stamp entries with a run that never happened.
 *
 * <p>{@link com.positivity.supplier.internal.spi.TireIdentificationPort} is the one deliberate
 * exception to "every port is implemented": it is a placeholder pending confirmation that DOT
 * scanning is required (§12 decision 10), and {@code TireIdentificationDormancyTest} asserts it
 * stays that way. Every other port has exactly one implementation, and
 * {@code SupplierPortAdoptionTest} fails if that stops being true.
 */
package com.positivity.supplier.internal.spi;
