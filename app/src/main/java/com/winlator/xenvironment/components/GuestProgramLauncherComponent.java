package com.winlator.xenvironment.components;

import android.content.Context;
import android.os.Process;

import com.winlator.core.Callback;
import com.winlator.core.EnvVars;
import com.winlator.core.ProcessHelper;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

/**
 * Запуск Windows-программы: proot (Linux-окружение) → box64 (x86-64 → ARM64) → wine.
 * Упрощённая версия компонента из Winlator Cmod (MIT): без пресетов и настроек Winlator,
 * только 64-битный режим WoW64 (32-битные программы Wine запускает сам, без box86).
 */
public class GuestProgramLauncherComponent extends EnvironmentComponent {
    private String guestExecutable;
    private static int pid = -1;
    private String[] bindingPaths;
    private EnvVars envVars;
    private EnvVars box64EnvVars;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execGuestProgram();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    public void suspendProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.suspendProcess(pid);
        }
    }

    public void resumeProcess() {
        synchronized (lock) {
            if (pid != -1) ProcessHelper.resumeProcess(pid);
        }
    }

    public int getPid() {
        return pid;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    /** Переменные Box64 (режим «быстрее / стабильнее» из BIOS). */
    public void setBox64EnvVars(EnvVars box64EnvVars) {
        this.box64EnvVars = box64EnvVars;
    }

    private int execGuestProgram() {
        Context context = environment.getContext();
        ImageFs imageFs = environment.getImageFs();
        File rootDir = imageFs.getRootDir();
        File tmpDir = environment.getTmpDir();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;

        EnvVars envVars = new EnvVars();
        envVars.put("BOX64_NOBANNER", "1");
        envVars.put("BOX64_DYNAREC", "1");
        envVars.put("BOX64_LOG", "0");
        envVars.put("BOX64_X11GLX", "1");
        if (box64EnvVars != null) envVars.putAll(box64EnvVars);
        envVars.put("HOME", ImageFs.HOME_PATH);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", "/tmp");
        envVars.put("LC_ALL", "en_US.utf8");
        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", imageFs.getWinePath() + "/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        envVars.put("LD_LIBRARY_PATH", "/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf");
        envVars.put("ANDROID_SYSVSHM_SERVER", UnixSocketConfig.SYSVSHM_SERVER_PATH);

        if ((new File(imageFs.getLib64Dir(), "libandroid-sysvshm.so")).exists() ||
            (new File(imageFs.getLib32Dir(), "libandroid-sysvshm.so")).exists())
            envVars.put("LD_PRELOAD", "libandroid-sysvshm.so");
        if (this.envVars != null) envVars.putAll(this.envVars);

        boolean bindSHM = envVars.get("WINEESYNC").equals("1");

        String command = nativeLibraryDir + "/libproot.so";
        command += " --kill-on-exit";
        command += " --rootfs=" + rootDir;
        command += " --cwd=" + ImageFs.HOME_PATH;
        command += " --bind=/dev";

        if (bindSHM) {
            File shmDir = new File(rootDir, "/tmp/shm");
            shmDir.mkdirs();
            command += " --bind=" + shmDir.getAbsolutePath() + ":/dev/shm";
        }

        command += " --bind=/proc";
        command += " --bind=/sys";

        if (bindingPaths != null) {
            for (String path : bindingPaths) command += " --bind=" + (new File(path)).getAbsolutePath();
        }

        command += " /usr/bin/env " + envVars.toEscapedString() + " box64 " + guestExecutable;

        envVars.clear();
        envVars.put("PROOT_TMP_DIR", tmpDir);
        envVars.put("PROOT_LOADER", nativeLibraryDir + "/libproot-loader.so");

        return ProcessHelper.exec(command, envVars.toStringArray(), rootDir, (status) -> {
            synchronized (lock) {
                pid = -1;
            }
            if (terminationCallback != null) terminationCallback.call(status);
        });
    }
}
