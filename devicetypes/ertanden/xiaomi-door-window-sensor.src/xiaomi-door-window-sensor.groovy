/**
 *  Xiaomi Door/Window Sensor
 *
 *  Copyright 2015 Eric Maycock
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
 */

metadata {
    definition(name: "Xiaomi Door/Window Sensor", namespace: "ertanden", author: "Eric Maycock") {
        capability "Contact Sensor"
        capability "Configuration"
        capability "Sensor"
        capability "Refresh"

        command "enrollResponse"

        attribute "lastCheckin", "String"

        fingerprint profileId: "0104", deviceId: "0104", inClusters: "0000, 0003, FFFF, 0019", outClusters: "0000, 0004, 0003, 0006, 0008, 0005, 0019", manufacturer: "LUMI", model: "lumi.sensor_magnet", deviceJoinName: "Xiaomi Contact"
    }

    simulator {
        status "closed": "on/off: 0"
        status "open": "on/off: 1"
    }

    tiles(scale: 2) {
        multiAttributeTile(name: "contact", type: "generic", width: 6, height: 4) {
            tileAttribute("device.contact", key: "PRIMARY_CONTROL") {
                attributeState "open", label: '${name}', icon: "st.contact.contact.open", backgroundColor: "#ffa81e"
                attributeState "closed", label: '${name}', icon: "st.contact.contact.closed", backgroundColor: "#79b821"
            }
        }

        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
            state "default", action: "refresh.refresh", icon: "st.secondary.refresh"
        }
        standardTile("configure", "device.configure", inactiveLabel: false, width: 2, height: 2, decoration: "flat") {
            state "configure", label: '', action: "configuration.configure", icon: "st.secondary.configure"
        }

        main(["contact"])
        details(["contact", "refresh", "configure"])
    }
}

def parse(String description) {
    log.debug "Parsing '${description}'"

    Map map = [:]
    if (description?.startsWith('on/off: ')) {
        map = parseCustomMessage(description)
    } else if (description?.startsWith('read attr -')) {
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
            //"st rattr 0x${device.deviceNetworkId} 1 1 0x20", "delay 200"
            "st rattr 0x${device.deviceNetworkId} 1 0x0000 0x0000", "delay 200"
    ]

    return refreshCmds + enrollResponse()
}
/*
def refresh() {
    log.debug "Refreshing Battery"
    def endpointId = 0x01
    [
            "st rattr 0x${device.deviceNetworkId} ${endpointId} 0x0000 0x0000", "delay 200"
    ] //+ enrollResponse()
}
*/

private Map parseReportAttributeMessage(String description) {
    Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
        def nameAndValue = param.split(":")
        map += [(nameAndValue[0].trim()): nameAndValue[1].trim()]
    }
    log.debug "Desc Map: $descMap"

    Map resultMap = [:]

    if (descMap.cluster == "0001" && descMap.attrId == "0020") {
        resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
    }
    return resultMap
}

private Map parseCustomMessage(String description) {
    def result
    if (description?.startsWith('on/off: ')) {

        if (description == 'on/off: 0') {
            result = getContactResult("closed")
        } else if (description == 'on/off: 1') {
            result = getContactResult("open")
        }

        return result
    }
}

private Map getContactResult(value) {
    def linkText = getLinkText(device)
    def descriptionText = "${linkText} was ${value == 'open' ? 'opened' : 'closed'}"
    def commands = [
            name           : 'contact',
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
