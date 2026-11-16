# Kit AppSec IA — código-fonte nativo (v9)

Shell Android (WebView + ponte Termux RUN_COMMAND + Ollama) que carrega o
`index.html` deste mesmo repo via OTA.

## Build
```
echo "sdk.dir=/caminho/do/android-sdk" > local.properties
./gradlew assembleDebug   # ou: gradle assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
```
Requer Android SDK (platform android-34, build-tools 34.0.0), JDK 17+.

## Arquitetura
- `MainActivity.kt` — WebView, ponte `Termux.exec/run/diag/log`, ponte `App.*` (OTA), injeta POLYFILL.
- `TermuxResultReceiver.kt` — recebe resultado do Termux via PendingIntent.
- `Dbg.kt` — log em Android/data/.../files/debug.log.
- `app/src/main/assets/index.html` — UI de fallback embutida (a de produção vem do OTA, raiz do repo).

## Pendências conhecidas (2026-11-16)
- Botão 🩺 é injetado pelo POLYFILL nativo em position:fixed e sobrepõe o botão enviar. Mover pra UI (OTA) usando Termux.diag()/Termux.log().
- Envio de mídia (câmera) não funciona com o index.html atual.
- Estabilidade: MIUI mata o Termux em background -> comandos não voltam. Mitigar com termux-wake-lock + bateria sem restrição.
