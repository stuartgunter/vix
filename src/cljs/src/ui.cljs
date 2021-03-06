;; cljs/src/ui.cljs: utility functions related to the user interface.
;; Copyright 2011-2013, Vixu.com, F.M. (Filip) de Waard <fmw@vixu.com>.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;; 
;; http://www.apache.org/licenses/LICENSE-2.0
;; 
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns vix.ui
  (:use-macros [vix.crossover.macros :only [get-snippets]])
  (:use [domina.css :only [sel]]
        [domina.xpath :only [xpath]])
  (:require [vix.util :as util]
            [domina :as domina]
            [clojure.string :as string]
            [goog.dom :as dom]
            [goog.dom.forms :as forms]
            [goog.dom.classes :as classes]
            [goog.ui.Dialog :as goog.ui.Dialog]
            [goog.ui.Dialog.ButtonSet :as goog.ui.Dialog.ButtonSet]
            [goog.ui.Dialog.EventType :as goog.ui.Dialog.EventType]
            [goog.ui.DatePicker :as DatePicker]
            [goog.ui.DatePicker.Events :as DatePickerEvents]
            [goog.events :as events]
            [goog.fx.DragDrop :as goog.fx.DragDrop]
            [goog.fx.DragDropGroup :as goog.fx.DragDropGroup]
            ;; [goog.fx.dom :as fx-dom]
            ;; [goog.fx.Animation :as Animation]
            ;; [goog.fx.Animation.EventType :as transition-event]
            ;; [goog.Timer :as timer]
            ))

(def snippets
  "Map with the HTML snippets generated by the get-snippets macro,
   e.g. {:feed/:add-feed-button \"<button ...>\" ...}."
  (get-snippets))

(defn clear-page!
  "Removes all child-nodes of the div#main-page node, effectively
   clearing the page."
  []
  (domina/destroy-children! (xpath "//div[@id='main-page']")))

(defn- execute-show!
  "Performs the action grunt work for the show! fn, apart from
   clearing the page, see its docstring for more information."
  [& all-actions]
  (loop [actions (flatten all-actions)]
    (when (not-empty actions)
      (let [{:keys [parent snippet transformations children] :as action}
            (first actions)]
        (domina/append! (xpath (or parent "//div[@id='main-page']"))
                        (snippets snippet))
        (doall ;; execute transformations
         (map (fn [{:keys [selector
                          text
                          attrs
                          remove-attr
                          add-class
                          remove-class
                          value
                          checked]}]
                (let [node (xpath selector)]
                  (when text
                    (domina/set-text! node text))
                  (when attrs
                    (domina/set-attrs! node attrs))
                  (when remove-attr
                    (domina/remove-attr! node remove-attr))
                  (when add-class
                    (domina/add-class! node add-class))
                  (when remove-class
                    (domina/remove-class! node remove-class))
                  (when value
                    (domina/set-value! node value))
                  (when (not (nil? checked))
                    (if checked
                      (domina/set-attr! (domina/single-node node)
                                        :checked "checked")
                      (domina/remove-attr! (domina/single-node node)
                                           :checked)))))
              transformations))
        (when (not-empty children)
          (apply execute-show! children))
        (recur (rest actions))))))

(defn show!
  "Clears the page and executes the provided actions.

   Accepts any number of arguments, each being an action map. Every
   action should at least contain a :snippet key that is mapped to a
   HTML string to insert. The action may contain a :parent key, that
   maps to a string containing an xpath selector for a parent element
   to place the newly created element in. Without a :parent value, the
   div#main-page element is used as a parent element by default. The
   action may also contain a :transformations key that maps to a
   sequence of transformations to perform after the snippet is added
   to the DOM. Each transformation is a map with a :selector key
   mapped to a string with an xpath selector for the node to
   transform, as well as a :text, :attrs, :remove-attr :value
   or :checked key (or multiple) containing the new text content for
   the node, an attribute map, a keyword of an attribute to remove,
   form field value or boolean respectively. The action map may also
   contain an optional :children key, which maps to a sequence of
   actions that will be recursively executed after the current action
   is added to the DOM and its transformations are completed (i.e.
   with nesting).

   See the views/feed and views/editor namespaces for example usage."
  [& all-actions]
  (clear-page!)
  (apply execute-show! all-actions))

(defn set-form-value [el-id-or-obj value]
  (let [el (util/get-element el-id-or-obj)]
    (forms/setValue el value)))

(defn get-form-value [el-id-or-obj]
  (let [el (util/get-element el-id-or-obj)]
    (forms/getValue el)))

(defn is-checked?
  "Accepts a checkbox element or element ID and returns true if checked."
  [el-id-or-obj]
  (= "on" (get-form-value el-id-or-obj)))

(defn get-form-value-by-name [form-el-id-or-obj name]
  (let [form (util/get-element form-el-id-or-obj)]
    (forms/getValueByName form name)))

(defn get-form-values
  "Returns a map containing the values for all form elements within
   the given form-el. Keys are element names converted to keywords.
   Values are either strings or a vector of strings if the form
   element has multiple values."
  [form-el]
  (let [form-data (forms/getFormDataMap (util/get-element form-el))]
    (zipmap (map keyword (. form-data (getKeys)))
            (map (fn [value]
                   (if (> (count value) 1)
                     (vec value)
                     (first value)))
                 (. form-data (getValues))))))

(defn button-to-obj [button]
  (util/map-to-obj {:key (name button)
                    :caption (if (= button :ok)
                               "OK"
                               (string/capitalize (name button)))}))

(defn button-set [& buttons]
  (let [button-set (new goog.ui.Dialog.ButtonSet)]
    (doseq [button buttons]
      (doto button-set
        (.addButton (button-to-obj button)
                    (= button (first buttons))
                    (= button :cancel))))
    button-set))

(defn display-dialog!
  "Displays a goog.ui.Dialog with given title and the HTML content
   from the provided content string. When the dialog is closed the
   dialog is removed and the provided callback function is called with
   the dialog result (:ok or :cancel) as the first argument and a map
   with the form data from the provided form-el HTML form node as the
   second argument."
  ([title content form-el options callback]
     (display-dialog! title content form-el options callback :ok :cancel))
  ([title content form-el {:keys [modal? auto-close?]} callback & buttons]
     (let [dialog (new goog.ui.Dialog)]
       (events/listen dialog
                      goog.ui.Dialog.EventType/SELECT
                      (fn [evt]
                        (. evt (preventDefault))
                        (. evt (stopPropagation))
                        (let [button (keyword (.-key evt))]
                          (callback button
                                    (when form-el (get-form-values form-el)))
                          (when (or auto-close? (= :cancel button))
                            (remove-dialog!)))))
       (doto dialog
         (.setModal modal?)
         (.setTitle title)
         (.setContent content)
         (.setButtonSet (apply button-set buttons))
         (.setVisible true)))))

(defn remove-dialog!
  "Removes any goog.ui.Dialog elements from the DOM."
  []
  (domina/destroy! (domina/by-class "modal-dialog"))
  (domina/destroy! (domina/by-class "modal-dialog-bg")))

(defn display-datepicker
  "Displays a date picker in a dialog and passes a goog.date.Date object
   (or nil if no date is selected) to the success-handler function if
   the user confirms the selection. If the include-time? argument is
   true two arguments are added to the success-handler fn: hour and
   minute (both strings)."
  [success-handler include-time?]
  (let [dp (goog.ui.DatePicker.)]
    (display-dialog "Pick a date"
                    (str (:ui/date-widget snippets)
                         (when include-time?
                           (:ui/datepicker-time-row snippets)))
                    (fn [evt]
                      (when (= "ok" (.-key evt))
                        (let [date-obj (. dp (getDate))]
                          (success-handler
                           {:date-object date-obj
                            :date-string (.toIsoString date-obj true)
                            :hour (get-form-value "hour")
                            :minute (get-form-value "minute")}))
                        (. dp (dispose)))
                      (remove-dialog)))

    (.decorate dp (dom/getElement "date-widget"))))

(defn add-or-remove-errors
  "Displays or removes error, using status-el and active-el, based on
   the provided validation-result (either :pass or a map with
   its :error key mapped to the particular error and its :message key
   to the human-readable error message that is to be displayed in
   status-el."
  [status-el active-el validation-result]
  (if (= validation-result :pass)
    (remove-error status-el active-el)
    (display-error status-el (:message validation-result) active-el)))

(defn display-error
  "Adds the class 'error' to the provided status-el, sets its text
  content to the given message and also adds the 'error' class to the
  other-elements provided as optional further arguments. Returns nil."
  [status-el message & other-elements]
  (doseq [el other-elements]
    (domina/add-class! el "error"))

  (doto status-el
    (domina/remove-class! "status-ok")
    (domina/remove-class! "hide")
    (domina/add-class! "status-error")
    (domina/add-class! "error")
    (domina/set-text! message))

  nil)

(defn remove-error
  "Removes the classes 'error' and 'status-error' from the given
   status-el, sets the text content of the status-el to an empty
   string and removes the 'error' class from any other-elements
   provided as optional further arguments. Returns nil."
  [status-el & other-elements]
  (doseq [el other-elements]
    (domina/remove-class! el "error"))
  
  (doto status-el
    (domina/remove-class! "status-error")
    (domina/remove-class! "error")
    (domina/set-text! ""))

  nil)

(defn enable-element [el-id-or-obj]
  (let [el (util/get-element el-id-or-obj)]
    (doto el
      (classes/remove "disabled")
      (.removeAttribute "disabled"))))

(defn disable-element [el-id-or-obj]
  (let [el (util/get-element el-id-or-obj)]
    (doto el
      (classes/add "disabled")
      (.setAttribute "disabled" "disabled"))))

(defn trigger-on-class [class trigger-on f]
  ;; converting to vector to avoid issues with doseq and arrays
  (doseq [el (cljs.core.Vector/fromArray (dom/getElementsByClass class))]
    (events/listen el trigger-on f)))

(defn remove-class-from-elements [class elements]
  (doseq [el elements]
    (classes/remove el class)))

(defn not-fixed? [el]
  (not (classes/has el "fixed")))

(def active-el (atom nil))

(defn get-draggable-item
  "Returns the actual draggable item starting from a (possibly nested)
   event target element and going up the tree recursively (up to 5 times).
   The event's target object refers to the element that was clicked on,
   which might be a nested node (e.g. a span or a element inside the li
   node that is the intended draggable item)."
  [el]
  (loop [current-el el
         times-called 0]
    (if (and (= (.-tagName current-el) "LI")
             (.hasAttribute current-el "draggable"))
      (cond
       (= (.-draggable current-el) false)
       (if (classes/has current-el "drop-on-grandparent")
         (util/get-parent (util/get-parent current-el))
         nil)
       (= (.-draggable current-el) true)
       current-el)
      (if (<= times-called 5)
        (recur (util/get-parent current-el) (inc times-called))
        nil))))

(defn node-contains?
  "Checks if the element passed as the first argument contains the
   element passed at the second argument."
  [x y]
  (when (and x y)
    (dom/contains x y)))

(defn can-add-to-nested-ul? [el-dragged el-dropped-on]
  (not (or (node-contains? el-dragged el-dropped-on)
           (node-contains? el-dropped-on el-dragged))))

(defn get-item-details-el [el]
  (first (util/get-children-by-class el "item-details")))

(defn remove-highlight-from-add-link-elements [elements]
  (doseq [add-link-el elements]
    (when (classes/has add-link-el "highlight-add-link-el")
      (classes/remove add-link-el "highlight-add-link-el")
      (set! (.-textContent add-link-el)
            (. (.-textContent add-link-el) (substr 2))))))

(defn clean-drop-data
  "Removes META elements from provided html string and returns
   innerHTML string. This is necessary because some versions of Chrome
   append a meta tag to the drop data."
  [html]
  (let [unclean-dummy-el (dom/createElement "div")
        clean-dummy-el (dom/createElement "div")]
    (set! (.-innerHTML unclean-dummy-el) html)
    (doseq [non-meta-child (filter #(not (= (.-tagName %) "META"))
                                   (util/get-children unclean-dummy-el))]
      (dom/appendChild clean-dummy-el non-meta-child))
    (.-innerHTML clean-dummy-el)))

(defn to-sortable-tree [parent-el after-drop-fn]
  (let [top-level-drop-zone-el (dom/getElement "menu-top-level-drop-zone")
        li-elements (filter not-fixed?
                            (util/get-children-by-tag parent-el "li"))
        add-link-elements (util/get-elements-by-tag-and-class
                           "a"
                           "add-item-to-nested-menu")]

    ;; required to make the top-level-drop-zone a valid drop target
    (. top-level-drop-zone-el
       (addEventListener
        "dragover"
        (fn [e]
          (. e (stopPropagation))
          (. e (preventDefault)))))

    (. top-level-drop-zone-el
       (addEventListener
        "drop"
        (fn [e]
          (. e (stopPropagation))
          (. e (preventDefault))
          (dom/appendChild parent-el @active-el))))
    
    (doseq [el li-elements]
      ;; not using Google's events/listen because it strips .dataTransfer
      (. el (addEventListener
             "dragstart"
             (fn [e]
               (when-let [dragged-el (get-draggable-item (.-target e))]
                 (reset! active-el dragged-el)
                 (classes/add dragged-el "dragging")
                 (comment
                   (set! (.effectAllowed (.-dataTransfer e)) "move"))
                 (. (.-dataTransfer e)
                    (setData "text/html" (.-innerHTML dragged-el)))

                 ;; show the top level drop zone (if relevant)
                 (when-not (= (util/get-parent dragged-el) parent-el)
                   (classes/remove top-level-drop-zone-el "invisible"))
                 
                 ;; highlight add link buttons that can be dropped on
                 (doseq [add-link-el add-link-elements]
                   (when-not (or (classes/has add-link-el
                                              "highlight-add-link-el")
                                 (node-contains? dragged-el add-link-el)
                                 (util/is-sibling? dragged-el
                                                   (util/get-parent
                                                    add-link-el)))
                     (classes/add add-link-el "highlight-add-link-el")
                     (set! (.-textContent add-link-el)
                           (str "\u21fe " (.-textContent add-link-el)))))))))
      
      ;; required to make the node a valid drop target  
      (. el (addEventListener
             "dragover"
             (fn [e]
               (. e (stopPropagation))
               (. e (preventDefault)))))

      (. el (addEventListener
             "drop"
             (fn [e]
               (. e (stopPropagation))
               (. e (preventDefault))
               
               (remove-highlight-from-add-link-elements add-link-elements)
               
               (when-let [drop-target (get-draggable-item (.-target e))]
                 (cond
                  ;; when dropped on an add-item node of a nested ul
                  (classes/has (.-target e) "add-item-to-nested-menu")
                  (when (can-add-to-nested-ul? @active-el drop-target)
                    (dom/insertSiblingBefore @active-el
                                             (util/get-parent (.-target e))))
                  :default
                  ;; when dropped on another node
                  (when-not (= @active-el drop-target)
                    (let [drop-data (clean-drop-data
                                     (. (.-dataTransfer e)
                                        (getData "text/html")))]
                      (if
                          ;; when dropped on an ancestor or descendant node
                          (or (node-contains? drop-target @active-el)
                              (node-contains? @active-el drop-target))
                        (let [dt-item-details-el (get-item-details-el
                                                  drop-target)
                              data-dummy-el (dom/createElement "div")]
                          (do
                            ;; use dummy el to access the item-details
                            ;; child node of the dragged element
                            (set! (.-innerHTML data-dummy-el) drop-data)
                            (set! (.-innerHTML (get-item-details-el
                                                @active-el))
                                  (.-innerHTML dt-item-details-el))
                            (set! (.-innerHTML dt-item-details-el)
                                  (.-innerHTML (first
                                                (util/get-children
                                                 data-dummy-el))))))
                        ;; when dropped on an unrelated node
                        (do
                          (set! (.-innerHTML @active-el)
                                (.-innerHTML drop-target))
                          (set! (.-innerHTML drop-target) drop-data)))))))
               (after-drop-fn))))

      (. el (addEventListener
             "dragend"
             (fn [e]
               (. e (stopPropagation))
               (. e (preventDefault))
               
               (classes/remove @active-el "dragging")
               (classes/add top-level-drop-zone-el "invisible")
               
               (remove-highlight-from-add-link-elements
                add-link-elements)))))))

; TODO: this function is still a work-in-progress
(comment
  (defn fx!
    ([fx-obj element duration]
       (fx! fx-obj element duration {:begin nil :end nil}))
    ([fx-obj element duration event-handlers]
       (let [begin-fn (:begin event-handlers)
             end-fn (:end event-handlers)
             animation (fx-obj. element duration)]

         (when (fn? begin-fn)
         (events/listen animation transition-event/BEGIN begin-fn))
 
       (when (fn? end-fn)
         (events/listen animation
                        transition-event/END
                        #((do (end-fn)
                              (. animation (destroy))))))
       (. animation (play)))))

  (def fade-in! (partial fx! fx-dom/FadeInAndShow true))
  (def fade-out! (partial fx! fx-dom/FadeOutAndHide true)))

(comment
  ; TODO: implement a nice animation for displaying status messages

  (defn remove-slug-error [status-el slug-el]
    (let [end-fn (fn []
                   ; make sure we don't remove new errors that
                   ; popped up after the start of the animation
                   (when (classes/has slug-el "error")
                     (swap! fade-out-animation-active false)
                     (classes/remove status-el "status-error")
                     (classes/remove status-el "error")
                     (dom/setTextContent status-el " ")))]
      (when (classes/has slug-el "error")
        (fade-out! status-el 1000 {:begin-fn nil :end-fn end-fn})
        (classes/remove slug-el "error")))))