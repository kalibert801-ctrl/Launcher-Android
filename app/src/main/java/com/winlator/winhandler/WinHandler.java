package com.winlator.winhandler;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;

/**
 * Связь с winhandler.exe внутри Wine (по образцу Winlator, MIT): через UDP просит Wine запустить
 * программу (ShellExecute) или вывести окно на передний план. Так все программы запускаются
 * внутри одного и того же Wine — без отдельного процесса на каждую.
 * AleksOS: оставлены только запуск и «на передний план».
 */
public class WinHandler {
    private static final int SERVER_PORT = 7947;
    private static final int CLIENT_PORT = 7946;
    private static final byte EXEC = 2;
    private static final byte INIT = 1;
    private static final byte BRING_TO_FRONT = 12;
    /** Размер пакета: как в Winlator (winhandler.exe читает не больше). */
    public static final int PACKET_SIZE = 64;
    /** Команды ShellExecuteA — в кодировке Windows (русская: 1251). */
    private static final Charset ANSI = charset();

    private DatagramSocket socket;
    private final ByteBuffer sendData = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(sendData.array(), PACKET_SIZE);
    private final DatagramPacket receivePacket = new DatagramPacket(receiveData.array(), PACKET_SIZE);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private volatile boolean initReceived = false;
    private volatile boolean running = false;
    private InetAddress localhost;

    private static Charset charset() {
        try { return Charset.forName("windows-1251"); }
        catch (Exception e) { return Charset.defaultCharset(); }
    }

    public boolean isReady() {
        return initReceived;
    }

    /** Поместится ли команда в один пакет. */
    public static boolean fits(String filename, String parameters) {
        return 1 + 12 + filename.getBytes(ANSI).length + parameters.getBytes(ANSI).length <= PACKET_SIZE;
    }

    /** Запустить программу внутри Wine: filename — что запускать, parameters — аргументы. */
    public void exec(final String filename, final String parameters) {
        addAction(() -> {
            byte[] filenameBytes = filename.getBytes(ANSI);
            byte[] parametersBytes = parameters.getBytes(ANSI);
            sendData.rewind();
            sendData.put(EXEC);
            sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
            sendData.putInt(filenameBytes.length);
            sendData.putInt(parametersBytes.length);
            sendData.put(filenameBytes);
            sendData.put(parametersBytes);
            sendPacket(CLIENT_PORT);
        });
    }

    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {}

    public void bringToFront(final String processName, final long handle) {
        if (!initReceived) return;
        addAction(() -> {
            byte[] bytes = processName.getBytes(ANSI);
            if (13 + bytes.length > PACKET_SIZE) return;
            sendData.rewind();
            sendData.put(BRING_TO_FRONT);
            sendData.putInt(bytes.length);
            sendData.put(bytes);
            sendData.putLong(handle);
            sendPacket(CLIENT_PORT);
        });
    }

    private boolean sendPacket(int port) {
        try {
            int size = sendData.position();
            if (size == 0 || socket == null) return false;
            sendPacket.setAddress(localhost);
            sendPacket.setPort(port);
            socket.send(sendPacket);
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private void addAction(Runnable action) {
        synchronized (actions) {
            actions.add(action);
            actions.notify();
        }
    }

    public void start() {
        try {
            localhost = InetAddress.getByName("127.0.0.1");
        }
        catch (IOException e) {
            return;
        }
        running = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            while (running) {
                synchronized (actions) {
                    while (initReceived && !actions.isEmpty()) {
                        try { actions.poll().run(); } catch (Exception ignored) {}
                    }
                    try { actions.wait(); } catch (InterruptedException e) { return; }
                }
            }
        });
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress((InetAddress)null, SERVER_PORT));
                while (running) {
                    socket.receive(receivePacket);
                    receiveData.rewind();
                    if (receiveData.get() == INIT) {
                        initReceived = true;
                        synchronized (actions) {
                            actions.notify();
                        }
                    }
                }
            }
            catch (IOException ignored) {}
        });
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        synchronized (actions) {
            actions.notify();
        }
    }
}
