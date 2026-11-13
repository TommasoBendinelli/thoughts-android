/* History payload logic from proposal-review 0.3.0; platform storage is native. */
function createReviewHistory(state, platform) {
  const { correspondingPrefix } = require("./partialReview");
  const digest = (text) => platform.sha256(text);
  const uuid = () => platform.uuid();
  const read = () =>
    state.events
      .slice()
      .sort(
        (a, b) =>
          a.observed_at.localeCompare(b.observed_at) ||
          a.event_id.localeCompare(b.event_id),
      );
  const append = (_, event) => {
    checkSize(event);
    state.events.push(event);
  };
  const snapshot = (_, filename, p) => ({
    proposal_id: p.id,
    source_file: filename,
    comment: p.reason,
    before: p.oldText,
    after: p.newText,
    ...(p.createdAt ? {created_at: p.createdAt} : {}),
    ...(p.conversationId ? {conversation_id: p.conversationId} : {}),
    ...(p.conversationUrl ? {conversation_url: p.conversationUrl} : {}),
  });
  const checkSize = (event) => {
    if (
      new TextEncoder().encode(JSON.stringify(event, null, 1)).length >
      1024 * 1024
    )
      throw Error("This review exceeds the 1 MiB history limit.");
  };
  function makeEvent(kind, snap, beforeHash, afterHash, fields = {}) {
    const now = new Date().toISOString();
    return {
      schema_version: 1,
      event_id: uuid(),
      proposal_id: snap.proposal_id,
      kind,
      observed_at: now,
      updated_at: now,
      authority: "local_source",
      source_operation_id: "",
      source_before_sha256: beforeHash,
      source_after_sha256: afterHash,
      snapshot: snap,
      revision: 1,
      deleted: false,
      producer: "android-proposal-review",
      ...fields,
    };
  }
  function baseline(root, filename, proposal, source, knownEvents = null) {
    const events = (knownEvents || read(root)).filter(
      (e) => e.proposal_id === proposal.id,
    );
    const first =
      events.find((e) => ["first_seen", "created"].includes(e.kind)) ||
      events[0];
    if (first) return first;
    const hash = digest(source);
    const event = makeEvent(
      "first_seen",
      snapshot(root, filename, proposal),
      hash,
      hash,
      {
        baseline_evidence: "first_observed",
        original_agent_output_verified: false,
        context: {
          document_at_observation: source,
          generation_instruction: null,
          conversation_ids:
            proposal.reason.match(
              /[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/g,
            ) || [],
        },
      },
    );
    append(root, event);
    if (knownEvents && knownEvents !== state.events) knownEvents.push(event);
    return event;
  }
  function activeDecisions(events) {
    const undone = new Set();
    for (const e of events) {
      if (e.kind === "review_undone") undone.add(e.related_event_id);
      if (e.kind === "review_redone") undone.delete(e.related_event_id);
    }
    return events.filter((e) => e.review && !undone.has(e.event_id));
  }
  function prepare(root, filename, proposal, source, plan) {
    const original = baseline(root, filename, proposal, source);
    const events = read(root).filter((e) => e.proposal_id === proposal.id);
    const previous = activeDecisions(events).at(-1);
    // Do not reset unknown correspondence after a prior ambiguous partial action.
    const remainingOriginal = previous
      ? previous.review.original_remaining_after
      : original.snapshot;
    const originalNew = remainingOriginal
      ? correspondingPrefix(
          remainingOriginal.after,
          proposal.newText,
          plan.newEnd,
        )
      : null;
    const originalOld = remainingOriginal
      ? correspondingPrefix(
          remainingOriginal.before,
          proposal.oldText,
          plan.oldEnd,
        )
      : null;
    const reviewedNew = proposal.newText.slice(0, plan.newEnd);
    const reviewedOld = proposal.oldText.slice(0, plan.oldEnd);
    const afterSource =
      source.slice(0, proposal.start) +
      plan.replacement +
      source.slice(proposal.end);
    const event = makeEvent(
      plan.partial
        ? plan.decision === "accept"
          ? "partially_accepted"
          : "partially_rejected"
        : plan.decision === "accept"
          ? "accepted"
          : "rejected",
      snapshot(root, filename, proposal),
      digest(source),
      digest(afterSource),
      {
        source_state: "editor_buffer",
        decision_at: new Date().toISOString(),
        review: {
          action: plan.decision,
          partial: plan.partial,
          offset_unit: "utf16",
          selected_new_range: [0, plan.newEnd],
          selected_old_range: [0, plan.oldEnd],
          selected_proposed_text: reviewedNew,
          selected_existing_text: reviewedOld,
          accepted_text: plan.decision === "accept" ? reviewedNew : null,
          rejected_text: plan.decision === "reject" ? reviewedNew : null,
          retained_text: plan.decision === "reject" ? reviewedOld : reviewedNew,
          remaining: plan.remaining,
          baseline_event_id: original.event_id,
          baseline_evidence: original.baseline_evidence || "first_recorded",
          original_agent_output_verified:
            original.original_agent_output_verified === true,
          original_snapshot: original.snapshot,
          original_corresponding_text: originalNew?.text ?? null,
          correspondence: originalNew?.method ?? "unknown",
          differs_from_first_recorded: originalNew
            ? reviewedNew !== originalNew.text
            : null,
          original_remaining_after:
            plan.remaining && originalNew && originalOld
              ? {
                  before: remainingOriginal.before.slice(originalOld.end),
                  after: remainingOriginal.after.slice(originalNew.end),
                }
              : null,
          context: {
            document_before_review: source,
            generation_instruction:
              original.context?.generation_instruction ?? null,
            conversation_ids:
              original.context?.conversation_ids ||
              proposal.reason.match(
                /[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}/g,
              ) ||
              [],
            proposal_rationale: proposal.reason,
          },
          preference: {
            chosen: plan.decision === "accept" ? reviewedNew : reviewedOld,
            rejected:
              plan.decision === "accept"
                ? (originalNew?.text ?? null)
                : reviewedNew,
            scope: "selected_passage",
            status: "candidate_requires_context_and_preference_validation",
            // Acceptance of unchanged text alone is not a preference pair.
            distinct_alternatives:
              plan.decision === "accept"
                ? Boolean(originalNew && reviewedNew !== originalNew.text)
                : reviewedOld !== reviewedNew,
          },
        },
      },
    );
    checkSize(event);
    const txn = {
      source_path: filename,
      after_sha256: event.source_after_sha256,
      before_sha256: event.source_before_sha256,
      event,
      before_source: source,
      after_source: afterSource,
    };
    return { event, afterSource, beforeSource: source };
  }
  function transition(event, kind, hash) {
    return makeEvent(kind, event.snapshot, hash, hash, {
      related_event_id: event.event_id,
      source_state: kind === "source_saved" ? "saved_file" : "editor_buffer",
    });
  }

  return {
    baseline,
    prepare,
    transition,
    activeDecisions,
    makeEvent,
    digest,
    snapshot,
    checkSize,
  };
}
if (typeof module !== "undefined") module.exports = { createReviewHistory };
