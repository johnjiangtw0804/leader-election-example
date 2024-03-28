import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MyProcess {
    private static final Long MAX_WAIT_TIME_FOR_SERVER_TO_SPAWN = 15000l;
    private static final int WAIT_TIME_FOR_SOCKET_TIMEOUT = 15000;
    private static final  Logger logger = Logger.getLogger(MyProcess.class.getName());

    private static int serverPort;
    private static String clientIP;
    private static int clientPort;
    private static UUID uuid;
    private static int flag;
    private static UUID leaderID;
    public static void init() throws IOException {
        try {
            FileHandler fh = new FileHandler("log.txt", false);
            logger.addHandler(fh);
            logger.setLevel(Level.INFO);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Error occurred while setting up logging", ex);
            throw(ex);
        }
        try {
            File config = new File("config.txt");
            BufferedReader reader = new BufferedReader(new FileReader(config));
            String[] serverIPAndPort = reader.readLine().split(",");
            serverPort = Integer.parseInt(serverIPAndPort[1]);
            String[] clientIPAndPort = reader.readLine().split(",");
            clientIP = clientIPAndPort[0];
            clientPort = Integer.parseInt(clientIPAndPort[1]);
            uuid = UUID.randomUUID();
            flag = 0;
            reader.close();
        } catch (IOException ex){
            logger.log(Level.SEVERE, "Error occurred while reading the config.txt", ex);
            throw(ex);
        }
    }
    public static void setLeader(UUID newLeader) {
        leaderID = newLeader;
        flag = 1;
    }
    public static void main(String[] args) {
        try {
            init();
        } catch (Exception exception){
            System.exit(1);
        }
        logger.info("Init process completed. Server port " + serverPort + " and client ip " + clientIP + " and port " + clientPort + " my uuid is: " + uuid);

        // Create a client thread to send an initial message to start the leader election process
        new Thread() {
            public void run() {
                Long prevTime = System.currentTimeMillis();
                while (flag == 0) {
                    if (System.currentTimeMillis() - prevTime >= MAX_WAIT_TIME_FOR_SERVER_TO_SPAWN) {
                        logger.warning("Initial client: Connection timeout, server not responding...");
                        break;
                    }
                    Message initMessage = new Message(uuid, flag);
                    boolean isOK = sendClientMessage(initMessage);
                    if (isOK) {
                        logger.info("Initial client: initial message sent: " + initMessage.toString());
                        break;
                    }
                    logger.info("Initial message sent failed retrying...");
                    try {
                        Thread.sleep(3000);
                    } catch  (Exception ex){
                        ex.printStackTrace();
                    }
                }
            }
        }.start();

        logger.info("---- Server process started ----");
        try (ServerSocket serverSocket = new ServerSocket(serverPort)) {
            while (flag == 0) {
                Socket connection = serverSocket.accept();
                ObjectInputStream objectInputStream = new ObjectInputStream(connection.getInputStream());
                Message recMessage = (Message)objectInputStream.readObject();
                logger.info("Server: received message uuid: " + recMessage.getUUID() + " flag: " + recMessage.getFlag());

                if (uuid.compareTo(recMessage.getUUID()) == 1) {
                    // do nothing
                    logger.info("Server: My processes's uuid is greater than the messages's uuid");
                    continue;
                } else if (uuid.compareTo(recMessage.getUUID()) == -1) {
                    // forward the message
                    logger.info("Server: My processes's uuid is less than the messages's uuid");
                    Long prevTime = System.currentTimeMillis();
                    while (!sendClientMessage(recMessage)) {
                        if (System.currentTimeMillis() - prevTime >= MAX_WAIT_TIME_FOR_SERVER_TO_SPAWN) {
                            logger.warning("Client: Connection timeout, server not responding...");
                            break;
                        }
                        Thread.sleep(1000);
                    }
                    logger.info("Client Message forwarded: " + recMessage.toString());
                    // if the current message contains flag equal to 1, then the leader has been selected, we can terminate
                    if (recMessage.getFlag() == 1) {
                        setLeader(recMessage.getUUID());
                    }
                } else if (uuid.compareTo(recMessage.getUUID()) == 0) {
                    // the leader is my process, forward the message and mark as elected
                    logger.info("Server: My processes's uuid is equal to the messages's uuid");
                    Message electedMsg = new Message(recMessage.getUUID(), 1);
                    Long prevTime = System.currentTimeMillis();
                    while (!sendClientMessage(electedMsg)) {
                        if (System.currentTimeMillis() - prevTime >= MAX_WAIT_TIME_FOR_SERVER_TO_SPAWN) {
                            logger.warning("Client: Connection timeout, server not responding...");
                            break;
                        }
                        Thread.sleep(1000);
                    }
                    logger.info("Client Message forwarded: " + electedMsg.toString());
                    setLeader(electedMsg.getUUID());
                }
            }
            logger.info("Server: Leader ID is " + leaderID + " Server is terminating...");
        } catch (Exception ex){
            logger.warning("Server: Server socket creation failed");
            System.exit(1);
        }
    }
    public static boolean sendClientMessage(Message msg) {
        try (Socket socket = new Socket(clientIP, clientPort)) {
            socket.setSoTimeout(WAIT_TIME_FOR_SOCKET_TIMEOUT);
            ObjectOutputStream objectOutputStream  = new ObjectOutputStream(socket.getOutputStream());
            objectOutputStream.writeObject(msg);
            return true;
        } catch(Exception ex) {
        }
        return false;
    }
}