# IBGateway

TODO: Brief description


## Releases and Dependency Information

* Releases are published to TODO_LINK

* Latest stable release is TODO_LINK

* All released versions TODO_LINK

[Leiningen] dependency information:

    [ibgateway "0.1.0-SNAPSHOT"]

[Maven] dependency information:

    <dependency>
      <groupId>ibgateway</groupId>
      <artifactId>ibgateway</artifactId>
      <version>0.1.0-SNAPSHOT</version>
    </dependency>

[Leiningen]: http://leiningen.org/
[Maven]: http://maven.apache.org/


## TWS Desktop

A) You can connect to TWS, with a VNC viewer (ex: TightVNC).

```
cd ~/Downloads/tvnjviewer-2.8.3-bin-gnugpl/
java -jar tightvnc-jviewer.jar
```

## Build 

B) You have to do an initial build of base docker images.
```
docker build --no-cache -f Dockerfile.tws.base -t twashing/ibgateway-tws-base:latest -t twashing/ibgateway-tws-base:`git rev-parse HEAD` .
docker build --no-cache -f Dockerfile.tws -t twashing/ibgateway-tws:latest -t twashing/ibgateway-tws:`git rev-parse HEAD` .

docker build --force-rm --no-cache -f Dockerfile.app.base -t twashing/ibgateway-app-base:latest -t twashing/ibgateway-app-base:`git rev-parse HEAD` .
docker build --force-rm --no-cache -f Dockerfile.app -t twashing/ibgateway-app:latest -t twashing/ibgateway-app:`git rev-parse HEAD` .

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


## NOTES

EMA looks slower than the SMA


## Change Log

* Version 0.1.0-SNAPSHOT


## Copyright and License

Copyright © 2018 TODO_INSERT_NAME

TODO: [Choose a license](http://choosealicense.com/)
