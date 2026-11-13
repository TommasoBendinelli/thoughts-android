"use strict";
const $ = id => document.getElementById(id);
let engine, state, fields, selectedId, historyMode = false, hideProposals = false;
let pendingPersist, focusedField, initialSelection, initialTarget, keyboardTarget, editorScrollY = 0;
const selections = new Map(), openedRaw = new Set();
const revealedProposals = new Set(); // Opening a location must not change the saved visibility preference.
// Proposals decided in the PDF view or Overview: {id: {action, applied}}. Offering them again
// here let one proposal carry two decisions (2026-10-10: an Accept waiting for Sync still
// showed Accept/Reject in this editor).
let decidedElsewhere = {};
// Accepts from the PDF view shown as plain source text (materializeDecidedElsewhere): the source
// they produced and the state before them. Until the user edits, that view counts as unchanged.
let materialized = null;
function decidedElsewhereHint(d) {
  const verb = d.action === "accept" ? "Accepted" : "Rejected";
  return d.applied
    ? verb + " in the PDF view and applied on the server. This copy of the source updates with the next Sync."
    : verb + " in the PDF view. The decision reaches the server with the next Sync.";
}
const platform = { sha256: text => Native.sha256(text), uuid: () => Native.uuid() };
function message(text = "") { $("message").textContent = text; }
function nativeResult(raw) { const r = JSON.parse(raw); if (r.error) throw Error(r.error); return r; }
function element(tag, cls, text) {
  const e = document.createElement(tag); if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text; return e;
}
function button(text, fn, label) {
  const b = element("button", "", text); b.type = "button"; b.onclick = fn;
  if (label) { b.setAttribute("aria-label", label); b.title = label; } return b;
}
// Typing calls this on every keystroke: scan the source once per change, not twice per call.
const CONFLICT_MARKER = /^(<<<<<<<|\|\|\|\|\|\|\||=======|>>>>>>>)(?: .*)?$/m;
let conflictSource = null, conflictValue = false;
function hasConflicts() {
  if (state.source !== conflictSource) {
    conflictValue = CONFLICT_MARKER.test(state.source);
    conflictSource = state.source;
  }
  return conflictValue;
}
// A keystroke in a file without markers can only create one on the lines it touched.
function noteTypedChange(before, change) {
  if (conflictSource !== before || conflictValue) return;
  const s = state.source, from = s.lastIndexOf("\n", change.start - 1) + 1;
  let to = s.indexOf("\n", change.start + change.inserted.length); if (to < 0) to = s.length;
  conflictValue = CONFLICT_MARKER.test(s.slice(from, to)); conflictSource = s;
}
function setIfChanged(target, key, value) { if (target[key] !== value) target[key] = value; }
function updateStatus() {
  const saved = state.source === state.savedSource ||
    (unchangedSinceMaterialized() && materialized.before.source === state.savedSource), conflicts = hasConflicts();
  setIfChanged($("status"), "textContent", saved ? "Saved locally" : "Unsaved changes");
  setIfChanged($("status"), "title", "Save on this phone. Sync publishes changes.");
  setIfChanged($("save"), "disabled", saved || conflicts);
  setIfChanged($("mergeMessage"), "hidden", !conflicts);
  setIfChanged($("undo"), "disabled", !state.undo.length); setIfChanged($("redo"), "disabled", !state.redo.length);
}
function unchangedSinceMaterialized() { return Boolean(materialized && !materialized.persisted && state.source === materialized.source); }
/* An Accept from the PDF view that has not been sent becomes an ordinary editor Accept: the
   wrapper is replaced by its text, which stays editable. A Save then carries the decision with
   the edit (a decision claim) and Android retires the queued PDF-view decision, so Fedora
   receives one decision and one source upload, never a resolution plus a stale upload. Closing
   without an edit leaves the queued decision as it was. */
function materializeDecidedElsewhere() {
  materialized = null;
  if (hasConflicts()) return [];
  const groups = new Map();
  for (const [id, d] of Object.entries(decidedElsewhere)) {
    if (!d.materialize) continue;
    const key = d.group || id;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(id);
  }
  if (!groups.size) return [];
  const step = e => ({...e});
  const before = { source: state.source, undo: state.undo.map(step), redo: state.redo.map(step), events: state.events.length };
  const edits = engine.acceptDecidedElsewhere([...groups.values()]);
  if (edits.length) materialized = { source: state.source, before };
  return edits;
}
function restoreBeforeMaterialized() {
  const b = materialized.before; materialized = null;
  Object.assign(state, { source: b.source, undo: b.undo, redo: b.redo }); state.events.length = b.events;
}
/* An offset in the source before [edits] (in order), in the source after them. */
function offsetThrough(pos, edits) {
  for (const e of edits) {
    if (pos >= e.start + e.removed.length) pos += e.inserted.length - e.removed.length;
    else if (pos > e.start) pos = e.start;
  }
  return pos;
}
// What Android holds for this page (ProposalReviewJournal keeps it in memory). A persist sends only
// the source edit since persistedSource, the undo stacks and events after persistedEvents.
let persistedSource = null, persistedEvents = 0;
// Until the user edits, Android keeps the state from before materializeDecidedElsewhere: the
// journal's history is append-only, and closing unedited must leave no trace of the shown Accept.
// A save, or any persist after an edit, sends the real state and the Accept becomes permanent.
let saving = false, persisting = null;
function persistView() {
  if (!materialized || materialized.persisted) return state;
  if (saving || !unchangedSinceMaterialized()) { materialized.persisted = true; return state; }
  const b = materialized.before;
  return { ...state, source: b.source, undo: b.undo, redo: b.redo, events: state.events.slice(0, b.events) };
}
function persistPayload(full) {
  const view = persisting = persistView();
  if (full || persistedSource === null) return JSON.stringify(view);
  const eventBase = Math.min(persistedEvents, view.events.length);
  const patch = { patch: 1, filename: view.filename, baseSha256: view.baseSha256, journalRevision: view.journalRevision,
    undo: view.undo, redo: view.redo, hideProposals: view.hideProposals,
    eventBase, eventCount: view.events.length, events: view.events.slice(eventBase) };
  if (view.source !== persistedSource) {
    const d = textEdit(persistedSource, view.source);
    patch.sourceEdit = { start: d.start, removed: d.removed.length, inserted: d.inserted,
      length: view.source.length, hash: textHash(view.source) };
  }
  return JSON.stringify(patch);
}
function persisted(r) {
  const view = persisting || state; persisting = null;
  if (r.journalRevision !== undefined) state.journalRevision = r.journalRevision;
  persistedSource = view.source;
  persistedEvents = typeof r.eventCount === "number" ? Math.min(r.eventCount, view.events.length) : view.events.length;
}
// call(raw) is a Native method; a patch made against another journal state is answered with
// {resync: true}, and the whole state follows.
function nativeWrite(call) {
  let r = nativeResult(call(persistPayload(false)));
  if (r.resync) r = nativeResult(call(persistPayload(true)));
  return r;
}
let firstPendingInput = 0;
function persist() {
  clearTimeout(pendingPersist); firstPendingInput = 0;
  const r = nativeWrite(raw => Native.persist(raw));
  persisted(r);
  if (r.warning) message(r.warning); updateStatus();
}
// Persist 0.7 s after typing pauses, and at least every 5 s during continuous typing.
function laterPersist() {
  clearTimeout(pendingPersist);
  const now = Date.now(); if (!firstPendingInput) firstPendingInput = now;
  const wait = Math.max(0, Math.min(700, firstPendingInput + 5000 - now));
  pendingPersist = setTimeout(() => { try { persist(); } catch(e) { message(e.message); } }, wait);
  updateStatus();
}
// Typing joins the open undo step only while it continues the same word at the caret, so
// Undo removes a word at a time instead of everything typed since the field was focused.
const TYPING_PAUSE_MS = 1000;
let typingRun = null;
function continuesTyping(run, change, kind, now) {
  if (!run || now - run.at > TYPING_PAUSE_MS || run.kind !== kind) return false;
  if (kind === "insert") return change.start === run.end && !(/\s$/.test(run.text) && /^\S/.test(change.inserted));
  if (kind === "delete") return change.start + change.removed.length === run.end;
  return false;
}
function typeEdit(source) {
  if (source === state.source) return;
  const change = textEdit(state.source, source), now = Date.now();
  const kind = change.removed && change.inserted ? "replace" : change.removed ? "delete" : "insert";
  const last = state.undo.at(-1);
  const before = state.source;
  if (!(last?.typing && continuesTyping(typingRun, change, kind, now) && engine.extend(source, change))) {
    engine.edit(source, change); state.undo.at(-1).typing = true;
  }
  noteTypedChange(before, change);
  typingRun = { at: now, kind, end: change.start + change.inserted.length, text: change.inserted };
  laterPersist();
}
function endTyping() { typingRun = null; if (state.undo.length) state.undo.at(-1).typing = false; }
function mutate(action) {
  // Events are append-only and undo steps are small, so the rollback copy is too.
  const step = e => ({...e});
  const before = { source: state.source, undo: state.undo.map(step), redo: state.redo.map(step),
    events: state.events.length, journalRevision: state.journalRevision, hideProposals: state.hideProposals };
  try { endTyping(); action(); persist(); render(); }
  catch(e) {
    Object.assign(state, { source: before.source, undo: before.undo, redo: before.redo,
      journalRevision: before.journalRevision, hideProposals: before.hideProposals });
    state.events.length = before.events;
    engine = createReviewEngine(state, platform); render(); message(e.message);
  }
}
function grow(t) {
  t.style.height = "auto"; t.style.height = Math.max(24, t.scrollHeight + 2) + "px";
}
function syntax(text, target) {
  target.replaceChildren(); let pos = 0;
  for (const m of text.matchAll(/\\[A-Za-z@]+/g)) {
    target.append(document.createTextNode(text.slice(pos, m.index)), element("span", "texCommand", m[0])); pos = m.index + m[0].length;
  }
  target.append(document.createTextNode(text.slice(pos)), document.createTextNode("\n"));
}
function hasSelection(id) {
  return ["old", "new"].some(role => { const s = selections.get(id + ":" + role); return s && s.end > s.start; });
}
function updateReviewActions(id, t) {
  const actions = $("actions-" + id); if (!actions) return;
  const selected = hasSelection(id);
  actions.classList.toggle("floating", selected);
  for (const b of actions.children) b.setAttribute("aria-label", b.textContent + (selected ? " selected text" : " whole proposal"));
  if (selected && t) {
    const card = $("proposal-" + id), area = card.getBoundingClientRect();
    const marker = t.parentNode.querySelector(".prefixSelected");
    const rect = (marker || t).getBoundingClientRect();
    actions.style.left = Math.max(0, Math.min(rect.left - area.left, area.width - actions.offsetWidth - 4)) + "px";
    actions.style.top = Math.max(44, rect.top - area.top - actions.offsetHeight - 4) + "px";
  } else { actions.style.top = ""; actions.style.left = ""; }
}
function rememberSelection(field, t) {
  if (!field.proposalId || !field.role) return;
  selectedId = field.proposalId;
  const key = field.proposalId + ":" + field.role;
  if (t.selectionEnd > t.selectionStart) selections.set(key, {start:t.selectionStart,end:t.selectionEnd,text:t.value});
  else selections.delete(key);
  t.repaint(); updateReviewActions(field.proposalId, t);
}
function paintRole(field, t, underlay) {
  const siblings = fields.fields.filter(f => f.proposalId === field.proposalId && f.role);
  const old = siblings.find(f => f.role === "old"), newer = siblings.find(f => f.role === "new");
  const oldText = old ? state.source.slice(old.start,old.end) : "";
  const d = oldText.trim() ? require("./wordDiff").changedTokenRanges(oldText, newer ? state.source.slice(newer.start,newer.end) : "") : {oldRanges:[],newRanges:[]};
  const ranges = field.role === "old" ? d.oldRanges : d.newRanges;
  const s = selections.get(field.proposalId + ":" + field.role);
  const bounds = [...new Set([0,t.value.length,...ranges.flatMap(r=>[r.start,r.end]),...(s?[s.start,s.end]:[])])].sort((a,b)=>a-b);
  underlay.replaceChildren();
  for (let i=0;i<bounds.length-1;i++) {
    const a=bounds[i],b=bounds[i+1],changed=ranges.some(r=>a>=r.start&&a<r.end);
    underlay.append(element("span", (changed?(field.role==="old"?"removed":"added"):"")+(s&&a>=s.start&&a<s.end?" prefixSelected":""), t.value.slice(a,b)));
  }
  underlay.append(document.createTextNode("\n"));
}
function editField(field, label, readOnly = false) {
  const stack = element("div", "editStack"), underlay = element("pre", "codeUnderlay"), t = element("textarea");
  underlay.setAttribute("aria-hidden","true");
  t.value=state.source.slice(field.start,field.end); t.readOnly=readOnly;
  t.spellcheck=!readOnly; t.setAttribute("autocorrect","off"); t.setAttribute("autocapitalize","sentences");
  t.setAttribute("aria-label",label); t.dataset.start=field.start; t.dataset.kind=field.role||field.kind||"context";
  if(field.proposalId)t.dataset.proposalId=field.proposalId;
  field.textarea=t; field.stack=stack;
  t.repaint=()=>field.role?paintRole(field,t,underlay):syntax(t.value,underlay);
  let paintTimer, frame;
  t.addEventListener("focus",()=>{focusedField=field;endTyping();});
  t.addEventListener("input",()=>{
    if(readOnly)return;
    try {
      fields.replace(field,t.value); selections.clear(); $("initialSelectionActions").hidden=true;
      if(field.proposalId)updateReviewActions(field.proposalId);
      cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>grow(t));
      clearTimeout(paintTimer);paintTimer=setTimeout(()=>{
        t.repaint();
        if(field.role)fields.fields.filter(f=>f.proposalId===field.proposalId&&f!==field).forEach(f=>f.textarea?.repaint());
      },100);
    } catch(e){message(e.message);}
  });
  for(const event of ["select","keyup","click"])t.addEventListener(event,()=>rememberSelection(field,t));
  t.addEventListener("blur",()=>{
    clearTimeout(paintTimer);t.repaint();endTyping();
    try{persist();}catch(e){message(e.message);}
  });
  stack.append(underlay,t);
  // The counterpart may not have been registered yet; paint after all fields exist.
  requestAnimationFrame(()=>{t.repaint();grow(t);});return stack;
}
function addContext(start,end) {
  // Bound layout and syntax painting to a few paragraphs even for very large files.
  const main=$("editor");
  while(start<end){
    let next=end;
    if(end-start>6000){const newline=state.source.indexOf("\n",start+3000);if(newline>=0&&newline<end)next=newline+1;}
    if(initialTarget&&initialTarget.start<next&&initialTarget.end>next)next=Math.min(end,initialTarget.end);
    const field=fields.add(start,next,{kind:"context"});
    const wrapper=element("div","context");wrapper.append(editField(field,"Source text"));main.append(wrapper);start=next;
  }
}
function prefixRanges(p) {
  const roles = p.oldText.trim() && p.newText.trim() ? ["old","new"] : p.newText.trim() ? ["new"] : ["old"];
  return roles.map(role=>{
    const s=selections.get(p.id+":"+role), g=p.groups[role==="old"?2:3];
    if(!s||s.text!==g.text||s.end<=s.start)throw Error(role==="old"?"Select the corresponding original passage too.":"Select the corresponding proposed passage too.");
    return {start:g.contentStart+s.start,end:g.contentStart+s.end};
  });
}
function decision(id,action,forcePartial=false) {
  if(decidedElsewhere[id]){message(decidedElsewhereHint(decidedElsewhere[id]));return;}
  if(isMoveHalf(id)){message(MOVE_HALF_HINT);return;}
  mutate(()=>{
    const p=engine.proposal(id),partial=forcePartial||hasSelection(id);
    engine.decide(id,action,partial?prefixRanges(p):null);selectedId=id;selections.clear();
  });
}
// Spec 3.6: only a Claude Code session opens on the phone; Codex threads live on the Mac.
function conversationButton(p) {
  if(!/^https:\/\/claude\.ai\/code\/session_[A-Za-z0-9]{1,64}$/.test(p.conversationUrl||"")||typeof Native==="undefined"||!Native.openConversation)return null;
  const b=button("Claude ↗",()=>Native.openConversation(p.conversationUrl),"Open the Claude conversation that wrote this proposal");
  b.className="conversation";return b;
}
function addProposal(p) {
  const card=element("section","proposal");card.id="proposal-"+p.id;
  const top=element("div","proposalHeader"), actions=element("div","reviewActions");actions.id="actions-"+p.id;
  top.append(element("strong","","Agent proposal"));
  const decided=decidedElsewhere[p.id];
  if(decided){
    for(const label of ["Accept","Reject"]){const b=button(label,()=>{});b.disabled=true;b.title=decidedElsewhereHint(decided);actions.append(b);}
    card.classList.add("decidedElsewhere");
    top.append(actions);
    const chat=conversationButton(p);if(chat)top.append(chat);
    card.append(top,element("p","hint",decidedElsewhereHint(decided)));
    if(p.oldText)card.append(element("label","label","Original · read-only"),element("pre","proposalOriginal",p.oldText));
    card.append(element("label","label","Proposed · read-only"),element("pre","proposalProposed",p.newText));
    return card;
  }
  if(isMoveHalf(p.id)){
    for(const label of ["Accept","Reject"]){const b=button(label,()=>{});b.disabled=true;b.title=MOVE_HALF_HINT;actions.append(b);}
  }else actions.append(button("Accept",()=>decision(p.id,"accept"),"Accept whole proposal"),button("Reject",()=>decision(p.id,"reject"),"Reject whole proposal"));
  actions.addEventListener("pointerdown",e=>e.preventDefault());
  const reason=element("div","reason",p.reason);reason.hidden=true;
  const rawPanel=element("div","rawPanel");rawPanel.hidden=!openedRaw.has(p.id);
  const normal=element("div");normal.hidden=openedRaw.has(p.id);
  const raw=button("Raw",()=>{
    if(openedRaw.has(p.id))openedRaw.delete(p.id);else openedRaw.add(p.id);
    endTyping();persist();render();
  },"Show raw proposal");raw.setAttribute("aria-expanded",String(!rawPanel.hidden));
  const why=button("Why?",()=>{reason.hidden=!reason.hidden;why.setAttribute("aria-expanded",String(!reason.hidden));},"Why this change?");
  why.setAttribute("aria-expanded","false");top.append(actions,raw,why);
  const chat=conversationButton(p);if(chat)top.append(chat);
  card.append(top,...(isMoveHalf(p.id)?[element("p","hint",MOVE_HALF_HINT)]:[]),reason,rawPanel,normal);
  if(p.format==="markdown"){
    if(openedRaw.has(p.id)) rawPanel.append(element("pre","rawMarkdown",state.source.slice(p.start,p.end)));
    else {
      if(p.oldText) normal.append(element("label","label","Original · read-only"),element("pre","proposalOriginal",p.oldText));
      normal.append(element("label","label","Proposed · read-only"),element("pre","proposalProposed",p.newText));
    }
  }else if(openedRaw.has(p.id)){
    rawPanel.append(editField(fields.add(p.start,p.end,{kind:"raw",proposalId:p.id}),"Raw proposal TeX"));
  }else{
    const old=fields.add(p.groups[2].contentStart,p.groups[2].contentEnd,{role:"old",proposalId:p.id});
    const newer=fields.add(p.groups[3].contentStart,p.groups[3].contentEnd,{role:"new",proposalId:p.id});
    if(p.oldText){normal.append(element("label","label","Original · read-only"),editField(old,"Original TeX for "+p.id,true));}
    normal.append(element("label","label","Proposed · editable"),editField(newer,"Proposed TeX for "+p.id));
  }
  return card;
}
function addBrokenProposal(issue) {
  const card=element("section","proposal broken");card.id="issue-"+issue.start;
  const top=element("div","proposalHeader"), actions=element("div","reviewActions");
  top.append(element("strong","",issue.id?"Agent proposal "+issue.id:"Agent proposal"));
  for(const label of ["Accept","Reject"]){const b=button(label,()=>{});b.disabled=true;b.title="Repair the proposal source to review it";actions.append(b);}
  top.append(actions);
  card.dataset.code=issue.code;
  card.append(top,element("p","hint",issue.message+" ("+issue.code.replaceAll("_"," ")+")"));
  return card;
}
function render() {
  // Rebuilt textareas only reach their height on the next frame. Hold the old height so the
  // page cannot shrink and clamp the scroll to the top, then restore the reading position.
  const main=$("editor"),scroll=historyMode?editorScrollY:scrollY;
  main.style.minHeight=main.hidden?"":main.offsetHeight+"px";
  $("initialSelectionActions").hidden=true;initialSelection=null;
  message();$("historyPanel").hidden=true;main.hidden=false;historyMode=false;
  main.replaceChildren();fields=createSourceFields(state,typeEdit);
  const parsed=engine.parsed();
  const ps=hasConflicts()?[]:parsed.proposals;
  // Broken wrappers are issues, not proposals: shown in place with decisions disabled, while
  // their source stays ordinary editable text so it can be repaired.
  const broken=hasConflicts()?[]:parsed.issues;
  const items=[...ps.map(p=>({start:p.start,proposal:p})),...broken.map(issue=>({start:issue.start,issue}))]
    .sort((a,b)=>a.start-b.start);
  $("previous").hidden=$("next").hidden=hideProposals||ps.length<2;
  $("visibility").hidden=!ps.length;
  $("visibility").setAttribute("aria-pressed",String(hideProposals));
  $("visibility").setAttribute("aria-label",hideProposals?"Show agent proposals":"Hide agent proposals");
  $("visibility").title=hideProposals?"Show agent proposals":"Hide agent proposals";
  // Hiding is remembered per file, so say so: otherwise a hidden proposal reads as plain text.
  const hiddenCount=hideProposals?ps.filter(p=>!revealedProposals.has(p.id)).length:0;
  $("hiddenProposals").hidden=!hiddenCount;
  $("hiddenProposalsText").textContent=hiddenCount===1?"1 agent proposal hidden":`${hiddenCount} agent proposals hidden`;
  let pos=0;
  for(const {proposal:p,issue} of items){
    if(issue){
      if(issue.start<pos)continue;
      if(issue.start>pos)addContext(pos,issue.start);
      main.append(addBrokenProposal(issue));
      pos=issue.start;
      continue;
    }
    if(p.start<pos)continue;
    if(p.start>pos)addContext(pos,p.start);
    if(hideProposals&&!revealedProposals.has(p.id)){
      main.append(element("div","originalHidden",p.oldText));
    }else main.append(addProposal(p));
    pos=p.end;
  }
  if(pos<state.source.length||!state.source.length){
    if(!state.source.length){const wrap=element("div","context");wrap.append(editField(fields.add(0,0,{kind:"context"}),"Source text"));main.append(wrap);}
    else addContext(pos,state.source.length);
  }
  if(broken.length)message(broken.length===1?"One agent proposal can't be reviewed. Its source remains editable here.":
    broken.length+" agent proposals can't be reviewed. Their source remains editable here.");
  updateStatus();
  // Runs after the textareas' own grow frames, which were requested first.
  requestAnimationFrame(()=>{main.style.minHeight="";window.scrollTo(0,scroll);});
}
function showHistory(id = null) {
  try {
    persist();
  } catch (e) {
    message(e.message);
    return;
  }
  const panel = $("historyPanel");
  if (!historyMode) editorScrollY = scrollY;
  panel.replaceChildren(
    button("‹ Back to source", () => render()),
    element("h2", "", id ? "Proposal history" : "Source history"),
  );
  for (const recovery of (state.mergeRecoveries || []).slice().reverse()) {
    const details=element("details");
    details.append(element("summary","","Draft before merge · "+new Date(recovery.recoveredAt).toLocaleString()),
      element("pre","",recovery.source));
    const record=element("details");
    record.append(element("summary","","Temporary merge recovery"),element("pre","",JSON.stringify(recovery,null,2)));
    details.append(record);panel.append(details);
  }
  const events = state.events
    .filter((e) =>
      id ? e.proposal_id === id : e.snapshot.source_file === state.filename,
    )
    .slice()
    .reverse();
  if (!events.length)
    panel.append(element("p", "hint", "No recorded decisions yet."));
  for (const e of events) {
    const row = element("article", "event");
    row.append(
      element("h3", "", e.kind.replaceAll("_", " ")),
      element(
        "small",
        "",
        new Date(e.decision_at || e.observed_at).toLocaleString() +
          " · " +
          (e.authority === "canonical_source"
            ? "Server confirmation"
            : e.source_state === "saved_file"
              ? "Saved locally"
              : "Local observation"),
      ),
    );
    row.append(element("p", "hint", e.proposal_id));
    if (e.review) {
      const r = e.review;
      row.append(
        element(
          "label",
          "label",
          r.action === "accept" ? "Accepted text" : "Rejected text",
        ),
        element("pre", "", r.accepted_text ?? r.rejected_text ?? ""),
        element("label", "label", "First-recorded corresponding text"),
        element(
          "pre",
          "",
          r.original_corresponding_text ?? "Correspondence unknown",
        ),
        element(
          "p",
          "hint",
          r.differs_from_first_recorded === null
            ? "Whether the wording differs is unknown."
            : r.differs_from_first_recorded
              ? "Edited before review."
              : "Unchanged from first-recorded wording.",
        ),
      );
      if (r.remaining)
        row.append(
          element("label", "label", "Still pending at this decision"),
          element("pre", "", r.remaining.after),
        );
    } else {
      row.append(element("pre", "", e.snapshot.after));
    }
    if (e.related_event_id)
      row.append(
        element("p", "hint", "Related decision: " + e.related_event_id),
      );
    const details = element("details"), record = element("pre", "", JSON.stringify(e, null, 2));
    details.append(element("summary", "", "Exact record"), record);
    // Android stores whole-document evidence once, outside the event; restore it when opened.
    details.addEventListener("toggle", () => {
      if (!details.open || details.dataset.loaded) return;
      details.dataset.loaded = "true";
      try { const raw = Native.eventRecord?.(e.event_id); if (raw) record.textContent = JSON.stringify(JSON.parse(raw), null, 2); }
      catch (error) { /* Keep the stored form. */ }
    });
    row.append(details);
    panel.append(row);
  }
  panel.hidden = false;
  $("editor").hidden = true;
  historyMode = true;
  window.scrollTo(0, 0);
}
function navigate(delta) {
  endTyping();persist();
  const ps=engine.parsed().proposals;if(!ps.length)return;
  const index=ps.findIndex(p=>p.id===selectedId);
  selectedId=ps[(index+delta+ps.length)%ps.length].id;
  $("proposal-"+selectedId)?.scrollIntoView({block:"start"});
}
function save(closeAfter=false, confirmedRemoval=false) {
  message();
  try {
    endTyping();
    persist();
    saving=true;
    let result;
    try{result=nativeWrite(raw=>confirmedRemoval?Native.saveConfirmed(raw):Native.save(raw));}finally{saving=false;}
    // A wrapper removed by hand, not by Accept/Reject: Android asks before saving.
    if(result.confirmProposalRemoval){Native.confirmProposalRemoval(closeAfter);return;}
    state=result.state;materialized=null;persisting=null;engine=createReviewEngine(state,platform);persisted(state);render();
    if(result.warning)message(result.warning);
    if(closeAfter&&!result.reconciled)Native.close();
  }catch(e){message(e.message);}
}
function discardAndClose() {
  try {
    endTyping();
    // Undo unsaved review decisions through the same history path as explicit Undo.
    while(state.source!==state.savedSource&&state.undo.length)engine.undo();
    if(state.source!==state.savedSource)engine.edit(state.savedSource);
    state.redo=[];selections.clear();persist();Native.close();
  }catch(e){message(e.message);}
}
function close() {
  if(historyMode){render();return;}
  try{
    endTyping();
    // Only shown, not edited: put the wrapper back; the PDF-view decision stays queued.
    if(unchangedSinceMaterialized())restoreBeforeMaterialized();
    persist();Native.requestClose(state.source!==state.savedSource);
  }catch(e){message(e.message);}
}
function revealTarget(payload) {
  const start=payload.selectionStart,end=payload.selectionEnd;
  if(typeof start!=="number")return;
  let field=fields.fields.find(f=>f.textarea&&start>=f.start&&end<=f.end);
  if(!field)field=fields.fields.find(f=>f.textarea&&f.start<=start&&f.end>=start);
  if(!field){
    $("navigationMessage").textContent="The selected passage could not be located in the current source.";
    $("navigationMessage").hidden=false;
    Native.initialSelectionReady?.();return;
  }
  const t=field.textarea,a=Math.max(0,start-field.start),b=Math.min(t.value.length,Math.max(a,end-field.start));
  t.focus({preventScroll:true});t.setSelectionRange(a,b);focusedField=field;
  if(b>a){
    rememberSelection(field,t);
    const underlay=field.stack.querySelector(".codeUnderlay");
    if(!field.role){
      underlay.replaceChildren(document.createTextNode(t.value.slice(0,a)),element("span","prefixSelected",t.value.slice(a,b)),document.createTextNode(t.value.slice(b)+"\n"));
    }
    initialSelection={field,start:a,end:b};
  }
  const marker=field.stack.querySelector(".prefixSelected");
  // The invisible marker also positions a safe line-caret fallback without guessing text.
  let caret;
  if(!marker){
    caret=element("span","",t.value.slice(a,a+1)||" ");
    field.stack.querySelector(".codeUnderlay").replaceChildren(document.createTextNode(t.value.slice(0,a)),caret,document.createTextNode(t.value.slice(a+1)+"\n"));
  }
  const rect=(marker||caret||t).getBoundingClientRect();
  window.scrollBy(0,rect.top+rect.height/2-window.innerHeight/2);
  if(b>a){
    const current=(marker||t).getBoundingClientRect(),popup=$("initialSelectionActions");popup.hidden=false;
    popup.style.left=Math.max(8,Math.min(current.left,innerWidth-popup.offsetWidth-8))+"px";
    popup.style.top=(scrollY+current.top-popup.offsetHeight-8)+"px";
    $("cut").disabled=t.readOnly;$("paste").disabled=t.readOnly;
  }
  if(payload.openKeyboard&&!t.readOnly){
    keyboardTarget={textarea:t,start:a,end:b};
    Native.requestKeyboard?.();
  }
  else Native.initialSelectionReady?.();
}
function initialClipboard(action) {
  const s=initialSelection;if(!s)return;const t=s.field.textarea;
  try{
    if(action==="copy"||action==="cut")Native.copy(t.value.slice(s.start,s.end));
    if(action!=="copy"&&!t.readOnly){
      const replacement=action==="paste"?Native.paste():"";
      t.setRangeText(replacement,s.start,s.end,"end");t.dispatchEvent(new Event("input"));
    }
    $("initialSelectionActions").hidden=true;
  }catch(e){message(e.message);}
}
function initialize(payload) {
  state=payload.state;selectedId=payload.proposalId;hideProposals=Boolean(state.hideProposals);
  decidedElsewhere=payload.decidedElsewhere||{};
  if(selectedId)hideProposals=false;
  revealedProposals.clear();
  engine=createReviewEngine(state,platform);
  // Android loaded this state into its journal; the first persist sends only what changed.
  persistedSource=state.source;persistedEvents=state.events.length;
  if(!hasConflicts())engine.observe();persist();
  // Before navigation: its offsets are in the source as shown.
  const accepted=materializeDecidedElsewhere();
  if(accepted.length&&!payload.pdfNavigation){
    const edit=accepted.find(e=>e.id===selectedId);
    if(edit){payload={...payload,selectionStart:edit.start,selectionEnd:edit.start};selectedId=null;}
    else if(typeof payload.selectionStart==="number")payload={...payload,
      selectionStart:offsetThrough(payload.selectionStart,accepted),selectionEnd:offsetThrough(payload.selectionEnd,accepted)};
  }
  if(payload.pdfNavigation){
    const target=resolveSourceNavigation(payload.buildSource,state.source,payload.selectedPdfText,payload.mappedLine,payload.pdfContext,state.filename);
    payload={...payload,...target};
    $("navigationMessage").textContent=target.message;
    $("navigationMessage").hidden=!target.message;
  }else {$("navigationMessage").textContent="";$("navigationMessage").hidden=true;}
  selections.clear();openedRaw.clear();initialTarget={start:payload.selectionStart,end:payload.selectionEnd};
  if(hasConflicts()) {
    payload={...payload,selectionStart:state.source.search(/^<<<<<<< /m),selectionEnd:state.source.search(/^<<<<<<< /m),openKeyboard:false};
    selectedId=null;
  }
  if(typeof payload.selectionStart==="number"){
    for(const p of engine.parsed().proposals){
      if(payload.selectionStart>=p.start&&payload.selectionStart<p.end){
        revealedProposals.add(p.id);
        // A caret on the command/metadata needs the exact raw source field.
        const editable=p.format==="tex"?[[p.originalStart,p.originalEnd],[p.replacementStart,p.replacementEnd]]:[];
        if(!editable.some(([a,b])=>payload.selectionStart>=a&&payload.selectionStart<=b))openedRaw.add(p.id);
      }
    }
  }
  // Opened to place a card's markers by hand: keep them one tap from the clipboard.
  const markers=Array.isArray(payload.cardMarkers)&&payload.cardMarkers.length===2?payload.cardMarkers:null;
  $("markerBar").hidden=!markers;
  if(markers){
    $("copyStartMarker").onclick=()=>Native.copyMarker("Start marker",markers[0]);
    $("copyEndMarker").onclick=()=>Native.copyMarker("End marker",markers[1]);
  }
  $("filename").textContent=state.filename.split("/").at(-1);$("filename").title=state.filename;
  render();
  if(accepted.length)message(accepted.length===1
    ?"Accepted in the PDF view: the accepted text is shown as source text. Edit and Save; Sync sends the decision with your edit."
    :accepted.length+" proposals accepted in the PDF view are shown as source text. Edit and Save; Sync sends the decisions with your edit.");
  requestAnimationFrame(()=>requestAnimationFrame(()=>{
    if(selectedId){
      const broken=engine.parsed().issues.find(issue=>issue.id===selectedId);
      ($("proposal-"+selectedId)||(broken&&$("issue-"+broken.start)))?.scrollIntoView({block:"start"});
    }
    else if(typeof payload.selectionStart==="number")revealTarget(payload);
    else Native.initialSelectionReady?.();
    initialTarget=null;
  }));
}
$("back").onclick=close;$("save").onclick=()=>save(true);
$("history").onclick=()=>historyMode?render():showHistory();
$("undo").onclick=()=>mutate(()=>{engine.undo();selections.clear();});
$("redo").onclick=()=>mutate(()=>{engine.redo();selections.clear();});
$("previous").onclick=()=>navigate(-1);$("next").onclick=()=>navigate(1);
$("visibility").onclick=()=>{
  endTyping();revealedProposals.clear();hideProposals=!hideProposals;state.hideProposals=hideProposals;persist();render();
};
$("showHiddenProposals").onclick=()=>$("visibility").onclick();
for(const action of ["cut","copy","paste"])$(action).onclick=()=>initialClipboard(action);
document.addEventListener("selectionchange",()=>{
  const field=fields?.fields.find(f=>f.textarea===document.activeElement);
  if(field)rememberSelection(field,field.textarea);
});
document.addEventListener("pointerdown",e=>{
  if(!$("initialSelectionActions").contains(e.target))$("initialSelectionActions").hidden=true;
});
function focusKeyboardTarget(){
  const target=keyboardTarget;keyboardTarget=null;
  if(!target||!target.textarea.isConnected||target.textarea.readOnly)return false;
  target.textarea.focus({preventScroll:true});
  target.textarea.setSelectionRange(target.start,target.end);
  return true;
}
window.reviewApp={focusKeyboardTarget,isReady:()=>initialTarget===null&&!keyboardTarget,initialize,getState:()=>state,engine:()=>engine,save,render,flush:persist,close,discardAndClose,
  hasReviewSelection:()=>Boolean(selectedId&&hasSelection(selectedId)),
  decideSelection:action=>selectedId&&decision(selectedId,action,true),
};
Native.editorDiagnostic?.("initialize-start", "ui.js", 0);
try{
  initialize(nativeResult(Native.load()));
  Native.editorDiagnostic?.("initialize-ready", "ui.js", 0);
}catch(e){
  Native.editorDiagnostic?.("initialize-failed", "ui.js", 0);
  message(e.message);
}
