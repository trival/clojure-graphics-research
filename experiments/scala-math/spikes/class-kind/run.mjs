const m = await import('./out/kinds.js')
const f = m[process.argv[2]], n = 5e6
for (let r = 0; r < 3; r++) f(n)
const ts = []
for (let r = 0; r < 7; r++) { const t0 = performance.now(); f(n); ts.push(performance.now() - t0) }
ts.sort((a, b) => a - b)
console.log(process.argv[2].padEnd(6), (ts[3] * 1e6 / n).toFixed(2), 'ns')
