# Almacenamiento local

- Reportes: SQLite privado (`georeport_reports.db`). Cada inserción es durable e incremental; las fotos se consultan por un índice. La primera apertura migra el JSON anterior en una transacción. Si el JSON está corrupto, se conserva y el guardado falla; no se reemplaza por una colección vacía.
- Evidencias: `filesDir/report_photos`. Se importan mediante el selector del sistema, sin permisos generales de almacenamiento. Se conservan los originales, incluidos sus metadatos. Límite de 20 MiB y 100 megapíxeles por imagen; la vista previa se decodifica con muestreo hasta 1280 píxeles de lado mayor. Copia, lectura y guardado de reportes se realizan fuera del hilo de interfaz.
- Cámara: `cacheDir/camera_photos`. Es el único directorio expuesto por FileProvider, que no está exportado. Las capturas temporales se eliminan tras importarlas o cancelar. Los archivos parciales también se eliminan si la importación falla.
- Al reemplazar o quitar una foto se consulta primero si pertenece a un reporte. Los borradores y capturas sin uso de más de 24 horas se limpian al abrir la pantalla; se protege la selección restaurada. Los reportes guardados no se purgan automáticamente.
- Mapas: caché interna descartable, con límite configurado de 64 MiB y objetivo de reducción de 48 MiB. No se guardan evidencias en almacenamiento externo compartido.
- Copias automáticas: desactivadas y excluidas también de transferencia entre dispositivos para impedir que se copien usuarios, sesiones, coordenadas y fotos. Los datos locales se pierden al desinstalar o borrar los datos de la aplicación. No hay sincronización remota ni exportación implementada.

La protección de los archivos se basa en el sandbox privado de Android; no se añade cifrado propio a la base de datos ni a las fotos.

## Verificación

`./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`

Con un dispositivo de pruebas conectado: `./gradlew.bat :app:connectedDebugAndroidTest`. Las pruebas de almacenamiento usan bases aisladas y archivos temporales. Verificar además cámara cancelada, selección desde un proveedor externo, rotación, falta de espacio y conservación de la foto después de guardar y empezar otro reporte.
