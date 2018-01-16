FROM twashing/ibgateway-app-base:latest
MAINTAINER Timothy Washington


ENTRYPOINT [ "lein" , "run" , "-m" , "com.interrupt.ibgateway.core/-main" ]
