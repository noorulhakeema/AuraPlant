#define MIC_PIN A0   // use the same pin you used before

void setup() {
  SerialUSB.begin(115200);
  while (!SerialUSB && millis() < 5000);
  analogReadResolution(12);
}

void loop() {
  float total = 0, peak = 0;
  const int READINGS = 20;     // about 2 seconds

  for (int r = 0; r < READINGS; r++) {
    const int N = 200;
    long sum = 0;
    int samples[N];
    for (int i = 0; i < N; i++) {
      samples[i] = analogRead(MIC_PIN);
      sum += samples[i];
      delayMicroseconds(500);
    }
    float mean = sum / (float)N;
    float sq = 0;
    for (int i = 0; i < N; i++) {
      float d = samples[i] - mean;
      sq += d * d;
    }
    float rms = sqrt(sq / N);
    total += rms;
    if (rms > peak) peak = rms;
  }

  SerialUSB.print("avg ");
  SerialUSB.print(total / READINGS);
  SerialUSB.print("   peak ");
  SerialUSB.println(peak);
}