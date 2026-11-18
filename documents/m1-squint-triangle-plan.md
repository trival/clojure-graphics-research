# Milestone 1: WebGPU triangle in squint

**Status: planned.**

The first test of the squint direction from
`trivalibs-scala/documents/research/clojurescript-port-research.md`. A WebGPU
triangle is the vehicle. What gets evaluated is the working environment around
it:

| #   | Question                                                                                                                                   | Why it decides things                                                                     |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| Q1  | **Editor**: do hover, signature help and completion show our docstrings and options maps well enough to explore an API without the source? | The painter API is mostly optional parameters and configuration. Research §8 risk.        |
| Q2  | **Browser REPL**: can a human eval into the running page from the editor, and does hot reload keep the GPU state?                          | Research goals N1/N2.                                                                     |
| Q3  | **Agents**: can Claude Code eval into the same page, inspect values and see the rendered frame?                                            | Research goal N3; trivalibs is written by agents and by hand.                             |
| Q4  | **WebGPU from squint**: how do descriptors, async and typed arrays read, and what does the output cost?                                     | Interop and bundle size are the reasons for squint over ClojureScript.                    |

These are the environment prerequisites for the repo's main comparison,
[research/static-vs-live-workflow.md](research/static-vs-live-workflow.md). Q2
and Q3 also give its first L-side latency numbers (task T1).

Out of scope: math library, shader DSL, buffers, content registry (research
§6.6). The painter functions below are throwaway scaffolding that exists to
exercise Q1–Q4.

## State of squint (November 2026)

From the squint docs and the #squint Slack channel; to be confirmed in step 1.

- **Vite plugin** `squint-cljs/vite` (Vite 5–8) compiles `:paths` from
  `squint.edn`, hot-reloads changed `.cljs` modules, runs
  `^:dev/before-load` / `^:dev/after-load` hooks, and injects the `:main`
  namespace into `index.html`.
- **Browser nREPL**: `vite dev` starts an nREPL server on port **1339**, writes
  `.nrepl-port`, and routes evals over Vite's HMR socket into the page.
  Announced as working in July 2026 (`examples/browser-repl`); the maintainer
  says it "probably still has quirks". A Vite 8 breakage was fixed in May 2026.
- **Limitation**: the first `require` of an npm dependency Vite has not
  pre-bundled triggers a page reload and loses REPL state. Workaround:
  `optimizeDeps.include`.
- **Agent precedent**: borkdude had Claude build a Tetris game through the
  browser nREPL with `clj-nrepl-eval` (from `bhauman/clojure-mcp-light`).
- **Editor**: nothing squint-specific found for clojure-lsp / clj-kondo. Hover
  on project functions should work, since clj-kondo analyzes source statically.
  What squint-only forms (`js-await`, `^:async`, `#html`) do to linting is
  unknown.

Local toolchain: node 24 and bun 1.4 only. No Clojure tooling at all (step 0).

## Layout

```
package.json          "type": "module"; scripts dev / build
squint.edn            {:paths ["src"] :output-dir "js" :extension "js" :main …}
vite.config.js        plugins: [squint()]
index.html            <canvas id="canvas">
.clj-kondo/config.edn lint config, incl. option-key checks (step 4)
src/trivalibs/painter.cljs    scaffolding painter: init, shade, form, shape, draw!
src/trivalibs/dev.cljs        snapshot for agents (step 6)
examples/triangle/triangle.cljs  the sketch
```

`js/` (squint output) and `dist/` are gitignored.

## Steps

### 0. Clojure toolchain

squint compiles from npm (`squint-cljs`) and needs no JVM. Everything else is
editor and agent tooling, installed once on the host:

| Tool                 | For                                     | Install                                   |
| -------------------- | --------------------------------------- | ----------------------------------------- |
| Calva (VS Code)      | editor, REPL client; bundles clojure-lsp | VS Code extension `betterthantomorrow.calva` |
| clj-kondo            | linting from the CLI, for agents         | binary release or package manager         |
| babashka (`bb`)      | runs `bbin` and `clj-nrepl-eval`         | binary release or package manager         |
| bbin                 | installs babashka scripts                | per its README                            |
| `clj-nrepl-eval`     | agent evals over nREPL                   | `bbin install`, per clojure-mcp-light README |

A JVM (`clojure` CLI) is needed only if the full `clojure-mcp` server is tried
in step 6. Record versions and install commands here. Calva may download its
own clojure-lsp; check that it does not need a JVM for squint projects.

### 1. squint spike

- `npm`/`bun` project with `squint-cljs` and `vite`, latest versions, recorded
  here.
- Reproduce squint's `examples/browser-repl` setup with a hello-world namespace.
- Check: `vite dev` serves the page, `.nrepl-port` appears, saving a file hot
  reloads.
- Decide bun vs. npm: does the plugin's nREPL server run under `bun run dev`
  (vite on node via its shebang) and under `bunx --bun vite`? Prefer bun to
  match trivalibs if both work.

### 2. Raw WebGPU triangle

`examples/triangle/triangle.cljs` written directly against WebGPU, no painter
functions yet. Purpose: learn the interop before abstracting it.

Points to record:

- **Descriptor maps**: squint maps compile to JS objects and keywords to
  strings, so `{:size 64 :usage …}` is a valid descriptor. Kebab-case keys do
  not become camelCase: `:vertexBuffers` is required, or a key-conversion step.
  Decide which reads better.
- **Async**: `^:async` functions with `js-await` for `requestAdapter` /
  `requestDevice`, against `.then` chains.
- **Interop**: method calls (`.createRenderPipeline device …`), constants
  (`js/GPUBufferUsage.VERTEX`), bit flags (`bit-or`), typed arrays
  (`(js/Float32Array. #js […])` vs. a vector literal, which already is an array
  in squint).
- **Persistent state**: device, context and pipeline in `defonce` so a hot
  reload does not recreate the device. Verify squint's `defonce` semantics
  across module reloads.

### 3. Documented painter scaffolding

`src/trivalibs/painter.cljs` with a handful of functions shaped like the
trivalibs painter, written to exercise Q1:

```clojure
(defn ^:async init
  "Creates a painter on a canvas. Resolves once the GPU device is ready.

  Options:
  - `:format`     texture format of the canvas; default the browser's preferred
  - `:alpha-mode` \"opaque\" | \"premultiplied\"; default \"opaque\""
  [canvas {:keys [format alpha-mode] :or {alpha-mode "opaque"}}]
  …)

(defn shade
  "A shader pair from WGSL strings, plus its vertex layout. …"
  [p {:keys [vertex fragment attribs] :or {…}}] …)

(defn form "Vertex data (a Float32Array) uploaded to a GPU buffer. …" [p data opts] …)
(defn shape "A form drawn with a shade. …" [p form shade opts] …)
(defn draw! "Renders shapes to the canvas. …" [p shapes {:keys [clear-color]}] …)
```

The triangle example is rewritten against these. Only what the triangle needs
gets implemented; the docstrings and options are the point.

### 4. Editor evaluation (Q1)

Setup: VS Code + Calva (bundles clojure-lsp); clj-kondo CLI for agents.

Test each, with and without a connected REPL (Calva uses the nREPL `info` /
`complete` ops when connected, clojure-lsp otherwise):

| Check                                                                         | Result |
| ----------------------------------------------------------------------------- | ------ |
| Hover on `painter/init` shows docstring, rendered markdown                    |        |
| Signature help shows the destructured options map with `:or` defaults         |        |
| `painter/` completion lists the namespace's functions with doc                |        |
| Completion of option keys inside `{…}` at a call site                         |        |
| Unknown option key flagged (clj-kondo `:type-mismatch` map-key spec, below)   |        |
| Go-to-definition from the sketch into the library                             |        |
| squint forms (`js-await`, `^:async`, `js/…` interop) lint without noise       |        |
| Hover / completion on WebGPU interop (`.createBuffer`) — expected: nothing    |        |

Option-key checking candidate, in `.clj-kondo/config.edn`:

```clojure
{:linters {:type-mismatch
           {:namespaces
            {trivalibs.painter
             {init {:arities {2 {:args [:any {:op :keys
                                              :opt {:format :string
                                                    :alpha-mode :string}}]}}}}}}}}
```

Compare ways to document options and record which one reads best in hover:
docstring list, destructured arglist alone, `:arglists` metadata override.

### 5. Browser REPL for humans (Q2)

- Calva: connect to the running nREPL (generic connect, port from
  `.nrepl-port`). Record which connect sequence works.
- Eval a form in `triangle.cljs` that changes the clear color: the next frame
  shows it, no reload.
- Redefine a function called from the frame loop: takes effect without a hook?
  Redefine one captured in `requestAnimationFrame`: confirm it does not, and
  how the loop must be written so it does.
- Save the file with a changed WGSL string: `^:dev/after-load` rebuilds the
  pipeline, the device survives.
- Time a tunable edit from save to new frame. Target: well under a second
  (research N1). State how it was measured.
- Collect nREPL quirks (`describe` op output: which ops exist).

### 6. Agent REPL (Q3)

- With `clj-nrepl-eval` from step 0, the agent evals from Claude Code via
  Bash: `clj-nrepl-eval -p 1339 "(…)"`.
- The dev server runs outside the agent, started by the human (or by the agent
  as a background task); the sandbox must allow the localhost connection.
  Record what was needed.
- **Eyes**: `trivalibs.dev/snapshot` reads the canvas back (`toDataURL`, or a
  `copyTextureToBuffer` readback) and returns a data URL; a small script turns
  it into a PNG the agent can open. Check that an agent can change the color by
  eval and verify the result by looking.
- Compare with the full `clojure-mcp` server and Calva Backseat Driver only if
  `clj-nrepl-eval` falls short.
- Write the working commands into `CLAUDE.md` (Agents and the REPL), then test
  them from a fresh Claude Code session that has only `CLAUDE.md`.

### 7. Output (Q4)

- `vite build`: bundle size, minified and gzipped, against the Scala
  `base-triangle` example (`trivalibs-scala/examples/`, `jsMode full`).
- Read the emitted JS for `painter.cljs`: are the squint runtime pieces pulled
  in, and which? Any surprises in how `defn` with destructured options
  compiles (allocation per call)?

## Done when

- The triangle renders in the browser from squint source, through the
  documented painter functions.
- A human edits it live from the editor and an agent edits and inspects it
  through the REPL, with the commands in `CLAUDE.md`.
- Findings below are filled in, ending with a recommendation: continue to the
  research vertical slice (§13 option C, in squint), switch to shadow-cljs, or
  stop.

## Findings

_Filled in while working through the steps._

### Q1 Editor

### Q2 Browser REPL

### Q3 Agents

### Q4 WebGPU interop and output

### Recommendation

## Sources

- [squint browser REPL docs](https://cljdoc.org/d/io.github.squint-cljs/squint/0.14.205/doc/browser-repl)
- [#squint 2026-05-28: Vite fix, Tetris via clj-nrepl-eval](https://clojurians-log.clojureverse.org/squint/2026-05-28)
- [#squint 2026-07-20: "we have a browser nrepl now"](https://clojurians-log.clojureverse.org/squint/2026-07-20)
- [clojure-mcp-light (clj-nrepl-eval)](https://github.com/bhauman/clojure-mcp-light)
- [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver)
