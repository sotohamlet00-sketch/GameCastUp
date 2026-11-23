# GameCast: Mando HID, Juego en TV y Modo Consola con Lobby

App Android nativa (Kotlin 2.0.21, AGP 8.9.1, Gradle 8.11.1, minSdk 28, compileSdk/targetSdk 36). Sin root, sin servicios en la
nube, sin APIs de pago, todo open-source. Paquete `com.example.gamepadhid`, versión 4.0.0 (código 6).

> ## ⚠️ Estado honesto
> **Nada de este proyecto se ha compilado ni probado en un teléfono.** Se escribió y revisó sin Gradle, sin Android SDK y
> sin dispositivo; solo pasó comprobaciones estáticas (XML, recursos, ids, llaves, imports, clases del manifiesto y AIDL).
> Es probable que el primer `assembleDebug` y las primeras pruebas necesiten rondas de ajustes. Si algo falla, copia el
> log del paso en rojo de GitHub Actions o el diagnóstico de **Preparar Shizuku > Copiar diagnóstico**.

> ## ⚠️ No se puede publicar en Google Play
> El Modo Consola usa Shizuku, pantallas virtuales e inyección de entrada con permisos de shell, algo que la política de
> Google Play no admite. Se distribuye por **GitHub Releases** o **F-Droid**, instalando el APK a mano.

## Los tres modos

| Modo | Para qué | Cómo funciona |
|------|----------|---------------|
| **Mando HID** | Usar el teléfono como mando Bluetooth de una TV, TV box o PC | `BluetoothHidDevice` en un servicio en primer plano |
| **Juego en TV** | Ver la pantalla del teléfono en la TV (espejo simple) | `MediaProjection` → ventana sobre la pantalla externa (Miracast) **o** codificador H.264 enviado por la red (Cast/DLNA) |
| **Modo Consola (Shizuku)** | Un **Lobby** en la TV (estilo menú de consola) desde el que abres juegos y apps, con el **mando táctil en el teléfono** | Pantalla virtual con el Lobby y la app dentro; el mando inyecta toques en ella |

## Tres formas de conectar con la TV

«Conectar con la TV» (Ajustes o al iniciar un modo) busca a la vez por las tres vías. La elegida se guarda.

| Vía | Qué manda | Latencia | Para qué sirve |
|-----|-----------|----------|----------------|
| **A) Miracast / Wi-Fi Direct** | Pantalla externa del sistema (`TvOutputWindow`) | Mínima: el juego dibuja directo en la TV | Jugar. La TV se conecta desde el panel Cast del sistema (una app normal no puede buscar ni conectar Miracast) |
| **B) Google Cast** | Lista HLS (H.264 en MPEG-TS) servida por el teléfono; SDK oficial con el receptor por defecto | ≈ 6–10 s | Ver pantalla, vídeo, juegos lentos. Necesita los servicios de Google Play |
| **C) DLNA / UPnP** | MPEG-TS continuo servido por el teléfono (`/live.ts`) | ≈ 3–8 s | Igual que Cast, en TVs que son «MediaRenderer». Implementación **nativa** (SSDP + SOAP AVTransport) |

Cast no puede duplicar la pantalla en vivo con el SDK actual: por eso se manda un flujo HLS. La interfaz avisa del retraso.
Con Cast/DLNA la resolución se limita a 720p (lado largo 1280) para ahorrar ancho de banda; con Miracast, hasta 1080p.

**Buscador experto** (si en ~12 s no aparece la TV, o desde el aviso del Modo Consola): eliges marca y, opcionalmente, el
modelo (ej. `AU8000`); lee `assets/tv_compatibility.json` (15 marcas: Samsung, LG, Sony, TCL, Hisense, Xiaomi, Philips,
Panasonic, Roku, Vizio, Sharp, Toshiba, Amazon Fire TV, Chromecast/Google TV y Otra) y te dice qué método probar primero,
los pasos y las apps oficiales (botón que abre la búsqueda en Google Play, con alternativa web). Es orientativo.
Todo es inalámbrico: **no se sugieren cables**.

## Cómo funciona el Modo Consola

1. `ConsoleService` crea una **pantalla virtual «GameCast»** (servicio de usuario de Shizuku, banderas
   `PUBLIC | OWN_CONTENT_ONLY | PRESENTATION`, más banderas de confianza en Android 13+) y la apunta a la TV:
   - **Miracast:** directamente a la `Surface` de la ventana de la TV (sin `MediaProjection`).
   - **Cast/DLNA:** a la `Surface` del codificador (`StreamSession`).
2. En ella se lanza el **Lobby** (`ConsoleLobbyActivity`, `am start --display`): categorías Juegos, Entretenimiento,
   Aplicaciones y Ajustes, dibujadas en estilo XMB. Las apps se clasifican con `PackageManager` (`CATEGORY_GAME`,
   categorías de vídeo/audio y paquetes conocidos como YouTube o Netflix). Como es una app más de la pantalla virtual, se
   ve igual por cualquiera de las tres vías.
3. El teléfono se convierte en mando (`FloatingPadService`, plantilla Xbox) y **navega el Lobby**: D-pad o stick izquierdo
   mueven, **A** abre, **B** vuelve al inicio de la lista, **L1/R1** saltan de 5 en 5. El mando publica teclas en
   `LobbyInput`; no inyecta toques en el Lobby.
4. Al elegir una app se lanza **encima del Lobby** en la pantalla virtual y se comprueba en qué pantalla quedó. Si apareció
   en el teléfono, se cierra y el Lobby avisa de que no es compatible.
5. Pasa por el **mapeo obligatorio** (`ControlMapperActivity`, fase `MAPPING`): se ve la app en directo, eliges plantilla
   (**Xbox, PSP o Nintendo DS**), pulsas **Detección automática** (ML Kit busca rótulos como A/B/X/Y, START, SALTAR o ATACAR;
   lo no detectado se ajusta a mano), arrastras los puntos sobre los botones reales y pulsas **Guardar y Jugar**.
   Atrás o «Cancelar» piden confirmación, cierran la app y vuelven al Lobby.
6. En juego (`RUNNING`), cada control del mando inyecta toques en la pantalla virtual con su `displayId`: botones, **D-pad**,
   sticks y **mousepad** (zona rectangular donde el dedo virtual se mueve de forma relativa; en DS equivale a la pantalla
   táctil). **⌂** cierra la app y vuelve al Lobby; mantener **✕** sale del Modo Consola.

Acceso a las APIs ocultas, por orden: (1) UserService de Shizuku (AIDL, proceso shell); (2) respaldo con
`ShizukuBinderWrapper` + `HiddenApiBypass` para la inyección; (3) si todo falla, el motivo queda en el diagnóstico copiable.

## Novedades de la 4.0.0 (primera parte)

* **Auto-Fix (Shizuku):** en Samsung y otros Android restrictivos, las apps instaladas a mano no pueden superponerse ni
  ignorar las restricciones de batería. Al iniciar el Modo Consola, GameCast se concede esos permisos a sí misma con el
  servicio de usuario de Shizuku (`cmd appops set ... allow`, `cmd deviceidle whitelist`), mostrando «Optimizando GameCast…».
  Si no lo logra, aparece el aviso clásico. `Shizuku.newProcess` está obsoleto y se retirará; por eso se usa el UserService.
  «Probar Auto-Fix» en Preparar Shizuku enseña el informe. ACCESS_RESTRICTED_SETTINGS y la lista blanca de batería están sin
  verificar en todos los modelos.
* **Perfiles de control** (se eligen al iniciar el Modo Consola): **Automático** (mapa guardado o ML Kit), **Manual táctil**
  y **Modo App** (trackpad que es la pantalla en miniatura, deslizar arriba/abajo y Atrás; sin mapeo; Inicio vuelve al Lobby).
* **Botones físicos:** Volumen y Encendido pueden hacer de L1/R1, A/B…, cerrar la app, Atrás o salir. Globales (Ajustes) o por
  juego (mapeador y Ajustes). Se leen con `getevent` por Shizuku, también en Mando HID (con el mando en pantalla, Volumen se
  consume). **Limitación:** solo se observan; el volumen cambia y Encendido apaga la pantalla (se reenciende sola).
* **Portadas reales:** el Lobby y el selector de apps usan el icono real (`applicationInfo.loadIcon(pm)`).

## Novedades de la 4.0.0 (segunda parte)

* **Personalización** (Ajustes > «Personalizar…»): colores base (Índigo, Menta, Coral, Violeta, Ámbar, Cielo), una imagen propia
  como fondo de cada botón y un sonido propio (.mp3 u .ogg corto, hasta 1 MB) para cada uno.
* **Paleta dinámica:** al subir una imagen, `androidx.palette` extrae su color dominante (si es casi gris, el más vivo) y pasa a
  ser el acento del Lobby (fondo, cintas, brillos y textos), del mando flotante y de las pantallas de la app. El icono real
  del launcher no puede cambiar sin reiniciar, así que no se toca.
* **Sonidos con SoundPool** en el mando flotante, el Lobby y el mando HID en pantalla.
* **Animación de carga** nueva (`SplashView`, Canvas): un teléfono se transforma en gamepad, late con un pulso (se puede tocar
  para lanzar ondas) y da paso a la app. La misma animación abre el Lobby en la TV. Antes no había pantalla de carga propia.
* **Lobby sin emojis:** categorías con iconos vectoriales (`ic_cat_*`), tintados con el color del tema.
* El tema se aplica con etiquetas `android:tag` en los layouts (`gc_primary`, `gc_title`, `pad_*`).

## Shizuku: requisitos y activación

Shizuku **no es root**: es un servicio que tú arrancas (por depuración inalámbrica) y que presta permisos de shell solo a las
apps que autorices. La app detecta su estado en vivo y, si no está listo, ofrece **«Preparar Shizuku»** con la guía.

* Guía oficial: <https://shizuku.rikka.app/>
* Resumen: instalar Shizuku, activar opciones de desarrollador y «Depuración inalámbrica» (Android 11+, con Wi-Fi), iniciar
  Shizuku con su asistente (en Android 13 se puede hacer desde el mismo teléfono) y conceder el permiso a GameCast.
  **Hay que repetirlo tras cada reinicio del teléfono.**

## Dependencias (verificadas en la web)

`dev.rikka.shizuku:api` y `provider` 13.1.5 · `org.lsposed.hiddenapibypass:hiddenapibypass` 4.3 ·
`com.google.android.gms:play-services-cast-framework` 21.5.0 · `androidx.mediarouter:mediarouter` 1.2.5 ·
`com.google.mlkit:text-recognition` 16.0.1 (modelo incluido) · AndroidX core-ktx, appcompat y Material 1.12.
DLNA se hizo **nativo** porque ConnectSDK (última versión 1.6.0, ≈2015) arrastra bibliotecas de soporte antiguas y
`play-services-cast 7.8.0`, que romperían la compilación con AndroidX, Cast 21.5.0 y targetSdk 36.

## Permisos de red (ya NO es «sin permiso de internet»)

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NEARBY_WIFI_DEVICES`
(`neverForLocation`) y `usesCleartextTraffic="true"` (el servidor del teléfono y las TVs usan HTTP en la red local).
`ACCESS_LOCAL_NETWORK` está incluido a petición y **no está verificado en la documentación**. Ver `SEGURIDAD.md`.

## Limitaciones (honestas)

* **Los botones del propio juego SÍ se verán en la TV.** Solo desaparece el mando de GameCast.
* **Algunos juegos no respetan la pantalla secundaria** (los que fuerzan la principal o no son redimensionables). Se detecta y
  se avisa; en Android 10+ puede ayudar «Forzar actividades redimensionables» en opciones de desarrollador (no comprobado).
* **La inyección de toques y la creación de pantallas dependen de APIs ocultas** cuyas firmas cambian entre Android 13 y 16
  (firmas, banderas numéricas de `VirtualDisplay` recordadas de AOSP y formato de `dumpsys`): **no se pudieron verificar**.
* **Lanzar el Lobby en la pantalla virtual** exige que la actividad sea `exported` (la inicia el usuario shell); se cierra
  sola sin sesión. **Volver al Lobby** depende de `am force-stop`; si falla, la app sigue corriendo debajo.
* **Cast y DLNA tienen retraso** (6–10 s) y no sirven para juegos rápidos; sin audio en el flujo (el audio sigue saliendo por el
  teléfono). Cast necesita Google Play Services y que el router no aísle los dispositivos.
* **ML Kit no tiene un detector de «controles de juego»**: se reconoce texto sobre un fotograma copiado con `PixelCopy` y se
  elige el candidato más cercano. Muchos juegos usan iconos sin texto.
* Los emojis de las categorías del Lobby dependen de la fuente del sistema, y el fondo animado redibuja de forma continua
  (vigila el consumo). Contenido protegido (DRM, `FLAG_SECURE`) se ve en negro.
* Pendiente: unificar el háptico (`Fx.pressDown`) y quitar los hápticos duplicados; tipos de mando y personalización extrema.
* La app no modifica APKs de terceros ni da instrucciones para ello.

## Compilar con GitHub Actions y descargar el APK

El flujo `.github/workflows/build.yml` instala JDK 17, el SDK de Android y Gradle 8.11.1 (con caché), genera el wrapper,
ejecuta `./gradlew assembleDebug` y sube el APK como artefacto **`gamecast-apk`** (30 días). Se lanza en cada `push` a `main` y
a mano con «Run workflow».

### 1. Subir el proyecto desde el celular (Termux)

1. Crea un repositorio vacío en github.com (sin README).
2. Crea un token: Settings > Developer settings > Personal access tokens. En un token clásico marca `repo` **y `workflow`**.
3. En Termux:
   ```bash
   pkg update && pkg install git unzip
   termux-setup-storage
   cd ~ && unzip /sdcard/Download/gamecast_universal_final.zip
   cd gamecast_consola
   git init -b main
   git config user.name "TU_USUARIO" && git config user.email "tu@correo"
   git add . && git commit -m "GameCast"
   git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
   git push -u origin main          # usuario: tu usuario; contraseña: el TOKEN
   ```

### 2. Pasar el APK al teléfono con Android 13 sin enviar archivos

1. En ese teléfono abre github.com en el navegador e inicia sesión (los artefactos solo se descargan con sesión).
2. Repositorio > **Actions** > la última ejecución > **Artifacts** > `gamecast-apk`.
3. Descomprime el ZIP y abre `app-debug.apk` (permite «Instalar apps desconocidas» a esa app).
4. Si Actions falla, abre el paso en rojo, copia el error y pásalo para corregirlo.

## Cómo probar, por orden

1. **Mando HID** (el más simple): inicia el modo y empareja desde la TV o el PC.
2. **Conectar con la TV** (Ajustes > Elegir TV): comprueba qué vías ve cada TV. Prueba **Miracast** conectando desde el
   panel Cast del sistema; **Cast** y **DLNA** deberían aparecer solas en la misma red Wi-Fi.
3. **Juego en TV** con cada vía (espejo simple). Con Cast/DLNA espera unos segundos hasta que la TV empiece a reproducir.
4. **Modo Consola:** Preparar Shizuku > Probar conexión > Iniciar. Deberías ver el Lobby en la TV y el mando en el teléfono.
   Navega, abre un juego, mapea y juega. Copia el diagnóstico si algo falla.

## Cambios de API de Android 13 a 16 tenidos en cuenta

* **Android 13:** `POST_NOTIFICATIONS`; banderas de pantalla virtual de confianza; `createWindowContext` para la ventana en la TV.
* **Android 14:** tipos de servicio en primer plano con permiso propio: `specialUse` (permiso y `<property>` de subtipo) en
  `ConsoleService` y `FloatingPadService`; `HidService` `connectedDevice`; `ProjectionService` `mediaProjection`.
* **Android 14 y 15:** MediaProjection (espejo simple): servicio antes de `getMediaProjection`, callback antes del
  `VirtualDisplay`, permiso de un solo uso y captura de pantalla completa; parada al bloquear la pantalla en Android 15.
* **Android 14 a 16:** la firma de `IInputManager.injectInputEvent` puede tener 2 o 3 parámetros; se detecta por reflexión.
* **Android 15 y 16:** edge-to-edge forzado (cada pantalla aplica insets).

## Estructura

```
app/src/main/aidl/.../shizuku/IGameCastService.aidl   Servicio de usuario de Shizuku (launch, createDisplay, forceStop...)
app/src/main/assets/tv_compatibility.json             Base del Buscador experto (15 marcas)
app/src/main/java/com/example/gamepadhid/
  MainActivity, ControllerActivity, SettingsActivity, Permissions, Prefs
  shizuku/    ShizukuManager, GameCastClient, GameCastUserService (proceso shell), ShizukuSetupActivity, DiagLog
  shell/      InputInjection, PointerInjector
  virtual/    GameVirtualDisplay, GameLauncher, TouchInjectorShizuku, BinderInputBackend, ScreenInfo
  console/    ConsoleService, ConsoleSession (IDLE, STARTING, LOBBY, MAPPING, RUNNING, ERROR)
  lobby/      ConsoleLobbyActivity, LobbyView, LobbyInput, AppClassifier, LobbyIcons
  overlay/    FloatingPadService, ControlMapperActivity, MapperView, PackagePicker
  mapping/    ControlIds, GameMapping, MappingStore, AutoMapper (ML Kit)
  stream/     TsMuxer, ScreenEncoder, HlsServer, StreamSession
  transport/  TvDevice, CastOptionsProvider, GoogleCastController, DlnaController, TvPlayer, TvConnectActivity
  tvsearch/   ExpertTvSearchActivity
  hid/        HidManager, HidService, HidSession, GamepadState, GamepadDescriptor, PairingDialog, HidTexts
  cast/       ProjectionService, TvOutputWindow, DisplayScanner, TargetAdapter, CastCapabilities, CastNav
  ui/         JoystickView, Fx, AspectFrameLayout
.github/workflows/build.yml   Compilación en GitHub Actions
SEGURIDAD.md                  Qué hace Shizuku y qué hace (y no hace) esta app
```
