package Message;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;

public class ServerMessage implements Message<ServerMessage.ProtocolType,
        Message<?, ?>>, Serializable {
    private static final long serialVersionUID = 1L;
    private ProtocolType protocolKey;
    private Message<?, ?> dataMessage;

    public enum ProtocolType {
        PINGPONG,
        CHAT_SERVER,
        DATABASE_MANAGEMENT;
    }

    public ServerMessage(ProtocolType protocolKey, Message<?, ?> dataMessage) {
        this.protocolKey = protocolKey;
        this.dataMessage = dataMessage;
    }

    @Override
    public ProtocolType getKey() {
        return protocolKey;
    }

    @Override
    public Message<?, ?> getData() {
        return dataMessage;
    }

    public void setData(Message<?, ?> dataMessage) {
        this.dataMessage = dataMessage;
    }

    @Override
    public String toString() {
        return ("protocol key: " + protocolKey + "\t\t\t data: " + dataMessage);
    }

    public static ByteBuffer toByteBuffer(Message<ProtocolType, Message<?, ?>> message)
            throws IOException {
        byte[] bufferMessage = null;
        ByteArrayOutputStream bufferOutput = null;
        ObjectOutputStream objectOutput = null;
        ByteBuffer buffer = ByteBuffer.allocate(2024);

        try {

            bufferOutput = new ByteArrayOutputStream();
            objectOutput = new ObjectOutputStream(bufferOutput);
            objectOutput.writeObject(message);
            objectOutput.flush();
            bufferMessage = bufferOutput.toByteArray();
        }
        finally {
            if (null != objectOutput) {
                objectOutput.close();
            }

            if (null != bufferOutput) {
                bufferOutput.close();
            }
        }

        return buffer.put(bufferMessage);
    }

    @SuppressWarnings("unchecked")
    public static Message<ProtocolType, Message<?, ?>> toMessageObject(ByteBuffer buffer)
            throws IOException, ClassNotFoundException {
        Message<ProtocolType, Message<?, ?>> message = null;
        ByteArrayInputStream bufferInput = null;
        ObjectInputStream objectInput = null;
        byte[] bufferMessage = buffer.array();

        try {
            bufferInput = new ByteArrayInputStream(bufferMessage);
            objectInput = new ObjectInputStream(bufferInput);

            message = (Message<ProtocolType, Message<?, ?>>) objectInput.readObject();

        }
        finally {
            if (null != bufferInput) {
                bufferInput.close();
            }

            if (null != objectInput) {
                objectInput.close();
            }
        }

        return message;
    }

}
