// Written for Thoughts. License is GPLv3.

/* Run with node --test scripts/test-proposal-review.cjs. */
const { test } = require("node:test"),
  assert = require("node:assert/strict"),
  fs = require("node:fs"),
  path = require("node:path"),
  vm = require("node:vm"),
  crypto = require("node:crypto");
const root = path.join(__dirname, "../app/src/main/assets/proposal-review");
const platform = {
  sha256: (s) => crypto.createHash("sha256").update(s).digest("hex"),
  uuid: () => crypto.randomUUID(),
};
function load(source, filename = "test.tex") {
  const ctx = vm.createContext({ TextEncoder, TextDecoder, atob, console });
  for (const f of ["modules.js", "history.js", "engine.js"])
    vm.runInContext(fs.readFileSync(path.join(root, f), "utf8"), ctx);
  const state = {
    filename,
    source,
    savedSource: source,
    events: [],
    undo: [],
    redo: [],
  };
  const e = ctx.createReviewEngine(state, platform);
  e.observe();
  return e;
}
function prefix(e, id, n, o) {
  const p = e.proposal(id);
  return [
    n === undefined
      ? null
      : { start: p.groups[3].contentStart, end: p.groups[3].contentStart + n },
    o === undefined
      ? null
      : { start: p.groups[2].contentStart, end: p.groups[2].contentStart + o },
  ].filter(Boolean);
}
const tex = (old, after) =>
  `Header.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{review-id}{Reason.}{${old}}{${after}}\nFooter.`;
test("partial acceptance, edit provenance, remainder identity, rejection and durable undo/redo", () => {
  const e = load(tex("", "Takes 4 minutes.\nNext."));
  e.edit(e.state.source.replace("4 minutes", "3 minutes"));
  const first = e.decide("review-id", "accept", prefix(e, "review-id", 16));
  assert.equal(first.review.accepted_text, "Takes 3 minutes.\n");
  assert.equal(first.review.original_corresponding_text, "Takes 4 minutes.\n");
  assert.equal(first.review.differs_from_first_recorded, true);
  assert.ok(
    e.state.source.includes(
      "Takes 3 minutes.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{review-id}{Reason.}{}{Next.}",
    ),
  );
  const second = e.decide("review-id", "reject");
  assert.equal(second.review.original_corresponding_text, "Next.");
  assert.equal(second.kind, "rejected");
  assert.equal(e.state.source, "Header.\nTakes 3 minutes.\n\nFooter.");
  e.undo();
  assert.ok(e.state.source.includes("Next.}"));
  e.redo();
  assert.equal(e.state.events.at(-1).kind, "review_redone");
  const snapshot = JSON.parse(JSON.stringify(e.state));
  const reopened = load(snapshot.source);
  Object.assign(reopened.state, snapshot);
  reopened.undo();
  assert.ok(reopened.state.source.includes("Next.}"));
  fs.writeFileSync(
    "/tmp/android-proposal-review-events.json",
    JSON.stringify(e.state.events),
  );
});
test("a half of a move pair is never decided in the editor, whole or in part (spec section 12)", () => {
  const half = "agent-0d1e2f30-4152-4637-8899-aabbccddeeff.cut";
  const source = tex("Marked passage.\n", "").replace("{review-id}", "{" + half + "}");
  for (const [action, selected] of [["accept", null], ["reject", null], ["accept", "prefix"]]) {
    const e = load(source);
    const ranges = selected ? prefix(e, half, undefined, 6) : null;
    assert.throws(() => e.decide(half, action, ranges), (error) => error.code === "move_proposal");
    assert.equal(e.state.source, source);
    assert.equal(e.state.events.filter((event) => event.kind !== "first_seen").length, 0);
  }
  // A Sync-side accept of the pair is never materialized here either.
  const e = load(source);
  assert.equal(e.acceptDecidedElsewhere([[half]]).length, 0);
  assert.equal(e.state.source, source);
  const { isMoveHalf } = require(path.join(root, "engine.js"));
  assert.ok(isMoveHalf(half) && isMoveHalf(half.replace(".cut", ".paste")));
  for (const other of ["review-id", "agent-0d1e2f30-4152-4637-8899-aabbccddeeff", half + ".x", "x" + half]) assert.ok(!isMoveHalf(other));
});
test("undo steps are spans: typing, decisions, undo/redo and legacy steps stay exact", () => {
  const e = load(tex("Old.", "New."));
  const original = e.state.source;
  // Type a word into the context, one keystroke per change, extending one undo step.
  let at = e.state.source.indexOf("Footer.");
  e.edit(e.state.source.slice(0, at) + "X" + e.state.source.slice(at));
  for (const c of "yz 😀") { at += c === "😀" ? 0 : 1; const pos = e.state.source.indexOf("Footer.");
    assert.ok(e.extend(e.state.source.slice(0, pos) + c + e.state.source.slice(pos))); }
  assert.equal(e.state.undo.length, 1);
  assert.ok(!("before" in e.state.undo[0]) && !("after" in e.state.undo[0]));
  const typed = e.state.source;
  assert.ok(typed.includes("Xyz 😀Footer."));
  e.decide("review-id", "accept");
  assert.equal(typeof e.state.undo.at(-1).event, "string");
  const decided = e.state.source;
  // Steps never embed the file: the whole journal stays near one copy of the source.
  assert.ok(JSON.stringify(e.state.undo).length < 400);
  e.undo(); assert.equal(e.state.source, typed); assert.equal(e.state.events.at(-1).kind, "review_undone");
  e.undo(); assert.equal(e.state.source, original);
  e.redo(); e.redo(); assert.equal(e.state.source, decided); assert.equal(e.state.events.at(-1).kind, "review_redone");
  // A stale step is refused instead of corrupting the source.
  const stale = load(original); stale.edit(original + "!");
  stale.state.source = original + "?";
  assert.throws(() => stale.undo(), /no longer matches/);
  // Journals written before spans keep working.
  const legacy = load(typed);
  const event = { ...e.state.events.find((x) => x.kind === "accepted") };
  Object.assign(legacy.state, { source: decided, events: e.state.events.slice(), undo: [
    { before: original, after: typed, event: null, typing: true }, { before: typed, after: decided, event }], redo: [] });
  const reopened = load(decided);
  Object.assign(reopened.state, JSON.parse(JSON.stringify(legacy.state)));
  const migrated = createEngineFor(reopened.state);
  assert.equal(migrated.state.undo[1].event, event.event_id);
  migrated.undo(); assert.equal(migrated.state.source, typed); migrated.undo(); assert.equal(migrated.state.source, original);
});
function createEngineFor(state) {
  const ctx = vm.createContext({ TextEncoder, TextDecoder, atob, console });
  for (const f of ["modules.js", "history.js", "engine.js"])
    vm.runInContext(fs.readFileSync(path.join(root, f), "utf8"), ctx);
  return ctx.createReviewEngine(state, platform);
}
test("span helpers never split surrogate pairs and hash like ReviewTextEdit.kt", () => {
  const { textEdit, textHash } = require(path.join(root, "engine.js"));
  assert.deepEqual({ ...textEdit("a😀b", "a😁b") }, { start: 1, removed: "😀", inserted: "😁", afterLength: 4 });
  assert.deepEqual({ ...textEdit("😀😀", "😀") }, { start: 2, removed: "😀", inserted: "", afterLength: 2 });
  assert.deepEqual({ ...textEdit("x😀", "x😀😀") }, { start: 3, removed: "", inserted: "😀", afterLength: 5 });
  // Same constants are asserted in ProposalReviewJournalTest.
  assert.equal(textHash(""), 2166136261);
  assert.equal(textHash("héllo 😀 wörld\n"), 970283961);
  assert.equal(textHash("a".repeat(1000)), 500786573);
});
test("benchmark: 200 typing steps on a 60 KB source persist O(change), not O(history x file)", () => {
  const paragraph = "\\section{Results}\nThe distillation objective improves calibration across all held-out tasks. ";
  let source = ""; while (source.length < 60_000) source += paragraph + "\n";
  const e = load(source);
  const words = Array.from({ length: 200 }, (_, i) => `word${i} `);
  let legacyBytes = 0, cursor = 30_000;
  for (const word of words) {
    const before = e.state.source;
    e.edit(before.slice(0, cursor) + word[0] + before.slice(cursor));
    for (let i = 1; i < word.length; i++) {
      const s = e.state.source; e.extend(s.slice(0, cursor + i) + word[i] + s.slice(cursor + i));
    }
    legacyBytes += JSON.stringify({ before, after: e.state.source }).length;
    cursor += word.length;
  }
  assert.equal(e.state.undo.length, 200);
  const started = process.hrtime.bigint();
  const state = JSON.stringify(e.state);
  const stringifyMs = Number(process.hrtime.bigint() - started) / 1e6;
  const undoBytes = JSON.stringify(e.state.undo).length;
  console.log(`# legacy undo JSON ${(legacyBytes / 1e6).toFixed(1)} MB; span undo JSON ${(undoBytes / 1e3).toFixed(1)} KB; ` +
    `whole state ${(state.length / 1e3).toFixed(0)} KB stringified in ${stringifyMs.toFixed(1)} ms`);
  assert.ok(legacyBytes > 20_000_000);
  assert.ok(undoBytes < 30_000);
  for (let i = 0; i < 200; i++) e.undo();
  assert.equal(e.state.source, source);
});
test("replacement demands two corresponding prefixes and preserves exact untouched macro bytes", () => {
  const e = load(tex("Old first.\nOld rest.", "New first.\nNew rest."));
  assert.throws(
    () => e.decide("review-id", "accept", prefix(e, "review-id", 10)),
    /both|two selections/,
  );
  const ev = e.decide("review-id", "reject", prefix(e, "review-id", 10, 10));
  assert.equal(ev.review.retained_text, "Old first.\n");
  assert.equal(
    e.state.source,
    tex("Old rest.", "New rest.").replace("Header.\n", "Header.\nOld first.\n"),
  );
});
test("middle selections, split words, math, groups, environments, and unmatched sides fail closed", () => {
  for (const after of [
    "HelloWorld rest.",
    "\\textbf{First second} rest.",
    "$a+b$ rest.",
    "\\begin{itemize}x\\end{itemize} rest.",
  ]) {
    const e = load(tex("", after)),
      p = e.proposal("review-id");
    assert.throws(() =>
      e.decide("review-id", "accept", [
        {
          start: p.groups[3].contentStart + 2,
          end: p.groups[3].contentStart + 4,
        },
      ]),
    );
    assert.throws(() =>
      e.decide(
        "review-id",
        "accept",
        prefix(
          e,
          "review-id",
          after.startsWith("Hello")
            ? 3
            : after.startsWith("$")
              ? 3
              : after.startsWith("\\textbf")
                ? 14
                : 18,
        ),
      ),
    );
    assert.equal(e.state.source, tex("", after));
  }
  const e = load(tex("Old.", "New.\nRest."));
  assert.throws(
    () => e.decide("review-id", "accept", prefix(e, "review-id", 4, 4)),
    /corresponding prefixes/,
  );
});
test("duplicates and nested macros never become reviewable", () => {
  const source = tex("", "One.");
  const e = load(source + "\n" + source);
  assert.throws(() => e.decide("review-id", "accept"), { code: "duplicate_id" });
  const n = load(
    tex("", String.raw`\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{inside}{Why.}{}{Nested.}`),
  );
  assert.throws(() => n.decide("review-id", "accept"), { code: "nested_proposal" });
  assert.throws(() => n.decide("inside", "accept"), { code: "unknown_id" });
});
test("UTF-16 selections and unchanged word runs match extension", () => {
  const e = load(tex("", "😀 First.\nNext."));
  assert.throws(
    () => e.decide("review-id", "accept", prefix(e, "review-id", 1)),
    /Unicode/,
  );
  const ev = e.decide("review-id", "accept", prefix(e, "review-id", 9));
  assert.equal(ev.review.selected_proposed_text, "😀 First.\n");
  assert.equal(ev.review.selected_new_range[1], 10);
  const diff = require(path.join(root, "wordDiff.js")).changedTokenRanges(
    "We plot rates.",
    "We plot accurate new rates.",
  );
  assert.equal(
    "We plot accurate new rates.".slice(
      diff.newRanges[0].start,
      diff.newRanges[0].end,
    ),
    "accurate new",
  );
});
test("ambiguous baseline correspondence stays explicitly unknown", () => {
  const e = load(tex("", "One. Tail. Tail."));
  e.edit(tex("", "Edited. Tail."));
  const ev = e.decide("review-id", "accept", prefix(e, "review-id", 8));
  assert.equal(ev.review.correspondence, "unknown");
  assert.equal(ev.review.differs_from_first_recorded, null);
});

test("baseline observations are immutable and unique across repeated opens", () => {
  const e = load(tex("", "One."));
  e.observe();
  e.observe();
  assert.equal(e.state.events.length, 1);
  assert.equal(
    new Set(e.state.events.map((e) => e.event_id)).size,
    e.state.events.length,
  );
});

test("inline source fields keep later contexts and proposal metadata at exact offsets", () => {
  const { createSourceFields } = require(path.join(root,"sourceModel.js"));
  const original = 'Before.\n\\agentProposedEdit[created=2026-09-12T12:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{stable}{Reason.}{Old.}{New.}\nAfter.';
  const e=load(original), p=e.proposal('stable');
  const m=createSourceFields(e.state,s=>e.edit(s));
  const before=m.add(0,p.start), old=m.add(p.groups[2].contentStart,p.groups[2].contentEnd), proposed=m.add(p.groups[3].contentStart,p.groups[3].contentEnd), after=m.add(p.end,original.length);
  m.replace(before,'Longer introduction.\n');
  m.replace(proposed,'Edited new text.');
  m.replace(after,'\nChanged ending.');
  assert.equal(e.state.source.slice(old.start,old.end),'Old.');
  assert.equal(e.state.source.slice(proposed.start,proposed.end),'Edited new text.');
  assert.equal(e.state.source,'Longer introduction.\n\\agentProposedEdit[created=2026-09-12T12:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{stable}{Reason.}{Old.}{Edited new text.}\nChanged ending.');
});
test("empty new text remains editable and shifts following source without losing bytes",()=>{
  const {createSourceFields}=require(path.join(root,'sourceModel.js'));
  const state={source:'AB'}, m=createSourceFields(state,s=>state.source=s);
  const insertion=m.add(1,1), tail=m.add(1,2);
  m.replace(insertion,'😀');m.replace(tail,'Z');
  assert.equal(state.source,'A😀Z');
  m.replace(insertion,'');assert.equal(state.source,'AZ');
});


test("structured conversation survives Android parsing, history and editing", () => {
  const id = "0190b000-0000-7000-8000-00000000000b";
  const source = `\\agentProposedEdit[created=2026-09-16T12:00:00Z,conversation_id=${id}]{p}{Reason.}{Old.}{New.}`;
  const e = load(source);
  assert.equal(e.proposal('p').conversationId, id);
  assert.equal(e.state.events[0].snapshot.conversation_id, id);
  e.edit(source.replace('New.', 'Better.'));
  assert.equal(e.proposal('p').conversationId, id);
  const model = require(path.join(root, 'proposal-model.js'));
  assert.equal(model.parseProposals('test.tex', source).proposals[0].conversationId, id);
});

test("metadata is mandatory: legacy wrappers and the conversation sentence are invalid_metadata", () => {
  const id = "0190b000-0000-7000-8000-00000000000b";
  const model = require(path.join(root, 'proposal-model.js'));
  for (const meta of ['', '[created=2026-09-16T12:00:00Z]', `[conversation_id=${id}]`]) {
    const parsed = model.parseProposals('test.tex', `\\agentProposedEdit${meta}{p}{Reason.\nPrepared in Codex conversation ${id}.}{Old.}{New.}`);
    assert.deepEqual(parsed.proposals, []);
    assert.deepEqual(parsed.issues.map(i => [i.code, i.id]), [['invalid_metadata', 'p']]);
  }
  assert.deepEqual(model.parseProposals('test.tex', '\\agentProposedEdit[created=2026-09-16T12:00:00Z,conversation_id=bad]{p}{Why.}{Old.}{New.}').issues.map(i => i.code), ['invalid_metadata']);
});

test("Markdown proposal survives parsing, whole decision, and undo", () => {
  const meta = {id:"review-id", created_at:"2026-09-23T17:34:16.099Z",
    conversation_id:"0190c000-0000-7000-8000-00000000000c",
    explanation:"Align the introduction with the abstract.", original_text:"Old intro.\n",
    replacement_length:"New intro.\n".length};
  const header = Buffer.from(JSON.stringify(meta)).toString("base64url");
  const source = `Before.\n<!-- thoughts:agent-proposal:v1:start ${header} -->\nNew intro.\n<!-- thoughts:agent-proposal:v1:end review-id -->\nAfter.`;
  const accepted = load(source, "notes.md");
  assert.equal(accepted.proposal("review-id").oldText, "Old intro.\n");
  assert.equal(accepted.proposal("review-id").newText, "New intro.\n");
  accepted.decide("review-id", "accept");
  assert.equal(accepted.state.source, "Before.\nNew intro.\nAfter.");
  accepted.undo();
  assert.equal(accepted.state.source, source);
  const rejected = load(source, "notes.md");
  rejected.decide("review-id", "reject");
  assert.equal(rejected.state.source, "Before.\nOld intro.\nAfter.");
});

test("readable Markdown proposal preserves old text and whole decisions", () => {
  const source = `Before.\n<!-- thoughts:agent-proposal:v2:start
id: review-id
created_at: 2026-09-23T17:34:16.099Z
conversation_id: 0190c000-0000-7000-8000-00000000000c
replacement_length: 11
explanation:
| Align with the abstract.
original_text:
| Old &amp; intro.
| ${''}
-->\nNew intro.\n<!-- thoughts:agent-proposal:v2:end review-id -->\nAfter.`;
  const accepted = load(source, "notes.md");
  assert.equal(accepted.proposal("review-id").oldText, "Old & intro.\n");
  accepted.decide("review-id", "accept");
  assert.equal(accepted.state.source, "Before.\nNew intro.\nAfter.");
  const rejected = load(source, "notes.md");
  rejected.decide("review-id", "reject");
  assert.equal(rejected.state.source, "Before.\nOld & intro.\nAfter.");
});

test("closing markers survive manual edits and obsolete lengths", () => {
  const model = require(path.join(root, 'proposal-model.js'));
  for (const metadata of ['', 'replacement_length: 1387\n', 'replacement_length: 0\n']) {
    for (const replacement of ['', 'Edited 😀 text.\r\n\n', 'Longer text.\n'.repeat(100)]) {
      const source = `Before.\n<!-- thoughts:agent-proposal:v2:start\nid: review-id\ncreated_at: 2026-09-23T17:34:16.099Z\nconversation_id: 0190c000-0000-7000-8000-00000000000c\n${metadata}explanation:\n| Fix the text.\noriginal_text:\n| Old.\n| \n-->\n${replacement}<!-- thoughts:agent-proposal:v2:end review-id -->\nMethods.\n`;
      const parsed = model.parseProposals('notes.md', source);
      assert.deepEqual(parsed.issues, []);
      assert.equal(parsed.proposals[0].newText, replacement);
      const accepted = load(source, 'notes.md');
      accepted.decide('review-id', 'accept');
      assert.equal(accepted.state.source, 'Before.\n' + replacement + 'Methods.\n');
      accepted.undo();
      assert.equal(accepted.state.source, source);
      const rejected = load(source, 'notes.md');
      rejected.decide('review-id', 'reject');
      assert.equal(rejected.state.source, 'Before.\nOld.\nMethods.\n');
      for (const broken of [source.replace('v2:end review-id', 'v2:end other'),
        source.replace('-->\n', '-->\n<!-- thoughts:agent-proposal:v1:start nested -->\n'),
        source + '<!-- thoughts:agent-proposal:v2:end review-id -->\n']) {
        assert.ok(model.parseProposals('notes.md', broken).issues.length);
      }
    }
  }
});

// ---------------------------------------------------------------- agent-proposal spec fixtures
// cases.json is copied verbatim from thoughts-server protocol/fixtures/agent-proposals/.
const fixtures = JSON.parse(fs.readFileSync(path.join(__dirname, "../app/src/test/resources/agent-proposals/cases.json"), "utf8"));
function webViewModel() {
  // The model as the WebView loads it: through modules.js, without Node globals such as Buffer.
  const ctx = vm.createContext({ TextEncoder, TextDecoder });
  vm.runInContext(fs.readFileSync(path.join(root, "modules.js"), "utf8"), ctx);
  return vm.runInContext("require('./proposal-model')", ctx);
}
function strictResult(model, item) {
  const cp = (i) => model.codePointOffset(item.source, i);
  const parsed = model.parseProposals(item.path, item.source);
  return {
    parse: {
      proposals: parsed.proposals.map((p) => ({
        id: p.id, created_at: p.created_at, conversation_id: p.conversation_id, conversation_url: p.conversation_url,
        explanation: p.explanation, original: p.original, replacement: p.replacement, span: { start: cp(p.start), end: cp(p.end) },
      })),
      issues: parsed.issues.map((i) => ({ code: i.code, span: { start: cp(i.start), end: cp(i.end) }, ...(i.id !== undefined ? { id: i.id } : {}) })),
    },
    decisions: item.decisions.map((d) => {
      const r = model.decide(item.path, item.source, d.ids, d.action);
      return r.ok ? { output: r.output } : { refused: r.code };
    }),
  };
}
test("modules.js bundles the current proposal model, partial review and word diff", () => {
  const { bundle } = require("./build-proposal-review-modules.cjs");
  assert.equal(fs.readFileSync(path.join(root, "modules.js"), "utf8"), bundle(), "run node scripts/build-proposal-review-modules.cjs");
});
for (const [label, model] of [["node", require(path.join(root, "proposal-model.js"))], ["webview", webViewModel()]]) {
  test(`agent-proposal fixtures pass strictly (${label}: codes, spans, refusals, writers)`, () => {
    let passed = 0;
    for (const item of fixtures.cases) {
      const got = strictResult(model, item);
      assert.deepEqual(JSON.parse(JSON.stringify(got.parse)), item.expect, item.id);
      assert.deepEqual(got.decisions, item.decisions.map((d) => d.expect), item.id);
      passed += 1;
    }
    for (const writer of fixtures.writers) assert.equal(model.formatProposal(writer.format, writer.fields), writer.expect, writer.id);
    assert.equal(passed, 171);
  });
}
test("fixture decisions through the review engine match the spec outputs and refusal codes", () => {
  for (const item of fixtures.cases) {
    for (const d of item.decisions) {
      if (d.ids.length !== 1) continue;
      const e = load(item.source, item.path);
      if ("output" in d.expect) {
        e.decide(d.ids[0], d.action);
        assert.equal(e.state.source, d.expect.output, item.id);
      } else assert.throws(() => e.decide(d.ids[0], d.action), { code: d.expect.refused }, item.id);
    }
  }
});
test("a broken proposal never blocks the valid proposals in the same file", () => {
  const good = "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{good}{Why.}{Old.}{New.}";
  const source = `${good}\n\\agentProposedEdit[created=2026-02-30T12:00:00Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{bad}{Why.}{a}{b}\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{tail}{w}{x}`;
  const e = load(source);
  assert.deepEqual(Array.from(e.parsed().issues, (i) => `${i.code}:${i.id}`), ["invalid_metadata:bad", "missing_argument:tail"]);
  assert.throws(() => e.decide("bad", "accept"), { code: "invalid_metadata" });
  e.decide("good", "accept");
  assert.ok(e.state.source.startsWith("New.\n\\agentProposedEdit[created=2026-02-30"));
});
test("format follows the file extension, never the content", () => {
  const md = "<!-- thoughts:agent-proposal:v2:start\nid: p\ncreated_at: 2026-10-10T12:00:00.000Z\nconversation_id: 0b6c5b7e-1d2a-4c3b-9f8e-112233445566\nexplanation:\n| w\noriginal_text:\n-->\nb\n<!-- thoughts:agent-proposal:v2:end p -->\n";
  assert.equal(load(md, "doc.tex").parsed().proposals.length, 0);
  assert.equal(load(md, "doc.MD").parsed().proposals.length, 1);
  assert.equal(load("\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{p}{w}{a}{b}", "notes.md").parsed().proposals.length, 0);
});
test("partial acceptance keeps metadata and refuses to split a Markdown proposal", () => {
  const source = "\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{p}{Why.\n}{}{First.\nSecond.\n}";
  const e = load(source);
  e.decide("p", "accept", prefix(e, "p", 7));
  assert.equal(e.state.source, "First.\n\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{p}{Why.\n}{}{Second.\n}");
  const md = load(fixtures.cases.find((c) => c.id === "md-v2-basic").source, "notes.md");
  const id = md.parsed().proposals[0].id;
  assert.throws(() => md.decide(id, "accept", [{ start: 0, end: 1 }]), /whole decisions only/);
});

// ---------------------------------------------------------------- ui.js persistence protocol
// A minimal DOM: enough for ui.js to render, type into a field and persist through a fake Native.
function fakeDom() {
  const byId = new Map();
  const listeners = new Map();
  class Node {
    constructor(tag) { Object.assign(this, { tagName: tag, children: [], style: {}, dataset: {}, attributes: {}, hidden: false,
      textContent: "", value: "", className: "", selectionStart: 0, selectionEnd: 0, offsetHeight: 20, offsetWidth: 100,
      scrollHeight: 20, isConnected: true, events: {} }); this.classList = { toggle() {}, add() {} }; }
    append(...nodes) { this.children.push(...nodes); for (const n of nodes) if (n && n.id) byId.set(n.id, n); }
    replaceChildren(...nodes) { this.children = []; this.append(...nodes); }
    setAttribute(k, v) { this.attributes[k] = v; if (k === "id") byId.set(v, this); }
    addEventListener(type, fn) { (this.events[type] ||= []).push(fn); }
    dispatchEvent(event) { for (const fn of this.events[event.type] || []) fn(event); }
    querySelector(selector) { return selector === ".codeUnderlay" ? new Node("pre") : null; } contains() { return false; } focus() {} scrollIntoView() {}
    setSelectionRange(a, b) { this.selectionStart = a; this.selectionEnd = b; }
    getBoundingClientRect() { return { top: 0, left: 0, width: 100, height: 20 }; }
    set id(v) { this._id = v; byId.set(v, this); } get id() { return this._id; }
  }
  const textareas = [];
  const document = {
    getElementById(id) { if (!byId.has(id)) { const n = new Node("div"); n.id = id; } return byId.get(id); },
    createElement(tag) { const n = new Node(tag); if (tag === "textarea") textareas.push(n); return n; },
    createTextNode(text) { return { text }; },
    addEventListener(type, fn) { listeners.set(type, fn); },
    activeElement: null,
  };
  return { document, textareas, Node };
}
function loadUi(native, payload) {
  const { document, textareas } = fakeDom();
  const timers = [];
  const ctx = vm.createContext({
    TextEncoder, TextDecoder, atob, console, document, Event: class { constructor(type) { this.type = type; } },
    requestAnimationFrame: (fn) => fn(), cancelAnimationFrame() {},
    setTimeout: (fn) => { timers.push(fn); return timers.length; }, clearTimeout() {},
    scrollY: 0, innerHeight: 800, innerWidth: 400,
    Native: { load: () => JSON.stringify(payload), sha256: platform.sha256, uuid: platform.uuid,
      editorDiagnostic() {}, initialSelectionReady() {}, ...native },
  });
  ctx.window = ctx; ctx.window.scrollTo = () => {}; ctx.window.scrollBy = () => {};
  for (const f of ["modules.js", "history.js", "engine.js", "sourceModel.js", "sourceNavigation.js", "ui.js"])
    vm.runInContext(fs.readFileSync(path.join(root, f), "utf8"), ctx);
  return { ctx, textareas, runTimers: () => { while (timers.length) timers.shift()(); } };
}
test("ui.js persists typing as patches against the journal Android holds", () => {
  let source = "";
  while (source.length < 60_000) source += "\\section{Results}\nThe objective improves calibration.\n\n";
  const journal = { revision: 3, source, events: 0 };
  const sent = [];
  const native = {
    persist(raw) {
      const p = JSON.parse(raw); sent.push(p);
      if (p.patch && p.journalRevision !== journal.revision) return JSON.stringify({ resync: true });
      if (p.patch) {
        if (p.sourceEdit) {
          const e = p.sourceEdit;
          journal.source = journal.source.slice(0, e.start) + e.inserted + journal.source.slice(e.start + e.removed);
          const { textHash } = require(path.join(root, "engine.js"));
          assert.equal(journal.source.length, e.length); assert.equal(textHash(journal.source), e.hash);
        }
        assert.equal(p.eventBase, journal.events); journal.events = p.eventCount;
      } else { journal.source = p.source; journal.events = p.events.length; }
      journal.revision += 1;
      return JSON.stringify({ journalRevision: journal.revision, eventCount: journal.events });
    },
  };
  const state = { filename: "results.tex", baseSha256: "base", source, savedSource: source, events: [], undo: [], redo: [], journalRevision: 3 };
  const { ctx, textareas, runTimers } = loadUi(native, { state, selectionStart: 100, selectionEnd: 100 });
  const field = textareas[0];
  for (const [i, c] of [..."new words here"].entries()) {
    field.value = field.value.slice(0, 50 + i) + c + field.value.slice(50 + i);
    field.dispatchEvent({ type: "input" });
  }
  runTimers();
  const typed = ctx.reviewApp.getState().source;
  assert.ok(typed.includes("new words here") && ctx.reviewApp.getState().undo.length >= 3);
  assert.equal(journal.source, typed);
  const patches = sent.filter((p) => p.patch);
  assert.ok(patches.length >= 1 && sent.every((p) => p.patch), "no whole-state persist while typing");
  assert.ok(JSON.stringify(patches.at(-1)).length < 2_000, "a typing persist is O(change)");
  // Another window advanced the journal: the page resends its whole state once.
  journal.revision += 5;
  field.value = "x" + field.value; field.dispatchEvent({ type: "input" }); runTimers();
  assert.equal(sent.at(-2).patch, 1); assert.equal(sent.at(-1).patch, undefined);
  assert.equal(journal.source, ctx.reviewApp.getState().source);
  // Undo still restores the opened text exactly.
  while (ctx.reviewApp.getState().undo.length) ctx.reviewApp.engine().undo();
  assert.equal(ctx.reviewApp.getState().source, source);
});
// 2026-10-16: an Accept from the PDF view stayed a read-only card in the editor until Sync.
const pdfAccepted = (id, after) =>
  `\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566]{${id}}{Reason.}{Old ${id}.}{${after}}`;
test("an Accept from the PDF view becomes source text, group by group, and undo restores the wrapper", () => {
  const source = `Header.\n${pdfAccepted("one", "New one.")}\nMiddle.\n${pdfAccepted("two", "New two.")}\nFooter.`;
  const e = load(source);
  const edits = e.acceptDecidedElsewhere([["one", "two"], ["missing", "two"]]);
  assert.equal(JSON.stringify(edits.map((x) => x.id)), '["one","two"]');
  assert.equal(e.state.source, "Header.\nNew one.\nMiddle.\nNew two.\nFooter.");
  assert.equal(JSON.stringify(e.state.events.filter((x) => x.kind === "accepted").map((x) => x.proposal_id)), '["one","two"]');
  // The failed group changed nothing: no half-applied decision, no stray event or undo step.
  assert.equal(e.state.undo.length, 2);
  e.undo();
  assert.ok(e.state.source.includes(pdfAccepted("two", "New two.")) && e.state.source.startsWith("Header.\nNew one."));
  e.undo();
  assert.equal(e.state.source, source);
  const md = load(`Before.\n<!-- thoughts:agent-proposal:v2:start
id: review-id
created_at: 2026-09-23T17:34:16.099Z
conversation_id: 0190c000-0000-7000-8000-00000000000c
explanation:
| Align.
original_text:
| Old intro.
-->\nNew intro.\n<!-- thoughts:agent-proposal:v2:end review-id -->\nAfter.`, "notes.md");
  assert.equal(md.acceptDecidedElsewhere([["review-id"]]).length, 1);
  assert.equal(md.state.source, "Before.\nNew intro.\nAfter.");
});
function pdfAcceptUi(extra = {}) {
  const source = `Header.\n${pdfAccepted("one", "New one.")}\nMiddle.\n${pdfAccepted("two", "New two.")}\nFooter.`;
  const calls = { saved: [], closes: [], persisted: [] };
  const native = {
    persist: (raw) => { calls.persisted.push(JSON.parse(raw)); return JSON.stringify({ journalRevision: 1, eventCount: 0 }); },
    save(raw) {
      const p = JSON.parse(raw);
      if (p.patch) return JSON.stringify({ resync: true });
      calls.saved.push(p);
      return JSON.stringify({ state: { ...p, savedSource: p.source } });
    },
    requestClose: (changed) => calls.closes.push(changed),
    close() {},
    ...extra,
  };
  const state = { filename: "paper.tex", baseSha256: "base", source, savedSource: source, events: [], undo: [], redo: [] };
  const ui = loadUi(native, { state, proposalId: "one", decidedElsewhere: {
    one: { action: "accept", applied: false, materialize: true, group: "operation-1" },
    two: { action: "reject", applied: false },
  } });
  return { ...ui, source, calls };
}
test("ui.js shows a PDF-view Accept as editable source text and saves edits to it as that decision", () => {
  const { ctx, textareas, source, calls } = pdfAcceptUi();
  const app = ctx.reviewApp, $ = (id) => ctx.document.getElementById(id);
  assert.ok(app.getState().source.startsWith("Header.\nNew one.\nMiddle.\n\\agentProposedEdit"));
  // Unedited, it reads as saved; the Reject made in the PDF view stays a locked card.
  assert.equal($("status").textContent, "Saved locally");
  assert.equal($("save").disabled, true);
  assert.match($("message").textContent, /Accepted in the PDF view/);
  const field = textareas.find((t) => t.value.includes("New one.") && !t.readOnly && t.dataset.kind === "context");
  assert.ok(field, "the accepted text is an ordinary editable source field");
  field.value = field.value.replace("New one.", "New one, edited.");
  field.dispatchEvent({ type: "input" });
  assert.equal($("status").textContent, "Unsaved changes");
  app.flush();
  assert.ok(calls.persisted.at(-1).sourceEdit, "after an edit the journal holds the accepted, edited text");
  assert.ok(calls.persisted.at(-1).events.some((e) => e.kind === "accepted" && e.proposal_id === "one"));
  app.save(false);
  const saved = calls.saved.at(-1);
  assert.ok(saved.source.startsWith("Header.\nNew one, edited.\nMiddle.\n\\agentProposedEdit"));
  assert.ok(saved.events.some((e) => e.kind === "accepted" && e.proposal_id === "one"), "the decision travels with the save");
  assert.equal(app.getState().savedSource, saved.source);
  assert.notEqual(saved.source, source);
});
test("ui.js closing an unedited PDF-view Accept puts the wrapper back and asks nothing", () => {
  const { ctx, source, calls } = pdfAcceptUi();
  // Android's append-only journal never holds the shown Accept until the user edits.
  ctx.reviewApp.flush();
  const shown = calls.persisted.at(-1);
  assert.ok(!shown.sourceEdit && !shown.events.some((e) => e.kind === "accepted"));
  ctx.reviewApp.close();
  assert.ok(calls.persisted.slice(1).every((p) => !p.sourceEdit && !p.events.some((e) => e.kind === "accepted")));
  assert.equal(JSON.stringify(calls.closes), "[false]");
  assert.equal(ctx.reviewApp.getState().source, source);
  assert.equal(ctx.reviewApp.getState().undo.length, 0);
  assert.ok(!ctx.reviewApp.getState().events.some((e) => e.kind === "accepted"));
});
test("ui.js offers the Claude conversation of a linked proposal and nothing for Codex or unlinked ones", () => {
  const link = "https://claude.ai/code/session_01ExampleSessionAbcdEfgh";
  const wrapper = (id, url) => `\\agentProposedEdit[created=2026-10-10T12:00:00.000Z,conversation_id=0b6c5b7e-1d2a-4c3b-9f8e-112233445566${url ? ",conversation_url=" + url : ""}]{${id}}{Reason.}{Old ${id}.\n}{New ${id}.\n}`;
  const source = `A.\n${wrapper("claude", link)}B.\n${wrapper("codex", "codex://threads/0b6c5b7e-1d2a-4c3b-9f8e-112233445566")}C.\n${wrapper("plain")}D.\n`;
  const opened = [];
  const state = { filename: "paper.tex", baseSha256: "base", source, savedSource: source, events: [], undo: [], redo: [] };
  const { ctx } = loadUi({ openConversation: (url) => opened.push(url), persist: () => JSON.stringify({}) }, { state });
  const buttons = (node, found = []) => {
    for (const child of node.children || []) {
      if (child.tagName === "button" && child.textContent === "Claude ↗") found.push(child);
      buttons(child, found);
    }
    return found;
  };
  const card = (id) => ctx.document.getElementById("proposal-" + id);
  assert.equal(buttons(card("codex")).length, 0);
  assert.equal(buttons(card("plain")).length, 0);
  const [chat] = buttons(card("claude"));
  assert.ok(chat, "the linked proposal has a conversation button");
  chat.onclick();
  assert.deepEqual(opened, [link]);
});
