#!/usr/bin/env nbb
;; The Kotoba string emitter against the `.cljc` compiler, THROUGH THE MACHINE.
;;
;; Not string equality: the `.cljc` shares a class table between identical
;; classes and this emitter appends each class's ranges where it meets them,
;; so two correct programs for the same pattern differ in bytes. What has to
;; agree is what they ACCEPT, so both programs are run on the same inputs by
;; the same `pattern-vm` and every answer is compared.
;;
;; The corpus is the real one -- every unique regex literal in kotoba-lang's
;; browser stack -- and this slice has no quantifiers, so a pattern carrying
;; one is counted as `not in this slice` rather than skipped silently.
;;
;; Exit codes: 0 passed, 1 disagreement, 2 REFUSED.
;;
;; The oracle requires `kotoba.lang.text`, so the sibling repo has to be on the
;; classpath -- a worktree does not sit beside it:
;;
;;   nbb --classpath "src:$HOME/github/com-junkawasaki/orgs/kotoba-lang/text/src" \
;;       test/emit_parity.cljs

(ns emit-parity
  (:require ["node:child_process" :as cp]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [kotoba.lang.text :as str]
            [pattern.compile :as oracle]))

(def script
  (or (first (filter (fn [a] (.endsWith a ".cljs")) (rest (.slice js/process.argv 0))))
      "test/emit_parity.cljs"))
(def repo-root (path/resolve (path/dirname (path/resolve script)) ".."))
(def fuel 200000000)

;; ⚠ The newline inputs are here since 2026-09-09. Without them `.` matching a
;; line terminator was invisible to this suite: both programs agreed with each
;; other because both were compiled from the same wrong assumption, and the
;; inputs never reached it.
(def inputs ["" "a" "0" "abc" "12" "px" "12px" " " "-1.5" "a@b.co" "]" "."
             "\n" "a\nb" "a\rb" "x\ny" "\r\n"])

(defn- sh [cmd args]
  (let [r (cp/spawnSync cmd (clj->js args) #js {:encoding "utf8" :timeout 900000})]
    {:exit (.-status r) :out (or (.-stdout r) "") :err (or (.-stderr r) "")}))

(defn- hex [n w] (let [h (.toString (js/Number n) 16)]
                   (str (apply str (repeat (- w (count h)) "0")) h)))

(defn program->text [{:keys [code classes fold]}]
  (let [spans (reduce (fn [{:keys [at out]} c]
                        {:at (+ at (quot (count c) 2)) :out (conj out [at (quot (count c) 2)])})
                      {:at 0 :out []} classes)
        ranges (vec (mapcat identity classes))
        ins (fn [[op a b c]] (str (hex op 1) (hex (or a 0) 6) (hex (or b 0) 6) (hex (or c 0) 1)))]
    (str "K" (if fold "1" "0") (hex (count code) 6) (hex (quot (count ranges) 2) 6)
         (apply str (for [[op a b] code]
                      (if (= op 1)
                        (let [[f n] (nth (:out spans) a)] (ins [1 f n (or b 0)]))
                        (ins [op a b 0]))))
         (apply str (for [i (range 0 (count ranges) 2)]
                      (str (hex (nth ranges i) 6) (hex (nth ranges (inc i)) 6)))))))

(defn- corpus []
  (let [orgs (or (first (filter (fn [d] (fs/existsSync (path/join d "cssom")))
                                [(path/resolve repo-root "..")
                                 (path/join (.-HOME js/process.env)
                                            "github" "com-junkawasaki" "orgs" "kotoba-lang")]))
                 (path/resolve repo-root ".."))
        r (sh "bash" ["-c" (str "find " orgs "/htmldom " orgs "/cssom " orgs "/browser "
                                orgs "/html " orgs "/css " orgs "/kiyaku "
                                "-name '*.cljc' -o -name '*.clj' 2>/dev/null | grep -v node_modules")])
        files (remove str/blank? (str/split-lines (:out r)))
        lit #"#\"((?:[^\"\\]|\\.)*)\""]
    (vec (distinct (mapcat (fn [f] (map second (re-seq lit (fs/readFileSync f "utf8")))) files)))))

(defn main []
  (when-not (zero? (:exit (sh "kotoba" ["--help"])))
    (println "REFUSED: the kotoba CLI is not runnable here") (js/process.exit 2))
  (let [patterns (corpus)]
    (when (< (count patterns) 50)
      (println "REFUSED: the corpus scrape found only" (count patterns) "patterns")
      (js/process.exit 2))
    (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "emit-parity-"))
          emit-out (path/join dir "pattern_emit.mjs")
          vm-out (path/join dir "pattern_vm.mjs")
          c1 (sh "kotoba" ["-M" "compile" (path/join repo-root "kotoba" "pattern_emit.kotoba")
                           "--target" "js" "--fuel" (str fuel) "--output" emit-out])
          c2 (sh "kotoba" ["-M" "compile" (path/join repo-root "kotoba" "pattern_vm.kotoba")
                           "--target" "js" "--fuel" (str fuel) "--output" vm-out])]
      (when-not (and (zero? (:exit c1)) (zero? (:exit c2)))
        (println "compile failed:" (:out c1) (:out c2)) (js/process.exit 1))
      (-> (js/Promise.all #js [(js/import emit-out) (js/import vm-out)])
          (.then
           (fn [mods]
             (let [emit (aget mods 0)
                   vm (aget mods 1)
                   compiled (atom 0) not-in-slice (atom 0) refused-both (atom 0) failures (atom 0)]
               (doseq [re patterns]
                 (let [mine (try ((aget (.instantiateKotoba emit) "compile-text") re)
                                 (catch :default e (str "TRAP: " (.-message e))))
                       theirs (try (program->text (oracle/compile-pattern re))
                                   (catch :default _ nil))]
                   (cond
                     (str/starts-with? mine "{:error")
                     (if (str/includes? mine ":quantifier/not-in-this-slice")
                       (swap! not-in-slice inc)
                       (if (nil? theirs)
                         (swap! refused-both inc)
                         (do (swap! failures inc)
                             (println "  DISAGREE (this refused, the oracle did not)" (pr-str re))
                             (println "    " mine))))

                     (nil? theirs)
                     (do (swap! failures inc)
                         (println "  DISAGREE (the oracle refused, this did not)" (pr-str re)))

                     :else
                     (let [answers (fn [prog]
                                     (mapv (fn [s]
                                             (try ((aget (.instantiateKotoba vm) "match?") prog s)
                                                  (catch :default e (str "TRAP " (.-message e)))))
                                           inputs))]
                       (swap! compiled inc)
                       (when-not (= (answers mine) (answers theirs))
                         (swap! failures inc)
                         (println "  DISAGREE" (pr-str re))
                         (println "    kotoba:" (pr-str (answers mine)))
                         (println "    cljc  :" (pr-str (answers theirs))))))))
               (println (str "SCANNED\t" (count patterns)))
               (println (str "  agreed through the machine: " (- @compiled @failures)
                             "   both refused: " @refused-both
                             "   quantifier (next slice): " @not-in-slice
                             "   disagreements: " @failures))
               (println (if (zero? @failures)
                          (str "emit parity: " (- @compiled @failures) "/" @compiled
                               " agree with the .cljc oracle through pattern-vm")
                          "emit parity: FAILED"))
               (js/process.exit (if (zero? @failures) 0 1)))))
          (.catch (fn [e] (println "ERROR" (str e)) (js/process.exit 1)))))))

(main)
