# Vid Downloader

Aplicación Android para descargar vídeo y audio de YouTube (y de cualquier web
compatible con [yt-dlp](https://github.com/yt-dlp/yt-dlp)). Está escrita en Kotlin,
usa Jetpack (Room, ViewBinding, Material 3) y empaqueta yt-dlp + FFmpeg de forma
nativa mediante [youtubedl-android](https://github.com/yausername/youtubedl-android),
así que **no hace falta instalar nada aparte**.

## Funciones

- Descarga de **vídeo** (MP4) o **audio** (MP3) eligiendo la calidad.
- **Resoluciones reales**: solo se muestran las que ofrece cada vídeo.
- **Playlists**: descarga la lista completa o solo el vídeo.
- **Subtítulos** opcionales (manuales y automáticos); si fallan, se descarga el vídeo sin ellos.
- **Pausar, reanudar y cancelar** descargas. Al reanudar se continúa desde donde se quedó.
- **Descargas simultáneas** configurables (1–4) desde Ajustes.
- **Pausa automática** si se pierde la conexión (no se marca como error).
- **Información rápida**: miniatura y título aparecen al instante.
- Carpeta de descargas configurable (SAF) o la carpeta de la app.
- Actualización automática de yt-dlp para evitar errores `HTTP 403`.

## Instalación

Descarga el APK desde la sección [Releases](../../releases):

- `Vid-Downloader-*-arm64-v8a.apk`: para prácticamente cualquier móvil actual (recomendado).
- `Vid-Downloader-*-universal.apk`: incluye todas las arquitecturas.

Los APK están firmados con la clave de depuración de Android, por lo que hay que
permitir la instalación desde orígenes desconocidos.

Requiere **Android 7.0 (API 24)** o superior y conexión a internet.

## Compilar

Requisitos: JDK 17, Android SDK 35 y conexión a internet (Gradle descarga las
dependencias y los binarios nativos).

```bash
# local.properties debe apuntar al SDK, por ejemplo:
# sdk.dir=C\:\\Users\\tu_usuario\\AppData\\Local\\Android\\Sdk

./gradlew :app:assembleDebug
```

El APK se genera en `app/build/outputs/apk/debug/`.

## Tecnología

- Kotlin + Coroutines
- Room (historial de descargas)
- yt-dlp + FFmpeg (youtubedl-android)
- Coil (miniaturas), Material Components
