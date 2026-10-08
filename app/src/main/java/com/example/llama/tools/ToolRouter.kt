package com.example.llama.tools

import com.example.llama.alarm.AlarmScheduler

class ToolRouter(private val alarmScheduler: AlarmScheduler) {
    fun route(command: ToolCommand, exactAccess: Boolean? = null): AlarmScheduler.Result =
        when (command) {
            is ToolCommand.SetAlarm -> alarmScheduler.schedule(command, exactAccess)
            is ToolCommand.CancelAlarm -> alarmScheduler.cancel(command)
        }
}
