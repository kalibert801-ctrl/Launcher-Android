cmake_minimum_required(VERSION 3.22.1)

project(PRoot)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration -Wno-error=implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-function-pointer-types")

add_library(talloc STATIC
            talloc/talloc.c)

include_directories(src
                    talloc)

add_executable(libproot.so
               src/cli/cli.c
               src/cli/proot.c
               src/cli/note.c
               src/execve/enter.c
               src/execve/exit.c
               src/execve/elf.c
               src/execve/auxv.c
               src/path/binding.c
               src/path/glue.c
               src/path/canon.c
               src/path/path.c
               src/path/proc.c
               src/path/temp.c
               src/syscall/seccomp.c
               src/syscall/syscall.c
               src/syscall/chain.c
               src/syscall/enter.c
               src/syscall/exit.c
               src/syscall/sysnum.c
               src/syscall/socket.c
               src/syscall/heap.c
               src/syscall/rlimit.c
               src/tracee/tracee.c
               src/tracee/mem.c
               src/tracee/reg.c
               src/tracee/event.c
               src/tracee/seccomp.c
               src/ptrace/ptrace.c
               src/ptrace/wait.c)

target_link_libraries(libproot.so
                      talloc)

# Загрузчик proot — самостоятельная программа без libc (как в Termux proot):
# proot подгружает её в каждый запускаемый Linux-процесс.
add_executable(libproot-loader.so
               src/loader/loader.c)
target_compile_options(libproot-loader.so PRIVATE -fPIC -ffreestanding -fno-stack-protector)
target_link_options(libproot-loader.so PRIVATE -static -nostdlib -Wl,--image-base=0x2000000000 -Wl,-z,noexecstack -Wl,--build-id=none)