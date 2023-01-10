package chatServer;

import Message.Message;

import java.io.Serializable;

public class ChatServerMessage implements
        Message<ChatServerMessage.ChatProtocolKeys, String>, Serializable{
    private static final long serialVersionUID = 1L;
    private ChatProtocolKeys key;
    private String data;

    public enum ChatProtocolKeys {
        REGISTRATION_REQUEST,
        REGISTRATION_ACK,
        REGISTRATION_REFUSE,
        NEW_CLIENT_REGISTRATION,
        MESSAGE,
        BROADCAST_MESSAGE,
        ERROR_MESSAGE,
        REMOVE_REQUEST;
    }

    public ChatServerMessage (ChatProtocolKeys key, String data) {
        this.key = key;
        this.data = data;
    }


    @Override
    public ChatProtocolKeys getKey() {
        return key;
    }

    @Override
    public String getData() {
        return data;
    }


    public void setKey(ChatProtocolKeys key) {
        this.key = key;
    }

    public void setData(String data) {
        this.data = data;
    }


    @Override
    public String toString() {
        return ("key: " + key.name() + " data: " + data);
    }
}