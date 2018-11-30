

## References
https://www.maketecheasier.com/use-launchd-run-scripts-on-schedule-macos/
http://www.launchd.info/
https://alvinalexander.com/mac-os-x/launchd-plist-examples-startinterval-startcalendarinterval
https://stackoverflow.com/questions/3570979/whats-the-difference-between-day-and-weekday-in-launchd-startcalendarinterv


## Launchd Files
/Users/timothyw/Library/LaunchAgents/edgarlyData.plist
/Users/timothyw/Projects/edgarly/ibgateway/edgarly-data.sh


## Command Lifecycle
launchctl list | grep <job.name>
launchctl load ~/Library/LaunchAgents/local.restart.plist
launchctl unload ~/Library/LaunchAgents/local.restart.plist
launchctl start <job.name>


## Example Run
sudo launchctl load /Users/timothyw/Library/LaunchAgents/edgarlyData.plist
sudo launchctl unload /Users/timothyw/Library/LaunchAgents/edgarlyData.plist
sudo launchctl start local.edgarlydata
sudo launchctl stop local.edgarlydata
launchctl list | grep local.edgarlydata

sudo launchctl stop local.edgarlydata && \
sudo launchctl unload /Users/timothyw/Library/LaunchAgents/edgarlyData.plist && \
sudo launchctl load /Users/timothyw/Library/LaunchAgents/edgarlyData.plist && \
sudo launchctl start local.edgarlydata && \
launchctl list | grep local.edgarlydata

# Restart
sudo launchctl stop local.edgarlydata && \
sudo launchctl unload /Users/timothyw/Library/LaunchAgents/edgarlyData.plist && \
sudo launchctl load /Users/timothyw/Library/LaunchAgents/edgarlyData.plist && \
launchctl list | grep local.edgarlydata


## Troubleshooting
> launchctl "Path had bad ownership/permissions"
https://stackoverflow.com/questions/28063598/error-while-executing-plist-file-path-had-bad-ownership-permissions

> Docker clear containers, volumes, networks
docker rm -f $(docker ps -a -q) && \
docker volume rm $(docker volume ls -q) && \
docker network rm $(docker network ls | tail -n+2 | awk '{if($2 !~ /bridge|none|host/){ print $1 }}') && \
docker-compose down


## Notes
NYSE Market Opening: Monday through Friday, 9:30 a.m. to 4:00 p.m. EST
