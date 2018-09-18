(ns com.interrupt.edgar.scanner-test
  (:require [clojure.test :refer :all]
            [com.interrupt.edgar.scanner :as sut]
            [clojure.core.async :as async :refer [<!!]]))

(deftest most-frequent-test
  (is (= #{{:symbol "A" :sec-type "STK"} {:symbol "B" :sec-type "STK"}}
         (->> [[{:symbol "A" :sec-type "STK"} {:symbol "B" :sec-type "STK"}]
               [{:symbol "A" :sec-type "STK"} {:symbol "B" :sec-type "STK"}]
               [{:symbol "A" :sec-type "STK"} {:symbol "C" :sec-type "STK"}]]
              (sut/most-frequent 2)
              set))))

(deftest scan-code->ch-kw-test
  (is (= :high-opt-imp-volat-ch (sut/scan-code->ch-kw "HIGH_OPT_IMP_VOLAT"))))

(deftest ch-kw->scan-code-test
  (is (= "HIGH_OPT_IMP_VOLAT" (sut/ch-kw->scan-code :high-opt-imp-volat-ch))))

(deftest scanner-decide-test
  (is (= 4 (sut/scanner-decide (map atom (range 5)) #(apply max %)))))
