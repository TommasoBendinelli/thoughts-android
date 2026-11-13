#!/usr/bin/env node
// Written for Thoughts. License is GPLv3.
/* Bundle the CommonJS review modules into assets/proposal-review/modules.js for the offline
 * WebView (it has no module loader). Run after editing proposal-model.js, partialReview.js or
 * wordDiff.js; scripts/test-proposal-review.cjs fails when modules.js is stale.
 *   node scripts/build-proposal-review-modules.cjs [--check] */
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../app/src/main/assets/proposal-review');
const MODULES = ['proposal-model', 'partialReview', 'wordDiff'];

function bundle() {
  const parts = ['const modules = {};\nfunction require(name) { return modules[name]; }\n'];
  for (const name of MODULES) {
    const source = fs.readFileSync(path.join(root, `${name}.js`), 'utf8');
    parts.push(`(function() { const module = {exports: {}};\n${source}\nmodules['./${name}'] = module.exports; })();\n`);
  }
  return parts.join('\n');
}

if (require.main === module) {
  const target = path.join(root, 'modules.js');
  const expected = bundle();
  if (process.argv.includes('--check')) {
    if (fs.readFileSync(target, 'utf8') !== expected) {
      console.error('modules.js is stale; run node scripts/build-proposal-review-modules.cjs');
      process.exit(1);
    }
  } else fs.writeFileSync(target, expected);
}
module.exports = { bundle };
