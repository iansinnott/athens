(ns athens.views
  (:require
    ["/components/Spinner/Spinner" :refer [Spinner]]
    ["/components/utils/style/style" :refer [GlobalStyles]]
    ["@material-ui/core/Snackbar" :as Snackbar]
    ["@react-aria/overlays" :refer [OverlayProvider]]
    [athens.config]
    [athens.electron.db-modal :as db-modal]
    [athens.style :refer [zoom]]
    [athens.subs]
    [athens.util :refer [get-os electron?]]
    [athens.views.app-toolbar :as app-toolbar]
    [athens.views.athena :refer [athena-component]]
    [athens.views.devtool :refer [devtool-component]]
    [athens.views.help :refer [help-popup]]
    [athens.views.left-sidebar :as left-sidebar]
    [athens.views.pages.core :as pages]
    [athens.views.right-sidebar :as right-sidebar]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [stylefy.core :as stylefy :refer [use-style]]))


;; Styles


(def app-wrapper-style
  {:display "grid"
   :grid-template-areas
   "'app-header app-header app-header'
    'left-sidebar main-content secondary-content'
   'devtool devtool devtool'"
   :grid-template-columns "auto 1fr auto"
   :grid-template-rows "auto 1fr auto"
   :height "100vh"})


;; Components


(defn alert
  []
  (let [alert- (rf/subscribe [:alert])]
    (when-not (nil? @alert-)
      (js/alert (str @alert-))
      (rf/dispatch [:alert/unset]))))


;; Snackbar

(def m-snackbar (r/adapt-react-class (.-default Snackbar)))


(rf/reg-sub
  :db/snack-msg
  (fn [db]
    (:db/snack-msg db)))


(rf/reg-event-db
  :show-snack-msg
  (fn [db [_ msg-opts]]
    (js/setTimeout #(rf/dispatch [:show-snack-msg {}]) 4000)
    (assoc db :db/snack-msg msg-opts)))


(defn main
  []
  (let [loading    (rf/subscribe [:loading?])
        os         (get-os)
        electron?  (electron?)
        modal      (rf/subscribe [:modal])]
    (fn []
      [:> OverlayProvider
       [:div (merge {:style {:display "contents"}}
                    (zoom))
        [:> GlobalStyles]
        [help-popup]
        [alert]
        (let [{:keys [msg type]} @(rf/subscribe [:db/snack-msg])]
          [m-snackbar
           {:message msg
            :open (boolean msg)}
           [:span
            {:style {:background-color (case type
                                         :success "green"
                                         "red")
                     :padding "10px 20px"
                     :color "white"}}
            msg]])
        [athena-component]
        (cond
          (and @loading @modal) [db-modal/window]

          @loading [:> Spinner]

          :else [:<>
                 (when @modal [db-modal/window])
                 [:div (use-style app-wrapper-style
                                  {:class [(case os
                                             :windows "os-windows"
                                             :mac "os-mac"
                                             :linux "os-linux")
                                           (when electron? "is-electron")]})
                  [app-toolbar/app-toolbar]
                  [left-sidebar/left-sidebar]
                  [pages/view]
                  [right-sidebar/right-sidebar]
                  [devtool-component]]])]])))
