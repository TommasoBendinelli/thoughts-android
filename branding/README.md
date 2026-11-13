# Thoughts icon

`thoughts-icon.svg` is the canonical full-color application artwork shared by Android and macOS.

`thoughts-icon-round.svg`, `thoughts-glyph.svg`, and `thoughts-monochrome.svg` are the round, adaptive, and themed Android variants of the same mark.

Regenerate every tracked launcher asset with:

```sh
python3 branding/generate_thoughts_icons.py --desktop-root ../thoughts-desktop
```

The Android application ID is `org.tbend.thoughts`, while inherited upstream source namespaces and icon filenames remain unchanged.
