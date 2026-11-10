# Ajustes visuales de GeoReport

Aplicados al formulario de incidencias, acceso, registro, historial, detalle y vista cercana. Se conservan los componentes Material y la identidad azul de la app. Referencias utilizadas: ui-ux-pro-max e impeccable (guía Android).

## Campos sin etiquetas duplicadas

La etiqueta pertenece exclusivamente a TextInputLayout. Las instrucciones pertenecen a helperText. El estilo común mantiene la etiqueta contraída con expandedHintEnabled=false y el editor utiliza altura adaptable con un mínimo de 56dp. No establecer alturas fijas ni un segundo hint en TextInputEditText.

Ejemplo equivalente al campo corregido en activity_home.xml:

```xml
<com.google.android.material.textfield.TextInputLayout
    style="@style/Widget.GeoReport.Input"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="16dp"
    android:hint="@string/description_label"
    app:helperText="@string/description_hint">

    <com.google.android.material.textfield.TextInputEditText
        style="@style/Widget.GeoReport.EditText"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="top|start"
        android:inputType="textMultiLine"
        android:minLines="3" />
</com.google.android.material.textfield.TextInputLayout>
```

El elemento raíz debe declarar los namespaces android y app. El fragmento reutiliza los estilos del proyecto.

## Acciones de realidad aumentada

activity_nearby.xml mantiene la barra superior fuera del scroll. Actualizar está en la barra (o en su menú si no cabe); volver utiliza la flecha de navegación. Ajustes de ubicación, permisos y explicación de RA están en el menú. Conceder permisos aparece únicamente cuando falta acceso a cámara o ubicación. El selector de radio permite varias filas y conserva blancos táctiles de al menos 48dp. La cámara mide 280dp en vertical y 220dp en horizontal.

## Medidas

- Margen interior de pantalla: 16dp.
- Separación entre secciones: 24dp.
- Separación entre campos y bloques: 16dp.
- Separación entre título y contenido o entre controles vecinos: 8dp.
- Padding interior de campos: 16dp vertical; altura mínima 56dp y altura wrap_content.
- Padding de formularios de acceso: 20dp; cuerpo de texto 16sp.
- Botones: mínimo 52dp, altura wrap_content; acciones de icono: mínimo 48dp.

En LinearLayout horizontal, utilizar ancho 0dp y layout_weight=1 para texto junto a un icono. En ConstraintLayout, utilizar ancho 0dp con restricciones start/end, altura wrap_content, layout_marginTop=16dp entre campos y 24dp entre secciones. No utilizar coordenadas absolutas ni alturas fijas para texto. fitScreenContent conserva el espacio de las barras del sistema y del teclado.

## Alcance de revisión

### Ajustes a partir de las capturas del dispositivo

- Detalle con cabecera de estado, número de reporte, fotografía recortada con acceso a imagen completa, descripción, ubicación y seguimiento visual en orden de fecha descendente.
- Administración con recuento real de reportes pendientes, botones de gestión por estado y controles secundarios para visibilidad en RA. La barra de navegación permanece visible al desplazarse.
- Cámara: eliminada la segunda rotación del sensor. TextureView ya incorpora SENSOR_ORIENTATION; la matriz corrige la proporción, aplica únicamente la rotación inversa de pantalla y recorta al centro. Marcadores y cámara comparten las dimensiones orientadas para el cálculo de campo de visión.
- Suavizado de orientación basado en el tiempo entre muestras, con reinicio después de interrupciones o pérdida de fiabilidad. No implica seguimiento espacial de objetos: la proyección sigue siendo geográfica y aproximada.

Referencia de cámara: https://developer.android.com/media/camera/camera2/camera-preview

Se revisó el código editado. Por solicitud del usuario no se ejecutaron pruebas ni compilación, y no se verificó el renderizado en un dispositivo Infinix. Las dimensiones se expresan en dp/sp para adaptarse a la densidad, no a una marca específica.
