/*

Copyright 2025 - VH

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

-------------------------------------------
 * Tholz SmartConnect (TCP) - Driver básico para Hubitat
 * Autor: VH / TRATO
 * Descrição:
 *  - Conecta por rawSocket a um controlador Tholz compatível (porta 4000)
 *  - Envia getDevice (leitura) e setDevice (escrita)
 *  - Cria child devices para saídas (Filtro/Aux/etc.) e Luzes (led0 on/off)
 *  - Atualiza estados dos childs a partir do JSON de resposta
 *
 *   1.0.0 - 01/10/2025 - Initial release.
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field Map PRESET_COLORS = [
    "RED":[252, 42, 0],
    "GREEN":[4, 252, 0],
    "BLUE":[0, 92, 252],
    "YELLOW":[252, 252, 0],
    "PINK":[248, 0, 252],
    "ORANGE":[242, 166, 22],
    "WHITE":[255, 255, 255],
]

metadata {
    definition(name: "Tholz SmartConnect (TCP)", namespace: "TRATO", author: "VH") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "Switch"
        capability "PushableButton"
        capability "Actuator"
        capability "ColorControl"   // <-- habilita tile nativo de cor no Dashboard
		capability "SwitchLevel"     // <— para o tile chamar setLevel(level)        

        // Comandos utilitários
        command "reconnect"
        command "getDevice"
        // Comandos rápidos para filtro/aux (por conveniência)
        command "filterOn"
        command "filterOff"
        command "aux1On"
        command "aux1Off"
        command "aux2On"
        command "aux2Off"
        // Luz simples (led0) - on/off
        command "lightsOn"
        command "lightsOff"
		command "allOn"
		command "allOff"
        
        // --- Heatings (comandos) ---
        command "heatOn"
        command "heatOff"
        command "setHeatMode", [[name:"mode*", type:"ENUM", constraints:["Off","Ligado","Automático","Econômico","Aquecer","Resfriar"]]]
        command "setHeatSetpoint", [[name:"sp(°C)*", type:"NUMBER"]]
        command "bumpHeatSetpoint", [[name:"+/-Δ(°C)*", type:"NUMBER"]]
        command "heatUp1C"
        command "heatDown1C"
        command "toggleHeatMode"

        // --- Luzes (comandos existentes + novos) ---
        command "setLightBrightness", [[name:"brightness(0-100)*", type:"NUMBER"]]
        command "chooseLightColor", [[name:"color*", type:"ENUM", constraints:["RED","GREEN","BLUE","YELLOW","PINK","ORANGE","WHITE"]]]
        command "setLightRGB", [
            [name:"R*", type:"NUMBER"],
            [name:"G*", type:"NUMBER"],
            [name:"B*", type:"NUMBER"]
        ]

        // --- Atributos de status no PAI ---
        attribute "lights", "string"         // "on" / "off"
        attribute "filter", "string"         // "on" / "off"
        attribute "aux1", "string"           // "on" / "off"
        attribute "aux2", "string"           // "on" / "off"
        attribute "aux3", "string"           // "on" / "off"
        // --- Heatings (atributos no PAI) ---
        attribute "heat0Mode", "string"
        attribute "heat0On", "string"
        attribute "heat0Setpoint", "number"
        attribute "heat0T1", "number"
        attribute "heat0T2", "number"
        attribute "heat0Temp", "number"

        attribute "temperature", "number"

        // --- Luzes (atributos extras p/ ColorControl) ---
        attribute "lightBrightness", "number"   // 0..100
        attribute "lightColorRGB", "string"     // "R,G,B"
        // ColorControl padrão
        attribute "hue", "number"               // 0..100
        attribute "saturation", "number"        // 0..100
        attribute "level", "number"             // 0..100
        attribute "color", "string"             // "#RRGGBB"
    }

    preferences {
        input name: "device_IP_address", type: "string", title: "IP do dispositivo Tholz", required: true
        input name: "device_port", type: "number", title: "Porta TCP", defaultValue: 4000, range: "1..65535", required: true
        input name: "autoRefreshSecs", type: "number", title: "Atualizar a cada (s)", defaultValue: 15, range: "5..3600"
        input name: "logEnable", type: "bool", title: "Habilitar debug logging", defaultValue: true
    }
}

def installed() {
    logInfo "Installed"
    sendEvent(name:"numberOfButtons", value:20)
}

def updated() {
    logInfo "Updated"
    sendEvent(name:"numberOfButtons", value:20)
    unschedule()
    state.remove("rxBuf")
    initialize()
}

def initialize() {
    connectSocket()
    scheduleRefresh()
}

def scheduleRefresh() {
    Integer s = (settings?.autoRefreshSecs ?: 15) as Integer
    if (s < 5) s = 5
    runIn(2, "refresh")
    schedule("*/${s} * * * * ?", "refresh")
}

def reconnect() {
    logWarn "Reconnecting by user request..."
    interfaces?.rawSocket?.close()
    runIn(2, "connectSocket")
}

def on()  { setLights(true)  }   // apenas luzes
def off() { setLights(false) }   // apenas luzes

def allOn()  { lightsOn(); filterOn(); aux1On(); aux2On() }
def allOff() { lightsOff(); filterOn(); aux1Off(); aux2Off() }


private void connectSocket() {
  try {
    logInfo "Conectando em ${device_IP_address}:${device_port} ..."
    interfaces.rawSocket.connect(device_IP_address, (int) device_port)
    state.lastRxAt = now()
  } catch (e) {
    logWarn "Falha ao conectar: ${e.message}"
    runIn(10, "connectSocket")
  }
}

def uninstalled() {
    try { interfaces?.rawSocket?.close() } catch (ex) {}
}

def refresh() {
    getDevice()
}

def getDevice() {
    sendJson([command: "getDevice"])
}

// ======== Commands de atalho (outputs) ========
def filterOn()  { setOutputById(0, true)  }
def filterOff() { setOutputById(0, false) }
def aux1On()    { setOutputById(10, true) }
def aux1Off()   { setOutputById(10, false)}
def aux2On()    { setOutputById(11, true) }
def aux2Off()   { setOutputById(11, false)}

def lightsOn()  { setLights(true)  }
def lightsOff() { setLights(false) }

def push(pushed) {
    if (pushed == null) return
    logInfo "Push ${pushed}"
    sendEvent(name:"pushed", value: pushed, isStateChange: true)

    switch (pushed) {
        case "1":  lightsOn();                  break
        case "2":  lightsOff();                 break
        case "3":  heatOn();                    break
        case "4":  heatOff();                   break
        case "5":  heatUp1C();                  break
        case "6":  heatDown1C();                break
        case "7":  filterOn();                  break
        case "8":  filterOff();                 break
        case "9":  aux1On();                    break
        case "10": aux1Off();                   break
        case "11": aux2On();                    break
        case "12": aux2Off();                   break
        case "13": setHeatMode("Automático");   break
        case "14": setHeatMode("Ligado");       break
        case "15": setHeatMode("Off");          break
        case "16": bumpHeatSetpoint(0.5);       break
        case "17": bumpHeatSetpoint(-0.5);      break
        case "18": toggleHeatMode();            break
        case "19": setOutputById(12, true);     break
        case "20": setOutputById(12, false);    break
        default:
            logWarn "Push ${pushed}: botão não mapeado"
    }
}

// === Novo comando toggleHeatMode ===
def toggleHeatMode() {
    String cur = device.currentValue("heat0Mode") ?: "Off"
    String next = cur.equalsIgnoreCase("Automático") ? "Ligado" : "Automático"
    logInfo "toggleHeatMode: alternando de ${cur} para ${next}"
    setHeatMode(next)
}

// ======== Luzes: comandos existentes ========

/* def setLightBrightness(Number level) {
    if (level == null) return
    Integer v = Math.max(0, Math.min(100, (level as Integer)))
    sendJson([command:"setDevice", argument:[leds:[led0:[brightness:v]]]])
    // Otimista + eventos ColorControl
    sendEvent(name:"lightBrightness", value: v)
    sendEvent(name:"level", value: v)
	sendEvent(name:"switch", value:"on")
    pubParentAttr("lights", true)    
    runIn(1, "getDevice")
}
*/

def setLightBrightness(Number level) {
    if (level == null) return
    Integer v = Math.max(0, Math.min(100, (level as Integer)))

    // Se brilho zero, trata como OFF; senão, ON.
    boolean willOn = (v > 0)

    sendJson([command:"setDevice", argument:[leds:[led0:[on:willOn, brightness:v]]]])

    // Eventos otimistas para o tile:
    sendEvent(name:"lightBrightness", value: v)
    sendEvent(name:"level", value: v)
    sendEvent(name:"switch", value: willOn ? "on" : "off")
    pubParentAttr("lights", willOn)

    runIn(1, "getDevice")
}


def chooseLightColor(String colorName) {
    String key = (colorName ?: "").trim().toUpperCase()
    List<Integer> rgb = PRESET_COLORS[key]
    if (!rgb) { logWarn "chooseLightColor: cor inválida '${colorName}'"; return }
    setLightRGB(rgb[0], rgb[1], rgb[2])
}

def setLightRGB(Number r, Number g, Number b) {
    Integer R = clip255(r), G = clip255(g), B = clip255(b)
    sendJson([command:"setDevice", argument:[leds:[led0:[on:true, color:[R,G,B], effect:255]]]])
    pubParentAttr("lights", true)
    sendEvent(name:"lightColorRGB", value: "${R},${G},${B}")
    // Atualiza atributos ColorControl de forma otimista usando level atual (ou 100)
    Integer lvl = (device.currentValue("lightBrightness") ?: device.currentValue("level") ?: 100) as Integer
    Map hsv = rgbToHsv(R, G, B, lvl)
    sendEvent(name:"hue", value: hsv.h)
    sendEvent(name:"saturation", value: hsv.s)
    sendEvent(name:"switch", value:"on")   // <— adiciona isto    
    sendEvent(name:"color", value: rgbToHex(R,G,B))
    if (lvl != null) sendEvent(name:"level", value: Math.max(0, Math.min(100, lvl)))
    runIn(1, "getDevice")
}

private Integer clip255(def v) {
    Integer x = (v == null) ? 0 : (v as Integer)
    return Math.max(0, Math.min(255, x))
}

// ======== ColorControl (NOVO) ========

def setColor(Map colorMap) {
    if (!colorMap) return
    BigDecimal h = (colorMap.hue ?: device.currentValue("hue") ?: 0) as BigDecimal   // 0..100
    BigDecimal s = (colorMap.saturation ?: device.currentValue("saturation") ?: 0) as BigDecimal // 0..100
    Integer    l = (colorMap.level ?: device.currentValue("level") ?: device.currentValue("lightBrightness") ?: 100) as Integer // 0..100

    Map rgb = hsvToRgb(h, s, l)
    // Define cor (mantém brightness coerente)
    setLightRGB(rgb.r, rgb.g, rgb.b)
    if (colorMap.containsKey("level")) {
        setLightBrightness(l)
    } else {
        // garantir eventos coerentes
        sendEvent(name:"hue", value: (h as BigDecimal))
        sendEvent(name:"saturation", value: (s as BigDecimal))
        sendEvent(name:"level", value: l)
        sendEvent(name:"color", value: rgbToHex(rgb.r, rgb.g, rgb.b))
    }
}

def setHue(Number h) {
    BigDecimal H = (h ?: 0) as BigDecimal
    BigDecimal S = (device.currentValue("saturation") ?: 100) as BigDecimal
    Integer   L = (device.currentValue("level") ?: device.currentValue("lightBrightness") ?: 100) as Integer
    Map rgb = hsvToRgb(H, S, L)
    setLightRGB(rgb.r, rgb.g, rgb.b)
    // manter brilho atual
    sendEvent(name:"hue", value: H)
    sendEvent(name:"saturation", value: S)
    sendEvent(name:"level", value: L)
    sendEvent(name:"color", value: rgbToHex(rgb.r, rgb.g, rgb.b))
}

def setSaturation(Number s) {
    BigDecimal S = (s ?: 0) as BigDecimal
    BigDecimal H = (device.currentValue("hue") ?: 0) as BigDecimal
    Integer   L = (device.currentValue("level") ?: device.currentValue("lightBrightness") ?: 100) as Integer
    Map rgb = hsvToRgb(H, S, L)
    setLightRGB(rgb.r, rgb.g, rgb.b)
    sendEvent(name:"hue", value: H)
    sendEvent(name:"saturation", value: S)
    sendEvent(name:"level", value: L)
    sendEvent(name:"color", value: rgbToHex(rgb.r, rgb.g, rgb.b))
}

def setLevel(Number level) {
    // brightness do ColorControl
    setLightBrightness(level)
}

// ======== Escrita ========

private void setOutputById(Integer targetId, Boolean onVal) {
    Map last = state?.lastDevice ?: [:]
    Map outs = (last?.outputs ?: [:]) as Map
    if (!outs) {
        logInfo "Outputs ainda não descobertos; pedindo getDevice antes de setOutputById(${targetId})."
        getDevice()
    }
    Map argOutputs = [:]
    outs?.each { k, v ->
        if (v instanceof Map && v.id == targetId) {
            argOutputs[k as String] = [on: onVal]
        }
    }
    if (argOutputs.isEmpty()) {
        String guessKey = guessOutputKeyForId(targetId, outs)
        if (guessKey) {
            argOutputs[guessKey] = [on: onVal]
        }
    }
    if (argOutputs.isEmpty()) {
        logWarn "Não foi possível mapear a saída com id=${targetId}. Execute refresh e tente novamente."
        return
    }
    sendJson([command: "setDevice", argument: [outputs: argOutputs]])

    if (targetId == 0)  pubParentAttr("filter", onVal)
    if (targetId == 10) pubParentAttr("aux1",   onVal)
    if (targetId == 11) pubParentAttr("aux2",   onVal)
    if (targetId == 12) pubParentAttr("aux3",   onVal)

    runIn(1, "getDevice")
}

private String guessOutputKeyForId(Integer idVal, Map outs) {
    String key = outs?.find { it?.value instanceof Map && it?.value?.id == idVal }?.key
    if (key) return key as String
    if (idVal == 0)  return "out0"
    if (idVal == 10) return "out1"
    if (idVal == 11) return "out2"
    return null
}

private void setLights(Boolean onVal) {
    sendJson([command: "setDevice", argument: [leds: [ led0: [ on: onVal ] ] ]])
    pubParentAttr("lights", onVal)
	sendEvent(name:"switch", value: onVal ? "on" : "off")
    
    runIn(1, "getDevice")
}

// ======== Envio baixo nível ========

private void sendJson(Map obj) {
    String js = JsonOutput.toJson(obj)
    String payload = js + "\n"
    if (logEnable) log.debug "TX: ${js}"
    try {
        interfaces.rawSocket.sendMessage(payload)
    } catch (e) {
        logWarn "Falha no envio (${e}); tentando reconectar..."
        reconnect()
    }
}

// ======== Socket callbacks ========

def parse(String description) {
    if (logEnable) log.debug "RX (String): ${description}"

    String chunk
    if (looksLikeHex(description)) {
        try {
            chunk = hexToUtf8(description)
        } catch (e) {
            log.warn "Falha ao decodificar HEX -> UTF8: ${e}"
            return
        }
    } else {
        chunk = description
    }

    state.rxBuf = (state.rxBuf ?: "") + (chunk ?: "")
    processBuffer()
}

// void parse(List<Map> description) { ... }  // (mantido, se existir no seu base)

private boolean looksLikeHex(String s) {
    if (!s) return false
    return (s ==~ /[0-9A-Fa-f]+/) && (s.length() % 2 == 0)
}

private String hexToUtf8(String hex) {
    byte[] bytes = new byte[hex.length() / 2]
    for (int i = 0; i < hex.length(); i += 2) {
        bytes[i/2] = (byte) Integer.parseInt(hex.substring(i, i+2), 16)
    }
    return new String(bytes, "UTF-8")
}

private void processBuffer() {
    String buf = state.rxBuf ?: ""
    if (!buf) return

    int level = 0
    int start = -1
    List<String> frames = []
    for (int i=0; i<buf.length(); i++) {
        char c = buf.charAt(i)
        if (c == '{') {
            if (level == 0) start = i
            level++
        } else if (c == '}') {
            level--
            if (level == 0 && start >= 0) {
                frames << buf.substring(start, i+1)
                start = -1
            }
        }
    }
    if (level == 0) {
        state.rxBuf = ""
    } else {
        state.rxBuf = (start >= 0 ? buf.substring(start) : buf)
    }

    frames.each { f -> handleJsonFrame(f) }
}

private void handleJsonFrame(String jsonText) {
    if (logEnable) log.debug "JSON frame: ${jsonText}"
    def slurper = new JsonSlurper()
    Map obj
    try {
        obj = (Map) slurper.parseText(jsonText)
    } catch (e) {
        logWarn "Falha ao parsear JSON: ${e}"
        return
    }
    if (!obj) return

    if (obj.command && obj.response instanceof Map) {
        Map resp = (Map) obj.response
        state.lastDevice = resp
        updateFromResponse(resp)
        return
    }

    if (obj.id || obj.outputs || obj.leds || obj.heatings) {
        state.lastDevice = obj
        updateFromResponse(obj)
        return
    }
}

private void updateFromResponse(Map resp) {
    // --------- Children ----------
    Map outs = (resp.outputs ?: [:]) as Map
    outs.each { key, val ->
        if (!(val instanceof Map)) return
        Integer idVal = (val.id ?: 0) as Integer
        Boolean isOn = (val.on == true)

        String dni = "${device.id}-${key}"
        def cd = getChildDevice(dni)
        if (!cd) {
            String nice = nameForOutputId(idVal) ?: key
            cd = addChildDevice("hubitat", "Generic Component Switch", dni,
                    [label: "${device.displayName} - ${nice}", isComponent: true, componentName: key, componentLabel: nice])
            cd.updateSetting("txtEnable",[value:"true", type:"bool"])
            cd.updateDataValue("outputKey", key)
            cd.updateDataValue("outputId", "${idVal}")
        }
        String ev = isOn ? "on" : "off"
        componentUpdate(cd, ev)    
        
    }

    Map leds = (resp.leds ?: [:]) as Map
    Map led0 = leds?.led0 as Map
    if (led0) {
        String dni = "${device.id}-led0"
        def cd = getChildDevice(dni)
        if (!cd) {
            cd = addChildDevice("hubitat", "Generic Component Switch", dni,
                [label: "${device.displayName} - Luzes", isComponent: true, componentName: "led0", componentLabel: "Luzes"])
            cd.updateSetting("txtEnable",[value:"true", type:"bool"])
            cd.updateDataValue("ledKey","led0")
        }
        String ev = (led0.on == true) ? "on" : "off"
        componentUpdate(cd, ev)
		sendEvent(name:"switch", value: ev)   // <— garante que o tile saia de “sending”

        // --- Feedback no device pai (luzes) ---
        if (led0.containsKey('on')) {
            pubParentAttr("lights", (led0.on == true))
        }
        if (led0.containsKey('brightness')) {
            Integer br = (led0.brightness as Integer)
            sendEvent(name:"lightBrightness", value: br)
            sendEvent(name:"level", value: br)
        }
        if (led0.containsKey('color') && led0.color instanceof List && led0.color.size() >= 3) {
            Integer R = (led0.color[0] as Integer)
            Integer G = (led0.color[1] as Integer)
            Integer B = (led0.color[2] as Integer)
            sendEvent(name:"lightColorRGB", value: "${R},${G},${B}")
            sendEvent(name:"color", value: rgbToHex(R,G,B))
            // Se não veio brightness, usa o atual para calcular HSV coerente
            Integer lvl = (led0.brightness != null) ? (led0.brightness as Integer) : ((device.currentValue("level") ?: 100) as Integer)
            Map hsv = rgbToHsv(R, G, B, lvl)
            sendEvent(name:"hue", value: hsv.h)
            sendEvent(name:"saturation", value: hsv.s)
            if (lvl != null) sendEvent(name:"level", value: Math.max(0, Math.min(100, lvl)))
        }
    }

    // --------- Filtro/Aux no pai ----------
    Boolean fOn = null; Boolean a1On = null; Boolean a2On = null; Boolean a3On = null
    outs?.each { k, v ->
        if (!(v instanceof Map)) return
        Integer idVal = (v.id ?: -1) as Integer
        Boolean isOn = (v.on == true)
        switch (idVal) {
            case 0:  fOn  = isOn; break
            case 10: a1On = isOn; break
            case 11: a2On = isOn; break
            case 12: a3On = isOn; break
        }
    }
    if (fOn  != null) pubParentAttr("filter", fOn)
    if (a1On != null) pubParentAttr("aux1",   a1On)
    if (a2On != null) pubParentAttr("aux2",   a2On)
    if (a3On != null) pubParentAttr("aux3",   a3On)

    // --------- Heatings (feedback) ----------
    Map heats = (resp.heatings ?: [:]) as Map
    Map heat0 = heats?.heat0 as Map
    if (heat0) { publishHeat0(heat0) }
}

private String nameForOutputId(Integer idVal) {
    switch (idVal) {
        case 0: return "Filtro"
        case 1: return "Apoio Elétrico"
        case 2: return "Apoio a Gás"
        case 3: return "Recirculação"
        case 4: return "Borbulhador"
        case 5: return "Circulação"
    }
    if (idVal >= 10 && idVal <= 19) return "Auxiliar ${(idVal-9)}"
    if (idVal >= 20 && idVal <= 29) return "Cascata ${(idVal-19)}"
    if (idVal >= 30 && idVal <= 39) return "Hidro ${(idVal-29)}"
    if (idVal >= 40 && idVal <= 59) return "Interruptor ${(idVal-39)}"
    return null
}

// ======== Component handlers (child -> parent) ========

def componentOn(cd) {
    String outKey = cd.getDataValue("outputKey")
    String ledKey = cd.getDataValue("ledKey")
    if (outKey) {
        sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:true]]]])
        Integer idVal = cd.getDataValue("outputId")?.toInteger()
        if (idVal != null) {
            if (idVal == 0)  pubParentAttr("filter", true)
            if (idVal == 10) pubParentAttr("aux1",   true)
            if (idVal == 11) pubParentAttr("aux2",   true)
            if (idVal == 12) pubParentAttr("aux3",   true)
        }
        runIn(1, "getDevice")
        return
    }
    if (ledKey) {
        sendJson([command:"setDevice", argument:[leds:[(ledKey):[on:true]]]])
        pubParentAttr("lights", true)
        sendEvent(name:"switch", value:"on")       // <—        
        runIn(1, "getDevice")
        return
    }
}

def componentOff(cd) {
    String outKey = cd.getDataValue("outputKey")
    String ledKey = cd.getDataValue("ledKey")
    if (outKey) {
        sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:false]]]])
        Integer idVal = cd.getDataValue("outputId")?.toInteger()
        if (idVal != null) {
            if (idVal == 0)  pubParentAttr("filter", false)
            if (idVal == 10) pubParentAttr("aux1",   false)
            if (idVal == 11) pubParentAttr("aux2",   false)
            if (idVal == 12) pubParentAttr("aux3",   false)
        }
        runIn(1, "getDevice")
        return
    }
    if (ledKey) {
        sendJson([command:"setDevice", argument:[leds:[(ledKey):[on:false]]]])
        pubParentAttr("lights", false)
        sendEvent(name:"switch", value:"off")      // <—        
        runIn(1, "getDevice")
        return
    }
}

private void componentUpdate(cd, String ev) {
    try {
        cd.parse([[name:"switch", value:ev, descriptionText:"${cd.displayName} was turned ${ev}"]])
    } catch (ignored) {
        if (ev == "on") cd.sendEvent(name:"switch", value:"on") else cd.sendEvent(name:"switch", value:"off")
    }
}

def componentRefresh(cd) {
    if (logEnable) log.debug "Child pediu refresh: ${cd?.displayName}"
    getDevice()
}

def componentPing(cd) {
    if (logEnable) log.debug "Child ping: ${cd?.displayName}"
    refresh()
}

// ======== Heatings - comandos ========

def heatOn()  { setHeatingOnOff(true)  }
def heatOff() { setHeatingOnOff(false) }

private void setHeatingOnOff(boolean onVal) {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    Integer typeVal = (heat0?.type   instanceof Number) ? (heat0.type   as Integer) : null
    Integer curMode = (heat0?.opMode instanceof Number) ? (heat0.opMode as Integer) : null

    Integer newMode
    if (!onVal) {
        newMode = 0
    } else {
        newMode = (curMode == null || curMode == 0) ? 1 : curMode
    }

    sendJson([command:"setDevice", argument:[heatings:[heat0:[on:onVal, opMode:newMode]]]])
    sendEvent(name:"heat0On",   value: onVal ? "on" : "off")
    if (newMode != null) sendEvent(name:"heat0Mode", value: opModeLabel(newMode, typeVal))
    runIn(1, "getDevice")
}

def setHeatMode(String mode) {
    Integer idx = opModeIndexFromLabel(mode)
    if (idx == null) { log.warn "setHeatMode: modo inválido '${mode}'"; return }
    boolean willOn = (idx != 0)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[opMode:idx, on:willOn]]]])
    sendEvent(name:"heat0Mode", value: opModeLabel(idx, null))
    sendEvent(name:"heat0On",   value: willOn ? "on" : "off")
    runIn(1, "getDevice")
}

def setHeatSetpoint(Number spC) {
    if (spC == null) return
    Integer raw = rawFromC(spC)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[sp:raw]]]])
    sendEvent(name:"heat0Setpoint", value: (spC as BigDecimal), unit:"°C")
    runIn(1, "getDevice")
}

def bumpHeatSetpoint(Number deltaC) {
    Map last = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    BigDecimal cur = cFromRaw(heat0?.sp) ?: 0.0G
    BigDecimal min = cFromRaw(heat0?.minSp)
    BigDecimal max = cFromRaw(heat0?.maxSp)

    BigDecimal target = (cur + (deltaC as BigDecimal))
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()
    setHeatSetpoint(target)
}

def heatUp1C() {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    BigDecimal cur = cFromRaw(heat0?.sp) ?: 0.0G
    BigDecimal min = cFromRaw(heat0?.minSp)
    BigDecimal max = cFromRaw(heat0?.maxSp)

    BigDecimal target = cur + 1.0G
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()
    setHeatSetpoint(target)
}

def heatDown1C() {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    BigDecimal cur = cFromRaw(heat0?.sp) ?: 0.0G
    BigDecimal min = cFromRaw(heat0?.minSp)
    BigDecimal max = cFromRaw(heat0?.maxSp)

    BigDecimal target = cur - 1.0G
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()
    setHeatSetpoint(target)
}

// ======== Socket status ========

void socketStatus(String message) {
    logWarn "socketStatus: ${message}"
    runIn(5, "connectSocket")
}

// ======== Logging helpers ========

private void logInfo(msg){ log.info "Tholz TCP: ${msg}" }
private void logWarn(msg){ log.warn "Tholz TCP: ${msg}" }

// ======== helpers ========

private void pubParentAttr(String name, boolean onVal) {
    String v = onVal ? "on" : "off"
    sendEvent(name: name, value: v, descriptionText: "${device.displayName} ${name} is ${v}")
}

// ======== Heatings helpers ========

private BigDecimal cFromRaw(def v) {
    if (v == null) return null
    if ((v instanceof Number) && v.intValue() == 0xAAAA) return null
    try { return (v as BigDecimal) / 10.0G } catch (ignored) { return null }
}

private Integer rawFromC(def c) {
    if (c == null) return null
    BigDecimal val = (c as BigDecimal)
    return Math.round(val * 10.0G) as Integer
}

@Field Map OP_MODE_LABELS_DEFAULT = [
    0:"Off", 1:"Ligado", 2:"Automático", 3:"Econômico", 4:"Aquecer", 5:"Resfriar"
]

private String opModeLabel(Integer opMode, Integer typeVal) {
    return OP_MODE_LABELS_DEFAULT.get(opMode, "Modo ${opMode}")
}

private Integer opModeIndexFromLabel(String label) {
    String x = (label ?: "").trim().toLowerCase()
    switch (x) {
        case "off": case "desligado": return 0
        case "on": case "ligado": return 1
        case "auto": case "automático": case "automatico": return 2
        case "econômico": case "economico": return 3
        case "aquecer": case "heat": return 4
        case "resfriar": case "cool": return 5
    }
    try { return Integer.parseInt(x) } catch (ignored) { return null }
}

// ======== Conversões HSV/RGB (para ColorControl) ========

private Map hsvToRgb(BigDecimal hue, BigDecimal sat, Integer lvl) {
    // Hubitat: hue/sat 0..100, level 0..100
    float H = ((hue ?: 0) as BigDecimal).floatValue() * 3.6f              // 0..360
    float S = Math.max(0f, Math.min(1f, ((sat ?: 0) as BigDecimal).floatValue() / 100f))
    float V = Math.max(0f, Math.min(1f, ((lvl ?: 0) as Integer) / 100f))

    float C = V * S
    float X = C * (1 - Math.abs((H / 60f) % 2 - 1))
    float m = V - C

    float r1=0, g1=0, b1=0
    if (H < 60)      { r1=C; g1=X; b1=0 }
    else if (H <120){ r1=X; g1=C; b1=0 }
    else if (H <180){ r1=0; g1=C; b1=X }
    else if (H <240){ r1=0; g1=X; b1=C }
    else if (H <300){ r1=X; g1=0; b1=C }
    else            { r1=C; g1=0; b1=X }

    int R = Math.round((r1 + m) * 255)
    int G = Math.round((g1 + m) * 255)
    int B = Math.round((b1 + m) * 255)
    return [r:R, g:G, b:B]
}

private Map rgbToHsv(Integer R, Integer G, Integer B, Integer lvlOverride=null) {
    float r = (R ?: 0) / 255f
    float g = (G ?: 0) / 255f
    float b = (B ?: 0) / 255f
    float max = Math.max(r, Math.max(g,b))
    float min = Math.min(r, Math.min(g,b))
    float d = max - min

    float H
    if (d == 0) H = 0
    else if (max == r) H = 60 * (((g - b) / d) % 6)
    else if (max == g) H = 60 * (((b - r) / d) + 2)
    else               H = 60 * (((r - g) / d) + 4)
    if (H < 0) H += 360

    float S = (max == 0) ? 0 : d / max
    // V derivado do brilho (podemos preferir brightness do dispositivo)
    Integer L = (lvlOverride != null) ? lvlOverride : Math.round(max * 100)

    Integer h = Math.round(H / 3.6f)        // 0..100
    Integer s = Math.round(S * 100)         // 0..100
    return [h:h, s:s, l:L]
}

private String rgbToHex(int r, int g, int b) {
    return String.format("#%02X%02X%02X", (r&0xFF), (g&0xFF), (b&0xFF))
}

// Publica atributos do heat0
private void publishHeat0(Map h) {
    if (!(h instanceof Map)) return
    Integer typeVal = (h.type instanceof Number) ? (h.type as Integer) : null
    Integer op      = (h.opMode instanceof Number) ? (h.opMode as Integer) : null
    Boolean on      = (h.on == true)
    BigDecimal sp   = cFromRaw(h.sp)
    BigDecimal t1   = cFromRaw(h.t1)
    BigDecimal t2   = cFromRaw(h.t2)

    BigDecimal tMain = null
    int maxIdx = -1
    h.each { k,v ->
        def m = (k instanceof String) ? (k =~ /^t(\d+)$/) : null
        if (m && m.matches()) {
            int idx = Integer.parseInt(m[0][1])
            BigDecimal tv = cFromRaw(v)
            if (tv != null && idx > maxIdx) {
                maxIdx = idx
                tMain = tv
            }
        }
    }
    if (tMain!= null) sendEvent(name:"temperature", value: tMain, unit:"°C")

    if (op != null) sendEvent(name:"heat0Mode", value: opModeLabel(op, typeVal))
    sendEvent(name:"heat0On", value: on ? "on" : "off")
    if (sp   != null) sendEvent(name:"heat0Setpoint", value: sp, unit: "°C")
    if (t1   != null) sendEvent(name:"heat0T1", value: t1, unit: "°C")
    if (t2   != null) sendEvent(name:"heat0T2", value: t2, unit: "°C")
    if (tMain!= null) sendEvent(name:"heat0Temp", value: tMain, unit: "°C")
}
