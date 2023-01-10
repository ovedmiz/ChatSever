package chatServer;

import java.io.IOException;

public class ChatServerMain {
    public static void main(String[] args) {
        try {

            Server chat = new Server();
            chat.addTcpConnection(Server.ProtocolPort.CHAT_PROTOCOL_PORT.getPort());
            chat.startServer();

            Thread.sleep(100000);

            chat.stopServer();

        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}