<p align="center">
  <img src="android/app/src/main/res/drawable-nodpi/launcher_logo.png" alt="Monoloco Saga" width="420">
</p>

# Monoloco Saga

Port para Android de las recompilaciones estáticas de una trilogía clásica de plataformas
de 16 bits. Las tres entregas en una sola app, con el mismo lanzador, el mismo menú rápido
y los mismos controles táctiles. Pensado para consolas Android como Retroid, AYN Odin 2 Portal
y RG477M.

> **Aviso.** Proyecto no oficial y gratuito, sin relación con los propietarios de los juegos.
> No incluye ninguna ROM ni datos de los juegos. Necesitas tus propias copias. No se vende.

## Cómo funciona

- **Lanzador:** eliges la entrega y pulsas **Jugar**, **Ajustes** o **Cambiar carpeta**.
  En la carpeta pones tus ROM (USA, sin cabecera o con cabecera de 512 bytes). La app las
  reconoce por su huella SHA-256 y las copia a su carpeta (`Android/data/com.monolocosaga`).
  Elegir carpeta no arranca el juego. Los ajustes de imagen y sonido son los mismos para las
  tres entregas (por defecto, pantalla completa y píxel cuadrado). El inicio automático es opcional y se cancela tocando
  la pantalla o pulsando el mando.
- **Menú rápido** con el botón Atrás: pausa el juego. Incluye imagen (formato, filtro, efecto,
  proporción de píxel; filtros Nítido, Suave, Nítido suavizado, HD, xBR, CRT Lottes,
  CRT Hyllian, FSR y los reescaladores neuronales Anime4K M, 3D y GAN), sonido, guardado y
  carga rápida, controles táctiles y contador de FPS.
- **Controles táctiles** con editor (mover, cambiar tamaño, cuadrícula y preajustes en XML).
  Se ocultan solos si hay un mando conectado.

## El código del juego

Las recompilaciones convierten el código de la ROM en C **en tu propio equipo**. Ese código
generado y los núcleos compilados (`libmonoloco_gameN.so`) contienen código del juego, así que
**nunca se suben a este repositorio**. Hay tres formas de tener un juego en la app:

1. **Compilar tú la APK** con tus ROM (ver abajo).
2. **GitHub Actions con el secreto opcional** `MONOLOCO_ROMS_URL`: enlace privado a una carpeta
   (o .zip) con tus ROM, por ejemplo de Dropbox tal cual. Cada ROM se reconoce por su huella.
   También valen `MONOLOCO_ROM1_URL`… uno por juego. Sin ellos la APK sale sin juegos.
3. **Importar un núcleo** hecho en tu PC con `tools/build_cores.sh`: copia el `.so` a la carpeta
   elegida junto a las ROM y pulsa **Cambiar carpeta**.

## Compilar

Requisitos: JDK 21, Python 3.10+, Rust/Cargo (opcional, acelera el análisis), Android SDK
platform 37, NDK 30.0.16248370 y CMake 4.1.2. Gradle 9.8.0 y AGP 9.4.1 están fijados.

```sh
git submodule update --init --recursive
```

`local.properties` en la raíz:

```properties
sdk.dir=/ruta/al/Android/Sdk
# Opcional: una línea por juego que quieras incluir
monoloco.rom1=/ruta/privada/juego1.sfc
monoloco.rom2=/ruta/privada/juego2.sfc
monoloco.rom3=/ruta/privada/juego3.sfc
```

```sh
./gradlew assembleRelease
```

Gradle genera el código de cada juego con su ROM (en `android/app/build/`, fuera de Git),
compila su núcleo y lo mete en la APK. Sin ROM, ese juego queda fuera.

Solo núcleos, para importarlos en una APK ya instalada:

```sh
ANDROID_NDK=/ruta/al/ndk tools/build_cores.sh --rom1 /ruta/juego1.sfc
```

### APK con GitHub Actions

Actions → **Android APK** → **Run workflow**. La APK se firma con la clave guardada en los
secretos `MONOLOCO_KEYSTORE_B64` y `MONOLOCO_KEYSTORE_PASSWORD` (alias `monolocosaga`), así cada
versión se instala encima de la anterior. La versión es `0.1.<número de ejecución>`.
Las ramas `ci/**` solo comprueban que compila.

## Créditos

- Recompilaciones originales de **elliotttate** y colaboradores:
  [DKC1Recomp](https://github.com/elliotttate/DKC1Recomp),
  [DKC2Recomp](https://github.com/elliotttate/DKC2Recomp) y
  [DKC3Recomp](https://github.com/elliotttate/DKC3Recomp) (MIT).
- [snesrecomp](https://github.com/elliotttate/snesrecomp) (PolyForm Noncommercial 1.0.0) y sus
  avisos de terceros.
- [SDL2](https://github.com/libsdl-org/SDL) (zlib).
- Port Android, lanzador, menú y controles: **KB-retro**

Los avisos de licencia completos están en [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y en
cada submódulo. Los nombres y marcas de los juegos pertenecen a sus propietarios.
