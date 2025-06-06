(ns metabase.driver.mongo.json
  "This namespace adds mongo specific type encoders to `cheshire`. It is copy of the relevant part of monger's `json`
   namespace.

   TODO: I believe this namespace should be completely removed. Trying to run tests without those mongo specific
         encoders yield no failures. Unfortunately I was unable to prove it is not needed yet, hence I'm leaving it
         in just to be safe. Removal should be considered during follow-up of monger removal."
  (:require
   [metabase.util.json :as json])
  (:import
   (org.bson.types BSONTimestamp ObjectId)))

(set! *warn-on-reflection* true)

(json/add-encoder ObjectId
                  (fn [^ObjectId oid ^com.fasterxml.jackson.core.json.WriterBasedJsonGenerator generator]
                    (.writeString generator (.toString oid))))
(json/add-encoder BSONTimestamp
                  (fn [^BSONTimestamp ts ^com.fasterxml.jackson.core.json.WriterBasedJsonGenerator generator]
                    (json/generate-map {:time (.getTime ts) :inc (.getInc ts)} generator)))
