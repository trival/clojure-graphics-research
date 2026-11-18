# Static or live: which workflow for the trivalibs painter

**Status: research method, findings pending.** This is the main question of this
repo. The milestones are there to produce the evidence.

## Question

For work with the trivalibs painter, which is more beneficial:

- **S (static)**: trivalibs in Scala.js. Types check every call site, the editor
  completes on types, and each change costs compile + link + page reload.
- **L (live)**: trivalibs in squint. Edits are hot-reloaded or evaluated into
  the running sketch, and humans and agents work in a REPL. Errors appear at run
  time, and completion works on namespaces, not on types.

"Beneficial" means: more and better sketch work per hour of human attention,
from humans coding by hand, from agents, and from the two together.

## What the first round left open

`trivalibs-scala/documents/research/clojurescript-port-research.md` settled that
a port is **feasible**. It argued the trade-off from structure: S cannot get
below a few seconds per edit (§2), and L loses completion, compile-time errors
and refactoring safety (§8). It measured nothing on the L side (§14) and did not
ask:

1. **How often each kind of edit happens.** If most edits in real sketch work
   are tunables, a cheaper path for S (the content registry plus tunables
   supplied by the host, research §13 option A) may close most of the gap.
2. **What latency is worth.** Whether going from 5 s to 0.5 s changes how a
   sketch develops (more variations tried, a different look found), or only
   saves time.
3. **What errors cost in each workflow.** How many errors the compiler catches
   in S that L finds only at run time, and how long each takes to find and fix
   on each side.
4. **What the agent loop needs.** S gives agents compiler errors on every call
   site they broke. L gives them evaluation results, inspection of live values
   and snapshots of the frame. Which one leads an agent to a correct, good
   looking result in fewer steps?
5. **Discoverability in practice.** Whether docstrings, options maps and lint
   rules in L are enough to learn the painter API, compared with type-driven
   completion in S.
6. **Middle ground.** How much of S's safety L can get back (clj-kondo type and
   map-key specs, runtime checks only in dev, headless smoke runs), and how much
   of L's liveness S can get (registry, host tunables, `smallestmodules` with
   Vite).

## Method

Comparative, on real painter work, with the same tasks done in both.

### Tasks

Picked from what sketch work actually consists of:

| #   | Task                                                              | Mostly tests             |
| --- | ----------------------------------------------------------------- | ------------------------ |
| T1  | Tune constants until a look is right (colors, fades, sizes)       | latency, flow            |
| T2  | Change a shader body (new lighting term, noise layer)             | latency, shader errors   |
| T3  | Add a structural element: new panel, effect pass, bake            | hot reconfiguration, API |
| T4  | Use a painter feature not used before, from editor help alone     | discoverability          |
| T5  | Find a visual bug (wrong panel bound, bad blend, NaN in a shader) | inspection               |
| T6  | Change the library API and update every sketch that uses it       | refactoring safety       |
| T7  | Write a new small sketch from a description                       | everything, end to end   |

Each task is done by a human by hand, by an agent alone, and by a human
directing an agent.

### Measures

- **Iteration latency**: save or eval → new frame, per kind of edit.
- **Iterations to done**, and time to done.
- **Errors**: where each was caught (compiler, lint, run time, by eye) and the
  time from introducing it to fixing it.
- **Lost state**: how often a change forced a reload or a reset of camera, time
  or input.
- **Agent sessions**: steps, tokens, the feedback signals used, and whether the
  result was right on the first look.
- **Subjective**: a short note after each session: flow, frustration, whether
  the result looks better for the extra iterations. Taste is the domain; it is
  recorded, not argued away.
- **Code**: how the same sketch reads in both (infix vs prefix math, research
  §6.1), and bundle size and frame time of the result.

How each number was taken is written down next to it, so it can be repeated.

### Prerequisites

T1–T2 need only a minimal painter in L (milestone 1 plus a few shades). T3–T7
need enough of the painter for a real sketch: the vertical slice of research §13
option C. S is measured on the existing Scala sketches, on the host with a warm
`sketch:watch`, not in the sandbox.

## Hypotheses

To be confirmed or refuted, not assumed:

- H1: Most edits in sketch work are T1/T2. L's advantage is largest there.
- H2: In L, agents catch most errors through evaluation and snapshots fast
  enough that missing types rarely costs them a correct result.
- H3: T6 is where S wins clearly, and the gap grows with the number of sketches.
- H4: With well-documented options maps and lint specs, T4 in L is close to S.
- H5: An improved S (registry + host tunables) gets T1 close to L but not T2–T3.

## Findings

_Per milestone, linked from here._

## Conclusion

_Open._
