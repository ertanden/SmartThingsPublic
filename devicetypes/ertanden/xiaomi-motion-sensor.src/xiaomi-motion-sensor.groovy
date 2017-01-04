/**
 *  Xiaomi Motion Sensor
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * Based on original DH by Eric Maycock 2015
 * modified 29/12/2016 a4refillpad
 * Added fingerprinting
 * Added heartbeat/lastcheckin for monitoring
 * Added battery and refresh
 * Motion background colours consistent with latest DH
 */

metadata {
    definition(name: "Xiaomi Motion Sensor", namespace: "ertanden", author: "a4refillpad") {
        capability "Motion Sensor"
        capability "Configuration"
        capability "Sensor"
        capability "Refresh"

        command "enrollResponse"
        command "reset"

        attribute "lastCheckin", "String"

        fingerprint profileId: "0104", deviceId: "0104", inClusters: "0000, 0003, FFFF, 0019", outClusters: "0000, 0004, 0003, 0006, 0008, 0005, 0019", manufacturer: "LUMI", model: "lumi.sensor_motion", deviceJoinName: "Xiaomi Motion"
    }

    simulator {
    }

    preferences {
        input "motionReset", "number", title: "Number of seconds after the last reported activity to report that motion is inactive (in seconds).", description: "", value: 120, displayDuringSetup: false
    }

    tiles(scale: 2) {
        multiAttributeTile(name: "motion", type: "generic", width: 6, height: 4) {
            tileAttribute("device.motion", key: "PRIMARY_CONTROL") {
                attributeState "active", label: 'motion', icon: "st.motion.motion.active", backgroundColor: "#ffa81e"
                attributeState "inactive", label: 'no motion', icon: "st.motion.motion.inactive", backgroundColor: "#79b821"
            }
        }
        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
        }
        standardTile("configure", "device.configure", inactiveLabel: false, width: 2, height: 2, decoration: "flat") {
            state "configure", label: '', action: "configuration.configure", icon: "st.secondary.configure"
        }

        standardTile("reset", "device.reset", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", action: "reset", label: "Reset Motion"
        }

        main(["motion"])
        details(["motion", "reset", "refresh", "configure"])
    }
}

def parse(String description) {
    log.debug "Parsing '${description}'"

    Map map = [:]
    if (description?.startsWith('read attr -')) {
        map = parseReportAttributeMessage(description)
    } else if (description?.startsWith('catchall:')) {
        map = parseCatchAllMessage(description)
    }

    log.debug "Parse returned $map"
    def result = map ? createEvent(map) : null
//  send event for heartbeat
    def now = new Date()
    sendEvent(name: "lastCheckin", value: now)

    if (description?.startsWith('enroll request')) {
        List cmds = enrollResponse()
        log.debug "enroll response: ${cmds}"
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
    }

    return result
}

private Map getBatteryResult(rawValue) {
    log.debug 'Battery'
    def linkText = getLinkText(device)

    log.debug rawValue

    def result = [
            name : 'battery',
            value: '--'
    ]

    def volts = rawValue / 1
    result.value = volts
    result.descriptionText = "${linkText} battery was ${result.value}%"

    return result
}

private Map parseCatchAllMessage(String description) {
    Map resultMap = [:]
    def cluster = zigbee.parse(description)
    log.debug cluster
    if (shouldProcessMessage(cluster)) {
        switch (cluster.clusterId) {

        }
    }

    return resultMap
}

private boolean shouldProcessMessage(cluster) {
    // 0x0B is default response indicating message got through
    // 0x07 is bind message
    boolean ignoredMessage = cluster.profileId != 0x0104 ||
            cluster.command == 0x0B ||
            cluster.command == 0x07 ||
            (cluster.data.size() > 0 && cluster.data.first() == 0x3e)
    return !ignoredMessage
}


def configure() {
    // Device-Watch allows 2 check-in misses from device + ping (plus 1 min lag time)
    // enrolls with default periodic reporting until newer 5 min interval is confirmed
    sendEvent(name: "checkInterval", value: 2 * 60 * 60 + 1 * 60, displayed: false, data: [protocol: "zigbee", hubHardwareId: device.hub.hardwareID])

    log.debug "Configuring Reporting, IAS CIE, and Bindings."

    return refresh() // send refresh cmds as part of config
}

def enrollResponse() {
    log.debug "Sending enroll response"
    String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
    [
            //Resending the CIE in case the enroll request is sent before CIE is written
            "zcl global write 0x500 0x10 0xf0 {${zigbeeEui}}", "delay 200",
            "send 0x${device.deviceNetworkId} 1 ${endpointId}", "delay 500",
            //Enroll Response
            "raw 0x500 {01 23 00 00 00}",
            "send 0x${device.deviceNetworkId} 1 1", "delay 200"
    ]
}

/**
 * PING is used by Device-Watch in attempt to reach the Device
 * */
def ping() {
    //return zigbee.readAttribute(0x0001, 0x0020) // Read the Battery Level
    return zigbee.readAttribute(0x0000, 0x0000)
}

def refresh() {
    log.debug "Refreshing Battery"
    def refreshCmds = [
            //"st rattr 0x${device.deviceNetworkId} 1 0x0001 0x0020", "delay 200"
            "st rattr 0x${device.deviceNetworkId} 1 0x0000 0x0000", "delay 200"
    ]

    return refreshCmds + enrollResponse()
}

private Map parseReportAttributeMessage(String description) {
    Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
        def nameAndValue = param.split(":")
        map += [(nameAndValue[0].trim()): nameAndValue[1].trim()]
    }
    log.debug "Desc Map: $descMap"

    Map resultMap = [:]

    if (descMap.cluster == "0001" && descMap.attrId == "0020") {
        resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
    } else if (descMap.cluster == "0406" && descMap.attrId == "0000") {
        def value = descMap.value.endsWith("01") ? "active" : "inactive"
        if (settings.motionReset == null || settings.motionReset == "") settings.motionReset = 120
        if (value == "active") runIn(settings.motionReset, stopMotion)
        resultMap = getMotionResult(value)
    }
    return resultMap
}

private Map parseCustomMessage(String description) {
    def resultMap = [:]
    return resultMap
}

private Map getMotionResult(value) {
    log.debug 'motion'
    def linkText = getLinkText(device)
    def descriptionText = "${linkText} was ${value == 'active' ? 'active' : 'inactive'}"
    def commands = [
            name           : 'motion',
            value          : value,
            descriptionText: descriptionText
    ]
    return commands
}

private byte[] reverseArray(byte[] array) {
    int i = 0;
    int j = array.length - 1;
    byte tmp;

    while (j > i) {
        tmp = array[j];
        array[j] = array[i];
        array[i] = tmp;
        j--;
        i++;
    }

    return array
}

private String swapEndianHex(String hex) {
    reverseArray(hex.decodeHex()).encodeHex()
}

def stopMotion() {
    sendEvent(name: "motion", value: "inactive")
}

def reset() {
    sendEvent(name: "motion", value: "inactive")
}
