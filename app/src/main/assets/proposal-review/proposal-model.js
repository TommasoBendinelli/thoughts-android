/*
 * Thoughts agent proposals: the shared JavaScript reader for the Android WebView and the
 * VS Code extension. Implements protocol/agent-proposals.md (version 1) of thoughts-server.
 *
 * Single source of truth: thoughts-android app/src/main/assets/proposal-review/proposal-model.js.
 * The VS Code extension vendors a byte-identical copy (scripts/sync-proposal-model.sh there);
 * edit this file only and run both repositories' fixture tests.
 *
 * Offsets are UTF-16 code units (JavaScript string indices). The spec's offsets are code points;
 * use codePointOffset() when exchanging offsets with other implementations.
 *
 * API
 *   sourceFormat(path)                 'tex' | 'markdown' | null (by extension only)
 *   parseProposals(path, text)         {format, proposals, issues}
 *     proposal: id, created_at, conversation_id, explanation, original, replacement, start, end,
 *               format, headerEnd, replacementStart, replacementEnd, groups (TeX arguments),
 *               plus client aliases createdAt, conversationId, reason, oldText, newText, kind,
 *               commandEnd, issues ([] — broken wrappers are issues, never proposals)
 *     issue:    code, start, end, id (when the wrapper's ID was read), message
 *   refusalCode(parsed, id)            null when id names exactly one valid proposal
 *   decidableProposal(parsed, id)      the proposal, or throws an Error carrying .code
 *   decide(path, text, ids, action)    {ok: true, output} | {ok: false, code}
 *   resolutionText(proposal, action)   replacement (accept) or original (reject)
 *   formatProposal(format, fields)     writer output (spec section 8)
 */
(function (root, factory) {
  'use strict';
  if (typeof module === 'object' && module && module.exports) module.exports = factory();
  else root.ThoughtsProposalModel = factory();
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const SPEC_VERSION = 1;
  const ID = /^[a-z0-9][a-z0-9._:-]{0,127}$/;
  const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  const TIMESTAMP = /^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\.[0-9]{1,9})?Z$/;
  // Mandatory since 2026-10-10 (spec 4.3): both values, in this order, then the optional link.
  const TEX_METADATA = /^created=([^,]*),conversation_id=([^,]*)(?:,conversation_url=([\s\S]*))?$/;
  // Spec 3.6: a Claude Code session or a Codex thread.
  const CONVERSATION_URL = /^(?:https:\/\/claude\.ai\/code\/session_[A-Za-z0-9]{1,64}|codex:\/\/threads\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/;
  const ASCII_WS = ' \t\n\r\f\v';
  const COMMAND_NAME = 'agentProposedEdit';
  const COMMAND = '\\' + COMMAND_NAME;
  const ARGUMENTS = 4;
  const VERBATIM_ENVIRONMENTS = new Set(['verbatim', 'verbatim*', 'Verbatim', 'Verbatim*', 'BVerbatim', 'BVerbatim*',
    'LVerbatim', 'LVerbatim*', 'lstlisting', 'minted', 'DocCode']);
  const DEFINERS = new Set(['def', 'gdef', 'edef', 'xdef', 'let', 'newcommand', 'renewcommand', 'providecommand',
    'DeclareRobustCommand', 'NewDocumentCommand', 'RenewDocumentCommand', 'ProvideDocumentCommand',
    'DeclareDocumentCommand', 'newrobustcmd', 'renewrobustcmd', 'providerobustcmd']);
  const CODES = ['unclosed_group', 'missing_argument', 'malformed_marker', 'unmatched_marker',
    'invalid_metadata', 'invalid_id', 'nested_proposal', 'duplicate_id'];
  const MESSAGES = {
    unclosed_group: 'An argument or metadata group of this proposal is never closed, so the rest of the file belongs to it.',
    missing_argument: 'This proposal needs four braced arguments: ID, explanation, original and replacement.',
    malformed_marker: 'This proposal marker line is not a valid start or end marker.',
    unmatched_marker: 'This proposal marker has no matching start or end marker.',
    invalid_metadata: 'This proposal has invalid metadata (creation time, conversation ID or link, header or a blank explanation).',
    invalid_id: 'This proposal ID is invalid; IDs use lowercase letters, digits and . _ : - (at most 128 characters).',
    nested_proposal: 'This proposal contains another proposal; revise the outer proposal instead.',
    duplicate_id: 'Another proposal in this file has the same ID.',
    unknown_id: 'No proposal with this ID exists in the file.'
  };

  // ------------------------------------------------------------------ common

  function isAsciiSpace(ch) { return ch !== undefined && ch !== '' && ASCII_WS.indexOf(ch) >= 0; }
  function trimAscii(value) {
    let a = 0, b = value.length;
    while (a < b && isAsciiSpace(value[a])) a += 1;
    while (b > a && isAsciiSpace(value[b - 1])) b -= 1;
    return value.slice(a, b);
  }
  function blank(value) { return trimAscii(value) === ''; }
  function isLeap(year) { return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0; }
  function validTimestamp(value) {
    const m = typeof value === 'string' ? TIMESTAMP.exec(value) : null;
    if (!m) return false;
    const [year, month, day, hour, minute, second] = m.slice(1, 7).map(Number);
    if (year === 0 || month < 1 || month > 12) return false;
    const days = [31, isLeap(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
    return day >= 1 && day <= days && hour <= 23 && minute <= 59 && second <= 59;
  }
  function validId(value) { return typeof value === 'string' && ID.test(value); }
  function validUuid(value) { return typeof value === 'string' && UUID.test(value); }
  function validConversationUrl(value) { return typeof value === 'string' && CONVERSATION_URL.test(value); }
  function proposalKind(original, replacement) {
    if (!original.length && replacement.length) return 'Addition';
    if (original.length && !replacement.length) return 'Deletion';
    return 'Edit';
  }
  // Find needle fully inside [from, hi).
  function findWithin(text, needle, from, hi) {
    const at = text.indexOf(needle, from);
    return at >= 0 && at + needle.length <= hi ? at : -1;
  }

  // ------------------------------------------------------------------ LaTeX (section 4)

  function isTexLetter(ch) {
    return ch !== undefined && ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch === '@');
  }
  function escaped(text, i) {
    let count = 0;
    while (i - 1 - count >= 0 && text[i - 1 - count] === '\\') count += 1;
    return count % 2 === 1;
  }
  function skipSpace(text, i) {
    const n = text.length;
    while (i < n) {
      if (isAsciiSpace(text[i])) i += 1;
      else if (text[i] === '%' && !escaped(text, i)) {
        const newline = text.indexOf('\n', i);
        i = newline < 0 ? n : newline + 1;
      } else break;
    }
    return i;
  }
  // text[i] === '{'. Returns {open, contentStart, contentEnd, end, text} or null when unclosed.
  function group(text, i) {
    let depth = 1, j = i + 1;
    const n = text.length;
    while (j < n) {
      const c = text[j];
      if (c === '\\') { j += 2; continue; }
      if (c === '%') {
        const newline = text.indexOf('\n', j);
        if (newline < 0) return null;
        j = newline + 1;
        continue;
      }
      if (c === '{') depth += 1;
      else if (c === '}') {
        depth -= 1;
        if (depth === 0) return { open: i, contentStart: i + 1, contentEnd: j, end: j + 1, text: text.slice(i + 1, j) };
      }
      j += 1;
    }
    return null;
  }
  function isDefinition(text, start) {
    let k = start;
    const back = () => { while (k > 0 && isAsciiSpace(text[k - 1])) k -= 1; };
    back();
    if (k > 0 && text[k - 1] === '{') { k -= 1; back(); }
    if (k > 0 && text[k - 1] === '*') { k -= 1; back(); }
    let j = k;
    while (j > 0 && /[A-Za-z@]/.test(text[j - 1])) j -= 1;
    return j < k && j > 0 && text[j - 1] === '\\' && !escaped(text, j - 1) && DEFINERS.has(text.slice(j, k));
  }
  function codePointAt(text, k) { return String.fromCodePoint(text.codePointAt(k)); }
  // The next live wrapper candidate in [i, hi): {start, after} (after = end of the command name).
  function nextCandidate(text, i, hi) {
    while (i < hi) {
      const c = text[i];
      if (c === '%') {
        const newline = findWithin(text, '\n', i, hi);
        i = newline < 0 ? hi : newline + 1;
        continue;
      }
      if (c !== '\\') { i += 1; continue; }
      let after = i + 1;
      while (after < hi && isTexLetter(text[after])) after += 1;
      if (after === i + 1) { i += 2; continue; }
      const word = text.slice(i + 1, after);
      if (word === 'begin') {
        let k = after;
        while (k < hi && (text[k] === ' ' || text[k] === '\t')) k += 1;
        if (k < hi && text[k] === '{') {
          const close = findWithin(text, '}', k + 1, hi);
          const name = close >= 0 ? text.slice(k + 1, close) : '';
          if (VERBATIM_ENVIRONMENTS.has(name)) {
            const closing = '\\end{' + name + '}';
            const end = findWithin(text, closing, close + 1, hi);
            i = end < 0 ? hi : end + closing.length;
            continue;
          }
        }
      }
      if (word === 'verb' || word === 'lstinline') {
        const k = after + (word === 'verb' && after < hi && text[after] === '*' ? 1 : 0);
        if (k < hi) {
          const delimiter = codePointAt(text, k);
          if (!isAsciiSpace(delimiter) && delimiter !== '[' && delimiter !== '{' && !/^\p{L}$/u.test(delimiter)) {
            const close = findWithin(text, delimiter, k + delimiter.length, hi);
            const newline = findWithin(text, '\n', k + 1, hi);
            if (close >= 0 && (newline < 0 || close < newline)) { i = close + delimiter.length; continue; }
          }
        }
      }
      if (word === COMMAND_NAME && !isDefinition(text, i)) {
        const k = skipSpace(text, after);
        if (text[k] === '{' || text[k] === '[') return { start: i, after };
      }
      i = after;
    }
    return null;
  }

  function parseTex(text) {
    const wrappers = [];
    const n = text.length;
    let i = 0;
    for (;;) {
      const found = nextCandidate(text, i, n);
      if (!found) break;
      const start = found.start;
      let cursor = found.after;
      let ident;
      // A wrapper without metadata is invalid_metadata (spec 4.3).
      let metadataError = true, createdAt = '', conversation = '', conversationUrl, metadataRange = null;
      let k = skipSpace(text, cursor);
      if (text[k] === '[') {
        const close = text.indexOf(']', k + 1);
        if (close < 0) { wrappers.push({ code: 'unclosed_group', start, end: n }); break; }
        const m = TEX_METADATA.exec(trimAscii(text.slice(k + 1, close)));
        if (m && validTimestamp(m[1]) && validUuid(m[2]) && (m[3] === undefined || validConversationUrl(m[3]))) {
          metadataError = false; createdAt = m[1]; conversation = m[2]; conversationUrl = m[3];
        }
        metadataRange = { start: k, end: close + 1 };
        cursor = close + 1;
      }
      const groups = [];
      let unclosed = false;
      for (let a = 0; a < ARGUMENTS; a += 1) {
        k = skipSpace(text, cursor);
        if (k >= n || text[k] !== '{') break;
        const g = group(text, k);
        if (!g) { unclosed = true; break; }
        groups.push(g);
        cursor = g.end;
        if (groups.length === 1) ident = trimAscii(g.text);
      }
      const item = { start, end: cursor };
      if (ident !== undefined) item.id = ident;
      if (unclosed) { wrappers.push(Object.assign(item, { code: 'unclosed_group', end: n })); break; }
      i = cursor;
      if (groups.length < ARGUMENTS) { wrappers.push(Object.assign(item, { code: 'missing_argument' })); continue; }
      let code = null;
      if (metadataError || blank(groups[1].text)) code = 'invalid_metadata';
      else if (!validId(ident)) code = 'invalid_id';
      else if (nextCandidate(text, groups[0].contentStart, groups[ARGUMENTS - 1].contentEnd)) code = 'nested_proposal';
      if (code) { wrappers.push(Object.assign(item, { code })); continue; }
      const explanation = groups[1].text;
      const headerEnd = metadataRange ? metadataRange.end : found.after;
      wrappers.push(Object.assign(item, {
        code: null,
        proposal: makeProposal('tex', {
          id: ident, created_at: createdAt, conversation_id: conversation, conversation_url: conversationUrl, explanation,
          original: groups[2].text, replacement: groups[3].text, start, end: cursor,
          headerEnd, replacementStart: groups[3].contentStart, replacementEnd: groups[3].contentEnd,
          originalStart: groups[2].contentStart, originalEnd: groups[2].contentEnd, metadata: metadataRange, groups
        })
      }));
    }
    return finish('tex', wrappers);
  }

  // ------------------------------------------------------------------ Markdown (section 5)

  const PREFIX = '<!-- thoughts:agent-proposal:';
  const V2_START = '<!-- thoughts:agent-proposal:v2:start';
  const V1_START = /^<!-- thoughts:agent-proposal:v1:start ([^ ]*) -->$/;
  const END = /^<!-- thoughts:agent-proposal:(v1|v2):end ([\s\S]*) -->$/;

  function line(text, s) {
    const newline = text.indexOf('\n', s);
    return newline < 0 ? { content: text.slice(s), after: text.length, terminated: false }
      : { content: text.slice(s, newline), after: newline + 1, terminated: true };
  }
  function nextMarker(text, i, hi) {
    for (;;) {
      const s = findWithin(text, PREFIX, i, hi);
      if (s < 0) return -1;
      if (s === 0 || text[s - 1] === '\n') return s;
      i = s + 1;
    }
  }
  function findEnd(text, version, ident, i) {
    const marker = '<!-- thoughts:agent-proposal:' + version + ':end ' + ident + ' -->\n';
    for (;;) {
      const e = text.indexOf(marker, i);
      if (e < 0) return null;
      if (e === 0 || text[e - 1] === '\n') return { start: e, end: e + marker.length };
      i = e + 1;
    }
  }
  function decodeBlock(lines, position, label) {
    if (position >= lines.length || lines[position] !== label) return null;
    position += 1;
    const parts = [];
    while (position < lines.length && lines[position].startsWith('| ')) {
      parts.push(lines[position].slice(2).split('--&gt;').join('-->').split('&amp;').join('&'));
      position += 1;
    }
    return { value: parts.join('\n'), position };
  }
  function v2Header(lines) {
    const ident = lines.length && lines[0].startsWith('id: ') ? lines[0].slice(4) : undefined;
    if (lines.length < 3 || !lines[1].startsWith('created_at: ') || !lines[2].startsWith('conversation_id: ')) return { fields: null, ident };
    let position = 3, conversationUrl;
    if (position < lines.length && lines[position].startsWith('conversation_url: ')) {
      conversationUrl = lines[position].slice('conversation_url: '.length);
      position += 1;
    }
    if (position < lines.length && lines[position].startsWith('replacement_length: ')) position += 1;
    const explanation = decodeBlock(lines, position, 'explanation:');
    if (!explanation) return { fields: null, ident };
    const original = decodeBlock(lines, explanation.position, 'original_text:');
    if (!original || original.position !== lines.length) return { fields: null, ident };
    return {
      ident,
      fields: {
        created_at: lines[1].slice('created_at: '.length), conversation_id: lines[2].slice('conversation_id: '.length),
        conversation_url: conversationUrl, explanation: explanation.value, original: original.value
      }
    };
  }
  const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  function base64UrlDecode(encoded) {
    if (encoded.length % 4 === 1) return null;
    const bytes = [];
    let buffer = 0, bits = 0;
    for (const ch of encoded) {
      buffer = (buffer << 6) | B64.indexOf(ch);
      bits += 6;
      if (bits >= 8) { bits -= 8; bytes.push((buffer >> bits) & 0xff); }
    }
    return new Uint8Array(bytes);
  }
  function base64UrlEncode(bytes) {
    let out = '', buffer = 0, bits = 0;
    for (const byte of bytes) {
      buffer = (buffer << 8) | byte;
      bits += 8;
      while (bits >= 6) { bits -= 6; out += B64[(buffer >> bits) & 63]; }
      buffer &= 0xff;
    }
    if (bits > 0) out += B64[(buffer << (6 - bits)) & 63];
    return out;
  }
  function v1Header(encoded) {
    if (!/^[A-Za-z0-9_-]+$/.test(encoded)) return { fields: null, ident: undefined };
    let data;
    try {
      const raw = base64UrlDecode(encoded);
      if (!raw || base64UrlEncode(raw) !== encoded) return { fields: null, ident: undefined };
      data = JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(raw));
    } catch (error) {
      return { fields: null, ident: undefined };
    }
    if (!data || typeof data !== 'object' || Array.isArray(data)) return { fields: null, ident: undefined };
    const ident = typeof data.id === 'string' ? data.id : undefined;
    const fields = { created_at: data.created_at, conversation_id: data.conversation_id,
      explanation: data.explanation, original: data.original_text };
    if (!Object.values(fields).every(value => typeof value === 'string')) return { fields: null, ident };
    if (data.conversation_url !== undefined && typeof data.conversation_url !== 'string') return { fields: null, ident };
    return { fields: Object.assign(fields, { conversation_url: data.conversation_url }), ident };
  }

  function parseMarkdown(text) {
    const wrappers = [];
    const n = text.length;
    let i = 0;
    for (;;) {
      const s = nextMarker(text, i, n);
      if (s < 0) break;
      const first = line(text, s);
      let version, headerEnd, header;
      if (first.terminated && first.content === V2_START) {
        version = 'v2';
        let j = first.after;
        const lines = [];
        headerEnd = -1;
        while (j < n) {
          const l = line(text, j);
          if (l.content === '-->' && l.terminated) { headerEnd = l.after; break; }
          lines.push(l.content);
          j = l.after;
        }
        if (headerEnd < 0) { wrappers.push({ code: 'unclosed_group', start: s, end: n }); break; }
        header = v2Header(lines);
      } else if (first.terminated && V1_START.test(first.content)) {
        version = 'v1';
        headerEnd = first.after;
        header = v1Header(V1_START.exec(first.content)[1]);
      } else if (first.terminated && END.test(first.content)) {
        // The ID names the issue for refusals but does not count towards duplicates.
        wrappers.push({ code: 'unmatched_marker', start: s, end: first.after, id: END.exec(first.content)[2], orphanEnd: true });
        i = first.after;
        continue;
      } else {
        wrappers.push({ code: 'malformed_marker', start: s, end: first.after });
        i = first.after;
        continue;
      }
      const ident = header.ident;
      if (!ident) { // no ID or an empty one: the start cannot be paired with an end marker
        wrappers.push({ code: 'invalid_metadata', start: s, end: headerEnd });
        i = headerEnd;
        continue;
      }
      const end = findEnd(text, version, ident, headerEnd);
      if (!end) {
        wrappers.push({ code: 'unmatched_marker', start: s, end: headerEnd, id: ident });
        i = headerEnd;
        continue;
      }
      i = end.end;
      const item = { start: s, end: end.end, id: ident };
      const f = header.fields;
      if (!f || !validTimestamp(f.created_at) || !validUuid(f.conversation_id) || blank(f.explanation)
          || (f.conversation_url !== undefined && !validConversationUrl(f.conversation_url))) {
        wrappers.push(Object.assign(item, { code: 'invalid_metadata' }));
      } else if (!validId(ident)) {
        wrappers.push(Object.assign(item, { code: 'invalid_id' }));
      } else if (nextMarker(text, headerEnd, end.start) >= 0) {
        wrappers.push(Object.assign(item, { code: 'nested_proposal' }));
      } else {
        wrappers.push(Object.assign(item, {
          code: null,
          proposal: makeProposal('markdown', {
            id: ident, created_at: f.created_at, conversation_id: f.conversation_id, conversation_url: f.conversation_url,
            explanation: f.explanation,
            original: f.original, replacement: text.slice(headerEnd, end.start), start: s, end: end.end,
            headerEnd, replacementStart: headerEnd, replacementEnd: end.start, version: version === 'v1' ? 1 : 2
          })
        }));
      }
    }
    return finish('markdown', wrappers);
  }

  // ------------------------------------------------------------------ model

  function makeProposal(format, fields) {
    const p = {
      id: fields.id, created_at: fields.created_at, conversation_id: fields.conversation_id,
      explanation: fields.explanation, original: fields.original, replacement: fields.replacement,
      start: fields.start, end: fields.end, format,
      headerEnd: fields.headerEnd, replacementStart: fields.replacementStart, replacementEnd: fields.replacementEnd
    };
    // Spec 3.6: absent, not empty, when the proposal has no link.
    if (fields.conversation_url !== undefined) p.conversation_url = fields.conversation_url;
    if (format === 'tex') {
      p.originalStart = fields.originalStart;
      p.originalEnd = fields.originalEnd;
      p.metadata = fields.metadata;
      p.groups = fields.groups;
    } else {
      p.version = fields.version;
      p.contentStart = fields.replacementStart;
      p.contentEnd = fields.replacementEnd;
    }
    // Client aliases used by the review UIs and proposal history.
    p.createdAt = p.created_at;
    p.conversationId = p.conversation_id;
    p.conversationUrl = p.conversation_url;
    p.reason = p.explanation;
    p.oldText = p.original;
    p.newText = p.replacement;
    p.kind = proposalKind(p.original, p.replacement);
    p.commandEnd = p.headerEnd;
    p.issues = [];
    return p;
  }

  function issueMessage(code) { return MESSAGES[code] || code; }

  function finish(format, wrappers) {
    const counts = new Map();
    for (const w of wrappers) if (w.id !== undefined && !w.orphanEnd) counts.set(w.id, (counts.get(w.id) || 0) + 1);
    const proposals = [], issues = [];
    for (const w of wrappers) {
      let code = w.code;
      if (code === null && counts.get(w.id) > 1) code = 'duplicate_id';
      if (code === null) proposals.push(w.proposal);
      else {
        const issue = { code, start: w.start, end: w.end };
        if (w.id !== undefined) issue.id = w.id;
        issue.message = issueMessage(code);
        issues.push(issue);
      }
    }
    issues.sort((a, b) => a.start - b.start);
    return { format, proposals, issues };
  }

  // ------------------------------------------------------------------ dispatch and decisions (sections 2, 7)

  function sourceFormat(path) {
    const lower = String(path || '').toLowerCase();
    if (lower.endsWith('.tex')) return 'tex';
    if (lower.endsWith('.md') || lower.endsWith('.markdown')) return 'markdown';
    return null;
  }

  function parseProposals(path, text) {
    const format = sourceFormat(path);
    if (format === 'tex') return parseTex(text);
    if (format === 'markdown') return parseMarkdown(text);
    return { format: null, proposals: [], issues: [] };
  }

  function refusalCode(parsed, id) {
    const matches = parsed.proposals.filter(p => p.id === id);
    if (matches.length === 1) return null;
    const flagged = parsed.issues.find(issue => issue.id === id);
    return flagged ? flagged.code : 'unknown_id';
  }

  function refusalError(code, id) {
    const error = new Error(`Proposal ${JSON.stringify(id)} cannot be decided: ${issueMessage(code)}`);
    error.code = code;
    return error;
  }

  function decidableProposal(parsed, id) {
    const code = refusalCode(parsed, id);
    if (code) throw refusalError(code, id);
    return parsed.proposals.find(p => p.id === id);
  }

  function resolutionText(proposal, action) {
    if (action === 'accept') return proposal.replacement;
    if (action === 'reject') return proposal.original;
    throw new Error(`Unknown proposal decision ${JSON.stringify(action)}.`);
  }

  function decide(path, text, ids, action) {
    if (!ids || !ids.length) return { ok: false, code: 'unknown_id' };
    const parsed = parseProposals(path, text);
    const chosen = [];
    for (const id of ids) {
      const code = refusalCode(parsed, id);
      if (code) return { ok: false, code };
      const p = parsed.proposals.find(candidate => candidate.id === id);
      if (!chosen.includes(p)) chosen.push(p);
    }
    let output = text;
    for (const p of chosen.slice().sort((a, b) => b.start - a.start)) {
      output = output.slice(0, p.start) + resolutionText(p, action) + output.slice(p.end);
    }
    return { ok: true, output };
  }

  // ------------------------------------------------------------------ writers (section 8)

  const TEX_ESCAPES = { '{': '\\{', '}': '\\}', '%': '\\%', '$': '\\$', '&': '\\&', '#': '\\#', '_': '\\_',
    '\\': '\\textbackslash{}', '^': '\\textasciicircum{}', '~': '\\textasciitilde{}' };
  function escapeTexExplanation(value) { return Array.from(value, ch => TEX_ESCAPES[ch] || ch).join(''); }
  function markdownBlock(value) {
    if (value === '') return '';
    return value.split('\n').map(l => '| ' + l.split('&').join('&amp;').split('-->').join('--&gt;') + '\n').join('');
  }
  function formatProposal(format, fields) {
    const explanation = trimAscii(fields.explanation || '');
    if (!validId(fields.id)) throw new Error('Invalid proposal ID.');
    const url = fields.conversation_url;
    if (!validTimestamp(fields.created_at) || !validUuid(fields.conversation_id) || !explanation
        || (url !== undefined && !validConversationUrl(url))) throw new Error('Invalid proposal metadata.');
    if (format === 'tex') {
      return `\\agentProposedEdit[created=${fields.created_at},conversation_id=${fields.conversation_id}${url ? ',conversation_url=' + url : ''}]` +
        `{${fields.id}}{${escapeTexExplanation(explanation)}\n}{${fields.original}}{${fields.replacement}}`;
    }
    if (format === 'markdown') {
      let replacement = fields.replacement;
      if (replacement && !replacement.endsWith('\n')) replacement += '\n';
      return `${V2_START}\nid: ${fields.id}\ncreated_at: ${fields.created_at}\nconversation_id: ${fields.conversation_id}\n` +
        (url ? `conversation_url: ${url}\n` : '') +
        `explanation:\n${markdownBlock(explanation)}original_text:\n${markdownBlock(fields.original)}-->\n` +
        `${replacement}<!-- thoughts:agent-proposal:v2:end ${fields.id} -->\n`;
    }
    throw new Error('Unsupported proposal format.');
  }

  // ------------------------------------------------------------------ offsets

  function codePointOffset(text, index) {
    let count = 0;
    for (let i = 0; i < index; i += 1) {
      const c = text.charCodeAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < index) {
        const d = text.charCodeAt(i + 1);
        if (d >= 0xdc00 && d <= 0xdfff) i += 1;
      }
      count += 1;
    }
    return count;
  }

  return {
    SPEC_VERSION, COMMAND, ID_PATTERN: ID, CODES, MESSAGES,
    sourceFormat, parseProposals, refusalCode, decidableProposal, decide, resolutionText, proposalKind,
    formatProposal, escapeTexExplanation, validTimestamp, validId, validConversationUrl, codePointOffset, issueMessage
  };
});
