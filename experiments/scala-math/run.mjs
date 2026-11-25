// Times Scala.js bench exports with the same method as
// experiments/math-dispatch/src/bench/harness.cljs: 3 warmups, median of 7
// timed rounds, ns per iteration.
//
//   node run.mjs <export> [n]           one export per process (isolated)
//   node run.mjs --shared <e1,e2,…> [n] several in one process, in order;
//                                       each is warmed and timed in turn
// OUT=out-class selects the class-only link (default: out, everything).
const b = await import(`./${process.env.OUT ?? 'out'}/bench.js`)

function measure(label, f, n) {
	for (let r = 0; r < 3; r++) f(n)
	const times = []
	let sink = 0
	for (let r = 0; r < 7; r++) {
		const t0 = performance.now()
		sink = f(n)
		times.push(performance.now() - t0)
	}
	times.sort((x, y) => x - y)
	const ns = (times[3] * 1e6) / n
	console.log(`${label.padEnd(22)} ${ns.toFixed(2).padStart(7)} ns/op   (checksum ${sink})`)
}

const args = process.argv.slice(2)
const shared = args[0] === '--shared'
const names = (shared ? args[1] : args[0]).split(',')
const n = Number((shared ? args[2] : args[1]) ?? 5_000_000)
for (const name of names) {
	if (!b[name]) throw new Error(`no export ${name}`)
	measure(name, b[name], n)
}
