# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this repo is

An experiment around one question: for work with the trivalibs painter, which is
better, a **statically typed library with a compile step on every change**
(trivalibs in Scala.js) or a **dynamic library on a REPL with live coding**
(trivalibs in squint)? The question applies to humans coding by hand, to agents,
and to both together. Method and criteria:
`documents/research/static-vs-live-workflow.md`. Every milestone feeds evidence
into it.

It follows `documents/research/clojurescript-port-research.md` (first round,
written in the Scala repo, moved here),
with one change: squint, not shadow-cljs. squint's JS-native data, plain ES
modules and small output match the library goals (research G5, G6) without a
persistent-collection runtime.

Nothing here is decided. Every milestone ends with written findings, and the
findings decide whether the experiment continues.

`trivalibs-scala/` is a git submodule of the Scala.js library. It is **reference
only**: read it for API shapes, painter internals, shader-DSL design and the
documents that shaped them. Never edit it from this repo.

## Build & Dev Commands

None yet; milestone 1 sets them up (`documents/m1-squint-triangle-plan.md`).
Record each command here when it exists.

## Documents

Same workflow as trivalibs:

- `documents/*.md`: living plans for work not finished yet. A plan is written
  before the work, updated while doing it, and carries a **Findings** section
  filled from real use, not from expectation.
- `documents/done/*.md`: finished plans, moved here unchanged except for a
  status line. Historical record of what was tried and why.
- `documents/research/*.md`: research. `static-vs-live-workflow.md` is the
  repo's central question; record evidence from milestones there. Not a roadmap;
  never treat API sketches in research as decisions.
- `trivalibs-scala/documents/`: the Scala library's design history. The
  `rust-painter/` and `done/` documents describe the painter model this port
  would carry over.

Work plan-first: for a non-trivial step, write or update the plan, then
implement, then record findings. When a finding contradicts the plan, update the
plan text, not just the code.

## Conventions

- **Standard Clojure conventions and formatting**: the community
  [Clojure Style Guide](https://guide.clojure.style/) and cljfmt defaults (as
  Calva formats). WGSL inside shader strings keeps one statement per line.
- **Docstrings on every public function**: one-line purpose, then parameters,
  options with their defaults, and non-obvious behaviour. Discoverability in the
  editor is a goal under test, so docstrings are part of the deliverable, not
  decoration.
- **Options maps** for optional configuration, destructured in the arglist
  (`[p {:keys [format clear-color] :or {format "bgra8unorm"}}]`) so signature
  help shows them. No props / config classes.
- **Library code vs. example code**: library code (`src/trivalibs/`) uses JS
  natives directly (`loop`, `aget`/`aset`, typed arrays), no seqs or lazy
  sequences on hot paths, no per-frame allocation. Example and sketch code may
  use convenience forms freely.
- **Per-frame update functions take `tpf`** (ms since last frame), as in
  trivalibs.
- **Throw plain JS `Error`s** (`(throw (js/Error. "…"))`).
- **Examples**: each implementation step gets an example; earlier examples are
  never deleted and must keep working.
- Measurements that compare against the Scala library (bundle size, timings)
  state how they were taken, so they can be repeated.

## Agents and the REPL

Agents work through the running sketch's REPL, not only through files. The setup
is milestone 1's subject; once it works, this section holds the exact commands
(start the dev server, eval a form, read back a frame). Until then: never assume
REPL output, run it.
