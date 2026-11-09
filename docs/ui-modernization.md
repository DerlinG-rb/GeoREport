# Mejoras de interfaz de GeoReport

## Alcance

Implementación Android nativa en Kotlin/XML con la skill UI/UX Pro Max. Paleta azul institucional, superficies claras, tipografía del sistema sin descargas, botones con jerarquía visual y objetivos táctiles de al menos 48 dp. Se conserva el tema claro también cuando Android está en modo nocturno.

La migración utiliza la dependencia Material existente y los [temas oficiales de Material 3](https://github.com/material-components/material-components-android/blob/master/docs/getting-started.md). No se añadieron dependencias ni se modificaron los servicios, políticas, modelos o almacenamiento.

## Pantallas y archivos

Todos los archivos de interfaz Kotlin están bajo `app/src/main/java/com/example/georeport/ui/`.

| Pantalla / componente | Archivos | Cambios |
| --- | --- | --- |
| Inicio de sesión | `auth/LoginActivity.kt`, `app/src/main/res/layout/activity_login.xml` | Tarjeta Material 3, enlaces accesibles y adaptables, confirmación del registro en la pantalla de destino, eliminación del aviso redundante de acceso correcto. |
| Registro | `auth/RegisterActivity.kt`, `app/src/main/res/layout/activity_register.xml` | Formulario y botones uniformes, navegación de 48 dp, errores con Snackbar. |
| Nuevo reporte / mapa | `home/HomeActivity.kt`, `app/src/main/res/layout/activity_home.xml` | Tarjetas, botones secundarios, campos de 16 sp, notificaciones consistentes y eliminación del aviso GPS redundante. Corrección de la llave faltante en el `companion object cod` preexistente. |
| Historial, administración y detalle | `reports/ReportsActivity.kt` | Barra de navegación con menú, tarjetas de resultados, contador, estado vacío, filtros locales, búsqueda con espera de 250 ms, carga progresiva de 20 resultados, formularios de diálogo Material, confirmación para cerrar sesión, navegación de detalle al listado. |
| Filtros de presentación | `reports/ReportFilters.kt` (nuevo) | Combina texto, estado, categoría y orden cronológico. Búsqueda sin distinción de mayúsculas ni acentos, por palabras, descripción, ubicación, categoría, estado, número y referencia. Opera únicamente sobre los resultados autorizados del servicio existente. |
| Incidencias cercanas / RA | `ar/NearbyActivity.kt` | Barra de navegación, chips de radio con área táctil accesible, controles secundarios y vista previa más compacta en horizontal. Conserva los límites, permisos, sensores y consulta existentes. |
| Componentes compartidos | `common/VisualStyle.kt`, `common/Feedback.kt` (nuevo), `common/ScreenLayout.kt` | Botones secundarios reutilizables, Snackbar con cierre y texto multilínea e iconos oscuros en las barras del sistema para el tema claro. |
| Tema | `app/src/main/res/values/themes.xml`, `values-night/themes.xml`, `values/styles.xml`, `values/colors.xml` | Material 3, paleta y estilos compartidos. |
| Pruebas de filtros | `app/src/test/java/com/example/georeport/ui/reports/ReportFiltersTest.kt` (nuevo) | Acentos, mayúsculas, palabras múltiples, filtros combinados, ambas ordenaciones, referencias, lista vacía y conservación de la lista original. |
| Pruebas de interfaz | `app/src/androidTest/java/com/example/georeport/UiLayoutInstrumentedTest.kt` (nuevo) | Inflado y medición de XML reales a 320 dp, 360 dp con texto al 200 % y 800 dp; tamaños táctiles; imágenes para revisión. |

## Verificación

Comando Android equivalente a análisis y pruebas Flutter, con compilación e instrumentación adicionales:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --max-workers=2 --console=plain
```

Los reportes se generan en `app/build/reports/`. Las pruebas de geometría y flujo existentes permanecen sin cambios. La prueba de layouts no verifica por sí sola cámara física, precisión GPS ni servicios externos.

Resultados del 9 de noviembre de 2026:

- Compilación debug correcta; APK en `app/build/outputs/apk/debug/app-debug.apk`.
- 14 pruebas unitarias correctas, incluidas 4 pruebas nuevas de filtros.
- 14 pruebas instrumentadas correctas en Pixel 4 / Android 10, repetidas sobre la compilación final mediante `adb shell am instrument -w com.example.georeport.test/androidx.test.runner.AndroidJUnitRunner`.
- Android Lint: 0 errores y 78 advertencias (incluye recursos sin uso, textos sin extraer, versiones de dependencias y observaciones del código existente).
- `git diff --check` correcto. Sin cambios en `data/`, `domain/` ni dependencias.
- Revisadas las capturas de inicio de sesión, formulario de reporte y registro con texto grande. Las imágenes de layouts aislados son auxiliares; la pantalla de inicio de sesión también se verificó ejecutando la actividad en el emulador.
