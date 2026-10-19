import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class InventoryServer {
    private static final int PORT = 8080;
    private static final int MAX_BODY_BYTES = 8_192;
    private static final String SESSION_COOKIE = "inventory_session";
    private static final String DEMO_USERNAME = "admin";
    private static final String DEMO_PASSWORD = "inventory123";
    private static final Path PUBLIC_DIR = Path.of("public");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();
    private static final List<Product> PRODUCTS = new CopyOnWriteArrayList<>(List.of(
            new Product(1, "WH-1001", "Wireless Headphones", "Electronics", 24, 79.99),
            new Product(2, "WH-1002", "Desk Lamp", "Home & Office", 8, 34.50),
            new Product(3, "WH-1003", "Ceramic Mug Set", "Kitchen", 42, 18.00),
            new Product(4, "WH-1004", "Notebook Set", "Stationery", 5, 12.75),
            new Product(5, "WH-1005", "USB-C Hub", "Electronics", 16, 49.95),
            new Product(6, "WH-1006", "Canvas Tote Bag", "Accessories", 31, 22.00)
    ));
    private static final AtomicInteger NEXT_PRODUCT_ID = new AtomicInteger(7);

    private record Product(int id, String sku, String name, String category, int quantity, double price) {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? PORT : Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", InventoryServer::handleApi);
        server.createContext("/", InventoryServer::handleStatic);
        server.start();
        System.out.println("Inventory app running at http://127.0.0.1:" + port);
        System.out.println("Demo login: " + DEMO_USERNAME + " / " + DEMO_PASSWORD);
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (path.equals("/api/login") && method.equals("POST")) {
                login(exchange);
            } else if (path.equals("/api/logout") && method.equals("POST")) {
                logout(exchange);
            } else if (path.equals("/api/session") && method.equals("GET")) {
                requireSession(exchange);
            } else if (path.equals("/api/products") && method.equals("GET")) {
                if (requireSession(exchange)) {
                    sendJson(exchange, 200, productsJson());
                }
            } else if (path.equals("/api/products") && method.equals("POST")) {
                if (requireSession(exchange)) {
                    addProduct(exchange);
                }
            } else {
                sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, "{\"error\":\"" + jsonEscape(exception.getMessage()) + "\"}");
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            System.err.println("Request failed: " + exception.getMessage());
            sendJson(exchange, 500, "{\"error\":\"The server could not complete the request\"}");
        } finally {
            exchange.close();
        }
    }

    private static void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(exchange);
        String username = form.getOrDefault("username", "");
        String password = form.getOrDefault("password", "");
        if (!constantTimeEquals(username, DEMO_USERNAME) || !constantTimeEquals(password, DEMO_PASSWORD)) {
            sendJson(exchange, 401, "{\"error\":\"Username or password is incorrect\"}");
            return;
        }

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        SESSIONS.put(token, username);
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=" + token + "; HttpOnly; Path=/; SameSite=Strict");
        sendJson(exchange, 200, "{\"username\":\"" + jsonEscape(username) + "\"}");
    }

    private static void logout(HttpExchange exchange) throws IOException {
        String token = sessionToken(exchange);
        if (token != null) {
            SESSIONS.remove(token);
        }
        exchange.getResponseHeaders().add("Set-Cookie",
                SESSION_COOKIE + "=; HttpOnly; Path=/; SameSite=Strict; Max-Age=0");
        sendJson(exchange, 200, "{\"ok\":true}");
    }

    private static boolean requireSession(HttpExchange exchange) throws IOException {
        String token = sessionToken(exchange);
        String username = token == null ? null : SESSIONS.get(token);
        if (username == null) {
            sendJson(exchange, 401, "{\"error\":\"Please log in to continue\"}");
            return false;
        }
        if (exchange.getRequestURI().getPath().equals("/api/session")) {
            sendJson(exchange, 200, "{\"username\":\"" + jsonEscape(username) + "\"}");
        }
        return true;
    }

    private static synchronized void addProduct(HttpExchange exchange) throws IOException {
        Map<String, String> form = parseForm(exchange);
        String sku = requiredValue(form, "sku", "SKU");
        String name = requiredValue(form, "name", "Product name");
        String category = requiredValue(form, "category", "Category");

        int quantity;
        double price;
        try {
            quantity = Integer.parseInt(form.getOrDefault("quantity", ""));
            price = Double.parseDouble(form.getOrDefault("price", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid quantity and price");
        }
        if (quantity < 0 || price < 0 || !Double.isFinite(price)) {
            throw new IllegalArgumentException("Quantity and price must be zero or greater");
        }
        if (sku.length() > 40 || name.length() > 100 || category.length() > 60) {
            throw new IllegalArgumentException("One or more product fields are too long");
        }
        boolean duplicateSku = PRODUCTS.stream().anyMatch(product -> product.sku().equalsIgnoreCase(sku));
        if (duplicateSku) {
            throw new IllegalArgumentException("That SKU is already in use");
        }

        Product product = new Product(NEXT_PRODUCT_ID.getAndIncrement(), sku, name, category, quantity, price);
        PRODUCTS.add(product);
        sendJson(exchange, 201, productJson(product));
    }

    private static String requiredValue(Map<String, String> form, String key, String label) {
        String value = form.getOrDefault(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Request is too large");
        }
        Map<String, String> values = new java.util.HashMap<>();
        String raw = new String(body, StandardCharsets.UTF_8);
        for (String entry : raw.split("&")) {
            if (entry.isEmpty()) {
                continue;
            }
            String[] pair = entry.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            values.put(key, value);
        }
        return values;
    }

    private static String sessionToken(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) {
            return null;
        }
        for (String cookie : cookieHeader.split(";")) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(SESSION_COOKIE)) {
                return pair[1];
            }
        }
        return null;
    }

    private static boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private static void handleStatic(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("GET")) {
                sendText(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String fileName = switch (path) {
                case "/", "/index.html" -> "index.html";
                case "/styles.css" -> "styles.css";
                case "/app.js" -> "app.js";
                default -> null;
            };
            if (fileName == null) {
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }

            Path file = PUBLIC_DIR.resolve(fileName);
            byte[] content = Files.readAllBytes(file);
            String contentType = fileName.endsWith(".css") ? "text/css; charset=utf-8"
                    : fileName.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : "text/html; charset=utf-8";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            addSecurityHeaders(exchange);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
        } catch (IOException exception) {
            System.err.println("Could not serve web page: " + exception.getMessage());
            sendText(exchange, 500, "The web app could not be loaded", "text/plain; charset=utf-8");
        } finally {
            exchange.close();
        }
    }

    private static String productsJson() {
        List<String> products = new ArrayList<>();
        for (Product product : PRODUCTS) {
            products.add(productJson(product));
        }
        return "[" + String.join(",", products) + "]";
    }

    private static String productJson(Product product) {
        return "{\"id\":" + product.id()
                + ",\"sku\":\"" + jsonEscape(product.sku())
                + "\",\"name\":\"" + jsonEscape(product.name())
                + "\",\"category\":\"" + jsonEscape(product.category())
                + "\",\"quantity\":" + product.quantity()
                + ",\"price\":" + product.price() + "}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        sendText(exchange, status, json, "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int status, String text, String contentType)
            throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                        + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }
}
