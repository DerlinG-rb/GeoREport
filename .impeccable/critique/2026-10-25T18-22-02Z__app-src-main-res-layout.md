---
target: app/src/main/res/layout
total_score: 21
max_score: 40
na_heuristics: 
p0_count: 1
p1_count: 4
target_identity: "file:C:\\Users\\Jaime\\AndroidStudioProjects\\GeoReport\\app\\src\\main\\res\\layout"
timestamp: 2026-10-25T18-22-02Z
slug: app-src-main-res-layout
---
Method: design review only (detector not applicable, native platform)

**Target:** `app/src/main/res/layout` — Login y Registro (Android, minSdk 24 / targetSdk 37)

## Design Health Score

| # | Heurística | Puntuación | Hallazgo clave |
|---|-----------|-------|-----------|
| 1 | Visibilidad del estado | 2 | Registro confirma con Toast; Login no da ninguna respuesta; sin loading ni éxito persistente |
| 2 | Sistema / mundo real | 3 | Español claro y patrón de auth familiar; filtra "Simulación visual" y "GeoREport" |
| 3 | Control y libertad | 3 | Hay back y back del sistema; "olvidé contraseña" es un callejón sin salida |
| 4 | Consistencia y estándares | 2 | Login con título dentro de la tarjeta, Registro fuera; 22 vs 26sp; el tema DayNight no se respeta |
| 5 | Prevención de errores | 1 | Solo valida "todo vacío" y contraseñas distintas; sin formato de email/teléfono/clave |
| 6 | Reconocimiento vs recuerdo | 3 | Labels e iconos visibles; sin autofill ni reglas de contraseña |
| 7 | Flexibilidad y eficiencia | 1 | Sin autofillHints, IME next/done, biometría, "recordarme" ni email precargado |
| 8 | Diseño estético y minimalista | 3 | Limpio y calmado, pero genérico; naranja/rojo de marca nunca se usan |
| 9 | Recuperación de errores | 2 | Toasts genéricos lejos del campo; los datos se conservan |
| 10 | Ayuda y documentación | 1 | Recuperación no funcional, sin privacidad/términos ni guía de emergencia |
| **Total** | | **21/40** | **Acceptable** |

## Design Specificity Verdict

**Veredicto: baja / intercambiable por categoría.**

La composición es un template de autenticación Material: logo en círculo, tarjeta blanca centrada, inputs outlined con icono, botón azul. Podría pertenecer a una app de delivery, banca o RR. HH. sin cambios. Para una app de *reporte ciudadano de incidentes* no hay señal de territorio, ubicación, urgencia, evidencia ni confianza cívica: solo el pin del logo y el badge del tagline lo insinúan. La paleta naranja/roja (`colors.xml:7-8`) está definida y no aparece en ninguna pantalla.

**Detector determinista:** no aplica (plataforma nativa).
**Overlays visuales:** no aplica. Revisión estática del código; no se capturó render en emulador.

## Overall Impression

La base es ordenada y fácil de leer, pero es una pantalla de login de plantilla para un producto que debería transmitir confianza ciudadana y manejar momentos de estrés. El mayor problema no es estético: la app no compila y el modo oscuro está roto por colores claros fijos. Después de eso, la validación y el final del registro son donde se pierde al usuario.

## What's Working

- **Jerarquía de una sola tarea**: marca → tarjeta → formulario → enlace secundario; una acción primaria clara por pantalla.
- **Identidad base coherente**: logo vectorial de pin con exclamación, azul consistente y botones de 52dp con radios de 12dp.
- **Lenguaje directo**: labels en español, iconos de apoyo y toggle de contraseña; el tagline explica el propósito de inmediato.

## Priority Issues

### [P0] `LoginActivity.kt` está truncado: la app no compila
- **Qué**: el archivo termina en `class\nLoginActivity : AppCompatActivity() {` y `/}` (`LoginActivity.kt:10-13`). No existe `onCreate`, binding ni listeners.
- **Por qué importa**: la pantalla de entrada es la única `LAUNCHER`; el bloqueo impide ver o probar cualquier decisión de diseño.
- **Fix**: restaurar la implementación completa con `ActivityLoginBinding`, `setContentView` y listeners para login, registro y recuperación. Tras eso, recién tiene sentido pulir UI.
- **Comando sugerido**: ninguno; corregir código antes de cualquier comando de diseño.

### [P1] El modo oscuro está roto por colores claros fijos
- **Qué**: el tema es DayNight (`themes.xml:3`) y `values-night` solo define `windowBackground`/status bar (`values-night/themes.xml:13-15`). Los layouts fuerzan `bg_light` (`activity_login.xml:9`), `surface_card` (`:66`), `text_primary` (`:79`) y `text_secondary` sin variantes nocturnas; no existe `values-night/colors.xml`.
- **Por qué importa**: con el sistema en oscuro, el usuario ve fondo claro, tarjetas blancas y componentes Material que toman colores nocturnos (hints, strokes, tint del password toggle) sobre blanco: un híbrido con contraste impredecible. Rompe la expectativa del sistema y la comodidad visual.
- **Fix**: crear tokens semánticos con `values-night` (`bg`, `surface`, `text_primary`, `text_secondary`, `stroke`) y usar atributos de tema (`?attr/colorSurface`, `colorOnSurface`) en lugar de colores fijos; probar ambos modos.
- **Comando sugerido**: `impeccable extract`, luego `impeccable polish`.

### [P1] Validación solo por Toast, sin errores de campo ni reglas
- **Qué**: todo se valida en `btnRegister` (`RegisterActivity.kt:39-55`): un único Toast si algo está vacío y otro si las contraseñas no coinciden. No hay `app:errorEnabled`, ni validación de email, teléfono o longitud de contraseña; el botón siempre está activo.
- **Por qué importa**: el usuario debe adivinar qué campo falló; los toasts desaparecen y no están junto al campo. Se permiten emails inválidos, teléfonos basura y contraseñas de un carácter.
- **Fix**: `TextInputLayout.setError()` inline por campo, validar formato de email, longitud de teléfono, mínimo de contraseña y coincidencia en el campo de confirmación; deshabilitar el submit hasta que sea válido y mover el foco al primer error.
- **Comando sugerido**: `impeccable harden`.

### [P1] Accesibilidad y ergonomía móvil débiles
- **Qué**: `text_secondary` `#718096` da ~4.0:1 sobre blanco y ~3.7:1 sobre `bg_light`, usado en subtítulos de 13-14sp (`activity_login.xml:84-91`, `activity_register.xml:47`) — falla AA (4.5:1). "¿Olvidaste tu contraseña?" es un TextView de 13sp con 4dp de padding (`activity_login.xml:143-155`) y los enlaces inferiores usan 6dp (`:190-199`, `activity_register.xml:222-231`): objetivos menores a 48dp. El back mide 44dp (`activity_register.xml:22-23`) y su `contentDescription="Volver"` está hardcodeado (`:25`). No hay `autofillHints` ni `imeOptions`. Con `targetSdk 37` (edge-to-edge forzado) no se manejan insets ni `windowSoftInputMode` (`app/build.gradle.kts:14`, `AndroidManifest.xml:5-13`): el contenido puede quedar bajo la barra de estado/navegación y el teclado tapar campos.
- **Por qué importa**: usuarios con baja visión, TalkBack o uso a una mano tienen fricción real; en pantallas modernas el logo y el back pueden quedar ocultos.
- **Fix**: oscurecer el texto secundario a ≥4.5:1, subir los enlaces a un target de 48dp con `selectableItemBackground`, mover "Volver" a string resource, agregar `autofillHints`/`imeOptions` y manejar insets con `ViewCompat.setOnApplyWindowInsetsListener` o `fitsSystemWindows`.
- **Comando sugerido**: `impeccable adapt`, luego `impeccable harden`.

### [P1] El registro termina en un callejón sin confianza
- **Qué**: al registrarse solo aparece un Toast y se cierra la pantalla (`RegisterActivity.kt:57-65`), sin éxito persistente, sin email precargado y sin pantalla de bienvenida. Se recogen nombre, email y teléfono sin aviso de privacidad ni consentimiento, y no hay ninguna guía de emergencia en una app de incidentes ciudadanos.
- **Por qué importa**: el "peak-end" del flujo es un mensaje que desaparece; el usuario vuelve al login y debe reescribir todo. Datos personales sensibles sin contexto de privacidad erosionan la confianza, y alguien en peligro no encuentra una salida clara.
- **Fix**: pantalla de éxito o retorno al login con email precargado y banner persistente; enlace a privacidad y consentimiento en el registro; bloque "¿Es una emergencia? Llama al 911" y evaluar reporte como invitado.
- **Comando sugerido**: `impeccable onboard`, luego `impeccable clarify`.

## Persona Red Flags

**Jordan (primerizo)**: el tagline dice qué es la app, pero no qué pasa después de registrarse. "Simulación visual" en los mensajes (`strings.xml:31-32`) es lenguaje interno que no entiende. "Olvidé mi contraseña" no lleva a ningún lado. No sabe si el teléfono es obligatorio ni para qué se usa.

**Sam (accesibilidad)**: los subtítulos de 13-14sp no alcanzan 4.5:1; TalkBack anuncia el logo con el nombre de la app justo antes del título duplicando la marca; el back dice "Volver" aunque el sistema esté en otro idioma; los enlaces son texto sin fondo seleccionable; sin autofill ni navegación por IME.

**Casey (móvil, una mano)**: el registro exige 5 campos antes de dar valor, el botón principal queda abajo (bien), pero el back del registro está arriba a la izquierda; interrumpirse y volver pierde lo escrito; en horizontal o con teclado abierto los últimos campos pueden quedar tapados por falta de insets/ajuste.

## Minor Observations

- Login y Registro usan estructuras distintas: título dentro de la tarjeta vs fuera, 22 vs 26sp, subtítulo 13 vs 14sp, margen 28 vs 24dp, centrado vs alineado a la izquierda. Unificar tokens.
- Los enlaces (`tvForgotPassword`, `tvGoToRegister`, `tvGoToLogin`) son `TextView` sin `?attr/selectableItemBackground`: no hay feedback al tocar.
- `app_name` es "GeoREport" (`strings.xml:2`), con mayúscula errática frente a "GeoReport".
- `purple_200`/`teal_*` siguen existiendo y el tema oscuro usa `purple_200` como primario (`values-night/themes.xml:5`): naming heredado que confunde.
- Sin ancho máximo, en tablet el formulario se estira a todo el ancho; conviene limitar a ~400-480dp.
- Hints definidos (`email_hint`, `password_hint`, etc.) pero nunca usados; los iconos vectoriales traen `fillColor` fijo además del tint.
- No hay estados `disabled`/loading en los botones, ni `errorEnabled` en los inputs.
- No existe PRODUCT.md ni DESIGN.md: la evaluación de especificidad queda limitada; sugerencia: `impeccable init` para capturar audiencia, tono y restricciones.

## Questions to Consider

- ¿El login debería ser la primera pantalla de una app de reporte ciudadano, o conviene permitir reportar como invitado y pedir cuenta después?
- ¿Qué versión se siente cívica y confiable en vez de "SaaS genérico": mapa, ubicación, evidencia, seguimiento del caso?
- ¿La app debería mostrar el acceso a emergencias (911) antes de pedir credenciales?
- ¿Se puede pedir menos en el registro (solo email o teléfono) y completar el perfil más tarde?
