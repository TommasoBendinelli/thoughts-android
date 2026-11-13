server-cli.zstpatch was produced by the server's zstd --patch-from encoding:
base: concatenate "PDF source line {i}: unchanged content for dictionary verification.\n" for i=0..11999.
result: replace "PDF source line 6000: unchanged content" with "PDF source line 6000: edited content".
Command: zstd --quiet --force --patch-from=base result -o server-cli.zstpatch
