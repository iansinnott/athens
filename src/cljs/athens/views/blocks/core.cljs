(ns athens.views.blocks.core
  (:require
    ["/components/Block/components/Anchor"   :refer [Anchor]]
    ["/components/Block/components/Toggle"   :refer [Toggle]]
    ["/components/Button/Button"             :refer [Button]]
    [athens.common.logging                   :as log]
    [athens.db                               :as db]
    [athens.electron.images                  :as images]
    [athens.events.selection                 :as select-events]
    [athens.router                           :as router]
    [athens.self-hosted.presence.views       :as presence]
    [athens.style                            :as style]
    [athens.subs.selection                   :as select-subs]
    [athens.util                             :as util :refer [mouse-offset vertical-center specter-recursive-path]]
    [athens.views.blocks.autocomplete-search :as autocomplete-search]
    [athens.views.blocks.autocomplete-slash  :as autocomplete-slash]
    [athens.views.blocks.bullet              :refer [bullet-drag-start bullet-drag-end]]
    [athens.views.blocks.content             :as content]
    [athens.views.blocks.context-menu        :as context-menu]
    [athens.views.blocks.drop-area-indicator :as drop-area-indicator]
    [com.rpl.specter                         :as s]
    [re-frame.core                           :as rf]
    [reagent.core                            :as r]
    [stylefy.core                            :as stylefy]))


;; Styles
;;
;; Blocks use Em units in many places rather than Rem units because
;; blocks need to scale with their container: sidebar blocks are
;; smaller than main content blocks, for instance.


(def block-container-style
  {:display         "flex"
   :line-height     "2em"
   :position        "relative"
   :border-radius   "0.125rem"
   :justify-content "flex-start"
   :flex-direction  "column"
   ::stylefy/manual [[:&.show-tree-indicator:before {:content    "''"
                                                     :position   "absolute"
                                                     :width      "1px"
                                                     :left       "calc(1.375em + 1px)"
                                                     :top        "2em"
                                                     :bottom     "0"
                                                     :opacity    "0"
                                                     :transform  "translateX(50%)"
                                                     :transition "background-color 0.2s ease-in-out, opacity 0.2s ease-in-out"
                                                     :background (style/color :border-color)}]
                     [:&:hover
                      :&:focus-within [:&.show-tree-indicator:before {:opacity "1"}]]
                     [:&:after {:content        "''"
                                :z-index        -1
                                :position       "absolute"
                                :top            "0.75px"
                                :right          0
                                :bottom         "0.75px"
                                :left           0
                                :opacity        0
                                :pointer-events "none"
                                :border-radius  "0.25rem"
                                :transition     "opacity 0.075s ease"
                                :background     (style/color :link-color :opacity-lower)}]
                     [:&.is-selected:after {:opacity 1}]
                     [:&.is-presence [:.block-content {:padding-right "1rem"}]]
                     [:.user-avatar {:position "absolute"
                                     :left "4px"
                                     :top "4px"}]
                     [:.block-body {:display               "grid"
                                    :grid-template-columns "1em 1em 1fr auto"
                                    :grid-template-rows    "0 1fr 0"
                                    :grid-template-areas   "
                                      'above above above above'
                                      'toggle bullet content refs'
                                      'below below below below'"
                                    :border-radius         "0.5rem"
                                    :position              "relative"}
                      [:&:hover
                       :&:focus-within ["> .block-toggle" {:opacity "1"}]]
                      [:button.block-edit-toggle {:position   "absolute"
                                                  :appearance "none"
                                                  :width      "100%"
                                                  :background "none"
                                                  :border     0
                                                  :cursor     "text"
                                                  :display    "block"
                                                  :z-index    1
                                                  :top        0
                                                  :right      0
                                                  :bottom     0
                                                  :left       0}]]
                     [:.block-content {:grid-area  "content"
                                       :min-height "1.5em"}]
                     [:&.is-linked-ref {:background-color (style/color :background-plus-2)}]
                     ;; Inset child blocks
                     [:.block-container {:margin-left "2rem"
                                         :grid-area   "body"}]]})


(stylefy/class "block-container" block-container-style)


(def dragging-style
  {:opacity "0.25"})


(stylefy/class "dragging" dragging-style)


;; Components

(defn block-refs-count-el
  [count uid]
  [:div (stylefy/use-style {:margin-left "1em"
                            :grid-area "refs"
                            :z-index (:zindex-dropdown style/ZINDICES)
                            :visibility (when-not (pos? count) "hidden")})
   [:> Button {:on-click (fn [e]
                           (.. e stopPropagation)
                           (rf/dispatch [:right-sidebar/open-item uid]))}
    count]])


(defn block-drag-over
  "If block or ancestor has CSS dragging class, do not show drop indicator; do not allow block to drop onto itself.
  If above midpoint, show drop indicator above block.
  If no children and over X pixels from the left, show child drop indicator.
  If below midpoint, show drop indicator below."
  [e block state]
  (.. e preventDefault)
  (.. e stopPropagation)
  (let [{:block/keys [children
                      uid
                      open]} block
        closest-container    (.. e -target (closest ".block-container"))
        {:keys [x y]}        (mouse-offset e closest-container)
        middle-y             (vertical-center closest-container)
        dragging-ancestor    (.. e -target (closest ".dragging"))
        dragging?            dragging-ancestor
        is-selected?         @(rf/subscribe [::select-subs/selected? uid])
        target               (cond
                               dragging?           nil
                               is-selected?        nil
                               (or (neg? y)
                                   (< y middle-y)) :before
                               (or (not open)
                                   (and (empty? children)
                                        (< 50 x))) :first
                               (< middle-y y)      :after)]
    (when target
      (swap! state assoc :drag-target target))))


(defn drop-bullet
  "Terminology :
    - source-uid        : The block which is being dropped.
    - target-uid        : The block on which source is being dropped.
    - drag-target       : Represents where the block is being dragged. It can be `:first` meaning
                          dragged as a child, `:before` meaning the source block is dropped above the
                          target block, `:after` meaning the source block is dropped below the target block.
    - action-allowed    : There can be 2 types of actions.
        - `link` action : When a block is DnD by dragging a bullet while
                         `shift` key is pressed to create a block link.
        - `move` action : When a block is DnD to other part of Athens page. "

  [source-uid target-uid drag-target action-allowed]
  (let [move-action? (= action-allowed "move")
        event         [(if move-action?
                         :block/move
                         :block/link)
                       {:source-uid source-uid
                        :target-uid target-uid
                        :target-rel drag-target}]]
    (log/debug "drop-bullet" (pr-str {:source-uid     source-uid
                                      :target-uid     target-uid
                                      :drag-target    drag-target
                                      :action-allowed action-allowed
                                      :event          event}))
    (rf/dispatch event)))


(defn drop-bullet-multi
  "
  Terminology :
    - source-uids       : Uids of the blocks which are being dropped
    - target-uid        : Uid of the block on which source is being dropped"
  [source-uids target-uid drag-target]
  (let [source-uids          (mapv (comp first db/uid-and-embed-id) source-uids)
        target-uid           (first (db/uid-and-embed-id target-uid))
        event                (if (= drag-target :first)
                               [:drop-multi/child {:source-uids source-uids
                                                   :target-uid  target-uid}]
                               [:drop-multi/sibling {:source-uids source-uids
                                                     :target-uid  target-uid
                                                     :drag-target drag-target}])]
    (rf/dispatch [::select-events/clear])
    (rf/dispatch event)))


(defn block-drop
  "Handle dom drop events, read more about drop events at:
  : https://developer.mozilla.org/en-US/docs/Web/API/HTML_Drag_and_Drop_API#Define_a_drop_zone"

  [e block state]
  (.. e stopPropagation)
  (let [{target-uid :block/uid} block
        [target-uid _]          (db/uid-and-embed-id target-uid)
        {:keys [drag-target]}   @state
        source-uid              (.. e -dataTransfer (getData "text/plain"))
        effect-allowed          (.. e -dataTransfer -effectAllowed)
        items                   (array-seq (.. e -dataTransfer -items))
        item                    (first items)
        datatype                (.. item -type)
        img-regex               #"(?i)^image/(p?jpeg|gif|png)$"
        valid-text-drop         (and (not (nil? drag-target))
                                     (not= source-uid target-uid)
                                     (or (= effect-allowed "link")
                                         (= effect-allowed "move")))
        selected-items           @(rf/subscribe [::select-subs/items])]

    (cond
      (re-find img-regex datatype) (when (util/electron?)
                                     (images/dnd-image target-uid drag-target item (second (re-find img-regex datatype))))
      (re-find #"text/plain" datatype) (when valid-text-drop
                                         (if (empty? selected-items)
                                           (drop-bullet source-uid target-uid drag-target effect-allowed)
                                           (drop-bullet-multi selected-items target-uid drag-target))))

    (rf/dispatch [:mouse-down/unset])
    (swap! state assoc :drag-target nil)))


(defn block-drag-leave
  "When mouse leaves block, remove any drop area indicator.
  Ignore if target-uid and related-uid are the same — user went over a child component and we don't want flicker."
  [e block state]
  (.. e preventDefault)
  (.. e stopPropagation)
  (let [{target-uid :block/uid} block
        related-uid (util/get-dataset-uid (.. e -relatedTarget))]
    (when-not (= related-uid target-uid)
      ;; (prn target-uid related-uid  "LEAVE")
      (swap! state assoc :drag-target nil))))


(defn toggle
  [block-uid open]
  (rf/dispatch [:block/open {:block-uid block-uid
                             :open?     open}]))


(defn block-el
  "Two checks dec to make sure block is open or not: children exist and :block/open bool"
  ([block]
   [block-el block {:linked-ref false} {}])
  ([block linked-ref-data]
   [block-el block linked-ref-data {}])
  ([_block linked-ref-data _opts]
   (let [{:keys [linked-ref initial-open linked-ref-uid parent-uids]} linked-ref-data
         state (r/atom {:string/local      nil
                        :string/previous   nil
                        ;; one of #{:page :block :slash :hashtag}
                        :search/type       nil
                        :search/results    nil
                        :search/query      nil
                        :search/index      nil
                        :dragging          false
                        :drag-target       nil
                        :last-keydown      nil
                        :context-menu/x    nil
                        :context-menu/y    nil
                        :context-menu/show false
                        :caret-position    nil
                        :show-editable-dom false
                        :linked-ref/open   (or (false? linked-ref) initial-open)})]

     (fn [block linked-ref-data opts]
       (let [{:block/keys [uid
                           string
                           open
                           children
                           _refs]} block
             children-uids         (set (map :block/uid children))
             uid-sanitized-block   (s/transform
                                     (specter-recursive-path #(contains? % :block/uid))
                                     (fn [{:block/keys [original-uid uid] :as block}]
                                       (assoc block :block/uid (or original-uid uid)))
                                     block)
             {:keys [dragging]}    @state
             is-editing            @(rf/subscribe [:editing/is-editing uid])
             is-selected           @(rf/subscribe [::select-subs/selected? uid])
             present-user          @(rf/subscribe [:presence/has-presence uid])
             is-presence           (seq present-user)]

         ;; (prn uid is-selected)

         ;; If datascript string value does not equal local value, overwrite local value.
         ;; Write on initialization
         ;; Write also from backspace, which can join bottom block's contents to top the block.
         (when (not= string (:string/previous @state))
           (swap! state assoc :string/previous string :string/local string))

         [:div
          {:class             ["block-container"
                               (when (and dragging (not is-selected)) "dragging")
                               (when is-editing "is-editing")
                               (when is-selected "is-selected")
                               (when (and (seq children) open) "show-tree-indicator")
                               (when (and (false? initial-open) (= uid linked-ref-uid)) "is-linked-ref")
                               (when is-presence "is-presence")]
           :data-uid          uid
           ;; need to know children for selection resolution
           :data-childrenuids children-uids
           ;; :show-editable-dom allows us to render the editing elements (like the textarea)
           ;; even when not editing this block. When true, clicking the block content will pass
           ;; the clicks down to the underlying textarea. The textarea is expensive to render,
           ;; so we avoid rendering it when it's not needed.
           :on-mouse-enter    #(swap! state assoc :show-editable-dom true)
           :on-mouse-leave    #(swap! state assoc :show-editable-dom false)
           :on-click          (fn [e] (doall (.. e stopPropagation) (rf/dispatch [:editing/uid uid])))
           :on-drag-over      (fn [e] (block-drag-over e block state))
           :on-drag-leave     (fn [e] (block-drag-leave e block state))
           :on-drop           (fn [e] (block-drop e block state))}

          (when (= (:drag-target @state) :before) [drop-area-indicator/drop-area-indicator {:grid-area "above"}])

          [:div.block-body
           (when (seq children)
             [:> Toggle {:isOpen (if (or (and (true? linked-ref) (:linked-ref/open @state))
                                         (and (false? linked-ref) open))
                                   true
                                   false)
                         :on-click (fn [e]
                                     (.. e stopPropagation)
                                     (if (true? linked-ref)
                                       (swap! state update :linked-ref/open not)
                                       (toggle uid (not open))))}])
           (when (:context-menu/show @state)
             [context-menu/context-menu-el uid-sanitized-block state])
           [:> Anchor {:isClosedWithChildren (when (and (seq children)
                                                        (or (and (true? linked-ref) (not (:linked-ref/open @state)))
                                                            (and (false? linked-ref) (not open))))
                                               "closed-with-children")
                       :block block
                       :shouldShowDebugDetails (util/re-frame-10x-open?)
                       :on-click        (fn [e] (router/navigate-uid uid e))
                       :on-context-menu (fn [e] (context-menu/bullet-context-menu e uid state))
                       :on-drag-start   (fn [e] (bullet-drag-start e uid state))
                       :on-drag-end     (fn [e] (bullet-drag-end e uid state))}]
           [content/block-content-el block state]

           [presence/inline-presence-el uid]

           (when (and (> (count _refs) 0) (not= :block-embed? opts))
             [block-refs-count-el (count _refs) uid])]

          [autocomplete-search/inline-search-el block state]
          [autocomplete-slash/slash-menu-el block state]

          ;; Children
          (when (and (seq children)
                     (or (and (true? linked-ref) (:linked-ref/open @state))
                         (and (false? linked-ref) open)))
            (for [child children]
              [:<> {:key (:db/id child)}
               [block-el child
                (assoc linked-ref-data :initial-open (contains? parent-uids (:block/uid child)))
                opts]]))

          (when (= (:drag-target @state) :first) [drop-area-indicator/drop-area-indicator {:style {:grid-area "below"} :child true}])
          (when (= (:drag-target @state) :after) [drop-area-indicator/drop-area-indicator {:style {:grid-area "below"}}])])))))


(defn block-component
  [ident]
  (let [block (db/get-block-document ident)]
    [block-el block]))
