(ns bench.macros-combo)

(defmacro defcombo
  "Defines two loop functions over the same operand arrays, differing only in
  the call at the operation site: `<name>-mono` (dedicated function, what a
  static compiler emits) and `<name>-b` (generic dispatch).
  `extract` turns the known result type into a number for the checksum."
  [base op mono-fn extract as bs]
  (let [mk (fn [suffix call]
             `(defn ~(symbol (str base "-" suffix)) [n#]
                (let [as# ~as bs# ~bs]
                  (loop [i# 0 acc# 0]
                    (if (< i# n#)
                      (let [j# (bit-and i# 1023)
                            r# (~call (aget as# j#) (aget bs# j#))]
                        (recur (inc i#) (~'js* "~{} + ~{}" acc# (~extract r#))))
                      acc#)))))]
    `(do ~(mk "mono" mono-fn)
         ~(mk "b" (symbol "bench.disp-b" (name op))))))
