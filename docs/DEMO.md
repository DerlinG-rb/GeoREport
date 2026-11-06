# GeoReport: administración, institución simulada y seguimiento

Implementación sobre la aplicación existente: Kotlin, AppCompat/Material, vistas XML y vistas nativas, osmdroid, ubicación de Google Play, SQLite y preferencias privadas. No se sustituyó el proyecto. No hay backend principal ni sincronización de cuentas entre dispositivos. El administrador revisa los reportes del **mismo dispositivo/instalación**. La institución es un servicio separado de demostración en Python 3, sin dependencias externas.

## Ejecutar Android

Requisitos: JDK 21, Android SDK 37, herramientas de plataforma y las dependencias Gradle del proyecto. `local.properties` debe apuntar a su SDK.

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
./gradlew :app:installDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. El registro público siempre crea CIUDADANO. La sesión consulta el registro del usuario y no recibe el rol por Intent. Login/restauración dirige al administrador al panel, al ciudadano al formulario. Las actividades protegidas verifican sesión al reanudarse; las operaciones vuelven a autorizar en `ReportService`. Login y logout limpian la pila.

## Cuentas de desarrollo

Instale y abra primero la aplicación debug. Conecte el dispositivo con depuración USB y añada `platform-tools` al PATH. El comando solo funciona donde `adb run-as` esté autorizado (APK debug). No hay credenciales incluidas en el APK ni puerta administrativa exportada.

```bash
python3 scripts/create_dev_user.py --email admin@demo.test --role ADMINISTRADOR
python3 scripts/create_dev_user.py --email ciudadano@demo.test --role CIUDADANO
python3 scripts/create_dev_user.py --email otro@demo.test --role CIUDADANO
```

Se solicita la contraseña por entrada oculta y se exige confirmación. Use `--serial SERIAL` si hay varios dispositivos. El comando detiene la app, conserva usuarios existentes y escribe atómicamente el XML; rechaza sobrescribir cuentas. Las contraseñas se guardan con sal aleatoria y el hash iterado compatible con el proyecto. El registro público continúa disponible. Usuarios antiguos sin rol se migran a CIUDADANO al autenticar, sin cambiar su contraseña.

**Límite del prototipo:** el registro autorizado de usuarios sigue siendo almacenamiento local privado. Se consulta en cada autorización; no se usa un rol de sesión almacenado aparte. No constituye una barrera segura frente a root, modificación del APK o manipulación del dispositivo. Para producción se debe trasladar `ReportService` y la identidad al servidor, verificar propiedad/rol allí y reemplazar la autenticación local. Las claves de demostración no sustituyen identidad individual en servidor.

## API y portal

Desde la raíz, genere claves distintas en la sesión de terminal (no se guardan en el repositorio):

```bash
export GEOREPORT_INTEGRATION_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
export GEOREPORT_PORTAL_TOKENS="$(python3 -c 'import json,secrets; print(json.dumps({k:secrets.token_urlsafe(32) for k in ["obras-demo","ambiente-demo"]}))')"
python3 institution/server.py --host 0.0.0.0 --port 8080
```

En otra terminal puede iniciar el servicio solamente si conserva/exporta las mismas variables. No necesita instalación de paquetes. Por defecto, sin `--host`, escucha únicamente en `127.0.0.1`. Use `0.0.0.0` solo para pruebas en una red de confianza. El servidor de biblioteca estándar es de demostración, no un servidor de producción. El archivo `institution/received.sqlite3` mantiene las recepciones después de reiniciar; no lo elimine. Puede cambiarlo con `--database`.

Abra `http://localhost:8080` en el navegador. Consulte las variables **en su terminal privada** para copiar la clave correspondiente. Introduzca la clave de `obras-demo` o `ambiente-demo` en el portal; la clave determina la institución seleccionada y el servidor restringe listado y detalle a esa institución. Para cambiar, cierre sesión y use la otra clave. La clave de integración se introduce en el diálogo administrativo Android al enviar. No se persiste en preferencias ni se incorpora al APK. El portal mantiene su clave solo en memoria, limpia las evidencias al salir, y no usa almacenamiento local ni URLs con credenciales.

### Conectividad por entorno

- Emulador Android estándar: debug utiliza `http://10.0.2.2:8080` por defecto.
- Teléfono en la misma red: averigüe la IP LAN del ordenador y compile con `./gradlew :app:installDebug -PinstitutionUrl=http://192.168.1.50:8080` (sustituya la IP). Inicie el servidor con `--host 0.0.0.0` y permita el puerto en el firewall de la red privada.
- USB: `adb reverse tcp:8080 tcp:8080` y compile con `-PinstitutionUrl=http://127.0.0.1:8080`.
- Release: bloquea tráfico sin cifrar y usa `-PinstitutionReleaseUrl=https://SU_SERVICIO`. El valor por defecto es un dominio inválido deliberadamente. No hay despliegue oficial ni credenciales de integración de producción.

Solo el manifest `src/debug` permite HTTP. No se añadieron excepciones HTTP al release. La URL está centralizada en BuildConfig. El envío sale de Android porque no hay backend principal; al incorporar uno, traslade allí la clave y la comunicación institucional.

### Contrato HTTP

Todas las rutas de datos exigen `Authorization: Bearer CLAVE`. HTML/JS/CSS de acceso son públicos; las evidencias no.

| Método / ruta | Permiso | Resultado |
| --- | --- | --- |
| GET `/institutions` | Integración o institución | Todas las instituciones compatibles con la identidad; incluye categorías |
| POST `/reports` | Integración | Comprobante `{receipt_id, received_at}`; 201 nuevo, 200 reintento |
| GET `/reports` | Institución | Sus recepciones, sin fotografía en el listado (hasta 500) |
| GET `/reports/{receipt_id}` | Institución propietaria | Detalle y copia JPEG, 404 para otra institución |

POST incluye exclusivamente `report_id`, `category`, `description`, `location: {latitude, longitude}`, `created_at` ISO con zona, `photo` como `data:image/jpeg;base64,...` e `institution`. Requiere cabecera `Idempotency-Key: report_id:institution`. Valida categoría/destinatario, coordenadas finitas/rangos, campos, fecha y tamaño/formato JPEG. Los nombres/contactos/campos extra se rechazan. La app exporta una copia JPEG limitada y sin EXIF; conserva la fotografía original privada.

SQLite impone unicidad `(report_id, institution)` dentro de una transacción. Un reintento idéntico devuelve exactamente el comprobante original; contenido distinto devuelve 409. La app fija destinatario al primer intento: ante resultado incierto reintenta con ese destinatario para evitar doble canalización. El estado sigue APROBADO si falla o se pierde la respuesta; CANALIZADO y comprobante se escriben juntos después de recibir confirmación. Cada intento registra fecha, destinatario y resultado/error. No se registran claves ni fotografías en logs.

La API institucional valida el token de integración; no puede comprobar el estado de la base privada de Android. La autorización humana y el estado pertenecen al prototipo local. No hay endpoints públicos que permitan a ciudadanos cambiar estados.

## Recorrido de demostración

1. Entre como ciudadano. En el formulario existente: fotografía; categoría y descripción; ubicación GPS automática (o ajuste opcional tocando/arrastrando en el mapa). Envíe desde la misma pantalla, sin agregar pasos RA.
2. Use evidencia de una incidencia real, una descripción correcta y su ubicación verificada. Los datos sintéticos se reservan para pruebas automatizadas aisladas; no los introduzca como datos de ejecución.
3. Revise Mi historial y cierre sesión. Entre como administrador y vea el panel directamente.
4. Abra el reporte, pulse Iniciar revisión y luego Aprobar; alternativamente Rechazar exige motivo y confirmación.
5. En un APROBADO, pulse Enviar, introduzca la clave de integración, elija una institución compatible y confirme. Abra el portal con la clave de esa institución: aparecerán copia de imagen, ubicación, descripción y comprobante.
6. Detenga el servicio y pruebe con otro aprobado: debe conservar APROBADO, registrar el error y permitir reintento. Reinicie con la misma base. Los reintentos de recepción están cubiertos también por pruebas HTTP concurrentes y de timeout.
7. Un CANALIZADO puede marcarse RESUELTO. Vuelva como ciudadano: detalle y línea de tiempo incluyen estados, motivo, institución y comprobante. Otro ciudadano no debe poder leer ese historial.

Transiciones: ENVIADO → EN_VALIDACION → APROBADO → CANALIZADO → RESUELTO; EN_VALIDACION → RECHAZADO. No hay validación con IA ni acciones administrativas en la interfaz ciudadana.

## Persistencia y migración

SQLite versión 2 añade un identificador global estable por reporte, `workflow`, `events` y `attempts`. La actualización conserva payloads, tickets y rutas fotográficas. Los tickets visuales antiguos no se usan como claves de idempotencia porque podían repetirse. Los reportes antiguos empiezan ENVIADO; el evento inicial se reconstruye de autor y fecha de creación. Los cambios siguientes se registran transaccionalmente con estado previo/nuevo, fecha, responsable y observación. La migración previa desde preferencias continúa funcionando. No se borran datos ni se hace migración destructiva.

## RA geolocalizada con reportes reales

La RA utiliza exclusivamente reportes reales locales APROBADOS o CANALIZADOS autorizados por un administrador. No contiene ejemplos ni respuestas de sustitución. Radio inicial 500 m, selector 100/500/1000 m y máximo 20 resultados. SQLite v3 añade índice geográfico y visibilidad desactivada por defecto. No hay sincronización entre dispositivos.

Consulte [RA_REAL.md](RA_REAL.md) para arquitectura, autorización de reportes, privacidad, limitaciones y prueba física; [RA_VERIFICATION.md](RA_VERIFICATION.md) para resultados de verificación.

## Verificación

```bash
python3 -m unittest institution.test_server -v
node --check institution/portal/portal.js
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:assembleDebugAndroidTest
# Requiere emulador/dispositivo conectado:
./gradlew :app:connectedDebugAndroidTest
```

- Pruebas JVM: matriz completa de estados, rechazo obligatorio, comprobante requerido, denegación ciudadana, propiedad, distancia/rumbo y proyección/rotación/declinación.
- Pruebas HTTP: autorización, aislamiento institucional, campos, conflicto de contenido, concurrencia, persistencia y timeout real después de confirmar recepción en SQLite.
- Pruebas instrumentadas: almacenamiento existente y nuevos casos de migración v1→v2, sesión/roles/propiedad, trazabilidad, fallo de envío conservando APROBADO y comprobante después de reabrir SQLite. Compilarlas no equivale a ejecutarlas.
- Prueba física pendiente: captura/importación, GPS y ajuste de mapa, orientación de marcadores con giros y ambas orientaciones de pantalla, calibración/precisión, permisos denegados, pausa/reanudación, y circuito visual completo Android→portal.

Consulte `docs/VERIFICATION.md` para los resultados concretos de esta entrega.
