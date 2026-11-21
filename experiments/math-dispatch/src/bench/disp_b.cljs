(ns bench.disp-b
  "Strategy B: typeof test for numbers, then a method call on the left operand;
  the method tests the right operand (instanceof chain). Number on the left with
  a non-number on the right goes to the right operand's `r…` method."
  (:require-macros [bench.macros :refer [num?]]))

(defn add [a b]
  (if (num? a)
    (if (num? b) (js* "~{} + ~{}" a b) (.radd b a))
    (.add a b)))

(defn mul [a b]
  (if (num? a)
    (if (num? b) (js* "~{} * ~{}" a b) (.rmul b a))
    (.mul a b)))
