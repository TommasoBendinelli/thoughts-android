/* Undo steps store the changed span, not two copies of the file (ReviewTextEdit.kt):
   before = after[0, start) + removed + after[start + inserted.length, ...). Spans never split a
   surrogate pair, which would not survive UTF-8 encoding. */
function textEdit(before, after) {
  const max = Math.min(before.length, after.length);
  let start = 0;
  while (start < max && before.charCodeAt(start) === after.charCodeAt(start)) start++;
  if (start > 0 && isHighSurrogate(before.charCodeAt(start - 1))) start--;
  let tail = 0;
  while (tail < max - start && before.charCodeAt(before.length - 1 - tail) === after.charCodeAt(after.length - 1 - tail)) tail++;
  if (tail > 0 && isLowSurrogate(before.charCodeAt(before.length - tail))) tail--;
  return {
    start,
    removed: before.slice(start, before.length - tail),
    inserted: after.slice(start, after.length - tail),
    afterLength: after.length,
  };
}
function isHighSurrogate(c) { return c >= 0xd800 && c <= 0xdbff; }
function isLowSurrogate(c) { return c >= 0xdc00 && c <= 0xdfff; }
function matchesAfter(source, step) {
  return source.length === step.afterLength && source.startsWith(step.inserted, step.start);
}
function matchesBefore(source, step) {
  return source.length === step.afterLength - step.inserted.length + step.removed.length &&
    source.startsWith(step.removed, step.start);
}
/* FNV-1a over UTF-16 code units; ReviewTextEdit.hash computes the same value. */
function textHash(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 0x01000193);
  return h >>> 0;
}
/* Steps from journals written before spans: {before, after, event: {...}}. */
function compactStep(step) {
  const event = step.event && typeof step.event === "object" ? step.event.event_id : step.event ?? null;
  if (typeof step.before !== "string") return { ...step, event };
  return { ...textEdit(step.before, step.after), event, ...(step.typing ? { typing: true } : {}) };
}
/* Move pairs (agent-proposal spec section 12): "agent-<uuid>.cut" removes a review card's marked
   passage and "agent-<uuid>.paste" inserts it elsewhere, often in another file. They are decided
   together, so this editor, which sees one file, never decides a half, whole or in part. */
const MOVE_HALF = /^agent-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(cut|paste)$/;
const MOVE_HALF_HINT = "This proposal is half of a review-card move: it is accepted or rejected together with its other half. Decide it in the PDF view or Overview.";
function isMoveHalf(id) { return MOVE_HALF.test(id); }
/* A serializable editor journal. Every decision retains its exact source revision. */
function createReviewEngine(state, platform) {
  const { parseProposals, decidableProposal } = require("./proposal-model");
  const { planDecision } = require("./partialReview");
  state.events ||= [];
  state.undo = (state.undo || []).map(compactStep);
  state.redo = (state.redo || []).map(compactStep);
  const history = createReviewHistory(state, platform);
  let parsedSource, parsedValue;
  const parsed = () => {
    if (parsedSource !== state.source) {
      // The format comes from the file extension (agent-proposal spec section 2).
      parsedValue = parseProposals(state.filename, state.source);
      parsedSource = state.source;
    }
    return parsedValue;
  };
  // Throws an Error whose .code is the spec refusal code (duplicate_id, nested_proposal, ...).
  const proposal = (id) => decidableProposal(parsed(), id);
  function observe() {
    for (const p of parsed().proposals)
      history.baseline("", state.filename, p, state.source, state.events);
  }
  function edit(source, change = null) {
    if (source === state.source) return;
    state.undo.push({ ...(change || textEdit(state.source, source)), event: null });
    state.redo = [];
    state.source = source;
  }
  /* Continue the last undo step with a further change of the source it produced. */
  function extend(source, change = null) {
    const last = state.undo.at(-1);
    if (!last || !matchesAfter(state.source, last)) return false;
    const c = change || textEdit(state.source, source);
    const a = Math.min(last.start, c.start);
    const b = Math.max(last.start + last.inserted.length, c.start + c.removed.length);
    last.removed = state.source.slice(a, last.start) + last.removed + state.source.slice(last.start + last.inserted.length, b);
    last.inserted = source.slice(a, b + c.inserted.length - c.removed.length);
    last.start = a;
    last.afterLength = source.length;
    state.redo = [];
    state.source = source;
    return true;
  }
  function decide(id, action, selected = null) {
    if (isMoveHalf(id)) throw Object.assign(Error(MOVE_HALF_HINT), { code: "move_proposal" });
    const p = proposal(id);
    const plan = planDecision(state.source, p, action, selected);
    const prepared = history.prepare("", state.filename, p, state.source, plan);
    state.events.push(prepared.event);
    state.undo.push({
      ...textEdit(state.source, prepared.afterSource),
      event: prepared.event.event_id,
    });
    state.redo = [];
    state.source = prepared.afterSource;
    return prepared.event;
  }
  /* Accepts made in the PDF view or Overview and not yet sent: show their accepted text as
     ordinary source text. Each group is one queued decision and applies whole or not at all,
     so a save never claims part of a decision. Returns the edits, in order, for the IDs applied. */
  function acceptDecidedElsewhere(groups) {
    const applied = [];
    for (const ids of groups) {
      const before = { source: state.source, undo: state.undo.length, redo: state.redo, events: state.events.length };
      const edits = [];
      try {
        for (const id of ids) {
          const from = state.source;
          decide(id, "accept");
          edits.push({ id, ...textEdit(from, state.source) });
        }
        applied.push(...edits);
      } catch (e) {
        state.source = before.source; state.undo.length = before.undo;
        state.redo = before.redo; state.events.length = before.events;
      }
    }
    return applied;
  }
  function travel(redo) {
    const from = redo ? state.redo : state.undo,
      to = redo ? state.undo : state.redo;
    if (!from.length) return;
    const entry = from[from.length - 1];
    if (!(redo ? matchesBefore : matchesAfter)(state.source, entry))
      throw Error("Undo revision no longer matches this source.");
    const event = entry.event && typeof entry.event === "object"
      ? entry.event
      : entry.event ? state.events.find((e) => e.event_id === entry.event) : null;
    if (entry.event && !event) throw Error("Undo decision is missing from the review history.");
    from.pop();
    to.push(entry);
    const end = entry.start + (redo ? entry.removed : entry.inserted).length;
    state.source = state.source.slice(0, entry.start) + (redo ? entry.inserted : entry.removed) + state.source.slice(end);
    if (event)
      state.events.push(
        history.transition(
          event,
          redo ? "review_redone" : "review_undone",
          history.digest(state.source),
        ),
      );
  }
  function savedEvents() {
    const hash = history.digest(state.source);
    return history
      .activeDecisions(state.events)
      .filter(
        (e) =>
          e.source_after_sha256 === hash &&
          !state.events.some(
            (s) =>
              s.kind === "source_saved" &&
              s.related_event_id === e.event_id &&
              s.source_after_sha256 === hash,
          ),
      )
      .map((e) => history.transition(e, "source_saved", hash));
  }
  return {
    state,
    history,
    parsed,
    proposal,
    observe,
    edit,
    extend,
    decide,
    acceptDecidedElsewhere,
    undo: () => travel(false),
    redo: () => travel(true),
    savedEvents,
  };
}
if (typeof module !== "undefined") module.exports = { textEdit, textHash, matchesAfter, matchesBefore, compactStep, isMoveHalf };
