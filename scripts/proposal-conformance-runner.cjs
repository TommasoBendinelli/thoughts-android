#!/usr/bin/env node
// Written for Thoughts. License is GPLv3.
/* Runner for thoughts-server tools/proposal-conformance (results format of compare.py) driving
 * the shared JavaScript proposal model:
 *   android-js : assets/proposal-review/modules.js evaluated like the WebView loads it
 *   vscode     : the extension's vendored proposal-model.js (VSCODE=<extension dir>), if present
 * Spans are converted from UTF-16 to code points; issues and refusals carry spec codes.
 * Usage: node scripts/proposal-conformance-runner.cjs cases.json out.json */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ASSETS = path.join(__dirname, '../app/src/main/assets/proposal-review');
const VSCODE = process.env.VSCODE || path.join(process.env.HOME, 'repos/agentic-tooling-proposal-spec/vscode/agent-proposed-edit-review');

function webViewModel() {
  const context = vm.createContext({ TextEncoder, TextDecoder });
  vm.runInContext(fs.readFileSync(path.join(ASSETS, 'modules.js'), 'utf8'), context);
  return vm.runInContext("require('./proposal-model')", context);
}

function run(model, item) {
  const cp = index => model.codePointOffset(item.source, index);
  const parsed = model.parseProposals(item.path, item.source);
  return {
    parse: {
      proposals: Array.from(parsed.proposals, p => ({
        id: p.id, created_at: p.created_at, conversation_id: p.conversation_id, explanation: p.explanation,
        original: p.original, replacement: p.replacement, span: { start: cp(p.start), end: cp(p.end) },
      })),
      issues: Array.from(parsed.issues, i => ({
        code: i.code, span: { start: cp(i.start), end: cp(i.end) }, ...(i.id !== undefined ? { id: i.id } : {}), message: i.message,
      })),
    },
    decisions: item.decisions.map(d => {
      const result = model.decide(item.path, item.source, d.ids, d.action);
      return result.ok ? { output: result.output } : { refused: result.code };
    }),
  };
}

const corpus = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const capabilities = { spans: true, issues: true, issue_codes: true, decisions: true, refusal_codes: true,
  fields: ['id', 'created_at', 'conversation_id', 'explanation', 'original', 'replacement'] };
const implementations = { 'android-js': webViewModel() };
const vendored = path.join(VSCODE, 'proposal-model.js');
if (fs.existsSync(vendored)) implementations.vscode = require(vendored);
const out = {};
for (const [name, model] of Object.entries(implementations)) {
  out[name] = { capabilities, cases: {}, writers: {} };
  for (const item of corpus.cases) out[name].cases[item.id] = run(model, item);
  for (const writer of corpus.writers) out[name].writers[writer.id] = model.formatProposal(writer.format, writer.fields);
}
fs.writeFileSync(process.argv[3], JSON.stringify(out, null, 1));
