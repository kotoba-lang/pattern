# pattern

**A bounded pattern matcher, in Kotoba.** A program is inert data; the machine
that runs it is a Kotoba module with an **empty `requiredCapabilities`**; the
regex string that produces the program is compiled on the host, before
anything runs.

## Why

Counted 2026-09-09 across kotoba-lang's browser stack — **308 regex literals in
213 files**, 200 unique:

```
+   148   [class] 122   (capture) 102   \s  93   *  84   ?  74
\d  56   |        44   anchor     43   (?i) 38   (?:) 34   .  30
{n} 12   {n,m}     4    lookahead   3   \w   1   backreference 0
```

Kotoba has no regex head, so every port so far hand-rolls a scanner out of
`string-code-point-at` and `string-substring` — twelve modules do it today.
That, not size, is what blocks the browser stack: `htmldom` is 2114 lines with
**no atoms, no protocols, three interop sites**, and `loop`/`recur` is
admitted. It has eleven regexes. `cssom` has 115 in one file.

So: one scanner, in a library, driven by data.

## The two halves

| | |
|---|---|
| `kotoba/pattern_core.kotoba` | the machine. Takes a program and a string, answers `match?` / `search-start` / `search-end` / `search` |
| `src/pattern/compile.cljc` | the regex subset → program compiler. Host-side, portable `.cljc` |

```clojure
(require '[pattern.compile :as pc])
(pc/compile-pattern "\\d{3}-\\d{4}")
;; => {:code [[1 0 0] [1 0 0] [1 0 0] [0 45] [1 0 0] [1 0 0] [1 0 0] [1 0 0] [6 0]]
;;     :classes [[48 57]] :fold false}
```

A program is EDN, so it can be printed into a `.kotoba` module as a literal,
shipped in a resource, or read at runtime with `document-edn-read`. The machine
never parses anything, which is why it cannot be made to reject its input.

## Why a Pike VM and not backtracking

A backtracking matcher is easier to write and has no bound worth the name:
`(a+)+b` against a page of a's is the standard denial of service, and this
workspace runs matchers over input it did not write. This is a Thompson/Pike
machine — the run carries a **list of live threads** instead of a stack of
choices, deduplicated by program counter, so each input position is visited
once and time is O(input × program).

Measured, at `--fuel 20000`: with the dedup removed the machine dies of
`doc-vector-too-large` from **eight** a's; with it, twenty-four a's answer.
The parity corpus carries those inputs on purpose — without them it passed
either way, which is a test that was not testing.

## The subset, and what is refused

Literals, `.`, classes with ranges and negation, `\d \D \s \S \w \W`, groups
(`( )` and `(?: )` compile the same — the machine does not extract text yet),
alternation, the greedy quantifiers `* + ? {n} {n,m}`, `^` and `$`, and a
leading `(?i)`.

**Refused by name, not by silence** — `compile-pattern` throws: lookahead and
lookbehind (3 uses in the corpus), back references (0), non-greedy
quantifiers, and inline flags other than a leading `(?i)`. A pattern this
cannot express must not compile to one that means something else.

Two differences from JS RegExp are deliberate and are asserted in the parity
test rather than left to be discovered:

* `.` matches a newline here.
* the machine is leftmost-**longest**; JS is leftmost-first. For `a|ab`
  against `"ab"` the machine answers 2 and JS answers 1.

## Fuel

The default per-instance budget is **512** — a plain countdown returns at
n=510 — and a matcher spends fuel per function entry, so **the default cannot
finish a six-character search**. Measured: `--fuel 5000` runs a 40-character
search of `[0-9]+`. The knob is the host's, which is the point of it:

```bash
kotoba -M compile "$PWD/kotoba/pattern_core.kotoba" --target js --fuel 200000 --output pattern.mjs
```

## Tests

```bash
# 216 checks against the host's own RegExp, through the ESM amu emitted
nbb --classpath src test/pattern_parity.cljs

# the upstream limitation this library works around, so nobody has to remember
nbb test/entry_probe.cljs
```

`kotoba -M test` is **not** used here, and that is the one thing this repo
cannot do: a `main` that calls the machine — and any `test-*` the runner would
evaluate — makes the compiler refuse the module at the `:value` phase with
`"value is not a boolean"`, while the same module compiles for the JS backend
and answers correctly. `test/entry_probe.cljs` rebuilds that failing form from
the current source and **goes red the day the compiler stops refusing it**.

## Not done

No captures. 102 of the 308 literals use a group, and many of those are
grouping rather than capturing; the ones that need the matched text are the
next slice, and the instruction set reserves opcode 5 (`save`) for it. Until
then `search` answers *where*, not *what*.

The compiler is host-side. Moving it into Kotoba would let a guest accept a
pattern at runtime; nothing needs that yet.
