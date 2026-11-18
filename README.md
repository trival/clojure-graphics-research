# trivalibs in squint — an experiment

[trivalibs](https://github.com/trivial-space/trivalibs-scala) is a Scala.js
library for creative coding with WebGPU: a painter, a shader DSL, math and
typed buffers. This repo explores the same library in
[squint](https://github.com/squint-cljs/squint), ClojureScript syntax compiled
to plain JavaScript.

## The question

**Which workflow suits work with the trivalibs painter better?**

- **Static**: a statically typed library. The compiler checks every call site
  and the editor completes on types, but each change goes through compile, link
  and page reload (5–9 s measured in the Scala repo).
- **Live**: a dynamic library on a REPL. Edits land in the running sketch in
  under a second without losing state, and humans and agents can evaluate and
  inspect it live. Type checking and type-driven completion are gone.

Creative coding is mostly taste and iteration, and the code is written both by
hand and by agents. Both sides of the trade-off matter here, so this repo
compares them on real painter work rather than deciding by argument.

The method and criteria are in
[documents/research/static-vs-live-workflow.md](documents/research/static-vs-live-workflow.md).
It builds on the first round of research in the Scala repo,
[clojurescript-port-research.md](trivalibs-scala/documents/research/clojurescript-port-research.md).

## Layout

- `trivalibs-scala/`: the Scala.js library, as a git submodule. Used as a
  reference only.
- `documents/`: plans, research and findings.

## Status

Milestone 1: a WebGPU triangle in squint, testing the editor, browser REPL and
agent tooling. See
[documents/m1-squint-triangle-plan.md](documents/m1-squint-triangle-plan.md).
