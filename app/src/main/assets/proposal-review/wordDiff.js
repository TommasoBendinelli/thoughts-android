'use strict';

const MAX_MYERS_DISTANCE = 1024;
const WORD_CHARACTER = /[\p{L}\p{N}\p{M}_]/u;

function codePointAt(text, index) {
  const value = String.fromCodePoint(text.codePointAt(index));
  return { value, width: value.length };
}

function isWordCharacter(value) {
  return WORD_CHARACTER.test(value);
}

function tokenize(text) {
  const tokens = [];
  let cursor = 0;

  while (cursor < text.length) {
    const point = codePointAt(text, cursor);
    if (/\s/u.test(point.value)) {
      cursor += point.width;
      continue;
    }

    const start = cursor;
    if (text[cursor] === '\\') {
      cursor += 1;
      if (cursor < text.length && /[A-Za-z@]/.test(text[cursor])) {
        while (cursor < text.length && /[A-Za-z@]/.test(text[cursor])) {
          cursor += 1;
        }
        if (text[cursor] === '*') {
          cursor += 1;
        }
      } else if (cursor < text.length) {
        cursor += codePointAt(text, cursor).width;
      }
      while (text.slice(cursor, cursor + 2) === '{}') {
        cursor += 2;
      }
    } else if (isWordCharacter(point.value)) {
      cursor += point.width;
      while (cursor < text.length) {
        const next = codePointAt(text, cursor);
        if (isWordCharacter(next.value)) {
          cursor += next.width;
          continue;
        }
        if ((next.value === "'" || next.value === '’') && cursor + next.width < text.length) {
          const afterApostrophe = codePointAt(text, cursor + next.width);
          if (isWordCharacter(afterApostrophe.value)) {
            cursor += next.width + afterApostrophe.width;
            continue;
          }
        }
        break;
      }
    } else {
      cursor += point.width;
    }

    tokens.push({
      start,
      end: cursor,
      text: text.slice(start, cursor)
    });
  }
  return tokens;
}

function edgeMatches(oldTokens, newTokens) {
  const matches = [];
  let prefix = 0;
  while (
    prefix < oldTokens.length &&
    prefix < newTokens.length &&
    oldTokens[prefix].text === newTokens[prefix].text
  ) {
    matches.push([prefix, prefix]);
    prefix += 1;
  }

  let oldSuffix = oldTokens.length - 1;
  let newSuffix = newTokens.length - 1;
  const suffixMatches = [];
  while (
    oldSuffix >= prefix &&
    newSuffix >= prefix &&
    oldTokens[oldSuffix].text === newTokens[newSuffix].text
  ) {
    suffixMatches.push([oldSuffix, newSuffix]);
    oldSuffix -= 1;
    newSuffix -= 1;
  }
  return matches.concat(suffixMatches.reverse());
}

function matchingPairs(oldTokens, newTokens) {
  if (oldTokens.length === 0 || newTokens.length === 0) {
    return [];
  }

  const oldLength = oldTokens.length;
  const newLength = newTokens.length;
  const maximum = oldLength + newLength;
  const trace = [];
  const frontier = new Map([[1, 0]]);

  for (let distance = 0; distance <= maximum; distance += 1) {
    if (distance > MAX_MYERS_DISTANCE) {
      return edgeMatches(oldTokens, newTokens);
    }
    trace.push(new Map(frontier));

    for (let diagonal = -distance; diagonal <= distance; diagonal += 2) {
      const left = frontier.has(diagonal - 1) ? frontier.get(diagonal - 1) : Number.NEGATIVE_INFINITY;
      const down = frontier.has(diagonal + 1) ? frontier.get(diagonal + 1) : Number.NEGATIVE_INFINITY;
      let oldIndex;
      if (diagonal === -distance || (diagonal !== distance && left < down)) {
        oldIndex = down;
      } else {
        oldIndex = left + 1;
      }
      if (!Number.isFinite(oldIndex)) {
        oldIndex = 0;
      }

      let newIndex = oldIndex - diagonal;
      while (
        oldIndex < oldLength &&
        newIndex < newLength &&
        oldTokens[oldIndex].text === newTokens[newIndex].text
      ) {
        oldIndex += 1;
        newIndex += 1;
      }
      frontier.set(diagonal, oldIndex);

      if (oldIndex >= oldLength && newIndex >= newLength) {
        const matches = [];
        for (let backtrack = distance; backtrack >= 0; backtrack -= 1) {
          const previousFrontier = trace[backtrack];
          const currentDiagonal = oldIndex - newIndex;
          const previousLeft = previousFrontier.has(currentDiagonal - 1)
            ? previousFrontier.get(currentDiagonal - 1)
            : Number.NEGATIVE_INFINITY;
          const previousDown = previousFrontier.has(currentDiagonal + 1)
            ? previousFrontier.get(currentDiagonal + 1)
            : Number.NEGATIVE_INFINITY;
          const previousDiagonal =
            currentDiagonal === -backtrack ||
            (currentDiagonal !== backtrack && previousLeft < previousDown)
              ? currentDiagonal + 1
              : currentDiagonal - 1;
          const previousOldIndex = previousFrontier.has(previousDiagonal)
            ? previousFrontier.get(previousDiagonal)
            : 0;
          const previousNewIndex = previousOldIndex - previousDiagonal;

          while (oldIndex > previousOldIndex && newIndex > previousNewIndex) {
            matches.push([oldIndex - 1, newIndex - 1]);
            oldIndex -= 1;
            newIndex -= 1;
          }
          oldIndex = previousOldIndex;
          newIndex = previousNewIndex;
        }
        return matches.reverse();
      }
    }
  }
  return [];
}

function changedRunRanges(tokens, matchedIndices) {
  const ranges = [];
  let current;
  for (let index = 0; index < tokens.length; index += 1) {
    if (matchedIndices.has(index)) {
      if (current) {
        ranges.push(current);
        current = undefined;
      }
      continue;
    }

    if (current) {
      current.end = tokens[index].end;
    } else {
      current = { start: tokens[index].start, end: tokens[index].end };
    }
  }
  if (current) {
    ranges.push(current);
  }
  return ranges;
}

function changedTokenRanges(oldText, newText) {
  const oldTokens = tokenize(oldText);
  const newTokens = tokenize(newText);
  const matches = matchingPairs(oldTokens, newTokens);
  const matchedOld = new Set(matches.map(([oldIndex]) => oldIndex));
  const matchedNew = new Set(matches.map(([, newIndex]) => newIndex));

  return {
    oldRanges: changedRunRanges(oldTokens, matchedOld),
    newRanges: changedRunRanges(newTokens, matchedNew)
  };
}

module.exports = {
  changedTokenRanges,
  matchingPairs,
  tokenize
};
