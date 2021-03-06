/**
 *  Smokey Haas
 *
 *  Copyright 2014 Andrew Haas
 *
 */
metadata {
	definition (name: "Smokey Haas", namespace: "drandyhaas", author: "Andrew Haas") {

		capability "Smoke Detector"
		capability "Carbon Monoxide Detector"
		capability "Sensor"
		capability "Battery"

        attribute "updated", "number"
		attribute "alarmState", "string"

		fingerprint deviceId: "0xA100", inClusters: "0x20,0x80,0x70,0x85,0x71,0x72,0x86"
	}

	simulator {
		status "smoke": "command: 7105, payload: 01 FF"
		status "clear": "command: 7105, payload: 01 00"
		status "test": "command: 7105, payload: 0C FF"
		status "carbonMonoxide": "command: 7105, payload: 02 FF"
		status "carbonMonoxide clear": "command: 7105, payload: 02 00"
		status "battery 100%": "command: 8003, payload: 64"
		status "battery 5%": "command: 8003, payload: 05"
	}

	tiles {
		standardTile("smoke", "device.alarmState", width: 2, height: 2) {
			state("clear", label:"clear", icon:"st.alarm.smoke.clear", backgroundColor:"#ffffff")
			state("smoke", label:"SMOKE", icon:"st.alarm.smoke.smoke", backgroundColor:"#e86d13")
			state("carbonMonoxide", label:"MONOXIDE", icon:"st.alarm.carbon-monoxide.carbon-monoxide", backgroundColor:"#e86d13")
			state("tested", label:"TEST", icon:"st.alarm.smoke.test", backgroundColor:"#e86d13")
		}
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat") {
			state "battery", label:'${currentValue}% battery', unit:""
		}

		main "battery"
		details(["smoke", "battery"])
	}
}

def parse(String description) {
	def results = []
	if (description.startsWith("Err")) {
	    results << createEvent(descriptionText:description, displayed:true)
	} else {
		def cmd = zwave.parse(description, [ 0x80: 1, 0x84: 1, 0x71: 2, 0x72: 1 ])
		if (cmd) {
			zwaveEvent(cmd, results)
		}
	}
	log.debug "\"$description\" parsed to ${results.inspect()}"
    
    if (!device.currentValue("updated")) {
        sendEvent(name:"updated", value: 1)
    }
    else{
    	sendEvent(name:"updated", value: device.currentValue("updated")+1)//ACH
	}
    
	return results
}


def createSmokeOrCOEvents(name, results) {
	def text = null
	if (name == "smoke") {
		text = "$device.displayName smoke was detected!"
		// these are displayed:false because the composite event is the one we want to see in the app
		results << createEvent(name: "smoke",          value: "detected", descriptionText: text, displayed: false)
	} else if (name == "carbonMonoxide") {
		text = "$device.displayName carbon monoxide was detected!"
		results << createEvent(name: "carbonMonoxide", value: "detected", descriptionText: text, displayed: false)
	} else if (name == "tested") {
		text = "$device.displayName was tested"
		results << createEvent(name: "smoke",          value: "tested", descriptionText: text, displayed: false)
		results << createEvent(name: "carbonMonoxide", value: "tested", descriptionText: text, displayed: false)
	} else if (name == "smokeClear") {
		text = "$device.displayName smoke is clear"
		results << createEvent(name: "smoke",          value: "clear", descriptionText: text, displayed: false)
		name = "clear"
	} else if (name == "carbonMonoxideClear") {
		text = "$device.displayName carbon monoxide is clear"
		results << createEvent(name: "carbonMonoxide", value: "clear", descriptionText: text, displayed: false)
		name = "clear"
	} else if (name == "testClear") {
		text = "$device.displayName smoke is clear"
		results << createEvent(name: "smoke",          value: "clear", descriptionText: text, displayed: false)
		results << createEvent(name: "carbonMonoxide", value: "clear", displayed: false)
		name = "clear"
	}
	// This composite event is used for updating the tile
	results << createEvent(name: "alarmState", value: name, descriptionText: text)
}

def zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmReport cmd, results) {
	if (cmd.zwaveAlarmType == physicalgraph.zwave.commands.alarmv2.AlarmReport.ZWAVE_ALARM_TYPE_SMOKE) {
		if (cmd.zwaveAlarmEvent == 3) {
			createSmokeOrCOEvents("tested", results)
		} else {
			createSmokeOrCOEvents((cmd.zwaveAlarmEvent == 1 || cmd.zwaveAlarmEvent == 2) ? "smoke" : "smokeClear", results)
		}
	} else if (cmd.zwaveAlarmType == physicalgraph.zwave.commands.alarmv2.AlarmReport.ZWAVE_ALARM_TYPE_CO) {
		createSmokeOrCOEvents((cmd.zwaveAlarmEvent == 1 || cmd.zwaveAlarmEvent == 2) ? "carbonMonoxide" : "carbonMonoxideClear", results)
	} else switch(cmd.alarmType) {
		case 1:
			createSmokeOrCOEvents(cmd.alarmLevel ? "smoke" : "smokeClear", results)
			break
		case 2:
			createSmokeOrCOEvents(cmd.alarmLevel ? "carbonMonoxide" : "carbonMonoxideClear", results)
			break
		case 12:  // test button pressed
			createSmokeOrCOEvents(cmd.alarmLevel ? "tested" : "testClear", results)
			break
		case 13:  // sent every hour -- not sure what this means, just a wake up notification?
			if (cmd.alarmLevel != 255) {
				results << createEvent(descriptionText: "$device.displayName code 13 is $cmd.alarmLevel", displayed: true)
			}
			
			// Clear smoke in case they pulled batteries and we missed the clear msg
			if(device.currentValue("smoke") != "clear") {
				createSmokeOrCOEvents("smokeClear", results)
			}
			
			// Check battery if we don't have a recent battery event
            if (!(state.batterytime)) {
                state.batterytime = new Date().time
                log.debug "no prev batterytime, now $state.batterytime "
            }
            def bdiff = (new Date().time - state.batterytime)/60000 // the time in minutes (ms/1000/60)
            log.debug "bat time diff: $bdiff minutes"
			if (bdiff > 60 * 24) {//24 hours?
            	log.debug "get battery"
                state.batterytime = new Date().time
				results << new physicalgraph.device.HubAction(zwave.batteryV1.batteryGet().format())
			}
			break
		default:
			results << createEvent(displayed: true, descriptionText: "Alarm $cmd.alarmType ${cmd.alarmLevel == 255 ? 'activated' : cmd.alarmLevel ?: 'deactivated'}".toString())
			break
	}
}

// SensorBinary and SensorAlarm aren't tested, but included to preemptively support future smoke alarms
//
def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd, results) {
	if (cmd.sensorType == physicalgraph.zwave.commandclasses.SensorBinaryV2.SENSOR_TYPE_SMOKE) {
		createSmokeOrCOEvents(cmd.sensorValue ? "smoke" : "smokeClear", results)
	} else if (cmd.sensorType == physicalgraph.zwave.commandclasses.SensorBinaryV2.SENSOR_TYPE_CO) {
		createSmokeOrCOEvents(cmd.sensorValue ? "carbonMonoxide" : "carbonMonoxideClear", results)
	}
}

def zwaveEvent(physicalgraph.zwave.commands.sensoralarmv1.SensorAlarmReport cmd, results) {
	if (cmd.sensorType == 1) {
		createSmokeOrCOEvents(cmd.sensorState ? "smoke" : "smokeClear", results)
	} else if (cmd.sensorType == 2) {
		createSmokeOrCOEvents(cmd.sensorState ? "carbonMonoxide" : "carbonMonoxideClear", results)
	}
	
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpNotification cmd, results) {
	results << new physicalgraph.device.HubAction(zwave.wakeUpV1.wakeUpNoMoreInformation().format())
	results << createEvent(descriptionText: "$device.displayName woke up", isStateChange: false)
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd, results) {
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		map.descriptionText = "$device.displayName battery is low!"
	} else {
		map.value = cmd.batteryLevel
	}
    if (cmd.batteryLevel <= 80) {
		map.value = 10
		map.descriptionText = "$device.displayName battery was just $cmd.batteryLevel % !"
    }
	results << createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.Command cmd, results) {
	def event = [ displayed: false ]
	event.linkText = device.label ?: device.name
	event.descriptionText = "$event.linkText: $cmd"
	results << createEvent(event)
}