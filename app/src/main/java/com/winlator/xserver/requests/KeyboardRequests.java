package com.winlator.xserver.requests;

import static com.winlator.xserver.Keyboard.KEYSYMS_PER_KEYCODE;
import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Keyboard;
import com.winlator.xserver.XClient;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class KeyboardRequests {
    public static void getKeyboardMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int firstKeycode = inputStream.readUnsignedByte();
        int count = inputStream.readUnsignedByte();
        inputStream.skip(2);

        // AleksOS: по протоколу X ответ содержит count * KEYSYMS_PER_KEYCODE значений
        int[] keysyms = client.xServer.keyboard.keysyms;
        int total = count * KEYSYMS_PER_KEYCODE;
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(KEYSYMS_PER_KEYCODE);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(total);
            outputStream.writePad(24);

            int i = (firstKeycode - Keyboard.MIN_KEYCODE) * KEYSYMS_PER_KEYCODE;
            for (int n = 0; n < total; n++, i++) {
                outputStream.writeInt(i >= 0 && i < keysyms.length ? keysyms[i] : 0);
            }
        }
    }

    public static void getModifierMapping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(2);
            outputStream.writePad(24);
            outputStream.writePad(8);
        }
    }
}