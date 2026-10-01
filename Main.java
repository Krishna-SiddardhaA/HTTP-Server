import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

public class Main {

    public static final ConcurrentHashMap<String, Piperoom> activerooms = new ConcurrentHashMap<>();
    
    public static class Piperoom {
        public java.util.concurrent.LinkedBlockingQueue<byte[]> queue;
        public CountDownLatch latch;
        public long fileSize = 0;
        public String fileName = "GhostBridge_Transfer.zip"; // Default fallback
        public volatile boolean isFinished = false;

        public Piperoom() {
            this.queue = new java.util.concurrent.LinkedBlockingQueue<>(2000); 
            this.latch = new CountDownLatch(2);
        }
    }

    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");
        
        String tempDirectory = "";
        if (args.length >= 2 && args[0].equals("--directory")) {
            tempDirectory = args[1];
        }
        
        final String directory = tempDirectory;
        System.out.println("Serving files from directory: " + directory);

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket, directory)).start();
            }
        } catch (IOException e) {
            System.out.println("Server Exception: " + e.getMessage());
        }
    }

    public static void handleClient(Socket socket, String directory) {
        try {
            java.io.InputStream rawInput = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            
            while (true) {
                StringBuilder headerText = new StringBuilder();
                int b;
                
                while ((b = rawInput.read()) != -1) {
                    headerText.append((char) b);
                    if (headerText.toString().endsWith("\r\n\r\n")) {
                        break;
                    }
                }
                
                if (headerText.length() == 0) {
                    break;
                }
                
                String[] headerLines = headerText.toString().split("\r\n");
                if (headerLines.length == 0 || headerLines[0].isEmpty()) return;

                String[] requestParts = headerLines[0].split(" ");
                if (requestParts.length < 2) return;

                String method = requestParts[0];
                String path = requestParts[1];

                boolean closeConnection = false;
                String acceptedEncodings = "";
                String userAgent = null;
                long contentLength = 0;
                
                long totalFileSize = 0; 
                boolean isFirstChunk = false; 
                boolean isLastChunk = false; 
                String originalFileName = null;
                
                for (int i = 1; i < headerLines.length; i++) {
                    String headerLine = headerLines[i];
                    if (headerLine.isEmpty()) continue;
                    
                    String lowerHeader = headerLine.toLowerCase();
                    if (lowerHeader.startsWith("connection:") && lowerHeader.contains("close")) {
                        closeConnection = true;
                    } else if (lowerHeader.startsWith("accept-encoding:")) {
                        acceptedEncodings = headerLine.substring(16).trim();
                    } else if (lowerHeader.startsWith("user-agent:")) {
                        userAgent = headerLine.substring(11).trim();
                    } else if (lowerHeader.startsWith("content-length:")) {
                        contentLength = Long.parseLong(headerLine.substring(15).trim());
                    } else if (lowerHeader.startsWith("x-file-size:")) {
                        totalFileSize = Long.parseLong(headerLine.substring(12).trim());
                        isFirstChunk = true;
                    } else if (lowerHeader.startsWith("x-last-chunk:")) {
                        isLastChunk = headerLine.substring(13).trim().equals("true");
                    } else if (lowerHeader.startsWith("x-file-name:")) {
                        originalFileName = java.net.URLDecoder.decode(headerLine.substring(12).trim(), StandardCharsets.UTF_8);
                    }
                }
                
                String connHeader = closeConnection ? "Connection: close\r\n" : "";

                if (path.equals("/")) {
                    try {
                        java.io.File file = new java.io.File("index.html");
                        byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());

                        String responseHeaders = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" + connHeader + "Content-Length: " + fileBytes.length + "\r\n\r\n";
                        out.write(responseHeaders.getBytes());
                        out.write(fileBytes);
                    } catch (IOException e) {
                        String errorMsg = "HTTP/1.1 500 Internal Server Error\r\n" + connHeader + "\r\n";
                        out.write(errorMsg.getBytes());
                    }
                } 
                else if (path.startsWith("/echo/")) {
                    String body = path.substring(6);
                    boolean supportsGzip = false;
                    
                    if (!acceptedEncodings.isEmpty()) {
                        String[] encodings = acceptedEncodings.split(",");
                        for (String encoding : encodings) {
                            if (encoding.trim().equalsIgnoreCase("gzip")) {
                                supportsGzip = true;
                                break;
                            }
                        }
                    }
                    
                    byte[] originalBytes = body.getBytes(StandardCharsets.UTF_8);
                    byte[] responseBytes;
                    String responseHeaders = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" + connHeader;

                    if (supportsGzip) {
                        ByteArrayOutputStream compresssedBuffer = new ByteArrayOutputStream();
                        GZIPOutputStream gzipOutputStream = new GZIPOutputStream(compresssedBuffer);
                        gzipOutputStream.write(originalBytes);
                        gzipOutputStream.close();
                        
                        responseBytes = compresssedBuffer.toByteArray();
                        responseHeaders += "Content-Encoding: gzip\r\n";
                    } else {
                        responseBytes = originalBytes;
                    }
                    
                    responseHeaders += "Content-Length: " + responseBytes.length + "\r\n\r\n";
                    out.write(responseHeaders.getBytes());
                    out.write(responseBytes);
                } 
                else if (path.startsWith("/pipe/")) {
                    String roomCode = path.substring(6);
                    Piperoom room = activerooms.computeIfAbsent(roomCode, k -> new Piperoom());
                    
                    try {
                        if (method.equals("POST")) {
                            if (isFirstChunk) {
                                room.fileSize = totalFileSize;
                                if (originalFileName != null) room.fileName = originalFileName;
                                room.latch.countDown();
                            }
                            
                            room.latch.await();

                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            long totalBytesRead = 0;
                                
                            while (totalBytesRead < contentLength && (bytesRead = rawInput.read(buffer, 0, (int)Math.min(buffer.length, contentLength - totalBytesRead))) != -1) {
                                room.queue.put(java.util.Arrays.copyOf(buffer, bytesRead));
                                totalBytesRead += bytesRead;
                            }

                            if (isLastChunk) {
                                room.isFinished = true;
                            }

                            String response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
                            out.write(response.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                        else if (method.equals("GET")) {
                            room.latch.countDown();
                            room.latch.await();

                            String responseHeaders = "HTTP/1.1 200 OK\r\n" + 
                                                     "Content-Type: application/octet-stream\r\n" +
                                                     "Content-Disposition: attachment; filename=\"" + room.fileName + "\"\r\n" +
                                                     "Content-Length: " + room.fileSize + "\r\n\r\n";
                            out.write(responseHeaders.getBytes());
                            out.flush();
                            System.out.println("Starting download! File: " + room.fileName + " | Size: " + room.fileSize);
                            
                            while (true) {
                                byte[] data = room.queue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                                if (data != null) {
                                    out.write(data);
                                    out.flush();
                                } else if (room.isFinished && room.queue.isEmpty()) {
                                    break; 
                                }
                            }
                            activerooms.remove(roomCode);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        out.write("HTTP/1.1 500 Internal Server Error\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                }
                else if (path.startsWith("/user-agent")) {
                    if (userAgent != null) {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" + connHeader + "Content-Length: " + userAgent.length() + "\r\n\r\n" + userAgent).getBytes());
                    } else {
                        out.write(("HTTP/1.1 400 Bad Request\r\n" + connHeader + "\r\n").getBytes());
                    }
                } 
                else if (path.startsWith("/files/")) {
                    String fileName = path.substring(7);
                    Path filepath = Paths.get(directory, fileName);
                    
                    if (method.equals("GET")) {
                        if (Files.exists(filepath)) {
                            byte[] fileContents = Files.readAllBytes(filepath);
                            String responseHeaders = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" + connHeader + "Content-Length: " + fileContents.length + "\r\n\r\n";
                            out.write(responseHeaders.getBytes());
                            out.write(fileContents);
                        } else {
                            out.write(("HTTP/1.1 404 Not Found\r\n" + connHeader + "\r\n").getBytes());
                        }
                    } 
                    else if (method.equals("POST")) {
                        long totalBytesRead = 0;
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        
                        try (java.io.FileOutputStream fileStream = new java.io.FileOutputStream(filepath.toFile())) {
                            while (totalBytesRead < contentLength && (bytesRead = rawInput.read(buffer, 0, (int)Math.min(buffer.length, contentLength - totalBytesRead))) != -1) {
                                fileStream.write(buffer, 0, bytesRead);
                                totalBytesRead += bytesRead;
                            }
                        }
                        
                        String responseHeaders = "HTTP/1.1 201 Created\r\n" + connHeader + "\r\n";
                        out.write(responseHeaders.getBytes());
                    }
                } 
                else {
                    out.write(("HTTP/1.1 404 Not Found\r\n" + connHeader + "\r\n").getBytes());
                }
                
                out.flush();
                
                if (closeConnection) {
                    break;
                }
            }
            
            socket.close(); 
            
        } catch (IOException e) {
            System.out.println("Client disconnected: " + e.getMessage());
        }
    }
}