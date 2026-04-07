/**
 *  MIT License
 *  Copyright 2026
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
metadata {
    definition(name: 'ESPHome Thermostat', namespace: 'esphome', author: 'ESPHome', singleThreaded: true) {
        capability 'Actuator'
        capability 'Initialize'
        capability 'Refresh'
        capability 'SignalStrength'
        capability 'Thermostat'
        capability 'TemperatureMeasurement'

        // attribute populated by ESPHome API Library automatically
        attribute 'networkStatus', 'enum', [ 'connecting', 'online', 'offline' ]
    }

    preferences {
        input name: 'ipAddress',    // required setting for API library
                type: 'text',
                title: 'Device IP Address',
                required: true

        input name: 'password',     // optional setting for API library
                type: 'text',
                title: 'Device Password <i>(if required)</i>',
                required: false

        input name: 'climate',      // allows the user to select which climate entity to use
                type: 'enum',
                title: 'ESPHome Climate Entity',
                required: state.climates?.size() > 0,
                options: state.climates?.collectEntries { k, v -> [ k, v.name ] }

        input name: 'logEnable',    // if enabled the library will log debug details
                type: 'bool',
                title: 'Enable Debug Logging',
                required: false,
                defaultValue: false

        input name: 'logTextEnable',
                type: 'bool',
                title: 'Enable descriptionText logging',
                required: false,
                defaultValue: true
    }
}

// ESPHome climate mode to Hubitat thermostat mode mapping
@Field static final Map<Integer, String> CLIMATE_MODE_MAP = [
    0: 'off',        // CLIMATE_MODE_OFF
    1: 'auto',       // CLIMATE_MODE_HEAT_COOL
    2: 'cool',       // CLIMATE_MODE_COOL
    3: 'heat',       // CLIMATE_MODE_HEAT
    4: 'fan only',   // CLIMATE_MODE_FAN_ONLY
    5: 'dry',        // CLIMATE_MODE_DRY
    6: 'auto'        // CLIMATE_MODE_AUTO
]

// Hubitat thermostat mode to ESPHome climate mode mapping
@Field static final Map<String, Integer> HUBITAT_MODE_MAP = [
    'off':      0,   // CLIMATE_MODE_OFF
    'heat':     3,   // CLIMATE_MODE_HEAT
    'cool':     2,   // CLIMATE_MODE_COOL
    'auto':     1,   // CLIMATE_MODE_HEAT_COOL
    'fan only': 4,   // CLIMATE_MODE_FAN_ONLY
    'dry':      5    // CLIMATE_MODE_DRY
]

// ESPHome climate action to Hubitat operating state mapping
@Field static final Map<Integer, String> CLIMATE_ACTION_MAP = [
    0: 'idle',       // CLIMATE_ACTION_OFF
    2: 'cooling',    // CLIMATE_ACTION_COOLING
    3: 'heating',    // CLIMATE_ACTION_HEATING
    4: 'idle',       // CLIMATE_ACTION_IDLE
    5: 'idle',       // CLIMATE_ACTION_DRYING
    6: 'fan only'    // CLIMATE_ACTION_FAN
]

// ESPHome climate fan mode to Hubitat fan mode mapping
// Note: CLIMATE_FAN_OFF maps to 'auto' as Hubitat Thermostat has no 'off' fan mode
@Field static final Map<Integer, String> CLIMATE_FAN_MAP = [
    0: 'on',         // CLIMATE_FAN_ON
    1: 'off',        // CLIMATE_FAN_OFF
    2: 'auto',       // CLIMATE_FAN_AUTO
    3: 'low',        // CLIMATE_FAN_LOW
    4: 'medium',     // CLIMATE_FAN_MEDIUM
    5: 'high',       // CLIMATE_FAN_HIGH
    6: 'medium',     // CLIMATE_FAN_MIDDLE
    7: 'on',         // CLIMATE_FAN_FOCUS
    8: 'on',         // CLIMATE_FAN_DIFFUSE
    9: 'low'         // CLIMATE_FAN_QUIET
]

// Hubitat fan mode to ESPHome climate fan mode mapping
@Field static final Map<String, Integer> HUBITAT_FAN_MAP = [
    'on':     0,     // CLIMATE_FAN_ON
    'off':    1,     // CLIMATE_FAN_OFF
    'auto':   2,     // CLIMATE_FAN_AUTO
    'low':    3,     // CLIMATE_FAN_LOW
    'medium': 4,     // CLIMATE_FAN_MEDIUM
    'high':   5      // CLIMATE_FAN_HIGH
]

// =============================================================================
// Lifecycle methods
// =============================================================================

public void initialize() {
    // API library command to open socket to device, it will automatically reconnect if needed
    openSocket()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

public void installed() {
    log.info "${device} driver installed"
}

public void logsOff() {
    espHomeSubscribeLogs(LOG_LEVEL_INFO, false) // disable device logging
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

public void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

public void uninstalled() {
    closeSocket('driver uninstalled') // make sure the socket is closed when uninstalling
    log.info "${device} driver uninstalled"
}

public void refresh() {
    log.info "${device} refresh"
    state.clear()
    state.requireRefresh = true
    espHomeDeviceInfoRequest()
}

// =============================================================================
// Hubitat Thermostat capability commands
// =============================================================================

public void setThermostatMode(String mode) {
    Integer espMode = HUBITAT_MODE_MAP[mode]
    if (espMode == null) {
        log.warn "${device} unsupported thermostat mode: ${mode}"
        return
    }
    if (logTextEnable) { log.info "${device} setting thermostat mode to ${mode}" }
    espHomeClimateCommand(key: settings.climate as Long, mode: espMode)
}

public void setCoolingSetpoint(BigDecimal temp) {
    if (logTextEnable) { log.info "${device} setting cooling setpoint to ${temp}" }
    Map entity = state.climates?.get(settings.climate as String)
    if (entity?.supportsTwoPointTargetTemperature) {
        espHomeClimateCommand(key: settings.climate as Long, targetTemperatureHigh: temp as Float)
    } else {
        espHomeClimateCommand(key: settings.climate as Long, targetTemperature: temp as Float)
    }
}

public void setHeatingSetpoint(BigDecimal temp) {
    if (logTextEnable) { log.info "${device} setting heating setpoint to ${temp}" }
    Map entity = state.climates?.get(settings.climate as String)
    if (entity?.supportsTwoPointTargetTemperature) {
        espHomeClimateCommand(key: settings.climate as Long, targetTemperatureLow: temp as Float)
    } else {
        espHomeClimateCommand(key: settings.climate as Long, targetTemperature: temp as Float)
    }
}

public void setThermostatFanMode(String mode) {
    Integer espFanMode = HUBITAT_FAN_MAP[mode]
    if (espFanMode == null) {
        log.warn "${device} unsupported fan mode: ${mode}"
        return
    }
    if (logTextEnable) { log.info "${device} setting fan mode to ${mode}" }
    espHomeClimateCommand(key: settings.climate as Long, fanMode: espFanMode)
}

public void auto() { setThermostatMode('auto') }
public void cool() { setThermostatMode('cool') }
public void heat() { setThermostatMode('heat') }
public void off() { setThermostatMode('off') }
public void emergencyHeat() { setThermostatMode('heat') }
public void fanAuto() { setThermostatFanMode('low') }
public void fanCirculate() { setThermostatFanMode('medium') }
public void fanOn() { setThermostatFanMode('high') }

// =============================================================================
// ESPHome message parsing
// =============================================================================

// the parse method is invoked by the API library when messages are received
public void parse(Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'device':
            // Device information
            break

        case 'entity':
            if (message.platform == 'climate') {
                state.climates = (state.climates ?: [:]) + [ (message.key as String): message ]
                if (!settings.climate) {
                    device.updateSetting('climate', message.key as String)
                }

                // Auto-detect and publish supported thermostat modes
                List<String> modes = message.supportedModes?.collect { m ->
                    CLIMATE_MODE_MAP[m as Integer]
                }?.findAll()?.unique() ?: [ 'off' ]
                sendEvent(name: 'supportedThermostatModes', value: groovy.json.JsonOutput.toJson(modes))

                // Auto-detect and publish supported fan modes
                List<String> fanModes = message.supportedFanModes?.collect { m ->
                    CLIMATE_FAN_MAP[m as Integer]
                }?.findAll()?.unique() ?: []
                if (fanModes) {
                    state.supportedFanModes = fanModes
                    sendEvent(name: 'supportedThermostatFanModes', value: groovy.json.JsonOutput.toJson(fanModes))
                }
                return
            }

            if (message.platform == 'sensor') {
                switch (message.deviceClass) {
                    case 'signal_strength':
                        state['signalStrength'] = message.key
                        break
                }
                return
            }
            break

        case 'state':
            // Check if the entity key matches the climate entity selected
            if (settings.climate as Long == message.key && message.platform == 'climate') {
                Map entity = state.climates?.get(settings.climate as String)

                // Thermostat mode
                if (message.mode != null) {
                    String mode = CLIMATE_MODE_MAP[message.mode as Integer] ?: 'off'
                    if (device.currentValue('thermostatMode') != mode) {
                        String descriptionText = "${device} thermostat mode is ${mode}"
                        sendEvent(name: 'thermostatMode', value: mode, descriptionText: descriptionText)
                        if (logTextEnable) { log.info descriptionText }
                    }

                    // Track last running mode for auto mode setpoint handling
                    if (mode == 'heat' || mode == 'cool') {
                        device.updateDataValue('lastRunningMode', mode)
                    }
                }

                // Operating state (action)
                if (message.action != null) {
                    String operatingState = CLIMATE_ACTION_MAP[message.action as Integer] ?: 'idle'
                    if (device.currentValue('thermostatOperatingState') != operatingState) {
                        String descriptionText = "${device} operating state is ${operatingState}"
                        sendEvent(name: 'thermostatOperatingState', value: operatingState, descriptionText: descriptionText)
                        if (logTextEnable) { log.info descriptionText }
                    }
                }

                // Current temperature
                if (message.currentTemperature != null) {
                    BigDecimal temp = (message.currentTemperature as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
                    if (device.currentValue('temperature') != temp) {
                        String unit = '\u00B0' + getTemperatureScale()
                        String descriptionText = "${device} temperature is ${temp}${unit}"
                        sendEvent(name: 'temperature', value: temp, unit: getTemperatureScale(), descriptionText: descriptionText)
                        if (logTextEnable) { log.info descriptionText }
                    }
                }

                // Temperature setpoints
                if (entity?.supportsTwoPointTargetTemperature) {
                    // Two-point: separate heating and cooling setpoints
                    if (message.targetTemperatureLow != null) {
                        BigDecimal temp = (message.targetTemperatureLow as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
                        if (device.currentValue('heatingSetpoint') != temp) {
                            String descriptionText = "${device} heating setpoint is ${temp}"
                            sendEvent(name: 'heatingSetpoint', value: temp, unit: getTemperatureScale(), descriptionText: descriptionText)
                            if (logTextEnable) { log.info descriptionText }
                        }
                    }
                    if (message.targetTemperatureHigh != null) {
                        BigDecimal temp = (message.targetTemperatureHigh as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
                        if (device.currentValue('coolingSetpoint') != temp) {
                            String descriptionText = "${device} cooling setpoint is ${temp}"
                            sendEvent(name: 'coolingSetpoint', value: temp, unit: getTemperatureScale(), descriptionText: descriptionText)
                            if (logTextEnable) { log.info descriptionText }
                        }
                    }
                    // Active setpoint reflects heating or cooling based on current mode
                    String currentMode = device.currentValue('thermostatMode')
                    BigDecimal activeSetpoint
                    if (currentMode == 'cool' && message.targetTemperatureHigh != null) {
                        activeSetpoint = (message.targetTemperatureHigh as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
                    } else if (message.targetTemperatureLow != null) {
                        activeSetpoint = (message.targetTemperatureLow as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
                    } else {
                        activeSetpoint = device.currentValue('thermostatSetpoint')
                    }
                    sendEvent(name: 'thermostatSetpoint', value: activeSetpoint, unit: getTemperatureScale())
                } else if (message.targetTemperature != null) {
                    // Single-point: one setpoint for all modes
                    BigDecimal temp = (message.targetTemperature as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)
                    sendEvent(name: 'thermostatSetpoint', value: temp, unit: getTemperatureScale())

                    String mode = CLIMATE_MODE_MAP[message.mode as Integer] ?: device.currentValue('thermostatMode')
                    if (mode == 'cool') {
                        if (device.currentValue('coolingSetpoint') != temp) {
                            String descriptionText = "${device} cooling setpoint is ${temp}"
                            sendEvent(name: 'coolingSetpoint', value: temp, unit: getTemperatureScale(), descriptionText: descriptionText)
                            if (logTextEnable) { log.info descriptionText }
                        }
                    } else if (mode == 'heat' || mode == 'auto') {
                        if (device.currentValue('heatingSetpoint') != temp) {
                            String descriptionText = "${device} heating setpoint is ${temp}"
                            sendEvent(name: 'heatingSetpoint', value: temp, unit: getTemperatureScale(), descriptionText: descriptionText)
                            if (logTextEnable) { log.info descriptionText }
                        }
                    }
                }

                // Fan mode
                if (message.fanMode != null) {
                    String fanMode = CLIMATE_FAN_MAP[message.fanMode as Integer] ?: 'on'
                    if (device.currentValue('thermostatFanMode') != fanMode) {
                        String descriptionText = "${device} fan mode is ${fanMode}"
                        sendEvent(name: 'thermostatFanMode', value: fanMode, descriptionText: descriptionText)
                        if (logTextEnable) { log.info descriptionText }
                    }
                }
                return
            }

            // Signal Strength
            if (state.signalStrength as Long == message.key && message.hasState) {
                Integer rssi = Math.round(message.state as Float)
                String unit = 'dBm'
                if (device.currentValue('rssi') != rssi) {
                    String descriptionText = "${device} rssi is ${rssi}"
                    sendEvent(name: 'rssi', value: rssi, unit: unit, descriptionText: descriptionText)
                    if (logTextEnable) { log.info descriptionText }
                }
                return
            }
            break
    }
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper
