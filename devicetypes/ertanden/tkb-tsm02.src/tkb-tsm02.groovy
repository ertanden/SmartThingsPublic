/*
 * TKB TSM02 4-in-1 Multi Sensor Device Type
 *
 * Based on Philio PSM02 4-in-1 Multi Sensor Device Type by eyeonall
 * AND My PSM01 Sensor created by SmartThings/Paul Spee
 * AND SmartThings' Aeon Multi Sensor Reference Device Type
 */

metadata {

    definition (name: "TKB TSM02 Sensor", namespace: "ertanden", author: "Ertan Deniz") {
        capability "Contact Sensor"
        capability "Motion Sensor"
        capability "Temperature Measurement"
        capability "Illuminance Measurement"
        capability "Configuration"
        capability "Sensor"
        capability "Battery"
        capability "Refresh"
        capability "Polling"

        fingerprint deviceId: "0x2001", model: "0002", inClusters: "0x80,0x85,0x70,0x72,0x86,0x30,0x31,0x84", outClusters: "0x20"
    }

    tiles {

        standardTile("contact", "device.contact", width: 2, height: 2) {
            state "closed", label: 'Closed', icon: "st.contact.contact.closed", backgroundColor: "#79b821"
            state "open", label: 'Open', icon: "st.contact.contact.open", backgroundColor: "#ffa81e"
        }

        standardTile("motion", "device.motion", width: 2, height: 2) {
            state "active", label:'motion', icon:"st.motion.motion.active", backgroundColor:"#53a7c0"
            state "inactive", label:'no motion', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff"
        }

        valueTile("temperature", "device.temperature", inactiveLabel: false) {
            state "temperature", label:'${currentValue}°',
                    backgroundColors:[
                            [value: 31, color: "#153591"],
                            [value: 44, color: "#1e9cbb"],
                            [value: 59, color: "#90d2a7"],
                            [value: 74, color: "#44b621"],
                            [value: 84, color: "#f1d801"],
                            [value: 95, color: "#d04e00"],
                            [value: 96, color: "#bc2323"]
                    ]
        }

        valueTile("illuminance", "device.illuminance", inactiveLabel: false) {
            state "luminosity", label:'${currentValue} ${unit}', unit:"lux"
        }

        valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat") {
            state "battery", label:'${currentValue}% battery', unit:""
        }

        main(["contact", "motion", "temperature", "illuminance"])
        details(["contact", "motion", "temperature", "illuminance", "battery", "configure", "refresh"])
    }

    preferences {
        input description: "This feature allows you to correct any temperature variations by selecting an offset. Ex: If your sensor consistently reports a temp that's 5 degrees too warm, you'd enter \"-5\". If 3 degrees too cold, enter \"+3\".", displayDuringSetup: false, type: "paragraph", element: "paragraph"
        input "tempOffset", "number", title: "Temperature Offset", description: "Adjust temperature by this many degrees", range: "*..*", displayDuringSetup: false
    }
}

preferences {
}

def installed() {
    log.debug "Installed with settings: ${settings}"

    setConfigured("false") //wait until the next time device wakeup to send configure command after user change preference
}

def updated() {
    log.debug "Updated with settings: ${settings}"

    setConfigured("false") //wait until the next time device wakeup to send configure command after user change preference
}

// Parse incoming device messages to generate events
def parse(String description)
{
    log.debug "TSM02 Parse called with ${description}"
    def result = []
    def cmd = zwave.parse(description, [0x20: 1, 0x30: 2, 0x31: 5, 0x70: 1, 0x72: 2, 0x80: 1, 0x84: 2, 0x85: 2, 0x86: 1])
    log.debug "TSM02 Parsed CMD: ${cmd.toString()}"
    if (cmd) {
        if( cmd.CMD == "8407" ) { result << new physicalgraph.device.HubAction(zwave.wakeUpV2.wakeUpNoMoreInformation().format()) }
        def evt = zwaveEvent(cmd)
        result << createEvent(evt)
    }
    def statusTextmsg = "Door is ${device.currentValue('contact')}, motion is ${device.currentValue('motion')}, battery is ${device.currentValue('battery')}, temp is ${device.currentValue('temperature')}°, and illuminance is ${device.currentValue('illuminance')} LUX."
    log.debug statusTextmsg
    log.debug "TSM02 Parse returned ${result}"
    return result
}

// Event Generation
//this notification will be sent only when device is battery powered
def zwaveEvent(physicalgraph.zwave.commands.wakeupv1.WakeUpNotification cmd) {
    def result = [createEvent(descriptionText: "${device.displayName} woke up", isStateChange: false)]
    def cmds = []
    if (!isConfigured()) {
        log.debug("late configure")
        result << response(configure())
    } else {
        log.debug("Device has been configured sending >> wakeUpNoMoreInformation()")
        cmds << zwave.wakeUpV1.wakeUpNoMoreInformation().format()
        result << response(cmds)
    }
    result
}

def zwaveEvent(physicalgraph.zwave.commands.configurationv1.ConfigurationReport cmd) {
    log.debug "---CONFIGURATION REPORT V1--- ${device.displayName} parameter ${cmd.parameterNumber} with a byte size of ${cmd.size} is set to ${cmd.configurationValue}"
}

def zwaveEvent(physicalgraph.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd)
{
    log.debug "TSM02: SensorMultilevel ${cmd.toString()}"
    def map = [:]
    switch (cmd.sensorType) {
        case 1:
            // temperature
            def cmdScale = cmd.scale == 1 ? "F" : "C"
            map.value = convertTemperatureIfNeeded(cmd.scaledSensorValue, cmdScale, cmd.precision)
            map.unit = getTemperatureScale()
            map.name = "temperature"
            if (tempOffset) {
                def offset = tempOffset as int
                def v = map.value as int
                map.value = v + offset
            }
            log.debug "Adjusted temp value ${map.value}"
            break;
        case 3:
            // luminance
            map.value = cmd.scaledSensorValue.toInteger().toString()
            map.unit = "lux"
            map.name = "illuminance"
            break;
    }
    //map
    createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) {
    log.debug "TSM02: BatteryReport ${cmd.toString()}}"
    def map = [:]
    map.name = "battery"
    map.value = cmd.batteryLevel > 0 ? cmd.batteryLevel.toString() : 1
    map.unit = "%"
    map.displayed = false
    //map
    createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd) {
    log.debug "TSM02: SensorBinaryReport ${cmd.toString()}}"
    def map = [:]
    switch (cmd.sensorType) {
        case 10: // contact sensor
            map.name = "contact"
            log.debug "TSM02 cmd.sensorValue: ${cmd.sensorValue}"
            if (cmd.sensorValue.toInteger() > 0 ) {
                log.debug "TSM02 DOOR OPEN"
                map.value = "open"
                map.descriptionText = "$device.displayName is open"
            } else {
                log.debug "TSM02 DOOR CLOSED"
                map.value = "closed"
                map.descriptionText = "$device.displayName is closed"
            }
            break;
        case 12: // motion sensor
            map.name = "motion"
            log.debug "TSM02 cmd.sensorValue: ${cmd.sensorValue}"
            if (cmd.sensorValue.toInteger() > 0 ) {
                log.debug "TSM02 Motion Detected"
                map.value = "active"
                map.descriptionText = "$device.displayName is active"
            } else {
                log.debug "TSM02 No Motion"
                map.value = "inactive"
                map.descriptionText = "$device.displayName no motion"
            }
            map.isStateChange = true
            break;
    }
    //map
    createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.Command cmd) {
    log.debug "TSM02: Catchall reached for cmd: ${cmd.toString()}}"
    [:]
}

def configure() {
    log.debug "TSM02: configure() called"

    setConfigured("true")

    delayBetween([

            //1 tick = 30 minutes
            zwave.configurationV1.configurationSet(parameterNumber: 3, size: 1, scaledConfigurationValue: 70).format(), // PIR Sensitivity 1-100
            zwave.configurationV1.configurationSet(parameterNumber: 5, size: 1, scaledConfigurationValue: 1).format(), // Security Mode
            zwave.configurationV1.configurationSet(parameterNumber: 7, size: 1, scaledConfigurationValue: 22).format(), // Enable Motion-OFF report
            zwave.configurationV1.configurationSet(parameterNumber: 8, size: 1, scaledConfigurationValue: 27).format(), // PIR Redetect Interval. Applies only if in security mode
            zwave.configurationV1.configurationSet(parameterNumber: 10, size: 1, scaledConfigurationValue: 12).format(), // Auto report Battery time 1-127, default 12
            zwave.configurationV1.configurationSet(parameterNumber: 11, size: 1, scaledConfigurationValue: 12).format(), // Auto report Door/Window state time 1-127, default 12
            zwave.configurationV1.configurationSet(parameterNumber: 12, size: 1, scaledConfigurationValue: 12).format(), // Auto report Illumination time 1-127, default 12
            zwave.configurationV1.configurationSet(parameterNumber: 13, size: 1, scaledConfigurationValue: 12).format(), // Auto report Temperature time 1-127, default 12
            zwave.wakeUpV1.wakeUpIntervalSet(seconds: 24 * 3600, nodeid:zwaveHubNodeId).format(),                        // Wake up every hour

    ])
}

private setConfigured(configure) {
    updateDataValue("configured", configure)
}

private isConfigured() {
    getDataValue("configured") == "true"
}
