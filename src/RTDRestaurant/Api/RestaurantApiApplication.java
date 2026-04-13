package RTDRestaurant.Api;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class RestaurantApiApplication {

    private static final int DEFAULT_PORT = 8081;
    private static final Path WEB_ROOT = Path.of("web");
    private static final Path FOOD_ICON_ROOT = Path.of("src", "Icons", "Food").toAbsolutePath().normalize();
    private static final long APPROVAL_MARK_WINDOW_MS = 5 * 60 * 1000L;
    private static final Map<Integer, PaymentRequestInfo> PAYMENT_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> RECENTLY_APPROVED_BILLS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        int port = resolveServerPort(args);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/auth/login", new LoginHandler());
        server.createContext("/api/auth/register", new RegisterHandler());
        server.createContext("/api/dishes", new DishHandler());
        server.createContext("/api/dish-image", new DishImageHandler());
        server.createContext("/api/voucher-image", new VoucherImageHandler());
        server.createContext("/api/tables", new TableHandler());
        server.createContext("/api/bills", new BillHandler());
        server.createContext("/api/bill-items", new BillItemHandler());
        server.createContext("/api/staff", new StaffHandler());
        server.createContext("/api/ingredients", new IngredientHandler());
        server.createContext("/api/import-receipts", new ImportReceiptHandler());
        server.createContext("/api/export-receipts", new ExportReceiptHandler());
        server.createContext("/api/vouchers", new VoucherHandler());
        server.createContext("/api/customers", new CustomerHandler());
        server.createContext("/api", new NotFoundHandler());
        server.createContext("/", new WebHandler());
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();

        System.out.println("Restaurant API is running on http://localhost:" + port + "/api/health");
    }

    private static int resolveServerPort(String[] args) {
        Integer fromArgs = parsePortFromArgs(args);
        if (fromArgs != null) {
            return fromArgs;
        }

        Integer fromSystemProperty = parsePortValue(System.getProperty("restaurant.api.port"));
        if (fromSystemProperty != null) {
            return fromSystemProperty;
        }

        Integer fromEnv = parsePortValue(System.getenv("RESTAURANT_API_PORT"));
        if (fromEnv != null) {
            return fromEnv;
        }

        Integer fromGenericEnv = parsePortValue(System.getenv("PORT"));
        if (fromGenericEnv != null) {
            return fromGenericEnv;
        }

        return DEFAULT_PORT;
    }

    private static Integer parsePortFromArgs(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }

        for (String arg : args) {
            if (isBlank(arg)) {
                continue;
            }
            String trimmed = arg.trim();

            if (trimmed.startsWith("--server.port=")) {
                Integer parsed = parsePortValue(trimmed.substring("--server.port=".length()));
                if (parsed != null) {
                    return parsed;
                }
            } else if (trimmed.startsWith("--port=")) {
                Integer parsed = parsePortValue(trimmed.substring("--port=".length()));
                if (parsed != null) {
                    return parsed;
                }
            }
        }

        return null;
    }

    private static Integer parsePortValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < 1 || parsed > 65535) {
                return null;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int getIntProperty(String key, int defaultValue) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }
            writeJson(exchange, 200, Json.object(
                    "status", "UP",
                    "service", "Restaurant API",
                    "database", OracleDb.describeTarget()
            ));
        }
    }

    private static final class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> body = readJsonBody(exchange.getRequestBody());
            String email = firstNonBlank(body.get("email"), queryParams(exchange.getRequestURI()).get("email"));
            String password = firstNonBlank(body.get("password"), queryParams(exchange.getRequestURI()).get("password"));

            if (isBlank(email) || isBlank(password)) {
                writeJson(exchange, 400, Json.object("error", "Missing email or password"));
                return;
            }

            try (Connection connection = OracleDb.openConnection()) {
                String sql = "SELECT ID_ND, Email, Matkhau, Vaitro, Trangthai FROM NguoiDung "
                        + "WHERE Email=? AND Matkhau=? AND Trangthai='Verified' FETCH FIRST 1 ROWS ONLY";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, email);
                    statement.setString(2, password);

                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            writeJson(exchange, 401, Json.object("error", "Invalid credentials or account not verified"));
                            return;
                        }

                        Map<String, Object> user = new LinkedHashMap<>();
                        user.put("id", resultSet.getInt("ID_ND"));
                        user.put("email", resultSet.getString("Email"));
                        user.put("role", resultSet.getString("Vaitro"));
                        user.put("status", resultSet.getString("Trangthai"));
                        writeJson(exchange, 200, Json.object("message", "Login successful", "user", user));
                    }
                }
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }
    }

    private static final class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> body = readJsonBody(exchange.getRequestBody());
            String name = firstNonBlank(body.get("name"), "").trim();
            String email = firstNonBlank(body.get("email"), "").trim();
            String password = firstNonBlank(body.get("password"), "").trim();

            if (isBlank(name) || isBlank(email) || isBlank(password)) {
                writeJson(exchange, 400, Json.object("error", "Missing name, email or password"));
                return;
            }

            try (Connection connection = OracleDb.openConnection()) {
                try (PreparedStatement check = connection.prepareStatement("SELECT COUNT(*) AS C FROM NguoiDung WHERE Email=?")) {
                    check.setString(1, email);
                    try (ResultSet resultSet = check.executeQuery()) {
                        if (resultSet.next() && resultSet.getInt("C") > 0) {
                            writeJson(exchange, 409, Json.object("error", "Email already exists"));
                            return;
                        }
                    }
                }

                int nextUserId = fetchNextId(connection, "NguoiDung", "ID_ND");
                int nextCustomerId = fetchNextId(connection, "KhachHang", "ID_KH");

                String insertUser = "INSERT INTO NguoiDung(ID_ND, Email, MatKhau, VerifyCode, Trangthai, Vaitro) VALUES(?, ?, ?, '', 'Verified', 'Khach Hang')";
                try (PreparedStatement statement = connection.prepareStatement(insertUser)) {
                    statement.setInt(1, nextUserId);
                    statement.setString(2, email);
                    statement.setString(3, password);
                    statement.executeUpdate();
                }

                String insertCustomer = "INSERT INTO KhachHang(ID_KH, TenKH, Ngaythamgia, Doanhso, Diemtichluy, ID_ND) VALUES(?, ?, TRUNC(SYSDATE), 0, 0, ?)";
                try (PreparedStatement statement = connection.prepareStatement(insertCustomer)) {
                    statement.setInt(1, nextCustomerId);
                    statement.setString(2, name);
                    statement.setInt(3, nextUserId);
                    statement.executeUpdate();
                }

                Map<String, Object> user = new LinkedHashMap<>();
                user.put("id", nextUserId);
                user.put("email", email);
                user.put("role", "Khach Hang");

                Map<String, Object> customer = new LinkedHashMap<>();
                customer.put("id", nextCustomerId);
                customer.put("name", name);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("message", "Register successful");
                payload.put("user", user);
                payload.put("customer", customer);
                writeJson(exchange, 201, Json.stringify(payload));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }
    }

    private static final class DishHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());
            String type = query.get("type");
            String sortBy = query.getOrDefault("sortBy", "");

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    StringBuilder sql = new StringBuilder("SELECT ID_MonAn, TenMon, DonGia, Loai, TrangThai FROM MonAn WHERE 1=1");
                    List<Object> params = new ArrayList<>();

                    if (!isBlank(type)) {
                        sql.append(" AND Loai=?");
                        params.add(type);
                    }

                    sql.append(orderByClauseForDishes(sortBy));

                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        for (int i = 0; i < params.size(); i++) {
                            statement.setObject(i + 1, params.get(i));
                        }

                        List<Map<String, Object>> dishes = new ArrayList<>();
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                Map<String, Object> dish = new LinkedHashMap<>();
                                dish.put("id", resultSet.getInt("ID_MonAn"));
                                dish.put("name", resultSet.getString("TenMon"));
                                dish.put("price", resultSet.getInt("DonGia"));
                                dish.put("type", resultSet.getString("Loai"));
                                dish.put("status", resultSet.getString("TrangThai"));
                                dishes.add(dish);
                            }
                        }

                        writeJson(exchange, 200, Json.object("items", dishes, "count", dishes.size()));
                    }
                    return;
                }

                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int nextId = fetchNextId(connection, "MonAn", "ID_MonAn");
                    String typeValue = body.getOrDefault("type", "Aries");
                    String sql = "INSERT INTO MonAn(ID_MonAn, TenMon, DonGia, Loai, TrangThai) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, nextId);
                        statement.setString(2, body.getOrDefault("name", ""));
                        statement.setInt(3, parseInt(body.get("price"), 0));
                        statement.setString(4, typeValue);
                        statement.setString(5, body.getOrDefault("status", "Dang kinh doanh"));
                        statement.executeUpdate();
                    }
                    String imageBase64 = firstNonBlank(body.get("imageBase64"), "").trim();
                    if (!imageBase64.isEmpty()) {
                        saveDishImage(typeValue, nextId, imageBase64);
                    }
                    writeJson(exchange, 201, Json.object("message", "Dish created", "id", nextId));
                    return;
                }

                if ("PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int id = parseInt(firstNonBlank(body.get("id"), query.get("id")), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }

                    String action = firstNonBlank(body.get("action"), query.get("action"));
                    if (!isBlank(action) && "status".equalsIgnoreCase(action.trim())) {
                        String sql = "UPDATE MonAn SET TrangThai=? WHERE ID_MonAn=?";
                        try (PreparedStatement statement = connection.prepareStatement(sql)) {
                            statement.setString(1, body.getOrDefault("status", "Dang kinh doanh"));
                            statement.setInt(2, id);
                            statement.executeUpdate();
                        }
                        writeJson(exchange, 200, Json.object("message", "Dish status updated", "id", id));
                        return;
                    }

                    String oldType = fetchDishTypeById(connection, id);
                    String newType = body.getOrDefault("type", "Aries");
                    String sql = "UPDATE MonAn SET TenMon=?, DonGia=?, Loai=?, TrangThai=? WHERE ID_MonAn=?";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, body.getOrDefault("name", ""));
                        statement.setInt(2, parseInt(body.get("price"), 0));
                        statement.setString(3, newType);
                        statement.setString(4, body.getOrDefault("status", "Dang kinh doanh"));
                        statement.setInt(5, id);
                        statement.executeUpdate();
                    }

                    String imageBase64 = firstNonBlank(body.get("imageBase64"), "").trim();
                    if (!imageBase64.isEmpty()) {
                        saveDishImage(newType, id, imageBase64);
                    } else if (!isBlank(oldType) && !oldType.equalsIgnoreCase(newType)) {
                        moveDishImage(oldType, newType, id);
                    }

                    writeJson(exchange, 200, Json.object("message", "Dish updated", "id", id));
                    return;
                }

                if ("DELETE".equals(method)) {
                    int id = parseInt(firstNonBlank(query.get("id"), readBodyId(exchange.getRequestBody())), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }
                    String sql = "UPDATE MonAn SET TrangThai='Dung kinh doanh' WHERE ID_MonAn=?";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, id);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Dish deleted", "id", id));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            } catch (IOException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private String fetchDishTypeById(Connection connection, int id) throws SQLException {
            String sql = "SELECT Loai FROM MonAn WHERE ID_MonAn=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString(1);
                    }
                }
            }
            return null;
        }

        private void saveDishImage(String type, int id, String imageBase64) throws IOException {
            String normalizedType = normalizeDishType(type);
            String payload = imageBase64.trim();
            int comma = payload.indexOf(',');
            if (payload.startsWith("data:") && comma >= 0) {
                payload = payload.substring(comma + 1);
            }

            byte[] bytes = Base64.getDecoder().decode(payload);
            Path folder = FOOD_ICON_ROOT.resolve(normalizedType).normalize();
            if (!folder.startsWith(FOOD_ICON_ROOT)) {
                throw new IOException("Invalid dish type");
            }
            Files.createDirectories(folder);
            Files.write(folder.resolve(id + ".jpg"), bytes);
        }

        private void moveDishImage(String oldType, String newType, int id) throws IOException {
            String fromType = normalizeDishType(oldType);
            String toType = normalizeDishType(newType);
            if (fromType.equalsIgnoreCase(toType)) {
                return;
            }

            Path source = FOOD_ICON_ROOT.resolve(fromType).resolve(id + ".jpg").normalize();
            Path targetFolder = FOOD_ICON_ROOT.resolve(toType).normalize();
            Path target = targetFolder.resolve(id + ".jpg").normalize();

            if (!source.startsWith(FOOD_ICON_ROOT) || !target.startsWith(FOOD_ICON_ROOT) || !Files.exists(source)) {
                return;
            }

            Files.createDirectories(targetFolder);
            Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        private String normalizeDishType(String value) {
            String type = firstNonBlank(value, "Unknown").trim();
            if (isBlank(type) || type.contains("..") || type.contains("/") || type.contains("\\")) {
                return "Unknown";
            }
            return type;
        }

        private String orderByClauseForDishes(String sortBy) {
            String normalized = sortBy == null ? "" : sortBy.trim().toLowerCase();
            return switch (normalized) {
                case "name", "ten", "tenmon", "ten a->z" -> " ORDER BY TenMon ASC";
                case "priceasc", "gia", "gia tang dan", "price-asc" -> " ORDER BY DonGia ASC";
                case "pricedesc", "gia giam dan", "price-desc" -> " ORDER BY DonGia DESC";
                default -> " ORDER BY ID_MonAn ASC";
            };
        }
    }

    private static final class DishImageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isOptions(exchange)) {
                handleCors(exchange);
                writeResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCors(exchange);
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> query = queryParams(exchange.getRequestURI());
            String type = firstNonBlank(query.get("type"), "Unknown").trim();
            int id = parseInt(query.get("id"), -1);

            String fileName = id > 0 ? id + ".jpg" : "unknown.jpg";
            Path candidate = FOOD_ICON_ROOT.resolve(type).resolve(fileName).normalize();
            Path fallback = FOOD_ICON_ROOT.resolve("Unknown").resolve("unknown.jpg").normalize();
            Path target = Files.exists(candidate) && candidate.startsWith(FOOD_ICON_ROOT) ? candidate : fallback;

            if (!Files.exists(target)) {
                handleCors(exchange);
                writeJson(exchange, 404, Json.object("error", "Dish image not found"));
                return;
            }

            byte[] body = Files.readAllBytes(target);
            Headers headers = exchange.getResponseHeaders();
            handleCors(exchange);
            headers.set("Content-Type", contentTypeFor(target.getFileName().toString()));
            headers.set("Cache-Control", "public, max-age=300");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
            exchange.close();
        }
    }

    private static final class VoucherImageHandler implements HttpHandler {
        private static final Path VOUCHER_ICON_ROOT = Path.of("src", "Icons", "Voucher").toAbsolutePath().normalize();

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isOptions(exchange)) {
                handleCors(exchange);
                writeResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCors(exchange);
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> query = queryParams(exchange.getRequestURI());
            int percent = parseInt(query.get("percent"), 20);
            String fileName = switch (percent) {
                case 20, 30, 50, 60, 100 -> percent + "off.jpg";
                default -> "20off.jpg";
            };

            Path candidate = VOUCHER_ICON_ROOT.resolve(fileName).normalize();
            Path fallback = VOUCHER_ICON_ROOT.resolve("20off.jpg").normalize();
            Path target = Files.exists(candidate) && candidate.startsWith(VOUCHER_ICON_ROOT) ? candidate : fallback;

            if (!Files.exists(target)) {
                handleCors(exchange);
                writeJson(exchange, 404, Json.object("error", "Voucher image not found"));
                return;
            }

            byte[] body = Files.readAllBytes(target);
            Headers headers = exchange.getResponseHeaders();
            handleCors(exchange);
            headers.set("Content-Type", contentTypeFor(target.getFileName().toString()));
            headers.set("Cache-Control", "public, max-age=300");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            }
            exchange.close();
        }
    }

    private static final class TableHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());
            String floor = query.get("floor");
            String status = query.get("status");

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    StringBuilder sql = new StringBuilder("SELECT ID_Ban, TenBan, Vitri, Trangthai FROM Ban WHERE 1=1");
                    List<Object> params = new ArrayList<>();

                    if (!isBlank(floor)) {
                        sql.append(" AND Vitri=?");
                        params.add(floor);
                    }
                    if (!isBlank(status)) {
                        sql.append(" AND Trangthai=?");
                        params.add(status);
                    }
                    sql.append(" ORDER BY ID_Ban ASC");

                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        for (int i = 0; i < params.size(); i++) {
                            statement.setObject(i + 1, params.get(i));
                        }

                        List<Map<String, Object>> tables = new ArrayList<>();
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                Map<String, Object> table = new LinkedHashMap<>();
                                table.put("id", resultSet.getInt("ID_Ban"));
                                table.put("name", resultSet.getString("TenBan"));
                                table.put("floor", resultSet.getString("Vitri"));
                                table.put("status", resultSet.getString("Trangthai"));
                                tables.add(table);
                            }
                        }

                        writeJson(exchange, 200, Json.object("items", tables, "count", tables.size()));
                    }
                    return;
                }

                if ("PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int id = parseInt(firstNonBlank(body.get("id"), query.get("id")), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }

                    String action = firstNonBlank(body.get("action"), query.get("action"));
                    String nextStatus;
                    if (isBlank(action)) {
                        nextStatus = body.getOrDefault("status", "Con trong");
                    } else {
                        String normalized = action.trim().toLowerCase();
                        nextStatus = switch (normalized) {
                            case "reserve" -> "Da dat truoc";
                            case "cancel-reserve", "release" -> "Con trong";
                            case "occupy" -> "Dang dung bua";
                            default -> body.getOrDefault("status", "Con trong");
                        };
                    }

                    try (PreparedStatement statement = connection.prepareStatement("UPDATE Ban SET Trangthai=? WHERE ID_Ban=?")) {
                        statement.setString(1, nextStatus);
                        statement.setInt(2, id);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Table status updated", "id", id, "status", nextStatus));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }
    }

    private static final class BillHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());
            LocalDate fromDate = parseDate(query.get("from"));
            LocalDate toDate = parseDate(query.get("to"));

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    Integer tableId = parseNullableInt(query.get("tableId"));
                    Integer customerId = parseNullableInt(query.get("customerId"));
                    boolean unpaidOnly = "true".equalsIgnoreCase(query.get("unpaidOnly"));

                        boolean hasPaymentMethodColumn = hasHoaDonColumn(connection, "PAYMENTMETHOD");
                        boolean hasPaymentRequestStatusColumn = hasHoaDonColumn(connection, "PAYMENTREQUESTSTATUS");
                        String paymentMethodSelect = hasPaymentMethodColumn
                            ? "PaymentMethod"
                            : "CAST(NULL AS VARCHAR2(20)) AS PaymentMethod";
                        String paymentRequestStatusSelect = hasPaymentRequestStatusColumn
                            ? "PaymentRequestStatus"
                            : "CAST(NULL AS VARCHAR2(20)) AS PaymentRequestStatus";

                        StringBuilder sql = new StringBuilder("SELECT ID_HoaDon, ID_KH, ID_Ban, NgayHD, TienMonAn, TienGiam, Tongtien, Trangthai, Code_Voucher, "
                            + paymentMethodSelect + ", " + paymentRequestStatusSelect + " FROM HoaDon WHERE 1=1");
                    List<Object> params = new ArrayList<>();

                    if (fromDate != null) {
                        sql.append(" AND TRUNC(NgayHD) >= ?");
                        params.add(Date.valueOf(fromDate));
                    }
                    if (toDate != null) {
                        sql.append(" AND TRUNC(NgayHD) <= ?");
                        params.add(Date.valueOf(toDate));
                    }
                    if (tableId != null && tableId > 0) {
                        sql.append(" AND ID_Ban=?");
                        params.add(tableId);
                    }
                    if (customerId != null && customerId > 0) {
                        sql.append(" AND ID_KH=?");
                        params.add(customerId);
                    }
                    if (unpaidOnly) {
                        sql.append(" AND Trangthai='Chua thanh toan'");
                    }
                    sql.append(" ORDER BY ID_HoaDon DESC");

                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        for (int i = 0; i < params.size(); i++) {
                            statement.setObject(i + 1, params.get(i));
                        }

                        List<Map<String, Object>> bills = new ArrayList<>();
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                Map<String, Object> bill = new LinkedHashMap<>();
                                int billId = resultSet.getInt("ID_HoaDon");
                                String billStatus = resultSet.getString("Trangthai");
                                bill.put("id", resultSet.getInt("ID_HoaDon"));
                                bill.put("customerId", resultSet.getInt("ID_KH"));
                                bill.put("tableId", resultSet.getInt("ID_Ban"));
                                bill.put("date", resultSet.getDate("NgayHD") == null ? null : resultSet.getDate("NgayHD").toLocalDate().toString());
                                bill.put("mealTotal", resultSet.getInt("TienMonAn"));
                                bill.put("discount", resultSet.getInt("TienGiam"));
                                bill.put("total", resultSet.getInt("Tongtien"));
                                bill.put("voucherCode", resultSet.getString("Code_Voucher"));
                                bill.put("status", billStatus);

                                String paymentRequestStatus = firstNonBlank(resultSet.getString("PaymentRequestStatus"), "none").toLowerCase();
                                String paymentMethod = resultSet.getString("PaymentMethod");
                                PaymentRequestInfo requestInfo = PAYMENT_REQUESTS.get(billId);
                                if (requestInfo != null && isUnpaidStatus(billStatus)) {
                                    paymentRequestStatus = "pending";
                                    paymentMethod = requestInfo.paymentMethod;
                                } else if (isPaidStatus(billStatus) && markRecentlyApproved(billId)) {
                                    paymentRequestStatus = "approved";
                                }
                                bill.put("paymentRequestStatus", paymentRequestStatus);
                                bill.put("paymentMethod", paymentMethod);

                                bills.add(bill);
                            }
                        }

                        writeJson(exchange, 200, Json.object("items", bills, "count", bills.size()));
                    }
                    return;
                }

                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int customerId = parseInt(body.get("customerId"), -1);
                    int tableId = parseInt(body.get("tableId"), -1);
                    if (customerId <= 0 || tableId <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing customerId or tableId"));
                        return;
                    }

                    int nextId;
                    try {
                        nextId = createUnpaidBillWithRecovery(connection, customerId, tableId);
                    } catch (SQLException createEx) {
                        if ("Customer already has an unpaid bill".equals(createEx.getMessage())) {
                            writeJson(exchange, 409, Json.object("error", "Customer already has an unpaid bill"));
                            return;
                        }
                        throw createEx;
                    }

                    writeJson(exchange, 201, Json.object("message", "Bill created", "id", nextId));
                    return;
                }

                if ("PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    String action = firstNonBlank(body.get("action"), query.get("action"));

                    if (!isBlank(action) && "create".equalsIgnoreCase(action.trim())) {
                        int customerId = parseInt(body.get("customerId"), -1);
                        int tableId = parseInt(body.get("tableId"), -1);
                        if (customerId <= 0 || tableId <= 0) {
                            writeJson(exchange, 400, Json.object("error", "Missing customerId or tableId"));
                            return;
                        }

                        int nextId;
                        try {
                            nextId = createUnpaidBillWithRecovery(connection, customerId, tableId);
                        } catch (SQLException createEx) {
                            if ("Customer already has an unpaid bill".equals(createEx.getMessage())) {
                                writeJson(exchange, 409, Json.object("error", "Customer already has an unpaid bill"));
                                return;
                            }
                            throw createEx;
                        }

                        writeJson(exchange, 201, Json.object("message", "Bill created", "id", nextId));
                        return;
                    }

                    int billId = parseInt(firstNonBlank(body.get("billId"), firstNonBlank(body.get("id"), query.get("id"))), -1);
                    if (billId <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing bill id"));
                        return;
                    }

                    if (isBlank(action)) {
                        writeJson(exchange, 400, Json.object("error", "Missing action"));
                        return;
                    }

                    String normalized = action.trim().toLowerCase();
                    if ("request-pay".equals(normalized)) {
                        String paymentMethod = firstNonBlank(body.get("paymentMethod"), "cash").trim().toLowerCase();
                        if (!isAllowedPaymentMethod(paymentMethod)) {
                            writeJson(exchange, 400, Json.object("error", "Invalid paymentMethod"));
                            return;
                        }

                        String billStatus = fetchBillStatus(connection, billId);
                        if (billStatus == null) {
                            writeJson(exchange, 404, Json.object("error", "Bill not found"));
                            return;
                        }
                        if (!isUnpaidStatus(billStatus)) {
                            writeJson(exchange, 409, Json.object("error", "Bill is already paid"));
                            return;
                        }

                        String dbRequestStatus = fetchPaymentRequestStatus(connection, billId);
                        if ("pending".equalsIgnoreCase(dbRequestStatus) || PAYMENT_REQUESTS.containsKey(billId)) {
                            writeJson(exchange, 409, Json.object("error", "Payment request is already pending", "id", billId));
                            return;
                        }

                        int customerId = parseInt(firstNonBlank(body.get("customerId"), query.get("customerId")), -1);
                        try {
                            try (PreparedStatement statement = connection.prepareStatement("UPDATE HoaDon SET PaymentMethod=?, PaymentRequestStatus='pending' WHERE ID_HoaDon=?")) {
                                statement.setString(1, paymentMethod);
                                statement.setInt(2, billId);
                                statement.executeUpdate();
                            }
                        } catch (SQLException ignoreMissingColumn) {
                            if (!isInvalidIdentifier(ignoreMissingColumn)) {
                                throw ignoreMissingColumn;
                            }
                        }
                        PAYMENT_REQUESTS.put(billId, new PaymentRequestInfo(customerId, paymentMethod, System.currentTimeMillis()));
                        writeJson(exchange, 200, Json.object("message", "Payment request sent", "id", billId, "paymentRequestStatus", "pending"));
                        return;
                    }

                    if ("confirm-pay".equals(normalized) || "pay".equals(normalized)) {
                        String billStatus = fetchBillStatus(connection, billId);
                        if (billStatus == null) {
                            writeJson(exchange, 404, Json.object("error", "Bill not found"));
                            return;
                        }
                        if (isPaidStatus(billStatus)) {
                            PAYMENT_REQUESTS.remove(billId);
                            RECENTLY_APPROVED_BILLS.put(billId, System.currentTimeMillis());
                            releaseTableForBill(connection, billId);
                            writeJson(exchange, 200, Json.object("message", "Bill already paid", "id", billId));
                            return;
                        }

                        String dbRequestStatus = fetchPaymentRequestStatus(connection, billId);
                        boolean hasPendingRequest = PAYMENT_REQUESTS.containsKey(billId) || "pending".equalsIgnoreCase(dbRequestStatus);
                        if (!hasPendingRequest && "confirm-pay".equals(normalized)) {
                            writeJson(exchange, 409, Json.object("error", "No payment request for this bill"));
                            return;
                        }

                        // Get payment method from request info
                        PaymentRequestInfo requestInfo = PAYMENT_REQUESTS.get(billId);
                        String paymentMethod = requestInfo != null ? requestInfo.paymentMethod : fetchPaymentMethod(connection, billId);
                        if (!isAllowedPaymentMethod(firstNonBlank(paymentMethod, "").toLowerCase())) {
                            paymentMethod = "cash";
                        }
                        String paymentStatus = "approved";

                        try {
                            try (PreparedStatement statement = connection.prepareStatement("UPDATE HoaDon SET Trangthai='Da thanh toan', PaymentMethod=?, PaymentRequestStatus=? WHERE ID_HoaDon=?")) {
                                statement.setString(1, paymentMethod);
                                statement.setString(2, paymentStatus);
                                statement.setInt(3, billId);
                                statement.executeUpdate();
                            }
                        } catch (SQLException missingColumnEx) {
                            if (!isInvalidIdentifier(missingColumnEx)) {
                                throw missingColumnEx;
                            }
                            try (PreparedStatement statement = connection.prepareStatement("UPDATE HoaDon SET Trangthai='Da thanh toan' WHERE ID_HoaDon=?")) {
                                statement.setInt(1, billId);
                                statement.executeUpdate();
                            }
                        }
                        releaseTableForBill(connection, billId);
                        PAYMENT_REQUESTS.remove(billId);
                        RECENTLY_APPROVED_BILLS.put(billId, System.currentTimeMillis());
                        writeJson(exchange, 200, Json.object("message", "Bill paid", "id", billId, "paymentMethod", paymentMethod, "paymentRequestStatus", paymentStatus));
                        return;
                    }

                    if ("voucher".equals(normalized) || "apply-voucher".equals(normalized)) {
                        String code = firstNonBlank(body.get("voucherCode"), query.get("voucherCode"));
                        if (isBlank(code)) {
                            writeJson(exchange, 400, Json.object("error", "Missing voucherCode"));
                            return;
                        }

                        String billStatus = fetchBillStatus(connection, billId);
                        if (billStatus == null) {
                            writeJson(exchange, 404, Json.object("error", "Bill not found"));
                            return;
                        }
                        if (!isUnpaidStatus(billStatus)) {
                            writeJson(exchange, 409, Json.object("error", "Only unpaid bill can apply voucher"));
                            return;
                        }

                        String currentVoucherCode = fetchBillVoucherCode(connection, billId);
                        if (!isBlank(currentVoucherCode)) {
                            writeJson(exchange, 409, Json.object("error", "Bill already has a voucher", "id", billId, "voucherCode", currentVoucherCode));
                            return;
                        }

                        try (PreparedStatement statement = connection.prepareStatement("UPDATE HoaDon SET Code_Voucher=? WHERE ID_HoaDon=? AND Code_Voucher IS NULL")) {
                            statement.setString(1, code);
                            statement.setInt(2, billId);
                            int updated = statement.executeUpdate();
                            if (updated == 0) {
                                writeJson(exchange, 409, Json.object("error", "Bill already has a voucher", "id", billId));
                                return;
                            }
                        }

                        Map<String, Object> pricing = fetchBillPricing(connection, billId);
                        writeJson(exchange, 200, Json.object(
                                "message", "Voucher applied",
                                "id", billId,
                                "voucherCode", pricing.get("voucherCode"),
                                "discount", pricing.get("discount"),
                                "total", pricing.get("total"),
                                "mealTotal", pricing.get("mealTotal")
                        ));
                        return;
                    }

                    writeJson(exchange, 400, Json.object("error", "Unsupported action"));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private int createUnpaidBillWithRecovery(Connection connection, int customerId, int tableId) throws SQLException {
            try {
                return createUnpaidBill(connection, customerId, tableId);
            } catch (SQLException ex) {
                if (tryDisableInvalidTrigger(connection, ex, "TG_SLHD_CTT")) {
                    return createUnpaidBill(connection, customerId, tableId);
                }
                throw ex;
            }
        }

        private int createUnpaidBill(Connection connection, int customerId, int tableId) throws SQLException {
            try (PreparedStatement checkUnpaid = connection.prepareStatement("SELECT COUNT(*) AS C FROM HoaDon WHERE ID_KH=? AND Trangthai='Chua thanh toan'")) {
                checkUnpaid.setInt(1, customerId);
                try (ResultSet resultSet = checkUnpaid.executeQuery()) {
                    if (resultSet.next() && resultSet.getInt("C") > 0) {
                        throw new SQLException("Customer already has an unpaid bill");
                    }
                }
            }

            int nextId = fetchNextId(connection, "HoaDon", "ID_HoaDon");
            String sql = "INSERT INTO HoaDon(ID_HoaDon, ID_KH, ID_Ban, NgayHD, TienMonAn, TienGiam, Tongtien, Trangthai) VALUES (?, ?, ?, TRUNC(SYSDATE), 0, 0, 0, 'Chua thanh toan')";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, nextId);
                statement.setInt(2, customerId);
                statement.setInt(3, tableId);
                statement.executeUpdate();
            }
            return nextId;
        }

        private boolean tryDisableInvalidTrigger(Connection connection, SQLException ex, String triggerName) {
            if (!isInvalidTriggerError(ex, triggerName)) {
                return false;
            }
            String sql = "ALTER TRIGGER " + triggerName + " DISABLE";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
                return true;
            } catch (SQLException disableEx) {
                return false;
            }
        }

        private String fetchBillStatus(Connection connection, int billId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT Trangthai FROM HoaDon WHERE ID_HoaDon=?")) {
                statement.setInt(1, billId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("Trangthai");
                    }
                }
            }
            return null;
        }

        private String fetchPaymentRequestStatus(Connection connection, int billId) throws SQLException {
            try {
                try (PreparedStatement statement = connection.prepareStatement("SELECT PaymentRequestStatus FROM HoaDon WHERE ID_HoaDon=?")) {
                    statement.setInt(1, billId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            return resultSet.getString("PaymentRequestStatus");
                        }
                    }
                }
            } catch (SQLException ex) {
                if (!isInvalidIdentifier(ex)) {
                    throw ex;
                }
                return null;
            }
            return null;
        }

        private String fetchPaymentMethod(Connection connection, int billId) throws SQLException {
            try {
                try (PreparedStatement statement = connection.prepareStatement("SELECT PaymentMethod FROM HoaDon WHERE ID_HoaDon=?")) {
                    statement.setInt(1, billId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            return resultSet.getString("PaymentMethod");
                        }
                    }
                }
            } catch (SQLException ex) {
                if (!isInvalidIdentifier(ex)) {
                    throw ex;
                }
                return null;
            }
            return null;
        }

        private String fetchBillVoucherCode(Connection connection, int billId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT Code_Voucher FROM HoaDon WHERE ID_HoaDon=?")) {
                statement.setInt(1, billId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString("Code_Voucher");
                    }
                }
            }
            return null;
        }

        private Map<String, Object> fetchBillPricing(Connection connection, int billId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT TienMonAn, TienGiam, Tongtien, Code_Voucher FROM HoaDon WHERE ID_HoaDon=?")) {
                statement.setInt(1, billId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    Map<String, Object> pricing = new LinkedHashMap<>();
                    if (resultSet.next()) {
                        pricing.put("mealTotal", resultSet.getInt("TienMonAn"));
                        pricing.put("discount", resultSet.getInt("TienGiam"));
                        pricing.put("total", resultSet.getInt("Tongtien"));
                        pricing.put("voucherCode", resultSet.getString("Code_Voucher"));
                    }
                    return pricing;
                }
            }
        }

        private boolean hasHoaDonColumn(Connection connection, String columnName) {
            String sql = "SELECT COUNT(*) AS C FROM USER_TAB_COLUMNS WHERE TABLE_NAME='HOADON' AND COLUMN_NAME=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, columnName == null ? "" : columnName.toUpperCase());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getInt("C") > 0;
                }
            } catch (SQLException ex) {
                return false;
            }
        }

        private boolean isInvalidIdentifier(SQLException ex) {
            String message = ex == null ? "" : firstNonBlank(ex.getMessage(), "").toUpperCase();
            return message.contains("ORA-00904") || message.contains("INVALID IDENTIFIER");
        }

        private boolean isAllowedPaymentMethod(String method) {
            return "cash".equals(method) || "card".equals(method) || "transfer".equals(method);
        }

        private void releaseTableForBill(Connection connection, int billId) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT ID_Ban FROM HoaDon WHERE ID_HoaDon=?")) {
                statement.setInt(1, billId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        int tableId = resultSet.getInt("ID_Ban");
                        try (PreparedStatement updateTable = connection.prepareStatement("UPDATE Ban SET Trangthai='Con trong' WHERE ID_Ban=?")) {
                            updateTable.setInt(1, tableId);
                            updateTable.executeUpdate();
                        }
                    }
                }
            } catch (SQLException ignored) {
                // Keep payment flow resilient even if the table update fails.
            }
        }

        private boolean isUnpaidStatus(String status) {
            String normalized = status == null ? "" : status.toLowerCase();
            return normalized.contains("chua thanh") || normalized.contains("chưa thanh");
        }

        private boolean isPaidStatus(String status) {
            String normalized = status == null ? "" : status.toLowerCase();
            return normalized.contains("da thanh") || normalized.contains("đã thanh");
        }

        private boolean markRecentlyApproved(int billId) {
            Long approvedAt = RECENTLY_APPROVED_BILLS.get(billId);
            if (approvedAt == null) {
                return false;
            }
            long age = System.currentTimeMillis() - approvedAt;
            if (age > APPROVAL_MARK_WINDOW_MS) {
                RECENTLY_APPROVED_BILLS.remove(billId);
                return false;
            }
            return true;
        }
    }

    private static final class PaymentRequestInfo {
        final int customerId;
        final String paymentMethod;
        final long requestedAt;

        PaymentRequestInfo(int customerId, String paymentMethod, long requestedAt) {
            this.customerId = customerId;
            this.paymentMethod = paymentMethod;
            this.requestedAt = requestedAt;
        }
    }

    private static final class BillItemHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    int billId = parseInt(query.get("billId"), -1);
                    if (billId <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing billId"));
                        return;
                    }

                    String sql = "SELECT ID_HoaDon, CTHD.ID_MonAn, TenMon, SoLuong, Thanhtien FROM CTHD "
                            + "JOIN MonAn ON MonAn.ID_MonAn = CTHD.ID_MonAn WHERE ID_HoaDon=? ORDER BY ID_HoaDon";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, billId);
                        List<Map<String, Object>> items = new ArrayList<>();
                        try (ResultSet resultSet = statement.executeQuery()) {
                            while (resultSet.next()) {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("billId", resultSet.getInt("ID_HoaDon"));
                                item.put("dishId", resultSet.getInt("ID_MonAn"));
                                item.put("dishName", resultSet.getString("TenMon"));
                                item.put("quantity", resultSet.getInt("SoLuong"));
                                item.put("total", resultSet.getInt("Thanhtien"));
                                items.add(item);
                            }
                        }
                        writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
                    }
                    return;
                }

                if ("POST".equals(method) || "PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int billId = parseInt(firstNonBlank(body.get("billId"), query.get("billId")), -1);
                    int dishId = parseInt(firstNonBlank(body.get("dishId"), query.get("dishId")), -1);
                    int quantity = parseInt(body.get("quantity"), 0);
                    if (billId <= 0 || dishId <= 0 || quantity <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing billId, dishId or quantity"));
                        return;
                    }

                    saveBillItemWithRecovery(connection, billId, dishId, quantity);

                    writeJson(exchange, 201, Json.object("message", "Bill item saved", "billId", billId, "dishId", dishId));
                    return;
                }

                if ("DELETE".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int billId = parseInt(firstNonBlank(query.get("billId"), body.get("billId")), -1);
                    int dishId = parseInt(firstNonBlank(query.get("dishId"), body.get("dishId")), -1);
                    if (billId <= 0 || dishId <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing billId or dishId"));
                        return;
                    }
                    deleteBillItemWithRecovery(connection, billId, dishId);
                    writeJson(exchange, 200, Json.object("message", "Bill item deleted", "billId", billId, "dishId", dishId));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private void saveBillItemWithRecovery(Connection connection, int billId, int dishId, int quantity) throws SQLException {
            try {
                saveBillItem(connection, billId, dishId, quantity);
            } catch (SQLException ex) {
                if (isInvalidTriggerError(ex, null)) {
                    disableBillItemTriggers(connection);
                    saveBillItem(connection, billId, dishId, quantity);
                    return;
                }
                throw ex;
            }
        }

        private void saveBillItem(Connection connection, int billId, int dishId, int quantity) throws SQLException {
            int unitPrice = fetchDishPrice(connection, dishId);
            if (unitPrice < 0) {
                throw new SQLException("Dish not found");
            }

            Integer existingQty = null;
            try (PreparedStatement check = connection.prepareStatement("SELECT SoLuong FROM CTHD WHERE ID_HoaDon=? AND ID_MonAn=?")) {
                check.setInt(1, billId);
                check.setInt(2, dishId);
                try (ResultSet resultSet = check.executeQuery()) {
                    if (resultSet.next()) {
                        existingQty = resultSet.getInt("SoLuong");
                    }
                }
            }

            if (existingQty != null) {
                int nextQty = existingQty + quantity;
                int nextTotal = nextQty * unitPrice;
                try (PreparedStatement update = connection.prepareStatement("UPDATE CTHD SET SoLuong=?, Thanhtien=? WHERE ID_HoaDon=? AND ID_MonAn=?")) {
                    update.setInt(1, nextQty);
                    update.setInt(2, nextTotal);
                    update.setInt(3, billId);
                    update.setInt(4, dishId);
                    update.executeUpdate();
                }
            } else {
                int lineTotal = quantity * unitPrice;
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO CTHD(ID_HoaDon, ID_MonAn, SoLuong, Thanhtien) VALUES (?, ?, ?, ?)")) {
                    insert.setInt(1, billId);
                    insert.setInt(2, dishId);
                    insert.setInt(3, quantity);
                    insert.setInt(4, lineTotal);
                    insert.executeUpdate();
                }
            }

            recomputeBillTotals(connection, billId);
        }

        private void deleteBillItemWithRecovery(Connection connection, int billId, int dishId) throws SQLException {
            try {
                deleteBillItem(connection, billId, dishId);
            } catch (SQLException ex) {
                if (isInvalidTriggerError(ex, null)) {
                    disableBillItemTriggers(connection);
                    deleteBillItem(connection, billId, dishId);
                    return;
                }
                throw ex;
            }
        }

        private void deleteBillItem(Connection connection, int billId, int dishId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM CTHD WHERE ID_HoaDon=? AND ID_MonAn=?")) {
                statement.setInt(1, billId);
                statement.setInt(2, dishId);
                statement.executeUpdate();
            }
            recomputeBillTotals(connection, billId);
        }

        private int fetchDishPrice(Connection connection, int dishId) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("SELECT DonGia FROM MonAn WHERE ID_MonAn=?")) {
                statement.setInt(1, dishId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getInt("DonGia");
                    }
                }
            }
            return -1;
        }

        private void recomputeBillTotals(Connection connection, int billId) throws SQLException {
            int mealTotal = 0;
            try (PreparedStatement statement = connection.prepareStatement("SELECT NVL(SUM(Thanhtien), 0) AS TOTAL FROM CTHD WHERE ID_HoaDon=?")) {
                statement.setInt(1, billId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        mealTotal = resultSet.getInt("TOTAL");
                    }
                }
            }

            String voucherCode = null;
            int voucherPercent = 0;
            String voucherType = "All";
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT h.Code_Voucher, v.Phantram, v.LoaiMA FROM HoaDon h LEFT JOIN Voucher v ON v.Code_Voucher = h.Code_Voucher WHERE h.ID_HoaDon=?")) {
                statement.setInt(1, billId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        voucherCode = resultSet.getString("Code_Voucher");
                        voucherPercent = resultSet.getInt("Phantram");
                        String fetchedType = resultSet.getString("LoaiMA");
                        if (!isBlank(fetchedType)) {
                            voucherType = fetchedType;
                        }
                    }
                }
            }

            int discountBase = mealTotal;
            if (!isBlank(voucherCode) && voucherPercent > 0 && !"All".equalsIgnoreCase(voucherType)) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT NVL(SUM(c.Thanhtien), 0) AS TOTAL FROM CTHD c JOIN MonAn m ON m.ID_MonAn = c.ID_MonAn WHERE c.ID_HoaDon=? AND m.Loai=?")) {
                    statement.setInt(1, billId);
                    statement.setString(2, voucherType);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            discountBase = resultSet.getInt("TOTAL");
                        }
                    }
                }
            }

            int discount = 0;
            if (!isBlank(voucherCode) && voucherPercent > 0) {
                discount = (discountBase * voucherPercent) / 100;
            }
            int total = Math.max(0, mealTotal - discount);

            try (PreparedStatement statement = connection.prepareStatement("UPDATE HoaDon SET TienMonAn=?, TienGiam=?, Tongtien=? WHERE ID_HoaDon=?")) {
                statement.setInt(1, mealTotal);
                statement.setInt(2, discount);
                statement.setInt(3, total);
                statement.setInt(4, billId);
                statement.executeUpdate();
            }
        }

        private void disableBillItemTriggers(Connection connection) {
            disableTriggerIgnoreError(connection, "TG_CTHD_THANHTIEN");
            disableTriggerIgnoreError(connection, "TG_HD_TIENMONAN");
            disableTriggerIgnoreError(connection, "TG_HD_TIENGIAM");
        }

        private void disableTriggerIgnoreError(Connection connection, String triggerName) {
            String sql = "ALTER TRIGGER " + triggerName + " DISABLE";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
            } catch (SQLException ignore) {
            }
        }
    }

    private static final class StaffHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    if (query.containsKey("id")) {
                        int id = parseInt(query.get("id"), -1);
                        if (id <= 0) {
                            writeJson(exchange, 400, Json.object("error", "Invalid id"));
                            return;
                        }
                        writeJson(exchange, 200, Json.object("staff", fetchStaffById(connection, id)));
                        return;
                    }
                    List<Map<String, Object>> items = fetchStaffList(connection);
                    writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
                    return;
                }

                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int nextId = fetchNextId(connection, "NhanVien", "ID_NV");
                    String sql = "INSERT INTO NhanVien(ID_NV,TenNV,NgayVL,SDT,Chucvu,ID_ND,ID_NQL,TinhTrang) VALUES (?,?,to_date(?,'yyyy-mm-dd'),?,?,?,?,'Dang lam viec')";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, nextId);
                        statement.setString(2, body.getOrDefault("tenNV", ""));
                        statement.setString(3, normalizeDateInput(body.get("ngayVL")));
                        statement.setString(4, body.getOrDefault("sdt", ""));
                        statement.setString(5, body.getOrDefault("chucvu", ""));
                        statement.setObject(6, parseNullableInt(body.get("idND")));
                        statement.setObject(7, parseNullableInt(body.get("idNQL")));
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 201, Json.object("message", "Staff created", "id", nextId));
                    return;
                }

                if ("PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int id = parseInt(firstNonBlank(body.get("id"), query.get("id")), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }
                    String sql = "UPDATE NhanVien SET TenNV=?, SDT=?, Chucvu=?, ID_NQL=?, TinhTrang=? WHERE ID_NV=?";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, body.getOrDefault("tenNV", ""));
                        statement.setString(2, body.getOrDefault("sdt", ""));
                        statement.setString(3, body.getOrDefault("chucvu", ""));
                        statement.setObject(4, parseNullableInt(body.get("idNQL")));
                        statement.setString(5, body.getOrDefault("tinhTrang", "Dang lam viec"));
                        statement.setInt(6, id);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Staff updated", "id", id));
                    return;
                }

                if ("DELETE".equals(method)) {
                    int id = parseInt(firstNonBlank(query.get("id"), readBodyId(exchange.getRequestBody())), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }
                    String sql = "UPDATE NhanVien SET TinhTrang='Da nghi viec' WHERE ID_NV=?";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, id);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Staff marked as resigned", "id", id));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private List<Map<String, Object>> fetchStaffList(Connection connection) throws SQLException {
            List<Map<String, Object>> items = new ArrayList<>();
            String sql = "SELECT ID_NV,TenNV,to_char(NgayVL,'yyyy-mm-dd') AS NgayVL,SDT,ChucVu,ID_ND,ID_NQL,TinhTrang FROM NhanVien ORDER BY ID_NV";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> staff = new LinkedHashMap<>();
                    staff.put("id", resultSet.getInt("ID_NV"));
                    staff.put("name", resultSet.getString("TenNV"));
                    staff.put("date", resultSet.getString("NgayVL"));
                    staff.put("phone", resultSet.getString("SDT"));
                    staff.put("role", resultSet.getString("ChucVu"));
                    staff.put("userId", resultSet.getObject("ID_ND"));
                    staff.put("managerId", resultSet.getObject("ID_NQL"));
                    staff.put("status", resultSet.getString("TinhTrang"));
                    items.add(staff);
                }
            }
            return items;
        }

        private Map<String, Object> fetchStaffById(Connection connection, int id) throws SQLException {
            String sql = "SELECT ID_NV,TenNV,to_char(NgayVL,'yyyy-mm-dd') AS NgayVL,SDT,ChucVu,ID_ND,ID_NQL,TinhTrang FROM NhanVien WHERE ID_NV=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        Map<String, Object> staff = new LinkedHashMap<>();
                        staff.put("id", resultSet.getInt("ID_NV"));
                        staff.put("name", resultSet.getString("TenNV"));
                        staff.put("date", resultSet.getString("NgayVL"));
                        staff.put("phone", resultSet.getString("SDT"));
                        staff.put("role", resultSet.getString("ChucVu"));
                        staff.put("userId", resultSet.getObject("ID_ND"));
                        staff.put("managerId", resultSet.getObject("ID_NQL"));
                        staff.put("status", resultSet.getString("TinhTrang"));
                        return staff;
                    }
                }
            }
            return new LinkedHashMap<>();
        }
    }

    private static final class IngredientHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    if (query.containsKey("id")) {
                        int id = parseInt(query.get("id"), -1);
                        if (id <= 0) {
                            writeJson(exchange, 400, Json.object("error", "Invalid id"));
                            return;
                        }
                        writeJson(exchange, 200, Json.object("ingredient", fetchIngredientById(connection, id)));
                        return;
                    }
                    List<Map<String, Object>> items = fetchIngredientList(connection);
                    writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
                    return;
                }

                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int nextId = fetchNextId(connection, "NguyenLieu", "ID_NL");
                    String sql = "INSERT INTO NguyenLieu(ID_NL,TenNL,Dongia,Donvitinh) VALUES(?,?,?,?)";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, nextId);
                        statement.setString(2, body.getOrDefault("tenNL", ""));
                        statement.setInt(3, parseInt(body.get("donGia"), 0));
                        statement.setString(4, body.getOrDefault("dvt", ""));
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 201, Json.object("message", "Ingredient created", "id", nextId));
                    return;
                }

                if ("PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int id = parseInt(firstNonBlank(body.get("id"), query.get("id")), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }
                    String sql = "UPDATE NguyenLieu SET TenNL=?, Dongia=?, Donvitinh=? WHERE ID_NL=?";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, body.getOrDefault("tenNL", ""));
                        statement.setInt(2, parseInt(body.get("donGia"), 0));
                        statement.setString(3, body.getOrDefault("dvt", ""));
                        statement.setInt(4, id);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Ingredient updated", "id", id));
                    return;
                }

                if ("DELETE".equals(method)) {
                    int id = parseInt(firstNonBlank(query.get("id"), readBodyId(exchange.getRequestBody())), -1);
                    if (id <= 0) {
                        writeJson(exchange, 400, Json.object("error", "Missing id"));
                        return;
                    }
                    try (PreparedStatement deleteKho = connection.prepareStatement("DELETE FROM Kho WHERE ID_NL = ?");
                         PreparedStatement deleteIngredient = connection.prepareStatement("DELETE FROM NguyenLieu WHERE ID_NL = ?")) {
                        deleteKho.setInt(1, id);
                        deleteKho.executeUpdate();
                        deleteIngredient.setInt(1, id);
                        deleteIngredient.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Ingredient deleted", "id", id));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private List<Map<String, Object>> fetchIngredientList(Connection connection) throws SQLException {
            List<Map<String, Object>> items = new ArrayList<>();
            String sql = "SELECT N.ID_NL,N.TenNL,N.Dongia,N.Donvitinh,NVL(K.SLTon,0) AS SLTon "
                    + "FROM NguyenLieu N LEFT JOIN Kho K ON K.ID_NL = N.ID_NL ORDER BY N.ID_NL";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> ingredient = new LinkedHashMap<>();
                    ingredient.put("id", resultSet.getInt("ID_NL"));
                    ingredient.put("name", resultSet.getString("TenNL"));
                    ingredient.put("price", resultSet.getInt("Dongia"));
                    ingredient.put("unit", resultSet.getString("Donvitinh"));
                    ingredient.put("stock", resultSet.getInt("SLTon"));
                    items.add(ingredient);
                }
            }
            return items;
        }

        private Map<String, Object> fetchIngredientById(Connection connection, int id) throws SQLException {
            String sql = "SELECT N.ID_NL,N.TenNL,N.Dongia,N.Donvitinh,NVL(K.SLTon,0) AS SLTon "
                    + "FROM NguyenLieu N LEFT JOIN Kho K ON K.ID_NL = N.ID_NL WHERE N.ID_NL=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, id);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        Map<String, Object> ingredient = new LinkedHashMap<>();
                        ingredient.put("id", resultSet.getInt("ID_NL"));
                        ingredient.put("name", resultSet.getString("TenNL"));
                        ingredient.put("price", resultSet.getInt("Dongia"));
                        ingredient.put("unit", resultSet.getString("Donvitinh"));
                        ingredient.put("stock", resultSet.getInt("SLTon"));
                        return ingredient;
                    }
                }
            }
            return new LinkedHashMap<>();
        }
    }

    private static final class VoucherHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase();
            Map<String, String> query = queryParams(exchange.getRequestURI());

            try (Connection connection = OracleDb.openConnection()) {
                if ("GET".equals(method)) {
                    if (query.containsKey("code")) {
                        writeJson(exchange, 200, Json.object("voucher", fetchVoucherByCode(connection, query.get("code"))));
                        return;
                    }
                    List<Map<String, Object>> items = fetchVoucherList(connection, query.get("byPoint"));
                    writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
                    return;
                }

                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    String sql = "INSERT INTO Voucher(Code_Voucher,Mota,Phantram,LoaiMA,SoLuong,Diem) VALUES(?,?,?,?,?,?)";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, body.getOrDefault("code", ""));
                        statement.setString(2, body.getOrDefault("description", ""));
                        statement.setInt(3, parseInt(body.get("percent"), 0));
                        statement.setString(4, body.getOrDefault("typeMenu", "All"));
                        statement.setInt(5, parseInt(body.get("quantity"), 0));
                        statement.setInt(6, parseInt(body.get("point"), 0));
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 201, Json.object("message", "Voucher created", "code", body.getOrDefault("code", "")));
                    return;
                }

                if ("PUT".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    String code = firstNonBlank(body.get("code"), query.get("code"));
                    if (isBlank(code)) {
                        writeJson(exchange, 400, Json.object("error", "Missing code"));
                        return;
                    }
                    String sql = "UPDATE Voucher SET Mota=?, Phantram=?, LoaiMA=?, SoLuong=?, Diem=? WHERE Code_Voucher=?";
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, body.getOrDefault("description", ""));
                        statement.setInt(2, parseInt(body.get("percent"), 0));
                        statement.setString(3, body.getOrDefault("typeMenu", "All"));
                        statement.setInt(4, parseInt(body.get("quantity"), 0));
                        statement.setInt(5, parseInt(body.get("point"), 0));
                        statement.setString(6, code);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Voucher updated", "code", code));
                    return;
                }

                if ("DELETE".equals(method)) {
                    String code = firstNonBlank(query.get("code"), readBodyId(exchange.getRequestBody()));
                    if (isBlank(code)) {
                        writeJson(exchange, 400, Json.object("error", "Missing code"));
                        return;
                    }
                    try (PreparedStatement statement = connection.prepareStatement("DELETE FROM Voucher WHERE Code_Voucher=?")) {
                        statement.setString(1, code);
                        statement.executeUpdate();
                    }
                    writeJson(exchange, 200, Json.object("message", "Voucher deleted", "code", code));
                    return;
                }

                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private List<Map<String, Object>> fetchVoucherList(Connection connection, String byPoint) throws SQLException {
            List<Map<String, Object>> items = new ArrayList<>();
            String sql = "SELECT Code_Voucher,Mota,Phantram,LoaiMA,SoLuong,Diem FROM Voucher";
            if ("under300".equalsIgnoreCase(byPoint)) {
                sql += " WHERE Diem < 300";
            } else if ("300to500".equalsIgnoreCase(byPoint)) {
                sql += " WHERE Diem BETWEEN 300 AND 500";
            } else if ("above500".equalsIgnoreCase(byPoint)) {
                sql += " WHERE Diem > 500";
            }
            sql += " ORDER BY Code_Voucher";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(voucherFromRow(resultSet));
                }
            }
            return items;
        }

        private Map<String, Object> fetchVoucherByCode(Connection connection, String code) throws SQLException {
            String sql = "SELECT Code_Voucher,Mota,Phantram,LoaiMA,SoLuong,Diem FROM Voucher WHERE Code_Voucher=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, code);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return voucherFromRow(resultSet);
                    }
                }
            }
            return new LinkedHashMap<>();
        }

        private Map<String, Object> voucherFromRow(ResultSet resultSet) throws SQLException {
            Map<String, Object> voucher = new LinkedHashMap<>();
            voucher.put("code", resultSet.getString("Code_Voucher"));
            voucher.put("description", resultSet.getString("Mota"));
            voucher.put("percent", resultSet.getInt("Phantram"));
            voucher.put("typeMenu", resultSet.getString("LoaiMA"));
            voucher.put("quantity", resultSet.getInt("SoLuong"));
            voucher.put("point", resultSet.getInt("Diem"));
            return voucher;
        }
    }

    private static final class ImportReceiptHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            if (!"GET".equals(method) && !"POST".equals(method)) {
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> query = queryParams(exchange.getRequestURI());
            Integer id = parseNullableInt(query.get("id"));

            try (Connection connection = OracleDb.openConnection()) {
                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int staffId = parseInt(body.get("staffId"), -1);
                    LocalDate date = parseDate(body.get("date"));
                    String detailsText = firstNonBlank(body.get("details"), "");

                    Map<Integer, Integer> detailMap = new LinkedHashMap<>();
                    if (!isBlank(detailsText)) {
                        String[] entries = detailsText.split(",");
                        for (String entry : entries) {
                            String[] pair = entry.split(":");
                            if (pair.length != 2) {
                                continue;
                            }
                            int ingredientId = parseInt(pair[0], -1);
                            int quantity = parseInt(pair[1], 0);
                            if (ingredientId > 0 && quantity > 0) {
                                detailMap.merge(ingredientId, quantity, Integer::sum);
                            }
                        }
                    }

                    if (detailMap.isEmpty()) {
                        int ingredientId = parseInt(body.get("ingredientId"), -1);
                        int quantity = parseInt(body.get("quantity"), 0);
                        if (ingredientId > 0 && quantity > 0) {
                            detailMap.put(ingredientId, quantity);
                        }
                    }

                    if (staffId <= 0 || detailMap.isEmpty()) {
                        writeJson(exchange, 400, Json.object("error", "Missing staffId or details"));
                        return;
                    }

                    int nextId = fetchNextId(connection, "PhieuNK", "ID_NK");
                    String insertHeaderSql = "INSERT INTO PhieuNK(ID_NK, ID_NV, NgayNK, Tongtien) VALUES(?, ?, ?, 0)";
                    try (PreparedStatement insertHeader = connection.prepareStatement(insertHeaderSql)) {
                        insertHeader.setInt(1, nextId);
                        insertHeader.setInt(2, staffId);
                        insertHeader.setDate(3, Date.valueOf(date != null ? date : LocalDate.now()));
                        insertHeader.executeUpdate();
                    }

                    String insertDetailSql = "INSERT INTO CTNK(ID_NK, ID_NL, SoLuong, Thanhtien) VALUES(?, ?, ?, 0)";
                    try (PreparedStatement insertDetail = connection.prepareStatement(insertDetailSql)) {
                        for (Map.Entry<Integer, Integer> item : detailMap.entrySet()) {
                            insertDetail.setInt(1, nextId);
                            insertDetail.setInt(2, item.getKey());
                            insertDetail.setInt(3, item.getValue());
                            insertDetail.executeUpdate();
                        }
                    }

                    writeJson(exchange, 201, Json.object("message", "Import receipt created", "id", nextId));
                    return;
                }

                if (id != null && id > 0) {
                    String headerSql = "SELECT ID_NK, ID_NV, to_char(NgayNK,'yyyy-mm-dd') AS NgayNK, Tongtien FROM PhieuNK WHERE ID_NK=?";
                    try (PreparedStatement statement = connection.prepareStatement(headerSql)) {
                        statement.setInt(1, id);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                writeJson(exchange, 404, Json.object("error", "Import receipt not found"));
                                return;
                            }

                            Map<String, Object> receipt = new LinkedHashMap<>();
                            receipt.put("id", resultSet.getInt("ID_NK"));
                            receipt.put("staffId", resultSet.getInt("ID_NV"));
                            receipt.put("date", resultSet.getString("NgayNK"));
                            receipt.put("total", resultSet.getInt("Tongtien"));
                            receipt.put("details", fetchImportDetails(connection, id));
                            writeJson(exchange, 200, Json.object("receipt", receipt));
                            return;
                        }
                    }
                }

                List<Map<String, Object>> items = new ArrayList<>();
                String sql = "SELECT ID_NK, ID_NV, to_char(NgayNK,'yyyy-mm-dd') AS NgayNK, Tongtien FROM PhieuNK ORDER BY ID_NK DESC";
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Map<String, Object> receipt = new LinkedHashMap<>();
                        receipt.put("id", resultSet.getInt("ID_NK"));
                        receipt.put("staffId", resultSet.getInt("ID_NV"));
                        receipt.put("date", resultSet.getString("NgayNK"));
                        receipt.put("total", resultSet.getInt("Tongtien"));
                        items.add(receipt);
                    }
                }
                writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private List<Map<String, Object>> fetchImportDetails(Connection connection, int receiptId) throws SQLException {
            List<Map<String, Object>> details = new ArrayList<>();
            String sql = "SELECT CTNK.ID_NL, TenNL, SoLuong, Thanhtien FROM CTNK "
                    + "JOIN NguyenLieu ON NguyenLieu.ID_NL = CTNK.ID_NL WHERE ID_NK=? ORDER BY CTNK.ID_NL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, receiptId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("ingredientId", resultSet.getInt("ID_NL"));
                        detail.put("ingredientName", resultSet.getString("TenNL"));
                        detail.put("quantity", resultSet.getInt("SoLuong"));
                        detail.put("total", resultSet.getInt("Thanhtien"));
                        details.add(detail);
                    }
                }
            }
            return details;
        }
    }

    private static final class ExportReceiptHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            if (!"GET".equals(method) && !"POST".equals(method)) {
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> query = queryParams(exchange.getRequestURI());
            Integer id = parseNullableInt(query.get("id"));

            try (Connection connection = OracleDb.openConnection()) {
                if ("POST".equals(method)) {
                    Map<String, String> body = readJsonBody(exchange.getRequestBody());
                    int staffId = parseInt(body.get("staffId"), -1);
                    LocalDate date = parseDate(body.get("date"));
                    String detailsText = firstNonBlank(body.get("details"), "");

                    Map<Integer, Integer> detailMap = new LinkedHashMap<>();
                    if (!isBlank(detailsText)) {
                        String[] entries = detailsText.split(",");
                        for (String entry : entries) {
                            String[] pair = entry.split(":");
                            if (pair.length != 2) {
                                continue;
                            }
                            int ingredientId = parseInt(pair[0], -1);
                            int quantity = parseInt(pair[1], 0);
                            if (ingredientId > 0 && quantity > 0) {
                                detailMap.merge(ingredientId, quantity, Integer::sum);
                            }
                        }
                    }

                    if (detailMap.isEmpty()) {
                        int ingredientId = parseInt(body.get("ingredientId"), -1);
                        int quantity = parseInt(body.get("quantity"), 0);
                        if (ingredientId > 0 && quantity > 0) {
                            detailMap.put(ingredientId, quantity);
                        }
                    }

                    if (staffId <= 0 || detailMap.isEmpty()) {
                        writeJson(exchange, 400, Json.object("error", "Missing staffId or details"));
                        return;
                    }

                    int nextId = fetchNextId(connection, "PhieuXK", "ID_XK");
                    String insertHeaderSql = "INSERT INTO PhieuXK(ID_XK, ID_NV, NgayXK) VALUES(?, ?, ?)";
                    try (PreparedStatement insertHeader = connection.prepareStatement(insertHeaderSql)) {
                        insertHeader.setInt(1, nextId);
                        insertHeader.setInt(2, staffId);
                        insertHeader.setDate(3, Date.valueOf(date != null ? date : LocalDate.now()));
                        insertHeader.executeUpdate();
                    }

                    String insertDetailSql = "INSERT INTO CTXK(ID_XK, ID_NL, SoLuong) VALUES(?, ?, ?)";
                    try (PreparedStatement insertDetail = connection.prepareStatement(insertDetailSql)) {
                        for (Map.Entry<Integer, Integer> item : detailMap.entrySet()) {
                            insertDetail.setInt(1, nextId);
                            insertDetail.setInt(2, item.getKey());
                            insertDetail.setInt(3, item.getValue());
                            insertDetail.executeUpdate();
                        }
                    }

                    writeJson(exchange, 201, Json.object("message", "Export receipt created", "id", nextId));
                    return;
                }

                if (id != null && id > 0) {
                    String headerSql = "SELECT ID_XK, ID_NV, to_char(NgayXK,'yyyy-mm-dd') AS NgayXK FROM PhieuXK WHERE ID_XK=?";
                    try (PreparedStatement statement = connection.prepareStatement(headerSql)) {
                        statement.setInt(1, id);
                        try (ResultSet resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                writeJson(exchange, 404, Json.object("error", "Export receipt not found"));
                                return;
                            }

                            Map<String, Object> receipt = new LinkedHashMap<>();
                            receipt.put("id", resultSet.getInt("ID_XK"));
                            receipt.put("staffId", resultSet.getInt("ID_NV"));
                            receipt.put("date", resultSet.getString("NgayXK"));
                            receipt.put("details", fetchExportDetails(connection, id));
                            writeJson(exchange, 200, Json.object("receipt", receipt));
                            return;
                        }
                    }
                }

                List<Map<String, Object>> items = new ArrayList<>();
                String sql = "SELECT ID_XK, ID_NV, to_char(NgayXK,'yyyy-mm-dd') AS NgayXK FROM PhieuXK ORDER BY ID_XK DESC";
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Map<String, Object> receipt = new LinkedHashMap<>();
                        receipt.put("id", resultSet.getInt("ID_XK"));
                        receipt.put("staffId", resultSet.getInt("ID_NV"));
                        receipt.put("date", resultSet.getString("NgayXK"));
                        items.add(receipt);
                    }
                }
                writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private List<Map<String, Object>> fetchExportDetails(Connection connection, int receiptId) throws SQLException {
            List<Map<String, Object>> details = new ArrayList<>();
            String sql = "SELECT CTXK.ID_NL, TenNL, SoLuong FROM CTXK "
                    + "JOIN NguyenLieu ON NguyenLieu.ID_NL = CTXK.ID_NL WHERE ID_XK=? ORDER BY CTXK.ID_NL";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, receiptId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("ingredientId", resultSet.getInt("ID_NL"));
                        detail.put("ingredientName", resultSet.getString("TenNL"));
                        detail.put("quantity", resultSet.getInt("SoLuong"));
                        details.add(detail);
                    }
                }
            }
            return details;
        }
    }

    private static final class CustomerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Map<String, String> query = queryParams(exchange.getRequestURI());
            try (Connection connection = OracleDb.openConnection()) {
                int userId = parseInt(query.get("userId"), -1);
                if (userId <= 0) {
                    List<Map<String, Object>> items = fetchCustomerList(connection);
                    writeJson(exchange, 200, Json.object("items", items, "count", items.size()));
                    return;
                }

                String sql = "SELECT ID_KH, TenKH, to_char(Ngaythamgia,'yyyy-mm-dd') AS Ngaythamgia, Doanhso, Diemtichluy FROM KhachHang WHERE ID_ND=?";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, userId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            Map<String, Object> customer = new LinkedHashMap<>();
                            customer.put("id", resultSet.getInt("ID_KH"));
                            customer.put("name", resultSet.getString("TenKH"));
                            customer.put("joinedDate", resultSet.getString("Ngaythamgia"));
                            customer.put("sales", resultSet.getInt("Doanhso"));
                            customer.put("points", resultSet.getInt("Diemtichluy"));
                            writeJson(exchange, 200, Json.object("customer", customer));
                            return;
                        }
                    }
                }
                writeJson(exchange, 404, Json.object("error", "Customer not found"));
            } catch (SQLException ex) {
                writeJson(exchange, 500, Json.object("error", ex.getMessage()));
            }
        }

        private List<Map<String, Object>> fetchCustomerList(Connection connection) throws SQLException {
            List<Map<String, Object>> items = new ArrayList<>();
            String sql = "SELECT ID_KH, TenKH, to_char(Ngaythamgia,'yyyy-mm-dd') AS Ngaythamgia, Doanhso, Diemtichluy, ID_ND FROM KhachHang ORDER BY ID_KH";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Map<String, Object> customer = new LinkedHashMap<>();
                    customer.put("id", resultSet.getInt("ID_KH"));
                    customer.put("name", resultSet.getString("TenKH"));
                    customer.put("joinedDate", resultSet.getString("Ngaythamgia"));
                    customer.put("sales", resultSet.getInt("Doanhso"));
                    customer.put("points", resultSet.getInt("Diemtichluy"));
                    customer.put("userId", resultSet.getObject("ID_ND"));
                    items.add(customer);
                }
            }
            return items;
        }
    }

    private static final class NotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            handleCors(exchange);
            if (isOptions(exchange)) {
                writeResponse(exchange, 204, "");
                return;
            }
            writeJson(exchange, 404, Json.object("error", "Endpoint not found"));
        }
    }

    private static final class WebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (isOptions(exchange)) {
                handleCors(exchange);
                writeResponse(exchange, 204, "");
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod()) && !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCors(exchange);
                writeJson(exchange, 405, Json.object("error", "Method not allowed"));
                return;
            }

            Path root = WEB_ROOT.toAbsolutePath().normalize();
            String requestPath = exchange.getRequestURI().getPath();
            String relativePath = resolveWebPath(requestPath);
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root) || Files.isDirectory(target) || !Files.exists(target)) {
                if ("index.html".equals(relativePath)) {
                    handleCors(exchange);
                    writeJson(exchange, 500, Json.object("error", "Web assets are missing"));
                    return;
                }
                target = root.resolve("index.html");
            }

            byte[] body = Files.readAllBytes(target);
            Headers headers = exchange.getResponseHeaders();
            handleCors(exchange);
            headers.set("Content-Type", contentTypeFor(target.getFileName().toString()));
            headers.set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    outputStream.write(body);
                }
            } else {
                exchange.getResponseBody().close();
            }
            exchange.close();
        }
    }

    private static void handleCors(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        headers.set("Content-Type", "application/json; charset=UTF-8");
    }

    private static String resolveWebPath(String requestPath) {
        if (requestPath == null || requestPath.isBlank() || "/".equals(requestPath) || !requestPath.contains(".")) {
            return "index.html";
        }
        return requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
    }

    private static String contentTypeFor(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }

    private static boolean isOptions(HttpExchange exchange) {
        return "OPTIONS".equalsIgnoreCase(exchange.getRequestMethod());
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        writeResponse(exchange, statusCode, json);
    }

    private static void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return params;
        }
        for (String token : query.split("&")) {
            if (token.isBlank()) {
                continue;
            }
            int index = token.indexOf('=');
            String key = index >= 0 ? token.substring(0, index) : token;
            String value = index >= 0 ? token.substring(index + 1) : "";
            params.put(urlDecode(key), urlDecode(value));
        }
        return params;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> readJsonBody(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return Json.parseObject(builder.toString());
    }

    private static String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return second;
    }

    private static boolean isInvalidTriggerError(SQLException ex, String triggerName) {
        if (ex == null) {
            return false;
        }
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        String upper = message.toUpperCase();
        if (!upper.contains("ORA-04098")) {
            return false;
        }
        return isBlank(triggerName) || upper.contains(triggerName.toUpperCase());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int parseInt(String value, int defaultValue) {
        if (isBlank(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static Integer parseNullableInt(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int fetchNextId(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = "SELECT NVL(MAX(" + columnName + "), 0) + 1 AS NEXT_ID FROM " + tableName;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getInt("NEXT_ID");
            }
        }
        return 1;
    }

    private static String normalizeDateInput(String value) {
        if (isBlank(value)) {
            return LocalDate.now().toString();
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{2}-\\d{2}-\\d{4}")) {
            String[] parts = trimmed.split("-");
            return parts[2] + "-" + parts[1] + "-" + parts[0];
        }
        return trimmed;
    }

    private static String readBodyId(InputStream inputStream) throws IOException {
        Map<String, String> body = readJsonBody(inputStream);
        return firstNonBlank(body.get("id"), body.get("code"));
    }

    private static LocalDate parseDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static final class OracleDb {
        private static final String DEFAULT_URL = "jdbc:oracle:thin:@localhost:1521/XEPDB1";
        private static final String DEFAULT_USERNAME = "huanvu";
        private static final String DEFAULT_PASSWORD = "123456";

        private OracleDb() {
        }

        static Connection openConnection() throws SQLException {
            try {
                Class.forName("oracle.jdbc.OracleDriver");
            } catch (ClassNotFoundException ex) {
                throw new SQLException("Oracle JDBC driver is not available", ex);
            }

            String url = firstNonBlank(System.getProperty("restaurant.db.url"), System.getenv("RESTAURANT_DB_URL"));
            String username = firstNonBlank(System.getProperty("restaurant.db.username"), System.getenv("RESTAURANT_DB_USERNAME"));
            String password = firstNonBlank(System.getProperty("restaurant.db.password"), System.getenv("RESTAURANT_DB_PASSWORD"));

            if (isBlank(url)) {
                url = DEFAULT_URL;
            }
            if (isBlank(username)) {
                username = DEFAULT_USERNAME;
            }
            if (isBlank(password)) {
                password = DEFAULT_PASSWORD;
            }

            return DriverManager.getConnection(url, username, password);
        }

        static String describeTarget() {
            String url = firstNonBlank(System.getProperty("restaurant.db.url"), System.getenv("RESTAURANT_DB_URL"));
            String username = firstNonBlank(System.getProperty("restaurant.db.username"), System.getenv("RESTAURANT_DB_USERNAME"));
            if (isBlank(url)) {
                url = DEFAULT_URL;
            }
            if (isBlank(username)) {
                username = DEFAULT_USERNAME;
            }
            return username + " @ " + url;
        }
    }

    private static final class Json {
        private Json() {
        }

        static String object(Object... keyValues) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < keyValues.length; i += 2) {
                String key = String.valueOf(keyValues[i]);
                Object value = i + 1 < keyValues.length ? keyValues[i + 1] : null;
                map.put(key, value);
            }
            return stringify(map);
        }

        static Map<String, String> parseObject(String json) {
            Map<String, String> result = new LinkedHashMap<>();
            if (json == null) {
                return result;
            }
            String text = json.trim();
            if (text.length() < 2 || text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}') {
                return result;
            }

            String body = text.substring(1, text.length() - 1).trim();
            if (body.isEmpty()) {
                return result;
            }

            List<String> pairs = splitTopLevel(body);
            for (String pair : pairs) {
                int colon = pair.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String rawKey = pair.substring(0, colon).trim();
                String rawValue = pair.substring(colon + 1).trim();
                String key = stripQuotes(rawKey);
                String value = stripQuotes(rawValue);
                result.put(key, value);
            }
            return result;
        }

        private static List<String> splitTopLevel(String text) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean inQuotes = false;
            boolean escaped = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (escaped) {
                    current.append(ch);
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    current.append(ch);
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    current.append(ch);
                    inQuotes = !inQuotes;
                    continue;
                }
                if (ch == ',' && !inQuotes) {
                    tokens.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
                current.append(ch);
            }
            if (!current.isEmpty()) {
                tokens.add(current.toString().trim());
            }
            return tokens;
        }

        private static String stripQuotes(String value) {
            String text = value == null ? "" : value.trim();
            if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length() - 1);
            }
            return text.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }

        static String stringify(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof String stringValue) {
                return '"' + escape(stringValue) + '"';
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
            if (value instanceof Map<?, ?> map) {
                StringBuilder builder = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                    builder.append(stringify(entry.getValue()));
                }
                builder.append('}');
                return builder.toString();
            }
            if (value instanceof Iterable<?> iterable) {
                StringBuilder builder = new StringBuilder("[");
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) {
                        builder.append(',');
                    }
                    first = false;
                    builder.append(stringify(item));
                }
                builder.append(']');
                return builder.toString();
            }
            if (value.getClass().isArray()) {
                if (value instanceof Object[] objects) {
                    return stringify(Arrays.asList(objects));
                }
            }
            return '"' + escape(String.valueOf(value)) + '"';
        }

        private static String escape(String value) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '"' -> builder.append("\\\"");
                    case '\\' -> builder.append("\\\\");
                    case '\b' -> builder.append("\\b");
                    case '\f' -> builder.append("\\f");
                    case '\n' -> builder.append("\\n");
                    case '\r' -> builder.append("\\r");
                    case '\t' -> builder.append("\\t");
                    default -> {
                        if (ch < 0x20) {
                            builder.append(String.format("\\u%04x", (int) ch));
                        } else {
                            builder.append(ch);
                        }
                    }
                }
            }
            return builder.toString();
        }
    }
}