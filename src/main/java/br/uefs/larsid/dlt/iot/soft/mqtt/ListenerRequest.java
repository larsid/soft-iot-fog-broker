package br.uefs.larsid.dlt.iot.soft.mqtt;

import br.uefs.larsid.dlt.iot.soft.services.Controller;
import br.uefs.larsid.dlt.iot.soft.utils.RequestDevicesScores;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import java.util.logging.Logger;

public class ListenerRequest implements IMqttMessageListener {

  /*-------------------------Constantes---------------------------------------*/
  private static final String TOP_K = "TOP_K_HEALTH";
  private static final String SENSORS = "SENSORS";
  private static final String SENSORS_RES = "SENSORS_RES/";
  private static final String SENSORS_FOG_RES = "SENSORS_FOG_RES/";
  private static final String TOP_K_FOG_RES = "TOP_K_HEALTH_FOG_RES/";
  private static final String GET_SENSORS = "GET sensors";
  private static final String GET_TOPK = "GET topk";
  private static final int QOS = 1;
  /*--------------------------------------------------------------------------*/

  private boolean debugModeValue;
  private MQTTClient MQTTClientUp;
  private MQTTClient MQTTClientHost;
  private List<String> nodesUris;
  private Controller controllerImpl;
  private static final Logger logger = Logger.getLogger(ListenerRequest.class.getName());

  /**
   * Método construtor.
   *
   * @param controllerImpl Controller - Controller que fará uso desse Listener.
   * @param MQTTClientUp   MQTTClient - Cliente MQTT do gateway superior.
   * @param MQTTClientHost   MQTTClient - Cliente MQTT do próprio gateway.
   * @param nodesUris   List<String> - Lista de URIs.
   * @param topics          String[] - Tópicos que serão assinados.
   * @param qos            int - Qualidade de serviço do tópico que será ouvido.
   * @param debugModeValue boolean - Modo para debugar o código.
   */
  public ListenerRequest(
    Controller controllerImpl,
    MQTTClient MQTTClientUp,
    MQTTClient MQTTClientHost,
    List<String> nodesUris,
    String[] topics,
    int qos,
    boolean debugModeValue
  ) {
    this.MQTTClientUp = MQTTClientUp;
    this.MQTTClientHost = MQTTClientHost;
    this.nodesUris = nodesUris;
    this.controllerImpl = controllerImpl;
    this.debugModeValue = debugModeValue;

    if (controllerImpl.hasNodes()) {
      for (String topic : topics) {
        this.MQTTClientUp.subscribe(qos, this, topic);
      }
    } else {
      for (String topic : topics) {
        this.MQTTClientHost.subscribe(qos, this, topic);
      }
    }
  }

  @Override
  public void messageArrived(String topic, MqttMessage message)
    throws Exception {
    /* params = [topic, id] */
    final String mqttMessage = new String(message.getPayload());
    int k;
    String id;
    JsonArray functionHealth;

    switch (topic) {
      case GET_TOPK:
        JsonObject jsonGetTopKUp = new Gson()
        .fromJson(mqttMessage, JsonObject.class);

        id = jsonGetTopKUp.get("id").getAsString();
        k = jsonGetTopKUp.get("k").getAsInt();
        functionHealth = jsonGetTopKUp.get("functionHealth").getAsJsonArray();

        if (k == 0) {
          printlnDebug("Top-K = 0");

          this.controllerImpl.sendEmptyTopK(id);
        } else {
          this.controllerImpl.setResponseTime(System.currentTimeMillis());
          Map<String, Integer> scoreMapEmpty = new LinkedHashMap<String, Integer>();

          this.controllerImpl.getTopKScores().put(id, scoreMapEmpty);

          if (controllerImpl.hasNodes()) {
            printlnDebug("==== Cloud gateway -> Fog gateway  ====");

            /* Criando uma nova chave, no mapa de requisições */
            this.controllerImpl.addResponse(id);

            byte[] messageDown = message.getPayload();

            this.publishToDown(TOP_K, messageDown);
          }

          this.controllerImpl.setJsonGetTopK(jsonGetTopKUp);

          /* Aguarda as respostas dos nós da camada inferior conectados a ele;
           * e publica para a camada superior o Top-K resultante.
           */
          this.controllerImpl.publishTopK(id, k, functionHealth);
        }

        break;
      case TOP_K:
        printlnDebug("==== Fog gateway -> Bottom gateway  ====");
        printlnDebug("Requesting the real scores of the devices...");

        JsonObject jsonGetTopKDown = new Gson()
            .fromJson(mqttMessage, JsonObject.class);

        id = jsonGetTopKDown.get("id").getAsString();

        if (this.controllerImpl.getNode().getDevices().isEmpty()) {
          printlnDebug("Sorry, there are no devices connected.");

          byte[] payload = "{}".toString().getBytes();

          MQTTClientUp.publish(TOP_K_FOG_RES + id, payload, 1);
        } else {
          this.controllerImpl.setJsonGetTopK(jsonGetTopKDown);

          RequestDevicesScores requester = new RequestDevicesScores(
            MQTTClientHost, 
            debugModeValue,
            this.controllerImpl.getNode().getDevices()
          );

          requester.startRequester();
        }

        break;
      case GET_SENSORS:
        printlnDebug("==== Cloud gateway -> Fog gateway  ====");

        /**
         * Caso existam dispositivos conectados ao próprio nó.
         */
        if (this.controllerImpl.getNode().getDevices().size() > 0) {
          JsonObject jsonGetSensors = new JsonObject();
          String deviceListJson = new Gson()
          .toJson(this.controllerImpl.loadSensorsTypes());

          jsonGetSensors.addProperty("sensors", deviceListJson);

          byte[] payload = jsonGetSensors
            .toString()
            .replace("\\", "")
            .getBytes();

          MQTTClientUp.publish(SENSORS_FOG_RES, payload, 1);
        } else {
          this.controllerImpl.getSensorsTypesJSON()
            .addProperty("sensors", "[]");

          /* Criando uma nova chave, no mapa de requisições */
          this.controllerImpl.addResponse("getSensors");

          byte[] messageDown = "".getBytes();

          this.publishToDown(SENSORS, messageDown);

          /* Aguarda as respostas dos nós da camada inferior conectados a
           * ele; e publica para a camada superior.
           */
          this.controllerImpl.publishSensorType();
        }

        break;
      case SENSORS:
        byte[] payload;

        printlnDebug("==== Fog gateway -> Bottom gateway  ====");

        JsonObject jsonGetSensors = new JsonObject();
        String deviceListJson = new Gson()
        .toJson(this.controllerImpl.loadSensorsTypes());

        jsonGetSensors.addProperty("sensors", deviceListJson);

        payload = jsonGetSensors.toString().getBytes();

        MQTTClientUp.publish(SENSORS_RES, payload, 1);

        break;
      default:
        String responseMessage = String.format(
          "\nOops! the request isn't recognized...\nTry one of the options below:\n- %s\n",
          GET_SENSORS
        );

        MQTTClientUp.publish(SENSORS_FOG_RES, responseMessage.getBytes(), 1);

        break;
    }
  }

  /**
   * Publica a requisição para os nós filhos.
   *
   * @param topicDown String - Tópico.
   * @param messageDown byte[] - Mensagem que será enviada.
   */
  private void publishToDown(String topicDown, byte[] messageDown) {
    String user = this.MQTTClientUp.getUserName();
    String password = this.MQTTClientUp.getPassword();

    for (String nodeUri : this.nodesUris) {
      String uri[] = nodeUri.split(":");

      MQTTClient MQTTClientDown = new MQTTClient(
        this.debugModeValue,
        uri[0],
        uri[1],
        user,
        password
      );

      MQTTClientDown.connect();
      MQTTClientDown.publish(topicDown, messageDown, QOS);
    }
  }

  private void printlnDebug(String str) {
    if (debugModeValue) {
      logger.info(str);
    }
  }
}
