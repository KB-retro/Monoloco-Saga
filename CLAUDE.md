# Monoloco Saga — notas para Claude

Port Android "3 en 1" de las tres recompilaciones estáticas de la trilogía de plataformas de 16 bits
(los tres repos originales están en `upstream/game1`, `upstream/game2` y `upstream/game3`).
Repo: https://github.com/KB-retro/Monoloco-Saga. No fusionar cambios de upstream salvo que se pida.

## Preferencias de KB-retro
- Hablar en español; commits y código en inglés. Changelogs de una línea por cambio.
- Trabajar solo en este repo; no tocar los proyectos originales.
- Avisar de cada paso y probar lo posible; decir claramente lo que no se ha probado en la consola.
- Nunca subir datos del juego: ROM, código generado a partir de ella, núcleos compilados (`.so` de
  juego) ni la clave de firma.
- Mantener créditos y avisos de licencia de los originales (README y créditos de la app).
- En GitHub, sin nombres de marcas ni títulos oficiales: el juego se describe de forma genérica.
  Las referencias a los originales solo en los créditos.
- Nombre de la app: **Monoloco Saga**, identificador `com.monolocosaga`. Logo de Patrulla Retro solo
  en el lanzador. Español por defecto, inglés como segundo idioma. Solo horizontal (sensorLandscape).
- Lanzador: Jugar / Ajustes / Cambiar carpeta; elegir carpeta no arranca el juego; inicio automático
  opcional que se cancela tocando o pulsando el mando. Primera instalación con pantalla
  «Instalando…», barra y porcentaje.
- Menú rápido solo con Atrás: pausa el juego; secciones a la izquierda, opciones a la derecha;
  L1/L2 a la izquierda, R1/R2 a la derecha; Continuar (verde, izquierda) y Salir (rojo, derecha,
  con confirmación) solo táctiles. Basado en el de G-Zero 64 (`KB-retro/g-zero-64`).
- Controles táctiles como NFSU-X / G-Zero 64: iconos con círculo neón turquesa, editor, cuadrícula,
  preajustes XML. Modo Automático / Visibles / Ocultos.
- APK con GitHub Actions (workflow «Android APK», «Run workflow»), firmada siempre con la misma clave
  (secretos `MONOLOCO_KEYSTORE_B64`, `MONOLOCO_KEYSTORE_PASSWORD`, alias `monolocosaga`),
  versión `0.1.<run>`. Ramas `ci/**` solo comprueban que compila.

## Arquitectura
- `android/app`: Java (paquete `com.monolocosaga`), Gradle en la raíz (`settings.gradle.kts`).
- `android/SDL`: submódulo SDL2 release-2.32.10 (mismo que G-Zero 64; C y Java deben coincidir).
- `android/native/frontend`: `libmain.so` (SDL_main). Ventana GLES 3, audio SDL, mandos SDL,
  puente JNI `GameBridge`. Carga el núcleo del juego elegido con `dlopen`.
- `android/native/core`: un núcleo por juego (`libmonoloco_game1.so`…). Cada uno se compila con
  **su propia** copia de snesrecomp (los tres originales fijan revisiones distintas), con visibilidad
  oculta y un `exports.map` que solo exporta `monoloco_core`. Interfaz C en `monoloco_core.h`.
  Los `gameN.cmake` reproducen el objetivo headless de cada original (parches de ppu, scripts que
  retocan el código generado…): revisar si cambia el commit fijado del submódulo.
- Cada partida corre en el proceso `:game`; al salir se mata el proceso (estado global del motor).
- Los núcleos necesitan el código generado desde la ROM. Sin él la APK se compila igual, sin ese
  juego. Tres vías: (1) compilación local con `local.properties` (`monoloco.rom1=...`), (2) secreto
  opcional `MONOLOCO_ROMS_URL` (carpeta o .zip; `tools/find_roms.py`) o `MONOLOCO_ROMn_URL` en Actions, (3) importar desde la carpeta un núcleo hecho en el PC
  con `tools/build_cores.sh` (se copia a `files/cores/` y se marca de solo lectura).
- Las ROM se importan desde la carpeta elegida: se reconocen por tamaño y SHA-256 (se quita la
  cabecera de 512 bytes si la hay) y se copian a `<carpeta de la app>/gameN/rom.sfc`.
- ROM hacks (versiones): parches `.bps` (por el CRC de origen), `.ips` (se pregunta el juego) o ROM
  parcheadas de 4 MB (por el título de la cabecera) en `gameN/hacks/<id>/` con su `rom.sfc`,
  `name.txt` y partidas propias. El frontend recibe `modified` y el núcleo acepta cualquier ROM de
  4 MB (`MONOLOCO_MODIFIED_ROM`); el código sigue siendo el de la ROM original.
- Entrada del mando empaquetada como en los originales: B=0 Y=1 Select=2 Start=3 Arriba=4 Abajo=5
  Izquierda=6 Derecha=7 A=8 X=9 L=10 R=11 (jugador 2 desplazado 12 bits).
- Imagen del núcleo: BGRA (ARGB8888 en memoria), 224 líneas, ancho variable (256 en 4:3).
