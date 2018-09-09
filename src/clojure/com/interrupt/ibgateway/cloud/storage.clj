(ns com.interrupt.ibgateway.cloud.storage
  (:require [environ.core :as env]
            [mount.core :as mount :refer [defstate]])
  (:import [com.amazonaws.auth AWSStaticCredentialsProvider BasicAWSCredentials]
           [com.amazonaws.services.s3 AmazonS3 AmazonS3ClientBuilder]
           [com.amazonaws.services.s3.model S3Object S3ObjectInputStream]))

(def aws-access-key-id (env/env :aws-access-key-id))

(def aws-secret-access-key (env/env :aws-secret-access-key))

(def aws-region (env/env :aws-region))

(defn s3-client
  [access-key-id secret-access-key region]
  (.. (AmazonS3ClientBuilder/standard)
      (withCredentials (->> secret-access-key
                            (BasicAWSCredentials. access-key-id)
                            AWSStaticCredentialsProvider.))
      (withRegion region)
      build))

(defstate s3
  :start (s3-client aws-access-key-id aws-secret-access-key aws-region))


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
