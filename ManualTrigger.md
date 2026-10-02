adb shell am broadcast -n com.example.shiftalarm/.alarm.SystemAlarmSetReceiver --ei extra_sys_alarm_hour 7 --ei extra_sys_alarm_minute 30 --es extra_sys_alarm_message "Test alarm"
