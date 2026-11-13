"use strict";
// Report locations and lifecycle events only; errors may contain private source text.
window.addEventListener("error", event => {
  Native.editorDiagnostic?.("script-error", event.filename || "", event.lineno || 0);
});
window.addEventListener("unhandledrejection", () => {
  Native.editorDiagnostic?.("unhandled-rejection", "", 0);
});
Native.editorDiagnostic?.("bootstrap", "diagnostics.js", 0);
