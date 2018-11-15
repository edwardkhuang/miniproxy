package com.horrible;

import java.io.*;
import java.net.*;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// code heavily derived from https://stackoverflow.com/questions/16351413/java-https-proxy-using-https-proxyport-and-https-proxyhost
public class MiniProxy extends Thread {
    public static final int PORT = 9999;
    public static final boolean PRINT_DEBUG_LINES = false;

    // start here.
    public static void main(String[] args) {
        try {
            System.out.println("Starting MiniProxy at " + InetAddress.getLocalHost().getHostAddress() + " on port " + PORT);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        (new MiniProxy()).run();
    }

    public MiniProxy() {
        super("MiniProxy");
    }

    @Override
    public void run() {
        // open a local passive socket to listen for clients
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            Socket socket;
            // hand off active requests to the Handler class
            while ((socket = serverSocket.accept()) != null) {
                (new Handler(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    public static class Handler extends Thread {
        public static final int MAXIMUM_MESSAGE_BODY_SIZE_BYTES = 600000;
        public static final int MESSAGE_BODY_BYTES_READ_PER_TICK = 4096;

        // create two regexs, one that handles HTTP Tunnelling, and one that handles standard GET/POST HTTP traffic
        public static final Pattern CONNECT_PATTERN =
          Pattern.compile("CONNECT (.+):(.+) HTTP/(1\\.[01])",
            Pattern.CASE_INSENSITIVE);
        public static final Pattern GET_POST_PATTERN =
          Pattern.compile("(GET|POST) (?:http)://([^/:]*)(?::([^/]*))?(/.*) HTTP/(1\\.[01])",
            Pattern.CASE_INSENSITIVE);

        private final Socket clientSocket;

        public Handler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try {
                // read out the HTTP request from the client.
                // interaction with this proxy is ALWAYS on behalf of the client making an outbound request
                List<String> request = parseMessage(clientSocket);

                // the first line from the request is referred to as the 'Request-Line'
                String requestLine = request.get(0);
                print(requestLine);
                Matcher connectMatcher = CONNECT_PATTERN.matcher(requestLine);

                // if the Request-Line happens to be a CONNECT request, we open an HTTPS Tunnel and forward data back and forth
                if (connectMatcher.matches()) {
                    final Socket hostSocket;
                    try {
                        // open an active socket outwards to the location the client requested to connect to
                        hostSocket = new Socket(connectMatcher.group(1), Integer.parseInt(connectMatcher.group(2)));
                    } catch (IOException | NumberFormatException e) {
                        e.printStackTrace();
                        respondWith502BadGateway(clientSocket, connectMatcher.group(3));
                        return;
                    }
                    try {
                        // once the active socket is open, tell the client that we're ready to begin sending encrypted traffic
                        respondWith200Okay(clientSocket, connectMatcher.group(3));

                        // create two threads, one for each traffic direction
                        // once the sockets are closed (either the client or host closes these), kill the threads
                        Thread remoteToClient = new Thread(() -> forwardTunnelledData(hostSocket, clientSocket, "<--"));
                        remoteToClient.start();
                        Thread clientToRemote = new Thread(() -> forwardTunnelledData(clientSocket, hostSocket, "-->"));
                        clientToRemote.start();
                        remoteToClient.join();
                        clientToRemote.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        hostSocket.close();
                    }
                    return;
                }

                // if the Request-Line happens to be GET or POST, we need to do some additional processing
                // but for the most part, it's also just opening a socket to the port and forwarding data
                Matcher httpMatcher = GET_POST_PATTERN.matcher(requestLine);
                if (httpMatcher.matches()) {
                    try {
                        String method = httpMatcher.group(1);
                        String hostString = httpMatcher.group(2);
                        String portString = httpMatcher.group(3);
                        Integer port = portString == null || "".equals(portString) ? 80 : Integer.parseInt(portString);

                        final Socket hostSocket;
                        try {
                            hostSocket = new Socket(hostString, port);
                        } catch (IOException | NumberFormatException e) {
                            e.printStackTrace();
                            respondWith502BadGateway(clientSocket, connectMatcher.group(3));
                            return;
                        }
                        byte[] requestBody = forwardMessage("-->", hostSocket, clientSocket, method, request);
                        List<String> responseHeaders = parseMessage(hostSocket);
                        byte[] responseBody = forwardMessage("<--", clientSocket, hostSocket, null, responseHeaders);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return;
                }

                err("Unmatched request: " + requestLine);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void respondWith502BadGateway(Socket clientSocket, String host) throws IOException {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream(),
              "ISO-8859-1");
            outputStreamWriter.write("HTTP/" + host + " 502 Bad Gateway\r\n");
            outputStreamWriter.write("Proxy-agent: MiniProxy/0.1\r\n");
            outputStreamWriter.write("\r\n");
            outputStreamWriter.flush();
        }

        private void respondWith200Okay(Socket clientSocket, String host) throws IOException {
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(clientSocket.getOutputStream(),
              "ISO-8859-1");
            outputStreamWriter.write("HTTP/" + host + " 200 Connection established\r\n");
            outputStreamWriter.write("Proxy-agent: MiniProxy/0.1\r\n");
            outputStreamWriter.write("\r\n");
            outputStreamWriter.flush();
        }

        private void forwardTunnelledData(Socket inputSocket, Socket outputSocket, String threadName) {
            print(threadName + " Begin forwarding data from " + inputSocket + " --> " + outputSocket);
            try {
                InputStream inputStream = inputSocket.getInputStream();
                OutputStream outputStream = outputSocket.getOutputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;
                do {
                    bytesRead = inputStream.read(buffer);
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead);
//                        print(threadName + " " + Base64.getEncoder().encodeToString(Arrays.copyOf(buffer, bytesRead)));
                        if (inputStream.available() < 1) {
                            outputStream.flush();
                        }
                    }
                } while (bytesRead >= 0);
                if (!inputSocket.isInputShutdown()) {
                    inputSocket.shutdownInput();
                }
                if (!outputSocket.isOutputShutdown()) {
                    outputSocket.shutdownOutput();
                }
            } catch (IOException e) {
                err("Connection was reset by server.");
            } catch (BufferOverflowException e) {
                err("Oops, BFE");
            }
        }

        /**
         * Pulls out the Request-Line/Status-Line and accompanying headers up-to the Message-Body
         * The Request-Line/Status-Line will ALWAYS be the first entry in the List
         * The headers will be inserted in the order they are read
         * The final CR LF will be inserted as the last entry in the list as an empty string.
         * @param socket
         * @return
         */
        private List<String> parseMessage(Socket socket) throws IOException {
            List<String> request = new ArrayList<>();
            String line;
            do {
                line = readLinePastCRLF(socket);
                request.add(line);
            } while (!"".equals(line));
            return request;
        }

        private byte[] forwardMessage(String direction, Socket forwardSocket, Socket clientSocket, String method, List<String> messageHeaders) throws IOException {
            byte[] forwardedData = new byte[]{};
            PrintWriter printWriter = new PrintWriter(forwardSocket.getOutputStream());
            for (String line : messageHeaders) {
                printWriter.println(line);
            }
            printWriter.flush();

            if (direction.equals("-->")) {
                if ("GET".equals(method)) {
                    // no more data to forward, just exit. If there is a Message-Body, RFC7230 is violated
                }
                if ("POST".equals(method)) {
                    int contentLength = getContentLengthFromMessage(messageHeaders);
                    forwardedData = forwardMessageBody(direction, clientSocket, forwardSocket, contentLength);
                }
            } else if (direction.equals("<--")) {
                int contentLength = getContentLengthFromMessage(messageHeaders);
                forwardedData = forwardMessageBody(direction, clientSocket, forwardSocket, contentLength);
                if (forwardSocket != null) {
                    forwardSocket.close();
                }
            }
            return forwardedData;
        }

        private int getContentLengthFromMessage(List<String> message) throws IOException {
            Map<String, String> headers = mapifyHeaders(message);
            int length = 0;
            try {
                length = Integer.parseInt(headers.get("Content-Length"));
            } catch (NumberFormatException e) {
                err("No Content-Length found");
            }
            return length;
        }

        // forward only as many bytes as the value of length is (this was the value of the Content-Length header)
        private byte[] forwardMessageBody(String threadName, Socket inputSocket, Socket outputSocket, int length) {
            print(inputSocket + " --> " + outputSocket);
            ByteBuffer fullMessage = ByteBuffer.allocate(MAXIMUM_MESSAGE_BODY_SIZE_BYTES);
            int fullMessageLength = 0;
            try {
                InputStream inputStream = inputSocket.getInputStream();
                OutputStream outputStream = outputSocket.getOutputStream();

                byte[] buffer = new byte[MESSAGE_BODY_BYTES_READ_PER_TICK];
                int read;
                if (length > 0) {
                    do {
                        if ((read = inputStream.read(buffer)) > 0) {
                            fullMessageLength += read;
                            outputStream.write(buffer, 0, read);
                            fullMessage.put(buffer);
                            if (inputStream.available() < 1) {
                                outputStream.flush();
                            }
                            length = length - read;
                        }
                    } while (read >= 0 && (length > 0));
                }
            print(threadName + " Sent " + fullMessageLength + " bytes of message-body");
            } catch (IOException e) {
                e.printStackTrace();
            } catch (BufferOverflowException e) {
                err("Message-Body exceeded maximum allowed size (" + MAXIMUM_MESSAGE_BODY_SIZE_BYTES + ")");
                e.printStackTrace();
            } finally {
                try {
                    if (!inputSocket.isInputShutdown()) {
                        inputSocket.shutdownInput();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return Arrays.copyOfRange(fullMessage.array(), 0, fullMessageLength);
        }

        private String readLinePastCRLF(Socket socket) throws IOException {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            int previousCharAsInt = 0;
            int charAsInt;
            while ((charAsInt = socket.getInputStream().read()) != -1) {
                if (!isCRorLF(charAsInt)) {
                    byteArrayOutputStream.write(charAsInt);
                }
                if (previousCharAsInt == '\r' && charAsInt == '\n') {
                    break;
                }
                previousCharAsInt = charAsInt;
            }
            return byteArrayOutputStream.toString("ISO-8859-1");
        }

        private boolean isCRorLF(int charAsInt) {
            return (charAsInt == '\r' || charAsInt == '\n');
        }

        private void print(String message) {
            if (PRINT_DEBUG_LINES) {
                System.out.println(this.getName() + "| " + message);
            }
        }

        private void err(String message) {
            System.err.println(this.getName() + "| " + message);
        }

        // header order is not preserved. this is technically wrong, and violates RFC2616 Section 4.2
        private Map<String, String> mapifyHeaders(List<String> request) throws IOException {
            Map<String, String> requestHeaders = new HashMap<>();
            for (int i = 1; i < request.size() - 1; i++) {
                String header = request.get(i);
                int delimiter = header.indexOf(':');
                String headerKey = header.substring(0, delimiter).trim();
                String headerValue = header.substring(delimiter+1).trim();
                requestHeaders.put(headerKey, headerValue);
            }
            return requestHeaders;
        }
    }
}