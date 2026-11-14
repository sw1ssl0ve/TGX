<p align="center">
  <a href="README.md">🇷🇺</a> ·
  <a href="README.en.md">🇬🇧</a> ·
  <a href="README.zh-CN.md">🇨🇳</a> ·
  <a href="README.de.md">🇩🇪</a> ·
  <a href="README.fr.md">🇫🇷</a> ·
  <a href="README.es.md"><strong>🇪🇸</strong></a> ·
  <a href="README.it.md">🇮🇹</a>
</p>

<h1 align="center">TGX</h1>
<p align="center">Un proxy local para Telegram en Android</p>

<p align="center">
  <img src="assets/giftgx.gif" width="85%" alt="Animación del logotipo de TGX" />
</p>

## ¿Qué es TGX?

TGX es una aplicación para Android que ejecuta un proxy MTProto local para Telegram en el dispositivo. Permite que Telegram use un proxy específico sin enviar todo el tráfico de Internet del teléfono a través de TGX. TGX no es una VPN ni cambia la ruta de red de otras aplicaciones. La disponibilidad de Telegram depende de la red y de la ruta utilizada; TGX no garantiza eludir bloqueos, ofrecer anonimato ni funcionar en todas las redes.

## Apoyar el proyecto

El apoyo es voluntario y ayuda al desarrollo y mantenimiento de TGX.

- **BTC:** `bc1q7pzjs4wuvffvpr69mcw6ln90gqtxptwmk7jvx5`
- **ETH:** `0x20C407bFF67e3ACbDc916d91b11c3E7A63342e7b`
- **USDT (TRON):** `TKRdMNhunBajoujiWd9aKXpiVH2GcJeYAg`

## Ilustración de TGX

<p align="center">
  <img src="assets/TGX.png" width="90%" alt="Ilustración conceptual de las rutas de TGX" />
</p>

Esta imagen proporcionada es una ilustración conceptual, no un esquema exacto del código actual. Por ejemplo, la aplicación no configura ni afirma admitir una ruta de Cloudflare Worker.

## Cómo funciona

1. TGX inicia un servicio en primer plano de Android y escucha en la dirección local `127.0.0.1:1443`.
2. Telegram se conecta a esa dirección como proxy MTProto. TGX inspecciona los datos MTProto iniciales y selecciona una ruta hacia un centro de datos de Telegram.
3. TGX usa TLS/WebSocket para el transporte: conexiones precalentadas, puntos WebSocket de Telegram y, después, dominios proxy de Cloudflare. Para los centros de datos configurados hay una ruta TCP directa si no se puede usar una ruta WebSocket.
4. TGX obtiene la lista de dominios de Cloudflare de [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), la valida y la actualiza aproximadamente cada hora. Si falla la descarga, conserva la última copia local válida.
5. El servicio supervisa el estado de la red de Android y administra el servidor local y las conexiones ascendentes. Un cambio de red o un fallo de ruta puede interrumpir una conexión; no se garantiza el funcionamiento.

TGX solo gestiona las conexiones de Telegram dirigidas a su proxy local. No es un túnel VPN para todo el dispositivo.

## Instalación y primeros pasos

### 1. Descargar el APK

Descarga la aplicación únicamente desde la página oficial de [GitHub Releases](https://github.com/sw1ssl0ve/TGX/releases). Al preparar este README, el repositorio aún no tenía Releases publicados con un APK. No instales compilaciones de sitios de terceros.

### 2. Instalar la aplicación

Si se publica un APK, ábrelo en Android y confirma la instalación. Si Android la bloquea, permite la instalación de aplicaciones desde ese origen en los ajustes del dispositivo. Los nombres de los menús dependen de la versión de Android y del fabricante.

### 3. Permisos

Android puede solicitar permiso para enviar notificaciones. Permite que TGX muestre la notificación del servicio en primer plano mientras funciona el proxy local; la notificación también incluye una acción para detenerlo. Si deniegas el permiso, TGX igualmente solicita iniciar el servicio, aunque Android puede ocultar la notificación. No hace falta conceder manualmente el acceso a Internet: el manifiesto de la aplicación declara el permiso `INTERNET`.

### 4. Primera conexión

Abre TGX e inicia el proxy. Tras el primer inicio correcto, TGX ofrecerá una vez abrir Telegram. Confirma la acción en TGX y luego añade o activa en Telegram el proxy MTProto propuesto. TGX abre un enlace `tg://proxy` con `127.0.0.1:1443` y tu Secret. Si Telegram no está instalado o no puede abrir el enlace, TGX mostrará un aviso; puedes configurar el proxy manualmente en Telegram con la dirección local, el puerto y el Secret de los ajustes de TGX.

### 5. Comprobar que funciona

En Telegram, comprueba la conexión y la carga de la lista de chats y conversaciones; después, envía y recibe un mensaje de prueba. También puedes probar a abrir una imagen u otro archivo multimedia. El estado que muestra TGX por sí solo no confirma que Telegram esté transfiriendo datos correctamente.

### 6. Si Telegram no se conecta

Comprueba el Secret en los ajustes de TGX: debe tener 32 caracteres hexadecimales. Puedes generar uno nuevo y pulsar **Guardar y abrir Telegram**. TGX lo guarda, reinicia el servicio si ya está funcionando o lo inicia, y después abre Telegram con el enlace nuevo. Confirma el proxy en Telegram y selecciona la entrada actual. TGX no puede leer ni editar la lista de proxies guardada en Telegram. Si Telegram conserva una entrada antigua junto a la nueva, selecciona el proxy actual y, si hace falta, elimina manualmente la entrada obsoleta. Si Telegram no se abrió, comprueba que esté instalado y añade el proxy manualmente.

## Historia del proyecto y agradecimientos

TGX se basa en ideas y enfoques técnicos compatibles del proyecto [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), como el transporte de MTProto por WebSocket y el enrutamiento mediante dominios proxy de Cloudflare. TGX es una aplicación Android independiente con su propia implementación en Kotlin, interfaz y gestión del ciclo de vida del servicio Android; no es un producto oficial de Flowseal.

Gracias a Flowseal y a quienes contribuyen al proyecto por publicar este trabajo. El repositorio tg-ws-proxy especifica la licencia MIT ([texto de la licencia](https://github.com/Flowseal/tg-ws-proxy/blob/main/LICENSE)). Actualmente el repositorio TGX no contiene un archivo `LICENSE`; por tanto, este README no asigna una licencia a TGX ni concede permiso para reutilizar su código. El propietario del repositorio debe decidir por separado la licencia de TGX y si los requisitos de MIT se aplican a material técnico derivado.

## Compartir el proyecto

Si TGX te resulta útil, deja una ⭐ en el repositorio, compártelo con tus amigos o cuéntaselo a comunidades de Telegram a las que pueda servirles: [github.com/sw1ssl0ve/TGX](https://github.com/sw1ssl0ve/TGX).
