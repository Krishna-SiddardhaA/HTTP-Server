import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class Main {
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
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            
            String requestLine;
            
            while ((requestLine = in.readLine()) != null) {
                if (requestLine.isEmpty()) continue;
                
                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 2) continue;
                
                String method = requestParts[0]; 
                String path = requestParts[1];
                
                // 1. GLOBAL HEADER PARSER: Read all headers before routing
                String headerLine;
                boolean closeConnection = false;
                String acceptedEncodings = "";
                String userAgent = null;
                int contentLength = 0;
                
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    String lowerHeader = headerLine.toLowerCase();
                    
                    if (lowerHeader.startsWith("connection:") && lowerHeader.contains("close")) {
                        closeConnection = true;
                    } else if (lowerHeader.startsWith("accept-encoding:")) {
                        acceptedEncodings = headerLine.substring(16).trim();
                    } else if (lowerHeader.startsWith("user-agent:")) {
                        userAgent = headerLine.substring(11).trim();
                    } else if (lowerHeader.startsWith("content-length:")) {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    }
                }
                
                // Prepare the connection header to append to all responses
                String connHeader = closeConnection ? "Connection: close\r\n" : "";

                // 2. CLEAN ROUTING
                if (path.equals("/")) {
                    String responseBody = "Hello, World!";
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" + connHeader + "Content-Length: " + responseBody.length() + "\r\n\r\n" + responseBody).getBytes());
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
                        char[] bodyChars = new char[contentLength];
                        int charactersRead = 0;
                        while (charactersRead < contentLength) {
                            int read = in.read(bodyChars, charactersRead, contentLength - charactersRead);
                            if (read == -1) break;
                            charactersRead += read;
                        }
                        
                        String body = new String(bodyChars, 0, charactersRead);
                        Files.write(filepath, body.getBytes());
                        
                        String responseHeaders = "HTTP/1.1 201 Created\r\n" + connHeader + "\r\n";
                        out.write(responseHeaders.getBytes());
                    }
                } 
                else {
                    out.write(("HTTP/1.1 404 Not Found\r\n" + connHeader + "\r\n").getBytes());
                }
                
                out.flush();
                
                // 3. THE HANG UP
                // If the client requested closure, break out of the while loop to close the socket
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