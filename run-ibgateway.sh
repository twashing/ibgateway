#!/bin/sh

# JVM_OPTS="-server -Xms4G -Xmx4G -XX:PermSize=512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ParallelGCThreads=20 -XX:ConcGCThreads=5 -XX:InitiatingHeapOccupancyPercent=70" docker-compose up tws app

export TWS_HOST="tws-live"
export TWS_PORT="4002"

docker-compose up tws-live app-live
