(ns athens.effects
  (:require
    [athens.async                :as async]
    [athens.common-db            :as common-db]
    [athens.common-events.schema :as schema]
    [athens.common.logging       :as log]
    [athens.db                   :as db]
    [athens.self-hosted.client   :as client]
    [cljs-http.client            :as http]
    [cljs.core.async             :refer [go <!]]
    [cljs.core.async.interop     :refer [<p!]]
    [com.stuartsierra.component  :as component]
    [datascript.core             :as d]
    [day8.re-frame.async-flow-fx]
    [goog.dom.selection          :refer [setCursorPosition]]
    [malli.core                  :as m]
    [malli.error                 :as me]
    [re-frame.core               :as rf]
    [stylefy.core                :as stylefy]))


;; Effects


;; TODO: remove this effect when :transact is removed.
(rf/reg-fx
  :transact!
  (fn [tx-data]
    (common-db/transact-with-middleware! db/dsdb tx-data)))


(rf/reg-fx
  :reset-conn!
  (fn [new-db]
    (d/reset-conn! db/dsdb new-db)
    (common-db/health-check db/dsdb)))


(rf/reg-fx
  :http
  (fn [{:keys [url method opts on-success on-failure]}]
    (go
      (let [http-fn (case method
                      :post http/post :get http/get
                      :put http/put :delete http/delete)
            res     (<! (http-fn url opts))
            {:keys [success body] :as all} res]
        (if success
          (rf/dispatch (conj on-success body))
          (rf/dispatch (conj on-failure all)))))))


(rf/reg-fx
  :timeout
  (let [timers (atom {})]
    (fn [{:keys [action id event wait]}]
      (case action
        :start (swap! timers assoc id (js/setTimeout #(rf/dispatch event) wait))
        :clear (do (js/clearTimeout (get @timers id))
                   (swap! timers dissoc id))))))


;; Using DOM, focus the target block.
;; There can actually be multiple elements with the same #editable-uid-UID HTML id
;; The same unique datascript block can be rendered multiple times: node-page, right sidebar, linked/unlinked references
;; In this case, find the all the potential HTML blocks with that uid. The one that shares the same closest ancestor as the
;; activeElement (where the text caret is before the new focus happens), is the container of the block to focus on.

;; If an index is passed, set cursor to that index.

;; TODO: some issues
;; - auto-focus on textarea
;; - searching for common-ancestor on inside of setTimeout vs outside
;;   - element sometimes hasn't been created yet (enter), sometimes has been just destroyed (backspace)
;; - uid sometimes nil

(rf/reg-fx
  :editing/focus
  (fn [[uid index]]
    (if (nil? uid)
      (when-let [active-el (.-activeElement js/document)]
        (.blur active-el))
      (js/setTimeout (fn []
                       (let [[uid embed-id]  (db/uid-and-embed-id uid)
                             html-id         (str "editable-uid-" uid)
                             ;; targets (js/document.querySelectorAll html-id)
                             ;; n       (count (array-seq targets))
                             el              (js/document.querySelector
                                               (if embed-id
                                                 (or
                                                   ;; find exact embed block
                                                   (str "textarea[id='" html-id "-embed-" embed-id "']")
                                                   ;; find embedded that starts with current html id (embed id changed due to re-render)
                                                   (str "textarea[id^='" html-id "-embed-']"))
                                                 ;; take default
                                                 (str "#" html-id)))]
                         #_(cond
                             (zero? n) (prn "No targets")
                             (= 1 n) (prn "One target")
                             (< 1 n) (prn "Several targets"))
                         (when el
                           (.focus el)
                           (when index
                             (setCursorPosition el index)))))
                     100))))


;; todo(abhinav)
;; think of this + up/down + editing/focus for common up down press
;; and cursor goes to apt position rather than last visited point in the block(current)
;; inspirations - intelli-j's up/down
(rf/reg-fx
  :set-cursor-position
  (fn [[uid start end]]
    (js/setTimeout (fn []
                     (when-let [target (js/document.querySelector (str "#editable-uid-" uid))]
                       (.focus target)
                       (set! (.-selectionStart target) start)
                       (set! (.-selectionEnd target) end)))
                   100)))


(rf/reg-fx
  :stylefy/tag
  (fn [[tag properties]]
    (stylefy/tag tag properties)))


(rf/reg-fx
  :alert/js!
  (fn [message]
    (js/alert message)))


(rf/reg-fx
  :confirm/js!
  (fn [[message true-cb false-cb]]
    (if (js/window.confirm message)
      (true-cb)
      (false-cb))))


(rf/reg-fx
  :right-sidebar/scroll-top
  (fn []
    (let [right-sidebar (js/document.querySelector ".right-sidebar-content")]
      (when right-sidebar
        (set! (.. right-sidebar -scrollTop) 0)))))


;; TODO: temporary, and limits to one client opened.
(def self-hosted-client (atom nil))


(defn self-hosted-health-check
  [url success-cb failure-cb]
  (go (let [ch  (go (<p! (.. (js/fetch (str "http://" url "/health-check"))
                             (then (fn [response]
                                     (if (.-ok response)
                                       :success
                                       :failure)))
                             (catch (fn [_] :failure)))))]
        (condp = (<! (athens.async/with-timeout ch 5000 :timed-out))
          :success   (success-cb)
          :timed-out (failure-cb)
          :failure   (failure-cb)))))


(rf/reg-fx
  :remote/client-connect!
  (fn [{:keys [url ws-url] :as remote-db}]
    (log/debug ":remote/client-connect!" (pr-str (:url remote-db)))
    (when @self-hosted-client
      (log/info ":remote/client-connect! already connected, restarting")
      (component/stop @self-hosted-client))
    (log/info ":remote/client-connect! health-check")
    (self-hosted-health-check
      url
      (fn []
        (log/info ":remote/client-connect! health-check success")
        (log/info ":remote/client-connect! connecting")
        (reset! self-hosted-client (-> ws-url
                                       client/new-ws-client
                                       component/start)))
      (fn []
        (log/warn ":remote/client-connect! health-check failure")
        (rf/dispatch [:remote/connection-failed])))))


(rf/reg-fx
  :remote/client-disconnect!
  (fn []
    (log/debug ":remote/client-disconnect!")
    (when @self-hosted-client
      (component/stop @self-hosted-client)
      (reset! self-hosted-client nil))))


(rf/reg-fx
  :remote/send-event-fx!
  (fn [event]
    (if (schema/valid-event? event)
      ;; valid event let's send it
      (do
        (log/debug "Sending event:" (pr-str event))
        (client/send! event))
      (let [explanation (-> schema/event
                            (m/explain event)
                            (me/humanize))]
        (log/warn "Tried to send invalid event. Error:" (pr-str explanation))))))


(rf/reg-fx
  :invoke-callback
  (fn [callback]
    (callback)))
