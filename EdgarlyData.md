

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

## Troubleshooting
> launchctl "Path had bad ownership/permissions"
https://stackoverflow.com/questions/28063598/error-while-executing-plist-file-path-had-bad-ownership-permissions

