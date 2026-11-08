cmake_minimum_required(VERSION 3.22.1)

# Нативная часть движка Windows AleksOS (код из Winlator Cmod, MIT):
#  - libproot.so / libproot-loader.so — Linux-окружение для Wine (proot);
#  - libwinlator.so — быстрые функции X-сервера (сокеты, рисование, общая память).
project(AleksOSEngine)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-function-pointer-types")

add_subdirectory(proot)

add_library(winlator SHARED
            winlator/drawable.c
            winlator/gpu_image.c
            winlator/sysvshared_memory.c
            winlator/xconnector_epoll.c
            winlator/alsa_client.c)

target_link_libraries(winlator
                      log
                      android
                      jnigraphics
                      aaudio
                      EGL
                      GLESv2
                      GLESv3)
