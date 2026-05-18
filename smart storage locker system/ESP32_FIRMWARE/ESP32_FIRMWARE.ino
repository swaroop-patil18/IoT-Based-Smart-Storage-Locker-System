#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>

// =====================
// WIFI CONFIG
// =====================
const char* ssid = "YOUR WIFI NAME";
const char* password = "WIFI password";

// =====================
// MQTT CONFIG
// =====================
const char* mqttBroker = "broker.hivemq.com";
const int mqttPort = 1883;
const char* mqttTopic = "smartlocker/unlock";

// =====================
// LOCKER PINS (L1..L4)
// =====================
int lockerPins[] = {5, 18, 19, 21};
String lockerIds[] = {"L1", "L2", "L3", "L4"};

WiFiClient espClient;
PubSubClient client(espClient);

void unlockLocker(String lockerId) {
  int pin = -1;
  for (int i = 0; i < 4; i++) {
    if (lockerIds[i] == lockerId) {
      pin = lockerPins[i];
      break;
    }
  }
  if (pin == -1) {
    Serial.println("❌ Invalid locker ID: " + lockerId);
    return;
  }

  digitalWrite(pin, LOW);   // activates relay (depends on relay type)
  Serial.println("🔓 Unlocking " + lockerId);
  delay(2000);
  digitalWrite(pin, HIGH);  // deactivates relay
  Serial.println("🔒 Locked " + lockerId);
}

void callback(char* topic, byte* payload, unsigned int length) {
  Serial.print("📨 Message arrived [");
  Serial.print(topic);
  Serial.print("]: ");

  StaticJsonDocument<200> doc;
  DeserializationError error = deserializeJson(doc, payload, length);
  if (error) {
    Serial.println("❌ JSON parse failed");
    return;
  }

  const char* lockerId = doc["lockerId"];
  const char* action = doc["action"];

  Serial.print("lockerId: ");
  Serial.print(lockerId);
  Serial.print(", action: ");
  Serial.println(action);

  if (String(action) == "UNLOCK") {
    unlockLocker(String(lockerId));
  }
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Connecting to MQTT...");
    String clientId = "ESP32_SmartLocker_" + String(random(0xffff), HEX);
    if (client.connect(clientId.c_str())) {
      Serial.println("connected");
      client.subscribe(mqttTopic);
      Serial.println("Subscribed to: " + String(mqttTopic));
    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      Serial.println(" retrying in 5s");
      delay(5000);
    }
  }
}

void setup() {
  Serial.begin(115200);

  for (int i = 0; i < 4; i++) {
    pinMode(lockerPins[i], OUTPUT);
    digitalWrite(lockerPins[i], HIGH); // relay off (active LOW)
  }

  WiFi.begin(ssid, password);
  Serial.print("Connecting to WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\n✅ WiFi connected");
  Serial.print("IP: ");
  Serial.println(WiFi.localIP());

  client.setServer(mqttBroker, mqttPort);
  client.setCallback(callback);
}

void loop() {
  if (!client.connected()) reconnect();
  client.loop();

  // Manual unlock via Serial Monitor
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.startsWith("UNLOCK ")) {
      String locker = cmd.substring(7);
      unlockLocker(locker);
    }
  }
}
