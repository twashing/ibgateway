(ns com.interrupt.ibgateway.cloud.storage
  (:require [mount.core :refer [defstate] :as mount])
  (:import [com.amazonaws AmazonServiceException]
           [com.amazonaws.services.s3 AmazonS3 AmazonS3ClientBuilder]
           [com.amazonaws.services.s3.model S3Object S3ObjectInputStream S3ObjectSummary]
           [com.amazonaws AmazonServiceException]
           [java.io File FileNotFoundException FileOutputStream IOException]
           [java.util List]))


;; In your environment, ensure that you have your AWS credentials set
;; AWS_ACCESS_KEY_ID
;; AWS_SECRET_ACCESS_KEY
;; AWS_REGION

(defstate s3
  :start (AmazonS3ClientBuilder/defaultClient))


(defn put-file [^AmazonS3 s3 bucket-name file-name]
  (.putObject s3
              bucket-name
              file-name
              (clojure.java.io/file file-name)))

(defn get-file [^AmazonS3 s3 bucket-name file-name]
  (let [^S3Object o               (.getObject s3 bucket-name file-name)
        ^S3ObjectInputStream s3is (.getObjectContent o)
        file-contents             (slurp s3is)]
    (spit file-name file-contents)))
