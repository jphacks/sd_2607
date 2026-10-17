#include <Arduino.h>
#include <M5Unified.h>

void setup() {
    Serial.begin(115200);
    auto config = M5.config();
    M5.begin(config);

    M5.Display.setTextSize(2);
    M5.Display.println("ESP32 ready");
    Serial.printf("ESP32 ready chip=%s flash=%u heap=%u\n",
                  ESP.getChipModel(), ESP.getFlashChipSize(), ESP.getFreeHeap());
}

void loop() {
    M5.update();

    if (M5.BtnA.wasPressed()) {
        M5.Display.println("Button A");
        Serial.println("Button A");
    }

    static uint32_t lastHeartbeat = 0;
    const uint32_t now = millis();
    if (now - lastHeartbeat >= 5000) {
        lastHeartbeat = now;
        Serial.printf("alive uptime_ms=%lu heap=%u\n",
                      static_cast<unsigned long>(now), ESP.getFreeHeap());
    }

    delay(10);
}
