#!/usr/bin/env bash
# Runs every Scala variant once per process on node and bun, plus a node GC
# count, and the comparable squint variants from ../math-dispatch. Output is
# one line per run: engine, build, variant, ns/op, GC count.
# Usage: bash bench.sh > results.txt   (from any directory)
set -u
D="$(cd "$(dirname "$0")" && pwd)"
C="$D/../math-dispatch"

CLASS="mixOps mixMethod mixSelf mixClassHand mixF64Hand partOps partSelf partSelfNoAlloc partClassHand partF64Per partF64Packed partF32Packed comboV3AddV3 comboV3MulS comboV4MulV4 comboM4MulV4"
MIXED="mixTuple mixStructF32 mixStructF64 mixStructF64Hand partStructF32 partStructF64 partDataViewF32 comboT3AddT3 swayOps swayHandF32 camViewProj"
PROTO="pMixOps pMixTuple pMixSelf pMixJRef pMixSRef pMixFRef pPartOps pPartSelfNoAlloc pPartJRef pPartSRef pPartFRef"

iters() {
  case "$1" in
    partStructF32|partStructF64|camViewProj) echo 1000000 ;;
    *) echo 5000000 ;;
  esac
}

ns() { grep -oE '[0-9.]+ ns/op' | grep -oE '^[0-9.]+'; }

scala_run() { # build variant
  local out=$1 v=$2 n
  n=$(iters "$v")
  local tn tb gc
  tn=$(OUT=$out node "$D/run.mjs" "$v" "$n" | ns)
  tb=$(OUT=$out bun "$D/run.mjs" "$v" "$n" | ns)
  gc=$(OUT=$out node --trace-gc "$D/run.mjs" "$v" "$n" | grep -cE "Scavenge|Mark-Compact|Minor")
  printf "scala %-9s %-18s node %7s  bun %7s  gc %6s\n" "$out" "$v" "$tn" "$tb" "$gc"
}

for v in $CLASS; do scala_run out-class "$v"; done
for v in $CLASS $MIXED $PROTO; do scala_run out "$v"; done

squint_run() { # workload variant
  local tn tb gc
  tn=$(node "$C/out/bench/hot.mjs" "$1" "$2" | ns)
  tb=$(bun "$C/out/bench/hot.mjs" "$1" "$2" | ns)
  gc=$(node --trace-gc "$C/out/bench/hot.mjs" "$1" "$2" | grep -cE "Scavenge|Mark-Compact|Minor")
  printf "squint %-9s %-18s node %7s  bun %7s  gc %6s\n" "$1" "$2" "$tn" "$tb" "$gc"
}

for v in cpu hand typed-alloc gl; do squint_run mix "$v"; done
for v in cpu hand class-inplace mono-alloc gl; do squint_run particles "$v"; done
for c in "vec3+vec3" "vec3*f32" "vec4*vec4" "mat4*vec4"; do
  tn=$(node "$C/out/bench/combos.mjs" "$c" mono | ns)
  tb=$(bun "$C/out/bench/combos.mjs" "$c" mono | ns)
  printf "squint combos    %-18s node %7s  bun %7s\n" "$c" "$tn" "$tb"
done

# Shared: all combos in one process, in order (the §4.1 "shared" setup).
echo "--- shared combos, class-only build (node)"
OUT=out-class node "$D/run.mjs" --shared comboV3AddV3,comboV3MulS,comboV4MulV4,comboM4MulV4
echo "--- shared combos, full build (node)"
OUT=out node "$D/run.mjs" --shared comboT3AddT3,comboV3AddV3,comboV3MulS,comboV4MulV4,comboM4MulV4
echo "--- shared combos, full build (bun)"
OUT=out bun "$D/run.mjs" --shared comboT3AddT3,comboV3AddV3,comboV3MulS,comboV4MulV4,comboM4MulV4
