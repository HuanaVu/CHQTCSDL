
package RTDRestaurant.Controller.Connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

// Kết nối tới DataBase của hệ thống

public class DatabaseConnection {

    private static DatabaseConnection instance;
    private static final String DEFAULT_URL = "jdbc:oracle:thin:@localhost:1521/XEPDB1";
    private static final String DEFAULT_USERNAME = "huanvu";
    private static final String DEFAULT_PASSWORD = "123456";
    private Connection connection;

    public static DatabaseConnection getInstance() {
        if (instance == null) {
            instance = new DatabaseConnection();
        }
        return instance;
    }

    private DatabaseConnection() {

    }
    //Thực hiện kết nối tới Database
    public void connectToDatabase() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }

        try {
            Class.forName("oracle.jdbc.OracleDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Không tìm thấy Oracle JDBC driver.", e);
        }

        String configuredUrl = System.getProperty("restaurant.db.url", System.getenv().getOrDefault("RESTAURANT_DB_URL", DEFAULT_URL));
        String username = System.getProperty("restaurant.db.username", System.getenv().getOrDefault("RESTAURANT_DB_USERNAME", DEFAULT_USERNAME));
        String password = System.getProperty("restaurant.db.password", System.getenv().getOrDefault("RESTAURANT_DB_PASSWORD", DEFAULT_PASSWORD));

        List<String> urlsToTry = buildCandidateUrls(configuredUrl);
        SQLException lastError = null;
        for (String url : urlsToTry) {
            try {
                connection = DriverManager.getConnection(url, username, password);
                System.out.println("Connected Oracle DB via: " + url);
                return;
            } catch (SQLException ex) {
                lastError = ex;
            }
        }

        SQLException failure = new SQLException("Khong ket noi duoc Oracle. Thu cac URL: " + String.join(", ", urlsToTry), lastError);
        throw failure;
    }

    private List<String> buildCandidateUrls(String preferredUrl) {
        List<String> urls = new ArrayList<>();
        addIfAbsent(urls, preferredUrl);
        addIfAbsent(urls, DEFAULT_URL);

        String host = System.getProperty("restaurant.db.host", System.getenv().getOrDefault("RESTAURANT_DB_HOST", "localhost"));
        String port = System.getProperty("restaurant.db.port", System.getenv().getOrDefault("RESTAURANT_DB_PORT", "1521"));
        String service = System.getProperty("restaurant.db.service", System.getenv().getOrDefault("RESTAURANT_DB_SERVICE", "XEPDB1"));
        String sid = System.getProperty("restaurant.db.sid", System.getenv().getOrDefault("RESTAURANT_DB_SID", "XE"));

        addIfAbsent(urls, "jdbc:oracle:thin:@" + host + ":" + port + "/" + service);
        addIfAbsent(urls, "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid);
        addIfAbsent(urls, "jdbc:oracle:thin:@" + host + ":" + port + "/XE");
        addIfAbsent(urls, "jdbc:oracle:thin:@" + host + ":" + port + "/ORCL");
        addIfAbsent(urls, "jdbc:oracle:thin:@" + host + ":" + port + ":ORCL");

        return urls;
    }

    private void addIfAbsent(List<String> urls, String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        if (!urls.contains(url)) {
            urls.add(url);
        }
    }
 
    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connectToDatabase();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Khong the lay ket noi Database", ex);
        }
        return connection;
    }
    
    public void setConnection(Connection connection) {
        this.connection = connection;
    }
}

