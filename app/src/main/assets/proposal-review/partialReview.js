'use strict';

const { parseProposals, resolutionText } = require('./proposal-model');

// A prefix must stand alone as TeX; never split a group, environment, comment,
// control sequence, math span, or verbatim construct across the proposal wrapper.
function balancedPrefix(text) {
  let braces = 0;
  let math = null;
  const environments = [];
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (ch === '%') {
      const end = text.indexOf('\n', i);
      if (end < 0) return false;
      i = end;
    } else if (ch === '\\') {
      const tail = text.slice(i);
      if (/^\\(?:verb\*?|begin\s*\{(?:verbatim\*?|lstlisting|minted|DocCode)\})/.test(tail)) return false;
      const env = /^\\(begin|end)\s*\{([^}]+)\}/.exec(tail);
      if (env) {
        if (env[1] === 'begin') environments.push(env[2]);
        else if (environments.pop() !== env[2]) return false;
        i += env[0].length - 1;
      } else if ('([)]'.includes(text[i + 1] || ' ')) {
        const token = text[++i];
        if (token === '(' || token === '[') { if (math) return false; math = token; }
        else { if (math !== (token === ')' ? '(' : '[')) return false; math = null; }
      } else {
        const command = /^\\[A-Za-z@]+/.exec(tail);
        i += command ? command[0].length - 1 : 1;
      }
    } else if (ch === '{') braces += 1;
    else if (ch === '}') { if (--braces < 0) return false; }
    else if (ch === '$') {
      const token = text[i + 1] === '$' ? '$$' : '$';
      if (token === '$$') i += 1;
      if (math && math !== token) return false;
      math = math ? null : token;
    }
  }
  return braces === 0 && !math && environments.length === 0 && !/\\[A-Za-z@]+\s*$/.test(text);
}

function prefixEnd(text, proposal, index, selections) {
  const group = proposal.groups[index];
  const selection = selections.find(s => s.start >= group.contentStart && s.end <= group.contentEnd && s.end > s.start);
  if (!selection) throw new Error(index === 2
    ? 'For a replacement, also select the corresponding prefix in the original (third) argument using a second selection.'
    : 'Select the beginning of the proposed (fourth) argument first.');
  const start = selection.start - group.contentStart;
  let end = selection.end - group.contentStart;
  if (group.text.slice(0, start).trim()) throw new Error('Only the top part can be reviewed. Start at the beginning of the argument.');
  // Include a selected line's trailing newline, but never consume the next line.
  const trailing = /^[ \t]*\r?\n/.exec(group.text.slice(end));
  if (trailing) end += trailing[0].length;
  if (!group.text.slice(0, end).trim()) throw new Error('Select some proposal text.');
  if (end < group.text.length && /[\p{L}\p{M}\p{N}_]/u.test(group.text[end - 1]) && /[\p{L}\p{M}\p{N}_]/u.test(group.text[end])) {
    throw new Error('Finish the selection at a word boundary.');
  }
  if (/[\uD800-\uDBFF]/.test(group.text[end - 1]) && /[\uDC00-\uDFFF]/.test(group.text[end] || '')) throw new Error('Finish the selection after the complete Unicode character.');
  if (!balancedPrefix(group.text.slice(0, end))) throw new Error('Extend the selection to include complete TeX groups, environments, and math.');
  return end;
}

// Partial decisions are a client feature (agent-proposal spec section 7): the result is an
// ordinary source edit that keeps a valid wrapper with the same ID, metadata and explanation
// around the undecided rest. `proposal` must be a valid proposal from proposal-model.js.
function planDecision(text, proposal, decision, selections = null) {
  if (!['accept', 'reject'].includes(decision)) throw new Error('Unknown review decision.');
  if (selections && proposal.format === 'markdown') throw new Error('Markdown proposals support whole decisions only.');
  if (!selections) return {
    replacement: resolutionText(proposal, decision), oldEnd: proposal.oldText.length,
    newEnd: proposal.newText.length, remaining: null, decision, partial: false
  };
  const expected = proposal.oldText.trim() ? 2 : 1;
  if (selections.length !== expected) throw new Error(expected === 2
    ? 'Select both the proposed prefix and its corresponding original prefix using two selections.'
    : 'Use one selection at the beginning of the proposed text.');
  const newEnd = prefixEnd(text, proposal, 3, selections);
  const oldEnd = proposal.oldText.trim() ? prefixEnd(text, proposal, 2, selections) : proposal.oldText.length;
  const oldRest = proposal.oldText.slice(oldEnd);
  const newRest = proposal.newText.slice(newEnd);
  const complete = !oldRest.trim() && !newRest.trim();
  if ((!oldRest.trim() !== !newRest.trim()) && proposal.oldText.trim()) {
    throw new Error('Select corresponding prefixes on both sides; resolve the whole proposal to remove an entire side.');
  }
  let wrapper = '';
  if (!complete) {
    // Splice only argument contents; preserve ID, rationale, spacing, and the
    // untouched portions of both arguments byte for byte.
    wrapper = text.slice(proposal.start, proposal.groups[2].contentStart) + oldRest +
      text.slice(proposal.groups[2].contentEnd, proposal.groups[3].contentStart) + newRest +
      text.slice(proposal.groups[3].contentEnd, proposal.end);
  }
  const chosen = decision === 'accept' ? proposal.newText.slice(0, newEnd) : proposal.oldText.slice(0, oldEnd);
  const separator = wrapper && chosen && !chosen.endsWith('\n') ? '\n' : '';
  const replacement = chosen + separator + wrapper;
  if (wrapper) {
    const parsed = parseProposals('remainder.tex', wrapper);
    if (parsed.issues.length || parsed.proposals.length !== 1 || parsed.proposals[0].id !== proposal.id ||
        parsed.proposals[0].start !== 0 || parsed.proposals[0].end !== wrapper.length) {
      throw new Error('The selection would leave invalid proposal structure.');
    }
  }
  return { replacement, oldEnd, newEnd, remaining: complete ? null : { before: oldRest, after: newRest }, decision, partial: !complete };
}

// A unique unchanged suffix gives an exact prefix correspondence, including
// edits within that prefix. Ambiguous/missing anchors remain explicitly unknown.
function correspondingPrefix(original, current, end) {
  if (original === current) return { text: original.slice(0, end), end, method: 'exact' };
  const suffix = current.slice(end);
  if (!suffix.length) return { text: original, end: original.length, method: 'whole_remaining' };
  const position = original.indexOf(suffix);
  if (position < 0 || original.indexOf(suffix, position + 1) >= 0) return null;
  return { text: original.slice(0, position), end: position, method: 'unique_unchanged_suffix' };
}

module.exports = { balancedPrefix, planDecision, correspondingPrefix };
