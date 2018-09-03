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

## TODO

- Troubleshoot EMA; why is it slower than SMA
  - Test with SINE Wave
- Signals (SMA, EMA, BB)
  ? When there's been a price change of more than $1, what crossovers happened, in the last 5 ticks


- Remaining Analytics
- Remaining Signals
- Strategies
- Scan market for most volatile stocks


- Code Cleanup
- Trading Engine
- Position Book

? Will Bid / Ask give us more price signals 
! Have to Plan + Reason about + Solve Problems, while developing the platform

- Artifactory for i) custom jars and ii) as a docker registry
- S3 Bucket sync'ing
  - https://rclone.org
  - http://duplicity.nongnu.org/features.html
  - https://s3tools.org/s3cmd-sync
  - https://www.tarsnap.com/index.html


## NOTES

EMA looks slower than the SMA


## Change Log

* Version 0.1.0-SNAPSHOT


## Copyright and License

Copyright © 2018 TODO_INSERT_NAME

TODO: [Choose a license](http://choosealicense.com/)
