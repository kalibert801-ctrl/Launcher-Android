package com.winlator.xenvironment.components;

import android.content.Context;
import android.os.Process;

import com.winlator.core.FileUtils;
import com.winlator.core.ProcessHelper;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Звуковой сервер PulseAudio для Android (из Winlator Cmod, LGPL): Wine (winepulse.drv) → PulseAudio →
 * модуль module-aaudio-sink → AAudio. Сервер сам держит ритм, переводит частоту и копит запас звука.
 * Сам PulseAudio — libpulseaudio.so в jniLibs, его модули — в assets/pulseaudio/modules.
 */
public class PulseAudioComponent extends EnvironmentComponent {
    private static final String[] MODULES = {"module-native-protocol-unix.so", "module-aaudio-sink.so", "libprotocol-native.so"};
    private final UnixSocketConfig socketConfig;
    private static int pid = -1;
    private static final Object lock = new Object();

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            pid = execPulseAudio();
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

    private static void copyModules(Context context, File modulesDir) {
        modulesDir.mkdirs();
        for (String name : MODULES) {
            File out = new File(modulesDir, name);
            if (out.isFile() && out.length() > 0) continue;
            try (InputStream in = context.getAssets().open("pulseaudio/modules/" + name);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            catch (Exception e) {
                out.delete();
            }
            FileUtils.chmod(out, 0755);
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        File workingDir = new File(context.getFilesDir(), "pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }
        File modulesDir = new File(workingDir, "modules/arm64");
        copyModules(context, modulesDir);

        // старый сокет мешает запуску
        new File(socketConfig.path).delete();

        File configFile = new File(workingDir, "default.pa");
        FileUtils.writeString(configFile, String.join("\n",
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\"" + socketConfig.path + "\"",
            "load-module module-aaudio-sink",
            "set-default-sink AAudioSink"
        ));

        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH=/system/lib64:" + nativeLibraryDir + ":" + modulesDir);
        envVars.add("HOME=" + workingDir);
        envVars.add("TMPDIR=" + environment.getTmpDir());

        String command = nativeLibraryDir + "/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        return ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
    }
}
