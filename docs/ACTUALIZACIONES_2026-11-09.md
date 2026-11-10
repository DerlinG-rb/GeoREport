# Actualizaciones de GeoReport — 9 de noviembre de 2026

## Resumen

Esta entrega corrige la presentación de formularios, reorganiza Cerca de ti, ajusta la orientación de la cámara y renueva el detalle y la administración de incidencias. También permite al ciudadano consultar sus propios reportes aprobados en realidad aumentada sin una autorización adicional de publicación.

## Formularios e inicio

- Una única etiqueta por campo, definida en TextInputLayout. Las indicaciones de dirección y descripción pasan a helperText para no cruzarse con el contenido escrito.
- Etiquetas contraídas, textos más cortos, altura mínima de 56dp y padding vertical de 16dp. La altura sigue siendo adaptable al contenido.
- Selector de categoría con MaterialAutoCompleteTextView.
- Espaciado consistente en inicio, acceso y registro; texto junto a iconos con ancho distribuido mediante pesos.
- Cabecera del formulario con mayor jerarquía e instrucciones propias del envío de reportes.
- Accesos compactos a Mi historial y Cerca de ti, y etiquetas de acciones fotográficas más breves.

## Cerca de ti y cámara

- Nueva estructura XML en activity_nearby.xml, con barra superior fija y contenido desplazable.
- Actualizar en la barra superior; ajustes de ubicación, permisos y explicación de la vista en el menú. El botón de permisos solo aparece cuando falta acceso.
- Separación visual entre cámara, radio, información de sensores y resultados. El selector admite varias filas.
- Vista previa de 280dp en vertical y 220dp en horizontal, con mensaje cuando la cámara no está lista.
- Eliminación de la rotación duplicada del sensor: TextureView incorpora la orientación del sensor y la matriz adicional compensa la rotación de pantalla y las proporciones.
- Cámara y marcadores comparten las dimensiones orientadas para mantener el mismo recorte de referencia.
- Suavizado de orientación según el tiempo entre muestras, reinicio tras interrupciones o baja fiabilidad y redibujado sincronizado con la pantalla.

## Consulta del ciudadano y privacidad

Antes, Cerca de ti consultaba únicamente reportes aprobados o canalizados que además tenían publicación compartida autorizada. La consulta era común a ambos roles: aprobar no equivalía a publicar.

Ahora combina:

1. Reportes propios APROBADOS o CANALIZADOS, con coordenadas válidas, filtrados por la identidad de la sesión.
2. Reportes ajenos con autorización de publicación y resumen público.

Se eliminan duplicados, se filtra por radio y se muestran hasta 20 resultados ordenados por distancia. Los resultados se identifican como «Tu reporte» o «Compartida». Al abrirlos, nearbyDetail vuelve a comprobar propiedad y estado o consulta la proyección pública. sharedDetail conserva su comportamiento exclusivamente público.

La aprobación no publica automáticamente descripciones privadas a otras cuentas. El administrador mantiene la autorización explícita de publicación. Las fotos no forman parte de los datos de los marcadores.

## Historial, detalle y administración

- Barra de navegación fija y desplazamiento al inicio cuando se reconstruye la pantalla.
- Cabecera de bandeja con cantidad real de pendientes para administración; búsqueda y filtros desplegables.
- Estado destacado y acciones diferenciadas para consultar o gestionar reportes.
- Detalle con cabecera azul, categoría, estado, número de reporte y fecha.
- Fotografía con vista previa recortada y acceso a la imagen completa disponible en la app; estados de carga y ausencia de imagen.
- Bloques de descripción y ubicación; coordenadas y referencia completa accesibles mediante un diálogo.
- Seguimiento con estados legibles, iconos, separadores y eventos del más reciente al más antiguo.
- Panel administrativo con la siguiente acción correspondiente al estado: revisar, aprobar, rechazar, enviar o resolver. Las reglas del flujo siguen aplicándose en el servicio.
- Controles secundarios de publicación en RA, institución asignada y diagnóstico de envíos.
- Acceso directo desde un reporte propio aprobado a Cerca de ti, con explicación del requisito de distancia.

## Uso esperado

El ciudadano registra un reporte y confirma su ubicación. El administrador lo revisa y aprueba. Al volver a la sesión ciudadana, el reporte puede consultarse en Cerca de ti si está dentro del radio y hay una ubicación válida. Para que otras cuentas de la misma instalación lo vean, el administrador autoriza un resumen público.

## Límites que permanecen

- Los datos son locales a la instalación. No hay sincronización entre teléfonos ni entre teléfono y emulador.
- La cámara no reconoce fotos, objetos, basura ni baches. Los marcadores representan coordenadas registradas; no es detección mediante visión artificial.
- La proyección depende de GPS y orientación fiables. La dirección y distancia son aproximadas.
- Los reportes pendientes, rechazados o resueltos no entran en esta consulta. Estar fuera del radio impide su aparición.
- Estar exactamente sobre un punto puede impedir representar una dirección, aunque el reporte esté en la lista.

## Validación y alcance de publicación

Se revisaron los cambios de código y su formato mediante git diff --check. Por indicación del usuario no se ejecutaron pruebas automatizadas ni compilación durante la preparación de esta entrega. No se afirma validación integral en dispositivo de todos los cambios finales.

Los errores tipográficos temporales observados en las declaraciones de acceso y registro se corrigieron durante el trabajo, pero esos archivos ya coinciden con la base versionada y no requieren cambios en estos commits. La cuenta de demostración se creó únicamente en el dispositivo: sus credenciales y datos no se incorporan al repositorio.

Esta entrega excluye modificaciones de skills y la configuración local de selección de dispositivo de Android Studio. No elimina las skills que ya existían en el historial del repositorio.

## Archivos de referencia

- [Pautas y XML de interfaz](UI_LAYOUTS.md)
- [Fuente de datos y autorización de RA](RA_REAL.md)
- [Administración y entorno de demostración](DEMO.md)
- [Documentación oficial de cámara Android](https://developer.android.com/media/camera/camera2/camera-preview)
