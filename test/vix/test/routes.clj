; test/vix/test/routes.clj tests for routes namespace.
; Copyright 2011, F.M. (Filip) de Waard <fmw@vix.io>.
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
; http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns vix.test.routes
  (:use [vix.routes] :reload
        [vix.db :only [create-document get-document db-name]]
        [vix.auth :only [add-user]]
        [clojure.contrib.json :only [json-str]]
        [vix.test.db :only [database-fixture +test-server+ +test-db+]])
  (:use [clojure.test]))

; Copied from Clojure 1.3.
; Also discussed in Joy of Clojure by Fogus & Houser, page 299.
(defn with-redefs-fn
  "Temporarily redefines Vars during a call to func.  Each val of
  binding-map will replace the root value of its key which must be
  a Var.  After func is called with no args, the root values of all
  the Vars will be set back to their old values.  These temporary
  changes will be visible in all threads.  Useful for mocking out
  functions during testing."
  {:added "1.3"}
  [binding-map func]
  (let [root-bind (fn [m]
                    (doseq [[a-var a-val] m]
                      (.bindRoot ^clojure.lang.Var a-var a-val)))
        old-vals (zipmap (keys binding-map)
                         (map deref (keys binding-map)))]
    (try
      (root-bind binding-map)
      (func)
      (finally
        (root-bind old-vals)))))

; Copied from Clojure 1.3.
; Also discussed in Joy of Clojure by Fogus & Houser, page 299.
(defmacro with-redefs
  "binding => var-symbol temp-value-expr

  Temporarily redefines Vars while executing the body.  The
  temp-value-exprs will be evaluated and each resulting value will
  replace in parallel the root value of its Var.  After the body is
  executed, the root values of all the Vars will be set back to their
  old values.  These temporary changes will be visible in all threads.
  Useful for mocking out functions during testing."
  {:added "1.3"}
  [bindings & body]
  `(with-redefs-fn ~(zipmap (map #(list `var %) (take-nth 2 bindings))
                            (take-nth 2 (next bindings)))
                    (fn [] ~@body)))


(defn request-map [method resource body & params]
  {:request-method method
   :uri resource
   :body (when body (java.io.StringReader. body))
   :params (first params)})

; FIXME: refactor these four request functions to get rid of duplication
(defn request
  ([method resource my-routes]
   (request method resource "" my-routes))
  ([method resource body my-routes & params]
   (my-routes (assoc (request-map method resource body params)
                     :session
                     {:username "someone"
                      :permissions {:* ["GET" "POST" "PUT" "DELETE"]}}))))

(defn unauthorized-request
  ([method resource my-routes]
   (unauthorized-request method resource "" my-routes))
  ([method resource body my-routes & params]
   (app (assoc (request-map method resource body params)
                     :session
                     {:username "nemo"
                      :permissions {:* ["GET" "POST" "PUT"]
                                    :blog []}}))))

(defn unauthenticated-request
  ([method resource my-routes]
   (unauthorized-request method resource "" my-routes))
  ([method resource body my-routes & params]
   (app (request-map method resource body params))))

(defn form-request [method resource my-routes form-params]
  (app (dissoc (assoc (request-map method resource nil nil)
                            :form-params
                            form-params)
                     :body)))

(deftest test-json-response
  (is (= (:status (json-response nil)) 404))

  ; FIXME: move from ISO-8859-1 to UTF-8...
  (is (= (json-response {:foo "bar"})
         {:status 200
          :headers {"Content-type" "application/json; charset=ISO-8859-1"}
          :body "{\"foo\":\"bar\"}"}))

  (is (= (json-response {:foo "bar"} 201)
         {:status 201
          :headers {"Content-type" "application/json; charset=ISO-8859-1"}
          :body "{\"foo\":\"bar\"}"})))

(deftest test-response
  (is (= (response "foo") {:status 200
                         :headers {"Content-type"
                                   "text/html; charset=ISO-8859-1"}
                         :body "foo"}))

  (is (= (:status (response nil) 404)))
  (is (= (:status (response "foo" 201) 201))))

(deftest test-catch-all
  (is (= (:status (catch-all +test-server+ +test-db+ "/blog/bar")) 404))
  (is (= (:body (catch-all +test-server+ +test-db+ "/blog/bar"))
         "<h1>Page not found</h1>"))

  (do
    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
       :slug "/blog/bar"
       :content "bar"
       :draft false}))

  (is (= (:status (catch-all +test-server+ +test-db+ "/blog/bar")) 200)))

(deftest ^{:integration true} test-routes
  (do
    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
       :feed "blog"
       :slug "/blog/bar"
       :content "bar"
       :draft false}))

  (with-redefs [db-name +test-db+]
    (is (= (:status (request :get "/" main-routes)) 200))
    (is (= (:status (request :get "/login" main-routes)) 200))
    (is (= (:status (request :get "/logout" main-routes)) 302))
    (is (= (:status (request :get "/admin/blog" main-routes)) 200))
    (is (= (:status (request :get "/json/blog" main-routes)) 200))

    (is (= (:status (request
                          :post
                          "/json/blog/new"
                          (json-str {:title "test-create"
                                     :slug "/blog/test"
                                     :content "hic sunt dracones"})
                          main-routes))
           201))

    (let [document (get-document +test-server+ +test-db+ "/blog/test")]
      (is (= (:title document)) "test-create"))

    (is (= (:status (request :get "/json/document/blog/bar" main-routes)) 200))
    (is (= (:status (request :get "/json/document/blog/t3" main-routes)) 404))
    
    ; FIXME: should add a test-case for a 409 conflict
    (let [document (get-document +test-server+ +test-db+ "/blog/bar")]
      (is (= (:status (request
                            :put
                            "/json/document/blog/bar"
                            (json-str (assoc document :title "hi!"))
                            main-routes))
             200))

      (is (= (:title (get-document +test-server+ +test-db+ "/blog/bar"))
             "hi!"))

      (is (= (:status (request
                            :put
                            "/json/document/blog/doesnt-exist"
                            (json-str (assoc document :title "hi!"))
                            main-routes))
             404)))
     

    (is (= (:status (request :delete "/json/document/blog/bar" main-routes))
           200))
    (is (= (get-document +test-server+ +test-db+ "/blog/bar") nil))
                        
    (is (= (:status (request :get "/static/style.css" main-routes)) 200))
    (is (= (:status (request :get "/static/none" main-routes)) 404))
    (is (= (:body (request :get "/static/none" main-routes))
           "<h1>Page not found</h1>"))
    
    (is (= (:status (request :get "/blog/bar" main-routes)) 404))
    (is (= (:body (request :get "/blog/bar" main-routes))
           "<h1>Page not found</h1>"))

    (is (= (:status (request :get "/blog/test" main-routes)) 200))))

(deftest ^{:integration true} test-routes-authorization
  (do
    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
       :feed "blog"
       :slug "/blog/test"
       :content "bar"
       :draft false}))

  (with-redefs [db-name +test-db+]
    (testing "Test if authorization is enforced correctly."
      (is (= (:status (unauthorized-request :get "/admin/blog" main-routes))
             302))
      (is (= (:status (unauthorized-request :get "/json/blog" main-routes))
             302))
      (is (= (:status (unauthorized-request
                        :post
                        "/json/blog/new"
                        (json-str {:title "test-create"
                                   :slug "/blog/test"
                                   :content "hic sunt dracones"})

                        main-routes))
             302))
      (is (= (:status (unauthorized-request
                        :get
                        "/json/document/blog/test"
                        main-routes))
             302))
      (is (= (:status (unauthorized-request
                        :put
                        "/json/document/blog/test"
                        (json-str {:title "test-create"
                                   :slug "/blog/test"
                                   :content "hic sunt dracones"})

                        main-routes))
             302))
      (is (= (:status (unauthorized-request
                        :delete
                        "/json/document/blog/test"
                        main-routes))
             302)))))

(deftest ^{:integration true} test-routes-authentication
  (do
    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
       :feed "blog"
       :slug "/blog/test"
       :content "bar"
       :draft false}))

  (testing "Test if authentication is enforced correctly."
    (with-redefs [db-name +test-db+]
      (is (= (:status (unauthenticated-request :get "/admin/blog" main-routes))
             302))
      (is (= (:status (unauthenticated-request :get "/json/blog" main-routes))
             302))
      (is (= (:status (unauthenticated-request
                        :post
                        "/json/blog/new"
                        (json-str {:title "test-create"
                                   :slug "/blog/test"
                                   :content "hic sunt dracones"})

                        main-routes))
             302))
      (is (= (:status (unauthenticated-request
                        :get
                        "/json/document/blog/test"
                        main-routes))
             302))
      (is (= (:status (unauthenticated-request
                        :put
                        "/json/document/blog/test"
                        (json-str {:title "test-create"
                                   :slug "/blog/test"
                                   :content "hic sunt dracones"})

                        main-routes))
             302))
      (is (= (:status (unauthenticated-request
                        :delete
                        "/json/document/blog/test"
                        main-routes))
             302)))))

(deftest test-logout
  (is (= (logout {:username "johndoe"})
         {:session {}
          :status 302
          :headers {"Location" "/"}})
      "should empty the session and redirect to /"))

(deftest test-login
  (do
    (add-user
      +test-server+
      +test-db+
      "fmw"
      "oops"
      {:* ["GET" "POST" "PUT" "DELETE"]}))

  (with-redefs [db-name +test-db+]
    (is (= (form-request :post "/login" main-routes {"username" "fmw"
                                                     "password" "foo"})
           {:status 302
            :headers {"Location" "/login"}
            :body ""}))

    (let [r (form-request :post "/login" main-routes {"username" "fmw"
                                                     "password" "oops"})]
      (is (= ((:headers r) "Location") "/admin/blog"))
      (is (= (:status r) 302)))
   ))


(defn test-ns-hook []
  (test-json-response)
  (test-response)
  (database-fixture test-catch-all)
  (database-fixture test-routes)
  (database-fixture test-routes-authorization)
  (database-fixture test-routes-authentication)
  (test-logout)
  (database-fixture test-login))
