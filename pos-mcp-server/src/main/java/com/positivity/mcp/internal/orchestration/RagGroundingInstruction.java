package com.positivity.mcp.internal.orchestration;

/**
 * The grounding instruction prepended to the retrieved RAG snippets in the assistant's system prompt.
 *
 * <p>#1124/#1125: retrieved context must carry an explicit grounding instruction. Without one, the
 * model treats the numbered snippets as loose background rather than an authoritative source to
 * prioritize over its own trained knowledge — observed in the gap-harness alpha eval as invented
 * PO/GL identifier formats, and later as an entirely fabricated core-charge workflow (invented
 * catalog flags, workorder types, events, permissions, and approval thresholds) produced <em>even
 * though</em> the {@code order.returns-refunds} snippet — which documents core charges as NOT modeled
 * in pos-order and forbids inventing a workflow — was in this exact block.
 *
 * <p>The instruction therefore does four things:
 *
 * <ul>
 *   <li>requires answering directly and completely from the numbered snippets when they cover the
 *       question — the model must not claim it lacks a reference, ask the user for more detail, or
 *       defer to an external doc / another system / a UI link when the snippet already states the
 *       fact. #1124 item 4: the gap-harness alpha eval showed 6 of 7 generation failures were this
 *       over-refusal — e.g. the {@code glossary.identifiers} snippet (VIN regex) and the
 *       {@code order.returns-refunds} snippet were retrieved into this exact block, yet the model
 *       answered "I don't have a reference in the current knowledge base." The original wording led
 *       with prohibitions and buried the positive obligation, so the model treated "when in doubt,
 *       refuse" as the safe default even with the answer in front of it;
 *   <li>forbids stating <em>any</em> unsupported fact — not only identifier/format/ownership facts
 *       (the original wording was scoped to those and left workflows, capabilities, fields,
 *       permissions, events, and thresholds unguarded, which is how the core-charge workflow slipped
 *       through) — and constrains answers to the entities, fields, names, and values that actually
 *       appear in the snippets (the 7th failure fabricated a {@code PriceBook} entity and field set
 *       absent from the retrieved pricing snippets);
 *   <li>makes a documented non-existence binding: when a snippet says a capability or concept is not
 *       modeled/implemented/supported, the model must say so and must not invent a workflow,
 *       calculation, field, or value for it, nor offer to perform an action the snippets don't
 *       support;
 *   <li>keeps the "say what you don't know instead of inventing" fallback, scoped to when the
 *       snippets genuinely do not contain the fact.
 * </ul>
 *
 * <p>Shared by {@link SpringAiPosAssistant} and {@link SpringAiStreamingPosAssistant} so the
 * streaming and non-streaming chat paths ground identically (the streaming path previously carried
 * only the bare "Relevant retrieved context:" header with no grounding instruction).
 */
final class RagGroundingInstruction {

    static final String CONTEXT_PREFIX = """
            Relevant retrieved context:
            Ground your answer in the numbered snippets below when they cover the question, and treat \
            them as the authoritative source — prefer them over your own trained/parametric knowledge. \
            When the snippets do contain the answer, answer directly and completely from them: do not \
            claim you lack a reference or that it is not in the knowledge base, do not ask the user for \
            more detail, and do not defer to an external document, another system, or a UI link when \
            the snippets already state the fact. Use only the entities, fields, names, and values that \
            appear in the snippets. Do not state any workflow, capability, field, entity, permission, \
            event, threshold, identifier format, code pattern, or service ownership that is not \
            supported by these snippets. If a snippet says a capability or concept is not modeled, not \
            implemented, or not supported, treat that as authoritative: say the platform does not model \
            it, and do not invent a workflow, calculation, field, or value for it or offer to perform \
            an action the snippets do not support. Only when the snippets genuinely do not contain the \
            fact needed, say what you don't know instead of inventing or guessing a plausible-sounding \
            answer from general knowledge.""";

    private RagGroundingInstruction() {}
}
