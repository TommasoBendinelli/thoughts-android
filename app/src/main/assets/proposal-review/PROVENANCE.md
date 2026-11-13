proposal-model.js is the single source of truth for reading agent proposals (thoughts-server protocol/agent-proposals.md).
The agent-proposed-edit-review VS Code extension vendors byte-identical copies of proposal-model.js and partialReview.js; sync them with its scripts/sync-proposal-model.sh.
partialReview and wordDiff originate from that extension's version 0.3.0.
modules.js bundles proposal-model.js, partialReview.js and wordDiff.js for the offline WebView; regenerate it with node scripts/build-proposal-review-modules.cjs.
history.js retains the extension event construction with native Android persistence and hashing adapters.
Run node --test scripts/test-proposal-review.cjs to check the spec fixtures, engine parity and history.
