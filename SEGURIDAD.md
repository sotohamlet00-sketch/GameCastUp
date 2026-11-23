# Seguridad y privacidad del Modo Consola (Shizuku)

## Qué es Shizuku (y qué no es)

Shizuku es una app de código abierto (RikkaApps) que permite a otras apps usar permisos de **shell**, los mismos que tiene un
ordenador conectado por ADB. El usuario la arranca (por depuración inalámbrica) y **autoriza una a una** las apps que pueden
usarla. **No es root**: no desbloquea el sistema ni modifica particiones, y deja de funcionar al reiniciar el teléfono hasta que
la vuelvas a arrancar. Puedes retirar el permiso a GameCast en cualquier momento desde la app Shizuku.

## Qué hace GameCast con esos permisos

* **Crear una pantalla virtual** («GameCast») y mandar su imagen a la TV: a una ventana sobre la pantalla externa
  (Miracast) o a un codificador de vídeo que se envía por tu red local (Google Cast / DLNA).
* **Lanzar el Lobby y las apps o juegos que tú eliges** en esa pantalla virtual (`am start --display`) y **cerrarlos**
  al volver al Lobby (`am force-stop` solo del paquete que abriste desde GameCast).
* **Inyectar toques** en la pantalla virtual cuando pulsas el mando flotante, en los puntos que tú configuraste.
* **Leer, una vez tras lanzar el juego, el listado de actividades del sistema** (`dumpsys activity activities`) solo para
  comprobar en qué pantalla quedó el juego. Se compara con el nombre del paquete del juego y no se guarda ni se muestra.

## Qué NO hace

* **No modifica APKs** de terceros ni da instrucciones para hacerlo.
* **No elimina los botones de los juegos**: los botones del propio juego se siguen viendo en la TV. Solo el mando de GameCast
  queda fuera de la TV.
* **No lee el contenido de otras apps**: no captura la pantalla principal, no lee textos ni ventanas, no usa servicios de
  accesibilidad.
* **No envía nada a Internet.** La app SÍ declara permisos de red (`INTERNET`, `ACCESS_NETWORK_STATE`,
  `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NEARBY_WIFI_DEVICES` con `neverForLocation`, y `ACCESS_LOCAL_NETWORK`,
  este último incluido a petición y sin verificar en la documentación) y `usesCleartextTraffic`, pero **solo para tu red
  local**: buscar TVs (Google Cast por mDNS/MediaRouter y DLNA por SSDP) y servir el vídeo a la TV desde un pequeño servidor
  HTTP **dentro del teléfono**. Ese servidor es accesible desde otros equipos de tu misma red Wi-Fi mientras Cast/DLNA
  están en marcha; usa una red de confianza. Mientras emite no pide contraseña a la TV. El texto de los botones del juego se
  reconoce **en el propio teléfono** (ML Kit, modelo incluido): no se envía ninguna imagen fuera.
* No usa cuentas, anuncios, analíticas ni librerías de pago.

## Qué se guarda (solo en el teléfono)

* Los mapas de controles (`mapping_<paquete>.json`) y unos pocos ajustes (nombre Bluetooth, TV elegida y su tipo).
* Un registro de diagnóstico **en memoria** (no se escribe en disco) que se borra al cerrar la app; puedes copiarlo tú si
  quieres compartirlo para depurar.

## Otros permisos

* **Mostrar sobre otras apps**: dibuja el mando flotante y la imagen en la TV (Miracast).
* **Red local y multicast**: descubrimiento de TVs (Cast/DLNA) y envío del vídeo; ver arriba.
* **Google Cast** usa los servicios de Google Play del teléfono (si no están, esa vía se desactiva).
* **Notificaciones y servicios en primer plano**: mantienen vivos el mando, la sesión del juego y las demás funciones; se ven
  en la barra de notificaciones y se detienen desde ella.
* **Dispositivos cercanos (Bluetooth)**: solo para el modo Mando HID.

## Riesgos que debes conocer

* Un servicio con permisos de shell es muy potente. Esta app solo usa los tres usos de arriba, pero un APK **modificado** por
  otra persona podría hacer más. Instala solo APKs que compiles tú del código fuente o descargues del repositorio oficial, y
  revoca el permiso en Shizuku si dejas de usar la app.
* Algunos juegos online prohíben las herramientas de inyección o mapeo de entrada en sus términos. Úsala bajo tu
  responsabilidad.

## Distribución

Por la política sobre pantallas virtuales, inyección de entrada y Shizuku, **esta app no se puede publicar en Google Play**.
Se distribuye por GitHub Releases o F-Droid.

## Cómo desactivarlo

Detén el Modo Consola desde GameCast (botón «Detener», mantener ✕ en el mando del Lobby, o la notificación), revoca a GameCast en la app Shizuku
y/o detén Shizuku. Desinstalar GameCast elimina sus datos.

## Novedades de la versión 4.0.0 (qué hacen con Shizuku)

* **Auto-Fix.** Al iniciar el Modo Consola, si falta el permiso de superposición (o nunca se aplicó), GameCast ejecuta
  desde el servicio de usuario `cmd appops set com.example.gamepadhid <OP> allow` para `ACCESS_RESTRICTED_SETTINGS`,
  `SYSTEM_ALERT_WINDOW`, `RUN_IN_BACKGROUND` y `RUN_ANY_IN_BACKGROUND`, además de `cmd deviceidle whitelist +<paquete>` y
  `pm grant <paquete> android.permission.POST_NOTIFICATIONS`. **Solo actúa sobre el propio paquete**: el servicio rechaza
  cualquier otro nombre. Equivale a lo que antes se hacía a mano con adb. El botón «Probar Auto-Fix» (Preparar Shizuku)
  muestra el informe completo.
* **Botones físicos.** Si asignas acciones a Volumen o Encendido, el servicio de usuario ejecuta `getevent -l`, que lee los
  eventos de entrada del teléfono, y **solo reenvía a la app las teclas Volumen y Encendido** (el resto se descarta en ese
  proceso; no se guarda ni se envía nada). Solo se activa si hay alguna acción asignada y mientras el Modo Consola o el
  Mando HID están en marcha.
* **Perfil App.** Los toques del trackpad, los deslizamientos y la tecla Atrás se inyectan en la pantalla virtual, nunca en
  la principal.
