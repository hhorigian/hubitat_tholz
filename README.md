# hubitat_tholz
Driver para integrar com o Controller Smartpool da Tholz

Configurar o SmartPool e pegar o IP dele, na mesma Rede que a Hubitat. 



Alguns comandos para usar no dashboard com tipo Button.


        case "1":  lightsOn();                   
        case "2":  lightsOff();                  
        case "3":  heatOn();                     
        case "4":  heatOff();                    
        case "5":  heatUp1C();                   
        case "6":  heatDown1C();                 
        case "7":  filterOn();                   
        case "8":  filterOff();                  
        case "9":  aux1On();                     
        case "10": aux1Off();                    
        case "11": aux2On();                     
        case "12": aux2Off();                    
        case "13": setHeatMode("Automático");    
        case "14": setHeatMode("Ligado");        
        case "15": setHeatMode("Off");           
        case "16": bumpHeatSetpoint(0.5);           
        case "17": bumpHeatSetpoint(-0.5);          
        case "18": toggleHeatMode();             
        case "19": setOutputById(12, true);         
        case "20": setOutputById(12, false);        
