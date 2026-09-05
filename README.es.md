<p align="center">
  <img src="docs/images/banner.jpg" alt="DSH Mobile — el DeepSeek Harness en tu bolsillo" width="100%">
</p>

<h1 align="center">DSH Mobile — Control remoto del DeepSeek Harness</h1>

<p align="center">
  Una aplicación complementaria de código abierto para Android que te lleva tu
  <b>DeepSeek Harness</b> en el bolsillo.<br>
  Dirige sesiones, revisa planes y objetivos, responde aprobaciones y preguntas, y recibe
  notificaciones cuando el harness termina — desde el teléfono, por tu red local.
</p>

<p align="center">
  <a href="https://dshm.zyphite.com"><img alt="Website" src="https://img.shields.io/badge/website-dshm.zyphite.com-4176E6?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sorsama/deepseek-harness-mobile?style=flat-square"></a>
  <a href="https://github.com/sorsama/deepseek-harness-mobile/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/sorsama/deepseek-harness-mobile/ci.yml?branch=main&style=flat-square"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/license-MIT-blue?style=flat-square"></a>
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh-CN.md">中文</a> ·
  <a href="README.hi.md">हिन्दी</a> ·
  <b>Español</b> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.th.md">ไทย</a>
</p>

DSH Mobile es una **aplicación complementaria no oficial** para el
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (MIT) que replica su GUI web
función por función, en el propio lenguaje visual del harness. Solo Android, Kotlin + Jetpack
Compose.

Su contraparte en el otro extremo es
[**dsh-relay**](https://github.com/sorsama/deepseek-harness-relay), un plugin del harness que añade
la capa de autenticación que el propio harness reconoce no tener, para que esta aplicación llegue a
un harness con una credencial real y una clave fijada en lugar de un puerto abierto. Consulta
[Relay](https://github.com/sorsama/deepseek-harness-mobile/wiki/Relay).

**[dshm.zyphite.com](https://dshm.zyphite.com)** es el sitio del proyecto: qué es la aplicación,
cómo se ve y cómo ponerla en marcha, todo en una página.

La [**wiki**](https://github.com/sorsama/deepseek-harness-mobile/wiki) es la guía para quien la
usa: [primeros pasos](https://github.com/sorsama/deepseek-harness-mobile/wiki/Getting-Started),
[conexión](https://github.com/sorsama/deepseek-harness-mobile/wiki/Connecting),
[solución de problemas](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting),
un [recorrido por las funciones](https://github.com/sorsama/deepseek-harness-mobile/wiki/Feature-Tour)
y las [preguntas frecuentes](https://github.com/sorsama/deepseek-harness-mobile/wiki/FAQ).

---

## Capturas

| Conectar | Chat | Trayectoria |
|:--:|:--:|:--:|
| <img src="docs/images/home.png" width="240" alt="Pantalla de conexión: harnesses recientes con alcance en vivo, descubrimiento, entrada manual y opciones de conexión automática"> | <img src="docs/images/chat.png" width="240" alt="Chat: turnos en streaming con un icono por herramienta, tarjetas de herramienta, panel de objetivo y compositor"> | <img src="docs/images/trajectory.png" width="240" alt="Trayectoria: un registro por turno con totales de uso"> |
| Harnesses recientes con alcance en vivo, descubrimiento en la LAN, `host:port` manual, conexión automática. | Turnos en streaming, un glifo por herramienta, tarjetas de herramienta desplegables, selector de permisos. | La misma sesión como registro turno a turno, con totales de uso. |

| Detalles de la sesión | Subagentes |
|:--:|:--:|
| <img src="docs/images/session-info.png" width="240" alt="Panel de detalles: desglose del contexto, objetivo, modo plan, trabajos, cola, subagentes, información del host"> | <img src="docs/images/subagent.png" width="240" alt="Catálogo de subagentes, con hijos que se pueden continuar"> |
| Desglose del contexto, objetivo, modo plan, trabajos en segundo plano, turnos en cola, información del host, exportación del registro de sesión. | El catálogo de subagentes: abre la transcripción de un hijo, haz un seguimiento o interrúmpelo. |

## Funciones

- **Conexión sin fricción** — descubre automáticamente un harness en tu Wi-Fi (escaneo activo de
  la subred + handshake de disponibilidad), recuerda los hosts y comprueba si responden al entrar,
  admite `host:port` manual, loopback para configuraciones en el mismo dispositivo, y opciones de
  conexión automática (último usado / LAN / mismo dispositivo).
- **Navegación estilo Discord** — desliza hacia la derecha desde el borde izquierdo para abrir la
  lista de chats agrupada por espacio de trabajo, hacia la izquierda para cerrarla, y desde el
  borde derecho hacia la izquierda para el panel de detalles de la sesión.
- **Experiencia de chat completa** — turnos en streaming con el razonamiento desplegable, markdown,
  tarjetas de herramienta de terminal/diff/lectura/búsqueda/web, panel de cola (editar / quitar /
  redirigir), paginación del historial, imágenes y archivos adjuntos.
- **Comandos de barra y habilidades** — el compositor coteja una línea que empieza por `/` con el
  catálogo de comandos de la propia sesión y la ejecuta a través de la pasarela de comandos del
  harness; lo que el catálogo no reclama se envía como prompt, que es la forma de invocar las
  habilidades.
- **Todo lo que hace la GUI** — objetivos (fases, rondas, pausar/reanudar/editar), modo plan y
  revisión del plan, aprobaciones de permisos, preguntas al usuario, panel de tareas pendientes,
  subagentes (catálogo, seguimientos, interrupción), trabajos en segundo plano, ejecuciones de
  flujos de trabajo, habilidades, selección de modelo, preajustes de agente, búsqueda en la sesión,
  registro de trayectoria, exportación de la sesión, valoración de mensajes.
- **Notificaciones** — turno completado, objetivo completado / bloqueado, una revisión o una
  pregunta esperándote; conexión en segundo plano mediante un servicio en primer plano.
- **Se ve como el harness** — exactamente los mismos tokens de diseño del DeepSeek Harness
  (colores, tipografía, radios, filas desplegables, shimmer, botones de tinta) con temas claro /
  oscuro / del sistema.
- **11 idiomas** — English, 中文, हिन्दी, Español, Français, العربية, বাংলা, Português, Русский,
  اردو, ไทย (con soporte RTL).

## Requisitos

- Android 8.0 o superior (minSdk 26).
- Un [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) en ejecución
  (probado con `0.1.3-alpha.1`). **0.10.0 necesita el harness 0.1.3** — esa versión
  dejó de registrar los fragmentos de una respuesta y los movió a un flujo en vivo que la
  app tiene que pedir, así que la app y el harness deben actualizarse a la vez: una app
  anterior nunca ve una respuesta escribiéndose en 0.1.3, y esta app no puede ejecutar
  comandos de barra en 0.1.2. Consulta [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Inicio rápido

1. Instala el último APK desde
   [Releases](https://github.com/sorsama/deepseek-harness-mobile/releases/latest).
2. Abre la aplicación y elige cómo conectar. No son variantes de un mismo ajuste: escoge el que
   coincida con lo que hayas configurado en el ordenador.

   **Relé** — cifrado, autenticado y funciona incluso fuera de tu Wi-Fi. Instala
   [`dsh-relay`](https://github.com/sorsama/deepseek-harness-relay) en el perfil web del harness:

   ```sh
   dsh plugin --profile web add dsh-relay
   dsh web
   ```

   Abre la URL que imprime **en ese mismo ordenador**, establece una contraseña y luego abre
   `/relay/pair`. En la aplicación: **Relé → Emparejar un relé**, y escanea el QR. Cuando todos
   los clientes que uses estén emparejados, desactiva `compat.addressGrants` en el relé: aquí
   nada lo necesita.

   **Red local** — sin configuración en el teléfono y sin autenticación alguna. Aplica el parche
   de LAN de un solo archivo de [`harness/README.md`](harness/README.md), reinicia `dsh web` y
   toca **Escanear red**. Solo en redes de confianza.

   **Detrás de tu propio proxy inverso HTTPS** — pega la dirección `https://` en el modo de red
   local. El proxy puede reenviar al loopback, así que el harness no necesita parche; pero cifra
   el enlace sin autenticar a nadie. Consulta [`harness/README.md`](harness/README.md).

   **USB / emulador** — `dsh web`, luego `adb reverse tcp:3080 tcp:3080`, y conéctate a
   `127.0.0.1:3080` en el modo de red local. No hace falta parche.
3. Elige una sesión, chatea y recibe una notificación cuando el harness termine.

Si falla un intento de conexión, la aplicación indica la causa; la página de
[solución de problemas](https://github.com/sorsama/deepseek-harness-mobile/wiki/Troubleshooting)
de la wiki está organizada por esa misma frase.

## Compatibilidad y seguridad

> **0.1.2:** Desde el harness 0.1.2 el harness autentica toda su API: pega una vez el enlace que imprime al arrancar cuando la app lo pida. Eso autentica el teléfono, pero no cifra la conexión, así que sigue siendo solo para redes de confianza.

- Consulta [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) para la matriz de versiones del harness
  y las superficies disponibles solo por loopback.
- **Lee primero [docs/SECURITY.md](docs/SECURITY.md).** El harness a secas no tiene autenticación,
  así que el modo de red local es solo para redes de confianza — por eso mismo la aplicación lo
  advierte en la pantalla de conexión. El modo relé añade una credencial real y un certificado
  fijado, pero autenticarse sigue otorgando el mismo poder que un shell en ese ordenador, porque
  es allí donde el agente ejecuta los comandos.

## Compilación

```sh
./gradlew :app:assembleDebug      # APK de depuración
./gradlew :app:assembleRelease    # APK de publicación (firmado si el entorno del keystore está definido)
```

La versión publicada procede de la etiqueta de git: el flujo de publicación exporta
`DSH_VERSION_NAME` a partir del nombre de la etiqueta y `versionCode` se deriva de ahí. Una
compilación local recurre al literal de `app/build.gradle.kts`.

Consulta [CONTRIBUTING.md](CONTRIBUTING.md) para el ciclo de desarrollo contra un harness real, la
distribución de módulos y el flujo de publicación.

## Repositorio

| Ruta | Qué contiene |
|---|---|
| `core/` | Núcleo del protocolo en JVM puro: DTO del protocolo, cliente RPC, enlaces WebSocket, bucle de reconexión, plegado de sesiones, clasificador de notificaciones |
| `app/` | Interfaz de Android: pantallas, descubrimiento/conexión, servicio en primer plano, notificaciones, i18n |
| `mock-harness/` | Simulación en Ktor del servidor `/api` del harness para las pruebas |
| `tools/capture/` | Graba tráfico real del harness como fixtures de conformidad |
| `harness/` | Parche complementario y guía para el modo LAN |
| — | El relé en sí vive en [sorsama/deepseek-harness-relay](https://github.com/sorsama/deepseek-harness-relay) |
| `docs/` | [Arquitectura](docs/ARCHITECTURE.md), [notas del protocolo](docs/PROTOCOL.md), [compatibilidad](docs/COMPATIBILITY.md), [seguridad](docs/SECURITY.md) |

## Licencia

[MIT](LICENSE). El material de terceros incluido se enumera en
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). El DeepSeek Harness y su marca son propiedad de
sus respectivos titulares; este proyecto es un control remoto independiente, hecho por la
comunidad.
