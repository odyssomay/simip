(ns simip.core
  (:use [clojure.java.io :only [resource]])
  (:require [seesaw 
             [core :as ssw]
             [chooser :as ssw-chooser]])  
  (:import javax.sound.midi.MidiSystem))

(def sequencer (atom nil))
(def transmitter (atom nil))
(add-watch sequencer nil (fn [_ _ _ s] (reset! transmitter (.getTransmitter s))))

(defn sequencer-ready? []
  (and @sequencer (.isOpen @sequencer) (.getSequence @sequencer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Controls

(defn start! []
  (when (sequencer-ready?)
    (.start @sequencer)))

(defn stop! [] 
  (when (sequencer-ready?)
    (.stop @sequencer) 
    (.setTickPosition @sequencer 0)))

(defn pause! [] 
  (when (sequencer-ready?)
    (.stop @sequencer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Position Control

(def position-indicator (ssw/slider :min 0 :max 0))
(def indicator-panel (ssw/card-panel :items [[position-indicator "position"]
                                             [(ssw/progress-bar :indeterminate? true :border 10) "progress"]]))

(defn update-position-indicator-range []
  (when (sequencer-ready?)
    (.setMaximum position-indicator (int (.getTickLength @sequencer)))))

(def init-position-indicator
  (memoize 
    (fn []
      (.start
        (Thread. (fn [] 
                   (if (sequencer-ready?)
                     (.setValue position-indicator (int (.getTickPosition @sequencer))))
                   (Thread/sleep 100)
                   (recur)))))))

(defn show-position-indicator []
  (init-position-indicator)
  (update-position-indicator-range)
  (ssw/show-card! indicator-panel "position"))

(defn show-progress-indicator []
  (ssw/show-card! indicator-panel "progress"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File handling

(let [midi-file (atom nil)]
  (defn reload-midi-file []
    (when (and @midi-file @sequencer (.isOpen @sequencer))
      (.setSequence @sequencer (MidiSystem/getSequence @midi-file))))

  (defn choose-midi-file []
    (when-let [f (ssw-chooser/choose-file :filters [["Midi Files" ["midi" "mid" "smf"]]])]
      (reset! midi-file f)
      (reload-midi-file)
      (show-position-indicator))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Device

(defn open-sequencer [s]
  (try
    (reset! sequencer s)
    (if-not (.isOpen s) (.open s))
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(.close s)))
    (catch javax.sound.midi.MidiUnavailableException _
      (ssw/alert "Midi device is unavailable")
      (System/exit 1))))

(let [synth (atom nil)
      receiver (atom nil)]
  (defn open-synthesizer [s]
    (try
      (when @synth (.close @synth))
      (when @receiver (.close @receiver))
      (reset! synth s)
      (let [r (.getReceiver @synth)]
        (reset! receiver r)
        (.open s)
        (.setReceiver @transmitter r)
        (.addShutdownHook (Runtime/getRuntime) (Thread. #(.close r)))
        )
      (catch javax.sound.midi.MidiUnavailableException _
        (ssw/alert "Midi device is unavailable")
        (System/exit 1)))))

(def get-sequencers
  (memoize 
    (fn []
      (->> 
        (MidiSystem/getMidiDeviceInfo)
        (map #(MidiSystem/getMidiDevice %))
        (filter #(isa? (class %) javax.sound.midi.Sequencer))))))

(def get-synthesizers
  (memoize 
    (fn []
      (->> 
        (MidiSystem/getMidiDeviceInfo)
        (map #(MidiSystem/getMidiDevice %))
        (filter #(isa? (class %) javax.sound.midi.Synthesizer))))))

(defn choose-midi-device []
  (show-progress-indicator)
  (when-let [s (ssw/input "Select midi device"
                          :title "Midi device selection" 
                          :choices (get-synthesizers)
                          :to-string #(.getDescription (.getDeviceInfo %)))]
    (stop!)
    ;(open-sequencer s)
    (open-synthesizer s)
    (reload-midi-file))
  (show-position-indicator))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main UI

(def player-panel
  (ssw/toolbar 
    :floatable? false
    :items 
    [(ssw/action :icon (resource "icons/play.png")
                 :handler (fn [_] (start!)))
     (ssw/action :icon (resource "icons/pause.png")
                 :handler (fn [_] (pause!)))
     :separator
     (ssw/action :icon (resource "icons/stop.png")
                 :handler (fn [_] (stop!)))
     :separator
     :separator
     (ssw/action :icon (resource "icons/document.png")
                 :handler (fn [_] (choose-midi-file)))
     (ssw/action :icon (resource "icons/document_refresh.png")
                 :handler (fn [_] (reload-midi-file)))
     (ssw/action :icon (resource "icons/gear.png")
                 :handler (fn [_] (choose-midi-device)))
     ]))

(defn -main [& args]
  (let [f (ssw/frame :title "Simip"
                     :content 
                     (ssw/border-panel :center player-panel
                                       :south  indicator-panel))]
    (open-sequencer (MidiSystem/getSequencer))
    (-> f ssw/pack! ssw/show!)))

