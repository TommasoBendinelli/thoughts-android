# Thoughts maintenance tools

This directory is the canonical home for the remaining Thoughts maintenance helpers.

Phone synchronization, rendering, source editing, and review operations use the standalone Thoughts server; there are no Mac queue processors or Syncthing transport helpers here.

`publish_phone_source_bundle.py` generates the content-addressed offline source artifact consumed by that server and writes to `THOUGHTS_SOURCE_BUNDLE_OUTPUT` when configured.

Run the focused helper tests with:

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py'
```
