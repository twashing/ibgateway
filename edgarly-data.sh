#!/bin/sh

# touch /Users/timothyw/Projects/edgarly/ibgateway/edgarlydata.log
# echo "Foo Running edgarly-data.sh" >> /Users/timothyw/Projects/edgarly/ibgateway/edgarlydata.log
cd /Users/timothyw/Projects/edgarly/ibgateway/
# sudo -u timothyw /usr/local/bin/docker-compose down && /usr/local/bin/docker-compose up
sudo -u timothyw /usr/local/bin/docker-compose up tws record-live-data
