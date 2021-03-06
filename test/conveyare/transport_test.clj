(ns conveyare.transport-test
  (:require [conveyare.transport :as x]
            [clojure.core.async :as a]
            [clojure.test :refer :all])
  (:import [java.io IOException]
           [java.net ServerSocket]
           [java.util Properties]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [kafka.server KafkaConfig KafkaServerStartable]
           [kafka.utils ZkUtils]
           kafka.admin.TopicCommand
           [org.apache.curator.test TestingServer])
  )

(defn start-server []
  (let [zk (TestingServer.)
        port (. (ServerSocket. 0) (getLocalPort))
        log-dir (. (Files/createTempDirectory "kafka" (into-array FileAttribute [])) (toFile))
        conf {"zookeeper.connect" (. zk (getConnectString))
              "broker.id" "1"
              "host.name" "localhost"
              "port" (str port)
              "log.dir" (. log-dir (getAbsolutePath))
              "log.flush.interval.messages" "1"}
        kafka (KafkaServerStartable. (KafkaConfig. conf))]
    (. kafka (startup))
    {:zk zk
     :kafka kafka
     :bootstrap.servers (str "localhost:" port)}))

(defn stop-server [server]
  (. (:kafka server) (shutdown))
  (. (:zk server) (stop)))

(defn start-transport [server commit-mode]
  (let [conf {:topics ["topic1"]
              :topic-ops (if commit-mode
                           {"topic1" {:commit-mode commit-mode}}
                           {"topic1" {:commit-mode :tracking}})
              :transport {:bootstrap.servers (:bootstrap.servers server)
                          :consumer-ops {:group.id "group1"}
                          :producer-ops {:compression.type "gzip"
                                         :max.request.size 5000000}}}
        transport (x/start conf)
        chan (get transport :out-chan)]
    ;; ... wait for startup (lame, yes)
    (Thread/sleep 2000)
    [transport chan]))

(defn stop-transport [transport]
  (x/stop transport))

(defn read-message [chan]
  (let [t (a/timeout 1000)
        [v ch] (a/alts!! [chan t])]
    (select-keys v [:topic
                    :partition
                    :offset
                    :key
                    :value])))

(deftest test-simple-send-receive
  (let [server (start-server)
        [transport chan] (start-transport server :tracking)]
    (x/process-receipt! transport {:produce true
                                   :topic "topic1"
                                   :value "hi!"
                                   :key "what"})
    (let [v (read-message chan)]
      (is (= {:offset 0
              :partition 0
              :topic "topic1"
              :value "hi!"
              :key "what"} v))
      (a/>!! (x/confirm-chan transport) v))
    (stop-transport transport)
    (stop-server server)))

(deftest test-confirm
  (testing "tracking commit-mode (default)"
    (let [server (start-server)
          [transport chan] (start-transport server :tracking)]
      (x/process-receipt! transport {:produce true
                                     :topic "topic1"
                                     :value "hi!"
                                     :key "what"})
      (x/process-receipt! transport {:produce true
                                     :topic "topic1"
                                     :value "bye!"
                                     :key "how"})
      (let [v1 (read-message chan)
            v2 (read-message chan)]
        (is (= {:offset 0
                :partition 0
                :topic "topic1"
                :value "hi!"
                :key "what"}
               v1))
        (is (= {:offset 1
                :partition 0
                :topic "topic1"
                :value "bye!"
                :key "how"}
               v2))
        (a/>!! (x/confirm-chan transport) v1)
        (x/commit transport))
      (Thread/sleep 100)
      (stop-transport transport)
      (Thread/sleep 100)
      (let [[transport chan] (start-transport server :tracking)]
        ;; last message should be read again due to no commit
        (let [v2again (read-message chan)]
          (is (= {:offset 1
                  :partition 0
                  :topic "topic1"
                  :value "bye!"
                  :key "how"}
                 v2again)))
        (stop-transport transport)
        (stop-server server))))
  (testing "disabled commit-mode"
    (let [server (start-server)
          [transport chan] (start-transport server :disabled)]
      (x/process-receipt! transport {:produce true
                                     :topic "topic1"
                                     :value "hi!"
                                     :key "what"})
      (x/process-receipt! transport {:produce true
                                     :topic "topic1"
                                     :value "bye!"
                                     :key "how"})
      (let [v1 (read-message chan)
            v2 (read-message chan)]
        (is (= {:offset 0
                :partition 0
                :topic "topic1"
                :value "hi!"
                :key "what"}
               v1))
        (is (= {:offset 1
                :partition 0
                :topic "topic1"
                :value "bye!"
                :key "how"}
               v2))
        (a/>!! (x/confirm-chan transport) v1)
        (x/commit transport))
      (Thread/sleep 100)
      (stop-transport transport)
      (Thread/sleep 100)
      (let [[transport chan] (start-transport server :disabled)]
        ;; last message should be read again due to no commit
        (let [v1again (read-message chan)]
          (is (= {:offset 0
                  :partition 0
                  :topic "topic1"
                  :value "hi!"
                  :key "what"}
                 v1again)))
        (stop-transport transport)
        (stop-server server))))
  )

(deftest offset-upkeep
  (testing "empty"
    (is (= {"topic1" {0 {:offset 3 :fast-forward #{} :at 1111}}}
           (x/update-offset {}
                            {:topic "topic1"
                             :partition 0
                             :offset 2}
                            1111
                            100))))
  (testing "new partition"
    (is (= {"topic1" {0 {:offset 3 :fast-forward #{} :at 1111}
                      3 {:offset 1 :fast-forward #{} :at 2222}}}
           (x/update-offset {"topic1" {0 {:offset 3 :fast-forward #{} :at 1111}}}
                            {:topic "topic1"
                             :partition 3
                             :offset 0}
                            2222
                            100))))
  (testing "new topic"
    (is (= {"topic1" {0 {:offset 3 :fast-forward #{} :at 1111}}
            "bananas" {5 {:offset 1000 :fast-forward #{} :at 3333}}}
           (x/update-offset {"topic1" {0 {:offset 3 :fast-forward #{} :at 1111}}}
                            {:topic "bananas"
                             :partition 5
                             :offset 999}
                            3333
                            100))))
  (testing "ignore weird case where offset is lower"
    (is (= {"topic1" {0 {:offset 3 :fast-forward #{6} :at 1111}}}
           (x/update-offset {"topic1" {0 {:offset 3 :fast-forward #{6} :at 1111}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 1}
                            4444
                            100)))
    (is (= {"topic1" {0 {:offset 3 :fast-forward #{6} :at 1111}}}
           (x/update-offset {"topic1" {0 {:offset 3 :fast-forward #{6} :at 1111}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 2}
                            4444
                            100))))
  (testing "offset is next, no fast-forward, advance simply"
    (is (= {"topic1" {0 {:offset 4 :fast-forward #{6} :at 4444}}}
           (x/update-offset {"topic1" {0 {:offset 3 :fast-forward #{6} :at 1111}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 3}
                            4444
                            100))))
  (testing "store up fast forward"
    (is (= {"topic1" {0 {:offset 4 :fast-forward #{6 7} :at 4444}}}
           (x/update-offset {"topic1" {0 {:offset 4 :fast-forward #{6} :at 4444}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 6}
                            5555
                            100)))
    (is (= {"topic1" {0 {:offset 4 :fast-forward #{6 100} :at 4444}}}
           (x/update-offset {"topic1" {0 {:offset 4 :fast-forward #{6} :at 4444}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 99}
                            5555
                            100))))
  (testing "advance and fast-forward"
    (is (= {"topic1" {0 {:offset 8 :fast-forward #{12 34 13 10} :at 6666}}}
           (x/update-offset {"topic1" {0 {:offset 5 :fast-forward #{12 7 34 8 13 10} :at 1111}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 5}
                            6666
                            100))))
  (testing "too many outstanding, but <60s"
    (is (= {"topic1" {0 {:offset 8 :fast-forward #{12 34 13 10 11 23 99 16} :at 6666}}}
           (x/update-offset {"topic1" {0 {:offset 8 :fast-forward #{12 34 13 10 11 23 99} :at 6666}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 15}
                            66665
                            7))))
  (testing "too many outstanding, and >60s"
    (is (= {"topic1" {0 {:offset 13 :fast-forward #{34 23 99 16} :at 6666}}}
           (x/update-offset {"topic1" {0 {:offset 8 :fast-forward #{12 34 13 10 11 23 99} :at 6666}}}
                            {:topic "topic1"
                             :partition 0
                             :offset 15}
                            66667
                            7))))
  )
