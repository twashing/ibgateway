# IBGateway

An adapter to the IBGateway interface


## Build 

A) The project requires an image from the [tws project](https://github.com/twashing/tws). After building images in that project, these tags should be available.

- edgarly/ibgateway-tws-base:latest
- edgarly/ibgateway-tws-base:`git rev-parse HEAD`
- edgarly/ibgateway-tws:latest 
- edgarly/ibgateway-tws:`git rev-parse HEAD`


B) You have to do an initial build of base docker images.
```
docker build --force-rm --no-cache -f Dockerfile.app.base -t edgarly/ibgateway-app-base:latest -t edgarly/ibgateway-app-base:`git rev-parse HEAD` .
docker build --force-rm --no-cache -f Dockerfile.app -t edgarly/ibgateway-app:latest -t edgarly/ibgateway-app:`git rev-parse HEAD` .

lein run -m com.interrupt.ibgateway.core/-main
```

C) Bringing up docker-compose 
```
# Basic
docker-compose up 

# Force a rebuild of containers
docker-compose up --force-recreate --build
```

D) Connecting to a container
```
$ docker exec -it ibgateway_tws_1 /bin/bashs
```

Running the app solo
```
lein run -m com.interrupt.ibgateway.core/-main
```

Troubleshoot with
```
docker run -it --entrypoint /bin/bash edgarly/ibgateway-app:latest
```


## Installing TWS API locally

This in the "Guide to installing 3rd party JARs", taken from [maven's documentation](https://maven.apache.org/guides/mini/guide-3rd-party-jars-local.html).
```
mvn install:install-file -Dfile=TwsApi.jar -DgroupId=com.interactivebrokers.tws \
  -DartifactId=tws-api -Dversion=9.74.01 -Dpackaging=jar
```


## TODO

- UI Performance
https://www.highcharts.com/docs/advanced-chart-features/boost-module
https://api.highcharts.com/highstock/boost


- Test with 40 + 20 period windows
- Signals (SMA, EMA, BB)
  ? When there's been a price change of more than $1, what crossovers happened, in the last 5 ticks

- Strategies

- Trading Engine
- Position Book

? Will Bid / Ask give us more price signals 

- Artifactory for i) custom jars and ii) as a docker registry

- Research Candlesticks
  https://www.youtube.com/watch?v=NEwixpz7Bow


## NOTES

Stock Market Strategy is a good resource
https://www.stock-market-strategy.com/
https://www.youtube.com/user/StockMarketStrategy/videos


## Change Log

* Version 0.1.0-SNAPSHOT


## Copyright and License

Copyright © 2018 TODO_INSERT_NAME

TODO: [Choose a license](http://choosealicense.com/)


;; TODO

;; > Record market data
;;
;; ** from a docker-compose environment
;; stand-up all components
;; [ok] parse command-line arguments (https://github.com/clojure/tools.cli)
;; see ";; SAVE live data" in com.interrupt.ibgateway.component.switchboard

;; **
;; kafka-listener -> Kafka
;; ewrapper -> TWS
;; migrate data sink atoms (high-opt-imp-volat, high-opt-imp-volat-over-hist, etc)
;;   > to core.async channels > then to kafka output


;; Add these to the 'platform/ibgateway' namespace
;;   scanner-start ( ei/scanner-subscribe )
;;   scanner-stop ( ei/scanner-unsubscribe )

;; record connection IDs

;; CONFIG for
;;   network name of tws

;; TESTs for ibgateway
;;   enable core.async onyx transport for services
;;   workbench for data transport in and out of service
;;   workbench for subscribing to tws
;;
;;   test if open, remain open
;;   test if closed, remain closed
;;   test start scanning; we capture distinct categories (volatility, etc)
;;   test stop scanning
;;   test toggle scan
;; {:scanner-command :start}
;; {:scanner-command :stop}


;; write (Transit) to Kafka
;; read (Transit) from Kafka
;; feed to analysis
