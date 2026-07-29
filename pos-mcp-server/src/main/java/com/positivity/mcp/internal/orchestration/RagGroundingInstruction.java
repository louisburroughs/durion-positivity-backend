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
 * <p>The instruction therefore does three things:
 *
 * <ul>
 *   <li>requires answering from the numbered snippets when they cover the question, and forbids
 *       stating <em>any</em> unsupported fact — not only identifier/format/ownership facts (the
 *       original wording was scoped to those and left workflows, capabilities, fields, permissions,
 *       events, and thresholds unguarded, which is how the core-charge workflow slipped through);
 *   <li>makes a documented non-existence binding: when a snippet says a capability or concept is not
 *       modeled/implemented/supported, the model must say so and must not invent a workflow,
 *       calculation, field, or value for it, nor offer to perform an action the snippets don't
 *       support;
 *   <li>keeps the "say what you don't know instead of inventing" fallback for facts the snippets
 *       don't contain.
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
            Do not state any workflow, capability, field, entity, permission, event, threshold, \
            identifier format, code pattern, or service ownership that is not supported by these \
            snippets. If a snippet says a capability or concept is not modeled, not implemented, or \
            not supported, treat that as authoritative: say the platform does not model it, and do \
            not invent a workflow, calculation, field, or value for it or offer to perform an action \
            the snippets do not support. If the snippets don't contain the fact needed, say what you \
            don't know instead of inventing or guessing a plausible-sounding answer from general \
            knowledge.""";

    private RagGroundingInstruction() {}
}
