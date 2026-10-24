cmake_minimum_required(VERSION 3.22)
project(MonolocoSaga LANGUAGES C CXX)

# Gradle passes MONOLOCO_GAMEn_GEN_DIR for every game it generated from a ROM.
# tools/build_cores.sh configures this directory with MONOLOCO_CORES_ONLY=ON.
option(MONOLOCO_CORES_ONLY "Build only the game cores (no frontend, no SDL)" OFF)
get_filename_component(MONOLOCO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
set(MONOLOCO_UPSTREAM "${MONOLOCO_ROOT}/upstream")
foreach(game 1 2 3)
    set(MONOLOCO_GAME${game}_GEN_DIR "" CACHE PATH "Generated sources of game ${game}")
endforeach()

if(NOT MONOLOCO_CORES_ONLY)
    # SDLActivity loads libSDL2.so, then libmain.so.
    set(SDL_SHARED ON CACHE BOOL "" FORCE)
    set(SDL_STATIC OFF CACHE BOOL "" FORCE)
    set(SDL_TEST OFF CACHE BOOL "" FORCE)
    add_subdirectory(${MONOLOCO_ROOT}/android/SDL ${CMAKE_BINARY_DIR}/SDL)

    add_library(main SHARED
        frontend/main.c
        frontend/bridge.c
        frontend/render.c
        frontend/shaders.c
        frontend/upscale.c
        frontend/perf.c)
    # The Reconstruct shader header comes straight from the second game (MIT).
    target_include_directories(main PRIVATE frontend core ${MONOLOCO_UPSTREAM}/game2/runner)
    set_target_properties(main PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)
    target_compile_options(main PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
    target_link_libraries(main PRIVATE SDL2::SDL2 GLESv3 log dl m)
endif()

set(MONOLOCO_BUILT_CORES "" CACHE INTERNAL "")
include(core/core.cmake)
add_subdirectory(core/game1)
add_subdirectory(core/game2)
add_subdirectory(core/game3)

# Gradle only builds library targets: make the frontend wait for the compile
# checks so CI really compiles the three cores (nothing of them is linked).
if(NOT MONOLOCO_CORES_ONLY)
    foreach(game 1 2 3)
        if(TARGET monoloco_game${game}_check)
            add_dependencies(main monoloco_game${game}_check)
        endif()
    endforeach()
endif()
