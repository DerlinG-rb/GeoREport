# Almacenamiento local

- Reportes: SQLite privado (`georeport_reports.db`). Cada inserción es durable e incremental; las fotos se consultan por un índice. La primera apertura migra el JSON anterior en una transacción. Si el JSON está corrupto, se conserva y el guardado falla; no se reemplaza por una colección vacía.
- Evidencias: `filesDir/report_photos`. Se importan mediante el selector del sistema, sin permisos generales de almacenamiento. Se conservan los originales, incluidos sus metadatos. Límite de 20 MiB y 100 megapíxeles por imagen; la vista previa se decodifica con muestreo hasta 1280 píxeles de lado mayor y aplica la orientación EXIF sin modificar el original. La copia se escribe primero como `.pending` y se renombra al terminar con la extensión detectada del contenido, no la proporcionada por el usuario. Copia, lectura y guardado de reportes se realizan fuera del hilo de interfaz.
- Cámara: `cacheDir/camera_photos`. Es el único directorio expuesto por FileProvider, que no está exportado. Las capturas temporales se eliminan tras importarlas o cancelar. Los archivos parciales también se eliminan si la importación falla.
- Al reemplazar o quitar una foto se consulta primero si pertenece a un reporte. Los borradores y capturas sin uso de más de 24 horas se limpian al abrir la pantalla; se protege la selección restaurada. Los reportes guardados no se purgan automáticamente.
- Mapas: caché interna descartable, con límite configurado de 64 MiB y objetivo de reducción de 48 MiB. No se guardan evidencias en almacenamiento externo compartido.
- Copias automáticas: desactivadas y excluidas también de transferencia entre dispositivos para impedir que se copien usuarios, sesiones, coordenadas y fotos. Los datos locales se pierden al desinstalar o borrar los datos de la aplicación. No hay sincronización remota ni exportación implementada.

La protección de los archivos se basa en el sandbox privado de Android; no se añade cifrado propio a la base de datos ni a las fotos.

## Verificación

`./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`

Con un dispositivo de pruebas conectado: `./gradlew.bat :app:connectedDebugAndroidTest`. Las pruebas de almacenamiento usan bases aisladas y archivos temporales. Verificar además cámara cancelada, selección desde un proveedor externo, rotación, falta de espacio y conservación de la foto después de guardar y empezar otro reporte.

## Correcciones de Home

- La vista previa inicializa `inSampleSize = 1` antes de calcular el muestreo. El valor predeterminado cero provocaba una división entre cero tanto para cámara como para galería.
- OSM usa HTTPS, identificación explícita `GeoREport/1.0 (com.example.georeport)` después de cargar preferencias, directorios de caché creados antes de inflar el mapa y zoom limitado al rango de teselas. Se mantienen dos descargas simultáneas como máximo, sin descargas masivas ni preventivas. La atribución queda visible debajo del mapa. Véase la [política del proveedor](https://operations.osmfoundation.org/policies/tiles/).
- Las teselas necesitan conexión a Internet la primera vez; disponer de coordenadas GPS no garantiza conexión. La selección de un punto funciona independientemente de la descarga de teselas.
- La prueba instrumental de foto pequeña verifica orientación, extensión y conservación exacta de bytes después de eliminar la captura temporal; la prueba de foto grande cubre el fallo de muestreo y el límite de la vista previa.
