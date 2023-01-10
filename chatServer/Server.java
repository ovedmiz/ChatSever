package chatServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import chatServer.ChatServerMessage.ChatProtocolKeys;
import Message.Message;
import Message.ServerMessage;
import Message.ServerMessage.ProtocolType;

public class Server {
    private ConnectionHandler connectionHandler;
    private MessageHandler messageHandler;

    public enum ProtocolPort {
        CHAT_PROTOCOL_PORT(55555);

        private final int PORT;

        ProtocolPort(int port) {
            this.PORT = port;
        }

        public int getPort() {
            return PORT;
        }
    }

    public Server() throws IOException {
        connectionHandler = new ConnectionHandler();
        messageHandler = new MessageHandler();
    }

    public void startServer() throws Exception {
        connectionHandler.startConnections();
    }

    public void stopServer() {
        connectionHandler.stopConnections();
    }

    public void addBroadcastConnection(int portNumber) throws Exception {
        if(connectionHandler.isRunning) {
            throw new Exception("cannot add connection after start.");
        }

        Connection broadcastConnection = new BroadcastConnection(portNumber);
        connectionHandler.addConnection(broadcastConnection);
    }

    public void addTcpConnection(int portNumber) throws Exception {
        if(connectionHandler.isRunning) {
            throw new Exception("cannot add connection after start.");
        }

        Connection tcpConnection = new TcpConnection(portNumber);
        connectionHandler.addConnection(tcpConnection);
    }

    public void addUdpConnection(int portNumber) throws Exception {
        if(connectionHandler.isRunning) {
            throw new Exception("cannot add connection after start.");
        }

        Connection udpConnection = new UdpConnection(portNumber);
        connectionHandler.addConnection(udpConnection);
    }

    /*---------------------- Connection Handler class ------------------------*/

    private class ConnectionHandler implements Runnable{
        private List<Connection> connectionsBeforeStart = new LinkedList<>();
        private Map<Channel, Connection> registeredConnections = new HashMap<>();
        private ByteBuffer messageBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        private Selector selector;
        private boolean isRunning = false;
        private boolean toContinueRun = false;

        private final static int BUFFER_SIZE = 1024;
        private final static int TIMEOUT_SELECT = 5000;

        private void startConnections() throws Exception {
            if(isRunning) {
                throw new Exception("is start allready.");
            }

            registerConnectionsToSelector();

            new Thread(this).start();
        }

        private void registerConnectionsToSelector() throws IOException {
            selector = Selector.open();

            for (Connection connection : connectionsBeforeStart) {
                connection.initAndRegisterToServer();
                registeredConnections.put(connection.getChannel(), connection);
            }
        }

        private void stopConnections() {
            toContinueRun = false;
            closeResource();
        }

        private void addConnection(Connection connection) throws Exception {
            Objects.requireNonNull(connection);
            connectionsBeforeStart.add(connection);
        }

        private void runServer() throws IOException, ClassNotFoundException {

            try {
                while (toContinueRun) {
                    if(0 == selector.select(TIMEOUT_SELECT)) {
                        System.out.println("server-running...");
                        continue;
                    }

                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iter = selectedKeys.iterator();

                    while (iter.hasNext()) {
                        SelectionKey key = iter.next();

                        if (key.isValid() && key.isAcceptable()) {
                            registerTcpClientToSelector(key);
                        }
                        else if (key.isValid() && key.isReadable()) {
                            readableHandler(key);
                        }

                        iter.remove();

                    }
                }
            }catch (Exception e) {
                if(toContinueRun) {
                    stopConnections();
                    System.out.println("runServer: " + e);
                }
            }

        }

        @Override
        public void run() {
            try {
                isRunning = true;
                toContinueRun = true;
                runServer();
            } catch (IOException | ClassNotFoundException e) {
                stopConnections();
                System.out.println("chatServer.Server- run override function: " + e);
            }
        }

        private void registerTcpClientToSelector(SelectionKey key)
                throws IOException {
            ServerSocketChannel tcpserverSocket =
                    (ServerSocketChannel) key.channel();
            SocketChannel clientTcp = tcpserverSocket.accept();
            clientTcp.configureBlocking(false);
            clientTcp.register(selector, SelectionKey.OP_READ);
            Connection connection = registeredConnections.get(tcpserverSocket);
            registeredConnections.put(clientTcp, connection);
            ((TcpConnection)connection).socketInfo.put(clientTcp,
                    new InfoAboutTcpConnection(clientTcp, connection));
        }

        private void readableHandler(SelectionKey key)
                throws IOException, ClassNotFoundException {
            Channel currChannel = key.channel();
            registeredConnections.get(currChannel).getMessageFromClient(
                    currChannel, messageBuffer);
        }

        private void closeResource() {
            try {
                Set<SelectionKey> selectedKeys = selector.keys();

                for (SelectionKey Key : selectedKeys) {
                    Key.channel().close();
                }

                selector.close();
            } catch (IOException e) {
                System.out.println("close resource" + e);
            }
        }
    }

    private void closeAndRemoveClient(SocketChannel client) throws IOException {
        client.close();
        connectionHandler.registeredConnections.remove(client, this);
    }


    /*----------------------- Message.Message Handler class --------------------------*/

    private class MessageHandler {
        Map<ProtocolType, Protocol> protocolsMap = new HashMap<>();

        public MessageHandler() {
            ChatServerProtocol chatServer = new ChatServerProtocol();
            addProtocol(chatServer);
        }

        private void handleMessage(ByteBuffer messageBuffer,
                                   InfoAboutConnection clientInfo)
                throws ClassNotFoundException, IOException {
            Message<ProtocolType, Message<?, ?>> message =
                    ServerMessage.toMessageObject(messageBuffer);
            Message<?, ?> messageProtocol = message.getData();
            Protocol protocol = protocolsMap.get(message.getKey());

            protocol.handleMessage(clientInfo, messageProtocol);
        }

        private void addProtocol(Protocol protocol) {
            protocolsMap.put(protocol.getKeyProtocol(), protocol);
        }
    }

    /*------------------------ Protocol interface ----------------------------*/

    private interface Protocol {
        public void handleMessage(InfoAboutConnection clientInfo ,
                                  Message<?, ?> message) throws IOException;
        public ProtocolType getKeyProtocol();
    }


    /*--------------------- ChatServer Protocol class ------------------------*/

    private class ChatServerProtocol implements Protocol {
        private Map<Channel, ChatClient> registedClientsInChat = new HashMap<>();
        private ServerMessage returnMessage = new ServerMessage(
                ProtocolType.CHAT_SERVER, null);

        private final static String CONNECTION_EXSIT_DATA = "Connection exsit.";
        private final static String OCCUPIED_CLIENT_NAME = "Client name is occupied.";
        private final static String NOT_REGISTED = "you are not registred.";
        private final static String INVALID_KEY = "Invalid key.";
        private final static String INVALID_CONNECTION = "Invalid connection.";
        private final static String JOINED = " joined.";
        private final static String LEFT = " left.";

        @Override
        public void handleMessage(InfoAboutConnection clientInfo,
                                  Message<?, ?> message) throws IOException {
            ChatServerMessage chatMessage = (ChatServerMessage) message;

            if(!isValidConnection(clientInfo)) {
                sendErrorMessage(clientInfo, chatMessage, INVALID_CONNECTION);
            }
            else if(!registedClientsInChat.containsKey(clientInfo.getChannel())) {
                registrationHandle(clientInfo, chatMessage);
            }
            else {
                switch (chatMessage.getKey()) {
                    case REGISTRATION_REQUEST:
                        sendRegistraionRefuseMessage(clientInfo, chatMessage,
                                CONNECTION_EXSIT_DATA);
                        break;

                    case MESSAGE:
                        chatMessageHandler(clientInfo, chatMessage);
                        break;

                    case REMOVE_REQUEST:
                        removeClientFromChat(clientInfo, chatMessage);
                        break;

                    default:
                        sendErrorMessage(clientInfo, chatMessage, INVALID_KEY);
                        break;
                }
            }
        }

        private boolean isValidConnection(InfoAboutConnection clientInfo) {
            return clientInfo.getConnection() instanceof TcpConnection &&
                    (ProtocolPort.CHAT_PROTOCOL_PORT.getPort() ==
                            clientInfo.getConnection().getPort());
        }

        private void registrationHandle(InfoAboutConnection clientInfo,
                                        ChatServerMessage chatMessage) throws IOException {
            if(chatMessage.getKey().equals(ChatServerMessage.ChatProtocolKeys.REGISTRATION_REQUEST)) {
                if(isClientNameExsit(chatMessage.getData())) {
                    sendRegistraionRefuseMessage(clientInfo, chatMessage,
                            OCCUPIED_CLIENT_NAME);
                }
                else {
                    registerClientToChat(clientInfo, chatMessage);
                }

            }
            else {
                sendErrorMessage(clientInfo, chatMessage, NOT_REGISTED);
            }
        }

        private void sendErrorMessage(InfoAboutConnection clientInfo,
                                      ChatServerMessage chatMessage, String reasonOfError)
                throws IOException {
            setChatMessage(chatMessage, ChatProtocolKeys.ERROR_MESSAGE, reasonOfError);
            sendMessageToClient(clientInfo, chatMessage);
        }

        private boolean isClientNameExsit(String clientName) {
            Collection<ChatClient> clients =   registedClientsInChat.values();
            for (ChatClient chatClient : clients) {
                if (0 == chatClient.getClientName().compareTo(clientName)) {
                    return true;
                }
            }

            return false;
        }

        private void registerClientToChat(InfoAboutConnection clientInfo,
                                          ChatServerMessage chatMessage) throws IOException {
            ChatClient client = new ChatClient(chatMessage.getData(), clientInfo);
            registedClientsInChat.put(clientInfo.getChannel(), client);
            chatMessage.setKey(ChatProtocolKeys.REGISTRATION_ACK);

            sendMessageToClient(clientInfo, chatMessage);
            updateChatNewUserRegister(clientInfo, chatMessage);
        }

        private void updateChatNewUserRegister(InfoAboutConnection clientInfo,
                                               ChatServerMessage chatMessage) throws IOException {
            setChatMessage(chatMessage, ChatProtocolKeys.NEW_CLIENT_REGISTRATION,
                    chatMessage.getData() + JOINED);
            sendMessageToChat(clientInfo, chatMessage);
        }

        private void sendRegistraionRefuseMessage(InfoAboutConnection clientInfo,
                                                  ChatServerMessage chatMessage, String reasonOfRefuse)
                throws IOException {
            setChatMessage(chatMessage, ChatProtocolKeys.REGISTRATION_REFUSE,
                    reasonOfRefuse);

            sendMessageToClient(clientInfo, chatMessage);
        }

        private void removeClientFromChat(InfoAboutConnection clientInfo,
                                          ChatServerMessage chatMessage) throws IOException {
            registedClientsInChat.remove(clientInfo.getChannel());
            connectionHandler.registeredConnections.remove(
                    clientInfo.getChannel());

            setChatMessage(chatMessage, ChatProtocolKeys.BROADCAST_MESSAGE,
                    chatMessage.getData() + LEFT);
            sendMessageToChat(clientInfo, chatMessage);
        }

        private void chatMessageHandler(InfoAboutConnection clientInfo,
                                        ChatServerMessage chatMessage) throws IOException {
            String clientName = registedClientsInChat.get(
                    clientInfo.getChannel()).getClientName();
            setChatMessage(chatMessage, ChatProtocolKeys.BROADCAST_MESSAGE,
                    clientName + ": " +  chatMessage.getData());

            sendMessageToChat(clientInfo, chatMessage);
        }

        private void sendMessageToChat(InfoAboutConnection clientInfoSender,
                                       ChatServerMessage chatMessage) throws IOException ,
                ConcurrentModificationException
        {
            System.out.println(chatMessage.getData());
            returnMessage.setData(chatMessage);
            ByteBuffer messageBuffer =
                    ServerMessage.toByteBuffer(returnMessage);

            Collection<ChatClient> Clients = registedClientsInChat.values();

            for (ChatClient chatClient : Clients) {
                InfoAboutConnection clientInfo = chatClient.getClientInfo();

                if(clientInfo != clientInfoSender) {
                    sendMessageIfConnectionValid(clientInfo, messageBuffer);
                }
            }
        }

        private void sendMessageToClient(InfoAboutConnection clientInfo,
                                         ChatServerMessage chatMessage) throws IOException {
            returnMessage.setData(chatMessage);
            ByteBuffer messageBuffer =
                    ServerMessage.toByteBuffer(returnMessage);

            sendMessageIfConnectionValid(clientInfo, messageBuffer);
        }

        private void sendMessageIfConnectionValid(InfoAboutConnection clientInfo,
                                                  ByteBuffer messageBuffer) throws IOException {
            if(((SocketChannel)(clientInfo.getChannel())).isConnected()) {
                clientInfo.getConnection().sendMessage(clientInfo, messageBuffer);
            }
            else {
                closeAndRemoveClient((SocketChannel)clientInfo.getChannel());
                registedClientsInChat.remove(clientInfo.getChannel());
            }
        }

        private void setChatMessage(ChatServerMessage chatMessage,
                                    ChatProtocolKeys key, String data) {
            chatMessage.setKey(key);
            chatMessage.setData(data);
        }


        @Override
        public ProtocolType getKeyProtocol() {
            return ProtocolType.CHAT_SERVER;
        }

    }


    /*------------------------- Connection Interface -------------------------*/

    private interface Connection {
        public void sendMessage(InfoAboutConnection clientInfo ,
                                ByteBuffer messageBuffer) throws IOException;
        public void initAndRegisterToServer() throws IOException;
        public Channel getChannel();
        public void getMessageFromClient(Channel channel, ByteBuffer messageBuffer)
                throws IOException, ClassNotFoundException;
        public int getPort();
    }


    /*------------------------ TCP Connection class --------------------------*/

    private class TcpConnection implements Connection {
        private ServerSocketChannel tcpserverSocket;
        private final int PORT_NUM;
        private Map<SocketChannel, InfoAboutConnection> socketInfo =
                new HashMap<>();

        public TcpConnection(int portNum) throws Exception {
            if(0 >= portNum) {
                throw new Exception("number of port is not valid.");
            }

            PORT_NUM = portNum;
        }

        @Override
        public void sendMessage(InfoAboutConnection clientInfo,
                                ByteBuffer messageBuffer) throws IOException {
            SocketChannel client = (SocketChannel) clientInfo.getChannel();

            messageBuffer.flip();
            while(messageBuffer.hasRemaining()) {
                client.write(messageBuffer);
            }
        }

        @Override
        public void initAndRegisterToServer() throws IOException {
            tcpserverSocket = ServerSocketChannel.open();
            tcpserverSocket.bind(
                    new InetSocketAddress(InetAddress.getLocalHost(),PORT_NUM));
            tcpserverSocket.configureBlocking(false);
            tcpserverSocket.register(connectionHandler.selector,
                    SelectionKey.OP_ACCEPT);
        }

        @Override
        public Channel getChannel() {
            return tcpserverSocket;
        }

        @Override
        public void getMessageFromClient(Channel channel, ByteBuffer messageBuffer)
                throws IOException, ClassNotFoundException {
            SocketChannel client = (SocketChannel) channel;

            messageBuffer.clear();
            if(-1 == client.read(messageBuffer)) {
                closeAndRemoveClient(client);
            }
            else {
                messageBuffer.clear();
                InfoAboutConnection clientInfo = socketInfo.get(client);
                messageHandler.handleMessage(messageBuffer, clientInfo);
            }
        }

        @Override
        public int getPort() {
            return PORT_NUM;
        }
    }


    /*------------------------ UDP Connection class --------------------------*/

    private class UdpConnection implements Connection {
        private DatagramChannel udpServerDatagram;
        private final int PORT_NUM;

        public UdpConnection(int portNum) throws Exception {
            if(0 >= portNum) {
                throw new Exception("number of port is not valid.");
            }

            PORT_NUM = portNum;
        }

        @Override
        public void sendMessage(InfoAboutConnection clientInfo,
                                ByteBuffer messageBuffer) throws IOException {
            DatagramChannel client = (DatagramChannel)clientInfo.getChannel();
            messageBuffer.flip();
            client.send(messageBuffer, clientInfo.getclientAddress());
            messageBuffer.clear();
        }

        @Override
        public void initAndRegisterToServer() throws IOException {
            udpServerDatagram = DatagramChannel.open();
            udpServerDatagram.socket().bind(new InetSocketAddress(PORT_NUM));
            udpServerDatagram.configureBlocking(false);
            udpServerDatagram.register(connectionHandler.selector,
                    SelectionKey.OP_READ);
        }

        @Override
        public Channel getChannel() {
            return udpServerDatagram;
        }

        @Override
        public void getMessageFromClient(Channel channel, ByteBuffer messageBuffer)
                throws IOException, ClassNotFoundException {
            DatagramChannel client = (DatagramChannel)channel;
            SocketAddress clientAddress = client.receive(messageBuffer);
            InfoAboutConnection clientInfo =
                    new InfoAboutUdpConnection(client, clientAddress, this);

            if(null != clientAddress) {
                messageHandler.handleMessage(messageBuffer, clientInfo);
            }
        }

        @Override
        public int getPort() {
            return PORT_NUM;
        }
    }


    /*--------------------- Broadcast Connection class -----------------------*/

    private class BroadcastConnection implements Connection {
        private DatagramChannel broadcastServerDatagram;
        private final int PORT_NUM;


        public BroadcastConnection(int portNum) throws Exception {
            if(0 >= portNum) {
                throw new Exception("number of port is not valid.");
            }

            PORT_NUM = portNum;
        }

        @Override
        public void sendMessage(InfoAboutConnection clientInfo,
                                ByteBuffer messageBuffer) throws IOException {
            DatagramChannel client = (DatagramChannel)clientInfo.getChannel();
            messageBuffer.flip();
            client.send(messageBuffer, clientInfo.getclientAddress());
            messageBuffer.clear();
        }

        @Override
        public void initAndRegisterToServer() throws IOException {
            broadcastServerDatagram = DatagramChannel.open();
            broadcastServerDatagram.socket().bind(
                    new InetSocketAddress(PORT_NUM));
            broadcastServerDatagram.configureBlocking(false);
            broadcastServerDatagram.register(connectionHandler.selector,
                    SelectionKey.OP_READ);
        }

        @Override
        public Channel getChannel() {
            return broadcastServerDatagram;
        }

        @Override
        public void getMessageFromClient(Channel channel, ByteBuffer messageBuffer)
                throws IOException, ClassNotFoundException {
            DatagramChannel client = (DatagramChannel)channel;
            SocketAddress clientAddress = client.receive(messageBuffer);
            InfoAboutConnection clientInfo =
                    new InfoAboutBroadcastConnection(client, clientAddress, this);

            if(null != clientAddress) {
                messageHandler.handleMessage(messageBuffer, clientInfo);
            }
        }

        @Override
        public int getPort() {
            return PORT_NUM;
        }
    }


    /*------------------- InfoAboutConnection interface ----------------------*/

    private interface InfoAboutConnection {
        public Channel getChannel();
        public SocketAddress getclientAddress();
        public Connection getConnection();
    }


    /*------------------- InfoAboutTcpConnection class -----------------------*/

    private class InfoAboutTcpConnection implements InfoAboutConnection {
        private SocketChannel chennelTCp;
        private Connection connection;

        public InfoAboutTcpConnection(SocketChannel chennelTCp,
                                      Connection connection) {
            this.chennelTCp = chennelTCp;
            this.connection = connection;
        }

        @Override
        public Channel getChannel() {
            return chennelTCp;
        }

        @Override
        public SocketAddress getclientAddress() {
            return null;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

    }


    /*------------------- InfoAboutUdpConnection class -----------------------*/

    private class InfoAboutUdpConnection implements InfoAboutConnection {
        private DatagramChannel udpServer;
        private SocketAddress clientAddress;
        private Connection connection;

        public InfoAboutUdpConnection(DatagramChannel udpServer,
                                      SocketAddress clientAddress, Connection connection) {
            this.udpServer = udpServer;
            this.clientAddress = clientAddress;
            this.connection = connection;
        }

        @Override
        public Channel getChannel() {
            return udpServer;
        }


        @Override
        public SocketAddress getclientAddress() {
            return clientAddress;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

    }


    /*---------------- InfoAboutBroadcastConnection class --------------------*/

    private class InfoAboutBroadcastConnection implements InfoAboutConnection {
        private DatagramChannel broadcastServer;
        private SocketAddress clientAddress;
        private Connection connection;

        public InfoAboutBroadcastConnection(DatagramChannel broadcastServer,
                                            SocketAddress clientAddress, Connection connection) {
            this.broadcastServer= broadcastServer;
            this.clientAddress = clientAddress;
            this.connection = connection;
        }

        @Override
        public Channel getChannel() {
            return broadcastServer;
        }


        @Override
        public SocketAddress getclientAddress() {
            return clientAddress;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

    }

    /*---------------- ChatClient class --------------------*/
    private class ChatClient {
        private String clientName;
        private InfoAboutConnection clientInfo;

        public ChatClient(String clientName, InfoAboutConnection clientInfo) {
            this.clientName = clientName;
            this.clientInfo = clientInfo;
        }

        public String getClientName() {
            return clientName;
        }

        public InfoAboutConnection getClientInfo() {
            return clientInfo;
        }
    }


}