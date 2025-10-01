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


metadata {
    definition(name: "Tholz SmartConnect (TCP)", namespace: "TRATO", author: "VH") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "Switch"
        capability "PushableButton"
        capability "Actuator"        
        
        

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

        // --- Heatings (comandos) ---
        command "heatOn"
        command "heatOff"
        command "setHeatMode", [[name:"mode*", type:"ENUM", constraints:["Off","Ligado","Automático","Econômico","Aquecer","Resfriar"]]]
        command "setHeatSetpoint", [[name:"sp(°C)*", type:"NUMBER"]]
        command "bumpHeatSetpoint", [[name:"+/-Δ(°C)*", type:"NUMBER"]]

		command "heatUp1C"
        command "heatDown1C"        

        
        // --- Atributos de status no PAI ---
        attribute "lights", "string"   // "on" / "off"
        attribute "filter", "string"   // "on" / "off"
        attribute "aux1", "string"     // "on" / "off"
        attribute "aux2", "string"     // "on" / "off"
        attribute "aux3", "string"     // "on" / "off"
        // --- Heatings (atributos no PAI) ---
        attribute "heat0Mode", "string"      // Off/Ligado/Auto/… (texto amigável)
        attribute "heat0On", "string"        // "on"/"off"
        attribute "heat0Setpoint", "number"  // °C
        attribute "heat0T1", "number"        // °C (se houver)
        attribute "heat0T2", "number"        // °C (se houver)
        attribute "heat0Temp", "number"      // °C (sensor principal do heat0)

        attribute "temperature", "number"
		command "toggleHeatMode"
        
        

        
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

def on() {
lightsOn()    
filterOn()    
aux1On()
aux2On()    
}

def off() {
lightsOff()
filterOn()    
aux1Off()    
aux2Off()    

}
private void connectSocket() {


  try {
    logInfo "Conectando em ${device_IP_address}:${device_port} ..."
        interfaces.rawSocket.connect(device_IP_address, (int) device_port)
      state.lastRxAt = now()
    //setBoardStatus("online")

  } catch (e) {
    logWarn "Falha ao conectar: ${e.message}"
    //setBoardStatus("offline")
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
// Por padrão, o exemplo da doc usa out0..outN com campo "on"
// Mapeamos: Filtro(id=0) -> normalmente out0; Aux1(id=10), Aux2(id=11)
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

    // Registra evento do botão (boa prática p/ dashboards & RM)
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
        case "16": bumpHeatSetpoint(0.5);       break  // ajuste fino opcional (+0,5 °C)
        case "17": bumpHeatSetpoint(-0.5);      break  // ajuste fino opcional (-0,5 °C)
        case "18": toggleHeatMode();            break
        case "19": setOutputById(12, true);     break  // Aux3 ON (id 12)
        case "20": setOutputById(12, false);    break  // Aux3 OFF
        default:
            logWarn "Push ${pushed}: botão não mapeado"
    }
}

// === Novo comando toggleHeatMode ===
def toggleHeatMode() {
    // Pega o último estado conhecido
    String cur = device.currentValue("heat0Mode") ?: "Off"

    String next
    if (cur.equalsIgnoreCase("Automático")) {
        next = "Ligado"
    } else {
        next = "Automático"
    }

    logInfo "toggleHeatMode: alternando de ${cur} para ${next}"
    setHeatMode(next)
}


// ======== Escrita ========

private void setOutputById(Integer targetId, Boolean onVal) {
    Map last = state?.lastDevice ?: [:]
    Map outs = (last?.outputs ?: [:]) as Map
    if (!outs) {
        // sem cache, forçamos leitura e tentamos depois
        logInfo "Outputs ainda não descobertos; pedindo getDevice antes de setOutputById(${targetId})."
        getDevice()
        // tentativa cega: aplica em todos outN que tenham id == targetId no último cache (se vier logo depois)
    }
    Map argOutputs = [:]
    outs?.each { k, v ->
        if (v instanceof Map && v.id == targetId) {
            argOutputs[k as String] = [on: onVal]
        }
    }
    if (argOutputs.isEmpty()) {
        // fallback: se não encontramos pelo cache, tentamos um chute comum (Filtro costuma ser out0; Aux 1/2 out1/out2)
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
    // tenta achar outN por id
    String key = outs?.find { it?.value instanceof Map && it?.value?.id == idVal }?.key
    if (key) return key as String
    // palpites comuns
    if (idVal == 0)  return "out0"
    if (idVal == 10) return "out1"
    if (idVal == 11) return "out2"
    return null
}

private void setLights(Boolean onVal) {
    sendJson([command: "setDevice", argument: [leds: [ led0: [ on: onVal ] ] ]])
    pubParentAttr("lights", onVal)  // otimista
    runIn(1, "getDevice")
    
}

// ======== Envio baixo nível ========

private void sendJson(Map obj) {
    String js = JsonOutput.toJson(obj)
    // Alguns firmwares aceitam sem delimitador; adicionar newline ajuda framing no lado do servidor.
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

// ======== Novo parse(String) para quando o Hubitat entregar texto ========
def parse(String description) {
    if (logEnable) log.debug "RX (String): ${description}"

    String chunk
    // Se a string parece HEX puro (ex.: 7B22636F...), decodifica p/ UTF-8.
    if (looksLikeHex(description)) {
        try {
            chunk = hexToUtf8(description)
        } catch (e) {
            log.warn "Falha ao decodificar HEX -> UTF8: ${e}"
            return
        }
    } else {
        // Caso venha JSON puro (com { } ), usa direto
        chunk = description
    }

    state.rxBuf = (state.rxBuf ?: "") + (chunk ?: "")
    processBuffer()   // reaproveita seu framing por balanço de chaves
}

// ======== (já existente) parse(List<Map>) continua valendo ========
// void parse(List<Map> description) { ... }  // <-- mantenha o seu

// ======== Helpers para detectar/decodificar HEX ========
private boolean looksLikeHex(String s) {
    if (!s) return false
    // só dígitos hex e comprimento par (cada 2 chars = 1 byte)
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

    // framing por balanço de chaves
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
    // mantém resto não-consumido
    if (level == 0) {
        state.rxBuf = ""
    } else {
        // sobra parcial
        state.rxBuf = (start >= 0 ? buf.substring(start) : buf)
    }

    frames.each { f ->
        handleJsonFrame(f)
    }
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

    // Respostas padrão têm "command" e "response"
    if (obj.command && obj.response instanceof Map) {
        Map resp = (Map) obj.response
        state.lastDevice = resp
        updateFromResponse(resp)
        return
    }

    // Alguns firmwares podem ecoar setDevice no topo (sem 'response'), ou enviar 'getDevice' direto
    if (obj.id || obj.outputs || obj.leds || obj.heatings) {
        state.lastDevice = obj
        updateFromResponse(obj)
        return
    }
}

private void updateFromResponse(Map resp) {
    // --------- Children (como você já tinha) ----------
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
    }

    // --------- NOVO: Atributos no device PAI ----------
    // Luzes (led0)
    if (led0?.containsKey('on')) {
        pubParentAttr("lights", (led0.on == true))
    }

    // Filtro e Auxiliares 1..3 com base nos IDs
    // IDs comuns: filtro=0, auxiliares=10,11,12 (ajuste aqui se o seu mapeamento for diferente)
    Boolean fOn  = null
    Boolean a1On = null
    Boolean a2On = null
    Boolean a3On = null

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
    if (heat0) {
        publishHeat0(heat0)
    }

    
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
        // luzes
        pubParentAttr("lights", true)
        runIn(1, "getDevice")
        return
    }
}

def componentOff(cd) {
    String outKey = cd.getDataValue("outputKey")
    String ledKey = cd.getDataValue("ledKey")
    if (outKey) {
        sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:false]]]])

        // ✅ idem para OFF
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
        runIn(1, "getDevice")
        return
    }
}

private void componentUpdate(cd, String ev) {
    try {
        cd.parse([[name:"switch", value:ev, descriptionText:"${cd.displayName} was turned ${ev}"]])
    } catch (ignored) {
        // Fallback para setComponentStatus de drivers genéricos
        if (ev == "on") cd.sendEvent(name:"switch", value:"on") else cd.sendEvent(name:"switch", value:"off")
    }
}

def componentRefresh(cd) {
    if (logEnable) log.debug "Child pediu refresh: ${cd?.displayName}"
    // Um único getDevice já atualiza todos os childs
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
    // pega contexto atual para decidir o modo
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    Integer typeVal = (heat0?.type   instanceof Number) ? (heat0.type   as Integer) : null
    Integer curMode = (heat0?.opMode instanceof Number) ? (heat0.opMode as Integer) : null

    // Se for ligar e o modo atual é 0 (Off) ou desconhecido, sobe para 1 (Ligado) por padrão.
    // Se for desligar, força opMode=0.
    Integer newMode
    if (!onVal) {
        newMode = 0 // Off
    } else {
        newMode = (curMode == null || curMode == 0) ? 1 : curMode // 1 = Ligado na maioria dos tipos de piscina
    }

    // Envia on + opMode juntos
    sendJson([command:"setDevice", argument:[heatings:[heat0:[on:onVal, opMode:newMode]]]])

    // publica otimista e confirma com getDevice
    sendEvent(name:"heat0On",   value: onVal ? "on" : "off")
    if (newMode != null) sendEvent(name:"heat0Mode", value: opModeLabel(newMode, typeVal))
    runIn(1, "getDevice")
}


def setHeatMode(String mode) {
    Integer idx = opModeIndexFromLabel(mode)
    if (idx == null) { log.warn "setHeatMode: modo inválido '${mode}'"; return }

    // Liga se modo ≠ 0; desliga se 0
    boolean willOn = (idx != 0)

    sendJson([command:"setDevice", argument:[heatings:[heat0:[opMode:idx, on:willOn]]]])
    // otimista
    sendEvent(name:"heat0Mode", value: opModeLabel(idx, null))
    sendEvent(name:"heat0On",   value: willOn ? "on" : "off")
    runIn(1, "getDevice")
}


def setHeatSetpoint(Number spC) {
    if (spC == null) return
    Integer raw = rawFromC(spC)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[sp:raw]]]])
    // publica otimista
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

    BigDecimal target = cur + 1.0G   // +1,0 °C
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

    BigDecimal target = cur - 1.0G   // -1,0 °C
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()

    setHeatSetpoint(target)
}



// ======== Socket status ========

void socketStatus(String message) {
    // mensagens típicas: "send error: Broken pipe", "receive error: Stream closed"
    logWarn "socketStatus: ${message}"
    // tenta reconectar depois de 5s
    runIn(5, "connectSocket")
}


// ======== Logging helpers ========

private void logInfo(msg){ log.info "Tholz TCP: ${msg}" }
private void logWarn(msg){ log.warn "Tholz TCP: ${msg}" }


// ========  helpers ========

private void pubParentAttr(String name, boolean onVal) {
    String v = onVal ? "on" : "off"
    sendEvent(name: name, value: v, descriptionText: "${device.displayName} ${name} is ${v}")
}

// ======== Heatings helpers ========

// Conversão: docs e exemplos usam inteiros tipo 320 -> 32.0°C
private BigDecimal cFromRaw(def v) {
    if (v == null) return null
    // 0xAAAA (43690) indica erro de sensor
    if ((v instanceof Number) && v.intValue() == 0xAAAA) return null
    // Conversão padrão 10: 320 => 32.0
    try { return (v as BigDecimal) / 10.0G } catch (ignored) { return null }
}

// Converte °C back -> inteiro (ex.: 32.0 => 320)
private Integer rawFromC(def c) {
    if (c == null) return null
    BigDecimal val = (c as BigDecimal)
    return Math.round(val * 10.0G) as Integer
}

// Mapeamento genérico de opMode (índices) para rótulo legível.
// A tabela depende do "type" do heating; aqui cobrimos os comuns.
@Field Map OP_MODE_LABELS_DEFAULT = [
    0:"Off", 1:"Ligado", 2:"Automático", 3:"Econômico", 4:"Aquecer", 5:"Resfriar"
]


// Resolve label do modo a partir do índice e (opcional) do type
private String opModeLabel(Integer opMode, Integer typeVal) {
    // Poderia personalizar por 'type' (ex.: Solar Residencial tem "Econômico").
    return OP_MODE_LABELS_DEFAULT.get(opMode, "Modo ${opMode}")
}

// Converte texto -> índice mais provável (aceita variações PT/EN)
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
    // fallback: número?
    try { return Integer.parseInt(x) } catch (ignored) { return null }
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

    // Temp principal = o maior índice de t{n} presente; se só t1/t2, escolha o maior índice disponível
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

