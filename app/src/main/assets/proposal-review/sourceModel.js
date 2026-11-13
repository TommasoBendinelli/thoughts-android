/* Ranges track the exact file bytes while independent on-screen fields are edited. */
function createSourceFields(state, edit) {
  const fields = [];
  function add(start, end, options = {}) {
    const field = { start, end, ...options };
    fields.push(field);
    return field;
  }
  function replace(field, value) {
    const { start, end } = field;
    const old = state.source.slice(start, end);
    if (value === old) return false;
    const delta = value.length - old.length;
    edit(state.source.slice(0, start) + value + state.source.slice(end));
    for (const other of fields) {
      if (other === field) continue;
      if (other.start >= end) { other.start += delta; other.end += delta; }
      else if (other.start <= start && other.end >= end) other.end += delta;
    }
    field.end = start + value.length;
    return true;
  }
  return { fields, add, replace };
}
if (typeof module !== "undefined") module.exports = { createSourceFields };
