# Incidencias reales cercanas en RA

## Integración y fuente

GeoReport utiliza Kotlin, AppCompat/Material, XML y vistas nativas. La sesión y los roles CIUDADANO/ADMINISTRADOR están en preferencias privadas (`UserStore`). `ReportService` autoriza las operaciones; `ReportStore` conserva reportes en SQLite `georeport_reports.db`. Las fotos originales permanecen en archivos privados. La cámara de RA utiliza Camera2; la orientación, `TYPE_ROTATION_VECTOR`; la ubicación, los proveedores GPS/red de `LocationManager` ya presentes en la implementación anterior. El formulario conserva Google Play Fused Location y osmdroid. No se añadieron dependencias, APIs de pago ni servicios externos.

No hay backend de reportes compartidos ni sincronización de cuentas. La RA consulta **exclusivamente reportes reales de esta instalación**. Un reporte creado en un teléfono no aparece en otro ni en el emulador. La API institucional existente es independiente y no se utiliza como fuente de RA. Para consultar entre dispositivos faltaría un backend común con identidad, permisos y sincronización: esta entrega no lo sustituye con datos de ejemplo.

SQLite v3 añade `report_locations`, un índice de coordenadas y `shared_visibility`. Se indexan las coordenadas existentes válidas sin modificar las fotografías, payloads, identificadores o estados. La migración no crea autorizaciones. Los registros antiguos carecen de confirmación de ubicación y se etiquetan como aproximados. La migración desde preferencias y v1 sigue disponible. Nuevos reportes e índice geográfico se guardan en una transacción.

## Autorización y privacidad

`ReportService.nearby`, `nearbyDetail` y `sharedDetail` exigen sesión. Cerca de ti combina dos fuentes: reportes propios APROBADOS o CANALIZADOS con coordenadas válidas, y reportes compartidos mediante autorización explícita. Los propios se filtran por el correo de la sesión y no necesitan publicación adicional. Se eliminan duplicados y se ordenan hasta 20 resultados por distancia dentro del radio. La interfaz diferencia «Tu reporte» y «Compartida».

La consulta SQL compartida mantiene `enabled=1`, descripción pública no vacía y estado APROBADO o CANALIZADO. Su DTO no contiene autor, correo, teléfono, fotografía ni descripción privada. La proyección de un reporte propio puede incluir su descripción original, exclusivamente para su propietario. `nearbyDetail` vuelve a comprobar propiedad y estado antes de abrirla; para reportes ajenos utiliza la consulta pública. `sharedDetail` conserva su comportamiento exclusivamente público.

Solo `setSharedVisibility`, autorizado como ADMINISTRADOR, permite compartir con otras cuentas. El administrador escribe y revisa un resumen público independiente; no se copia automáticamente la descripción privada. Debe excluir nombres, correos, teléfonos y cualquier dato identificable. Aprobar permite consultar el reporte al propietario en RA; publicar permite consultarlo a otras cuentas de la misma instalación. El rol de administrador no concede acceso a resúmenes privados ajenos mediante Cerca de ti; la gestión privada permanece en su panel.

La barrera de autorización reside en el servicio local y la consulta SQLite dentro del sandbox Android, no solo en la interfaz. Como la autenticación ya existente es local, no ofrece protección frente a root, modificación del APK o manipulación directa del almacenamiento del dispositivo. No se presenta como autorización de servidor.

### Autorizar un reporte existente

1. En la **misma instalación**, iniciar sesión como administrador y abrir el reporte.
2. Si está ENVIADO, pulsar **Iniciar revisión**, revisar su contenido y **Aprobar reporte**. No se puede compartir un pendiente, rechazado o resuelto.
3. En APROBADO o CANALIZADO, pulsar **Autorizar consulta compartida en RA**.
4. Escribir un resumen público sin datos personales y pulsar **Autorizar**. Se requieren coordenadas válidas.
5. Abrir **Ver incidencias cercanas en RA**, disponible en el formulario y el panel/historial, y pulsar Actualizar si ya estaba abierta.
6. Para retirar el reporte, pulsar **Retirar autorización de RA**. Resolverlo también lo excluye de las incidencias activas aunque conserve el registro de autorización.

La lista se consulta al entrar/reanudar, cambiar radio, pulsar Actualizar, desplazarse al menos 25 m o cada 30 s. Un cambio de estado/autorización se refleja en la siguiente consulta; al tocar un marcador se verifica inmediatamente. No hay caché persistente de RA: la instantánea en memoria indica la hora de lectura local, se descarta al pausar y se elimina ante error. Una consulta fallida muestra un error y reintento; nunca retorna ejemplos.

## Ubicación y consulta

Radio inicial 500 m; selector 100, 500 y 1000 m. Consulta por rectángulo geográfico e índice `(latitude,longitude)`, incluyendo cruce del antimeridiano y polos. Sobre esos candidatos calcula distancia Haversine, filtra el círculo exacto, ordena y limita a 20. No carga todos los payloads ni fotos de la base; SQLite no tiene una extensión espacial en este proyecto, por lo que la ordenación geodésica final se realiza en Kotlin.

Solicita cámara y ubicación al abrir por primera vez. Requiere coordenadas reales finitas, lectura de no más de 60 s y precisión estimada de 50 m o menos. Ante GPS apagado, permiso denegado o precisión insuficiente, informa el problema, vacía los resultados y ofrece permisos/ajustes/reintento. La ubicación aproximada concedida por Android puede no superar el umbral; no se inventan distancias.

El formulario permite tocar o arrastrar el marcador. En esta versión sus reportes se guardan como ubicación aproximada (`locationConfirmed=false`), incluso si se seleccionó el punto manualmente. El almacenamiento admite distinguir puntos confirmados, pero el formulario aún no registra esa confirmación. La cámara no determina por sí sola la posición del objeto fotografiado.

## Proyección y límites

La cámara trasera transmite imagen real. La matriz de rotación convierte la dirección geográfica a ejes del dispositivo; la proyección considera inclinación, rotación de pantalla y declinación magnética (`GeomagneticField`). Se suavizan cuaterniones con continuidad de signo, evitando discontinuidad al cruzar el norte. Una orientación ausente, de baja precisión o con más de 2 s no dibuja marcadores; la lista usa los mismos registros reales si existe ubicación válida.

Se estima el campo de visión a partir de distancia focal y tamaño físico del sensor, con recorte por relación de aspecto del flujo y recorte central de la vista. Se desactiva estabilización de vídeo/óptica y rotación/recorte automático cuando la API lo admite para reducir diferencias. Si no hay metadatos ópticos, se asumen 60° horizontales en el flujo nativo y se **informa** en la pantalla. La distorsión de lente, selección interna de cámaras y calibración de fabricante pueden producir diferencias; no es un registro óptico calibrado.

No existe altura del incidente. Los marcadores representan un rumbo horizontal aproximado, no un punto detectado en la imagen, profundidad ni elevación medida. La altura utilizada por el modelo magnético, si existe, es la del teléfono; si falta, su referencia es nivel del mar y solo afecta esa aproximación magnética. No se asigna una elevación al incidente. Reportes a menos de 1 m se mantienen en la lista pero no se les inventa un rumbo visible. Los marcadores fuera del encuadre se ocultan; en solapamientos se prioriza el más cercano, manteniendo todos los resultados en la lista accesible. Cada marcador incluye icono, categoría, distancia y estado en texto.

Cámara, sesión, sensores, ubicación, consultas y temporizador se liberan al pausar. No hay consultas de base ni de red en los eventos de orientación. El modo RA es opcional e independiente del envío de reportes.

Desde el formulario, **Ver incidencias cercanas en RA** abre la cámara sin validar ni enviar el reporte en preparación. La vista de cámara precede a los controles secundarios. **Mi historial y seguimiento** abre la consulta de reportes por separado.

Referencias primarias utilizadas: [SensorManager](https://developer.android.com/reference/android/hardware/SensorManager), [CameraCharacteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics), [Camera2 preview](https://developer.android.com/media/camera/camera2/camera-preview).

## Prueba física con dos ubicaciones conocidas

1. Elegir dos incidencias **reales**, en lugares conocidos A y B, con acceso seguro. No crear incidencias ficticias en la base de ejecución. Medir/consultar sus coordenadas verificables; por ejemplo, elegir A a menos de 100 m del punto de observación y B entre 100 y 500 m. No se proporcionan coordenadas inventadas.
2. En la misma instalación, reportar esas incidencias como ciudadano, usar evidencia real y confirmar sus puntos en el mapa. Como administrador, aprobar ambas y autorizar resúmenes públicos.
3. Situarse en el punto de observación, al aire libre, conceder permisos y esperar precisión ≤50 m. En radio 100 m debe aparecer A y no B; en 500 m, ambas, ordenadas por distancia. Tener en cuenta el error GPS al elegir puntos lejos del borde de cada radio.
4. Apuntar hacia A y girar lentamente hacia B: los marcadores deben desplazarse y salir del encuadre. Repetir alrededor del norte, con inclinación y en orientación horizontal. Comparar direcciones con referencias conocidas, sin usar la RA para navegación de precisión.
5. Tocar cada marcador y revisar resumen público, fecha, estado y distancia. Con una cuenta distinta al autor, confirmar que no se ofrecen fotos ni datos privados. Retirar autorización y actualizar: desaparece. Marcar RESUELTO y comprobar lo mismo.
6. Desplazarse más de 25 m, cambiar radios, pausar/reanudar, apagar ubicación, denegar cámara y denegar ubicación. Sin cámara/orientación debe funcionar la lista cuando hay GPS válido. Sin resultados debe verse “No hay incidencias disponibles cerca de tu ubicación”.

Las pruebas automatizadas usan datos sintéticos solo en bases aisladas que se eliminan al terminar. Las pruebas instrumentadas de SQLite no equivalen a validar GPS, cámara, calibración, movimiento ni alineación física de marcadores. Ver `RA_VERIFICATION.md` para resultados ejecutados y pendientes.
