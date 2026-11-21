(ns bench.harness
  "Timing: 3 warmup rounds, 7 timed rounds, median, reported as ns per op.")

(defn- now [] (js/performance.now))

(defn- median [xs]
  (let [s (.sort (.slice xs) (fn [a b] (- a b)))]
    (aget s (js/Math.floor (/ (.-length s) 2)))))

(defn measure
  "Runs (f n) for 3 warmups + 7 timed rounds; prints median ns per op."
  [label f n]
  (dotimes [_ 3] (f n))
  (let [times #js []
        sink #js [0]]
    (dotimes [_ 7]
      (let [t0 (now)
            r (f n)
            t1 (now)]
        (.push times (- t1 t0))
        (aset sink 0 r)))
    (let [ns-op (/ (* (median times) 1e6) n)]
      (println (str (.padEnd label 44) (.padStart (.toFixed ns-op 2) 7) " ns/op   (checksum " (aget sink 0) ")")))))
