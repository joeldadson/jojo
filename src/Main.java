import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class Main {
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_LOW_STOCK = 2;
    private static final String CATEGORY_CUSTOM_VALUE = "__custom__";
    private static final List<String> BOOK_CATEGORIES = List.of(
            "BIBLE",
            "CALENDAR",
            "MISSAL",
            "PRAYER BOOK",
            "HYMNAL"
    );

    // Serves files from ./static (relative to where you run the program).
    private static final Path STATIC_DIR = Path.of("static").toAbsolutePath().normalize();

    // Simple disk persistence (CSV) in ./data
    private static final Path DATA_DIR = Path.of("data").toAbsolutePath().normalize();
    private static final Path BOOKS_CSV = DATA_DIR.resolve("books.csv");
    private static final Path QTY_HISTORY_CSV = DATA_DIR.resolve("qty_history.csv");
    private static final Path TOTAL_QTY_HISTORY_CSV = DATA_DIR.resolve("total_qty_history.csv");
    private static final Path CUSTOMERS_CSV = DATA_DIR.resolve("customers.csv");
    private static final Path ORDERS_CSV = DATA_DIR.resolve("orders.csv");

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, Book> BOOKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<QtyPoint>> QTY_HISTORY = new ConcurrentHashMap<>();
    private static final List<QtyPoint> TOTAL_QTY_HISTORY = Collections.synchronizedList(new ArrayList<>());

    private static final AtomicLong NEXT_CUSTOMER_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, Customer> CUSTOMERS = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ORDER_ID = new AtomicLong(1);
    private static final ConcurrentHashMap<Long, Order> ORDERS = new ConcurrentHashMap<>();

    // Authentication and UX config that can be modified via settings
    private static String adminUser = "admin";
    private static String adminPass = "1234";
    private static String currentTheme = "dark";
    private static String currentFontSize = "14px";
    private static final Path SETTINGS_TXT = DATA_DIR.resolve("settings.txt");
    private static final String SESSION_COOKIE = "sid";
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    // Audit trail (in-memory)
    private static final List<ActivityEvent> ACTIVITY = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        ensureDataDir();
        loadFromDisk();
        seedIfEmpty();
        // If seed ran, persist it.
        saveBooksToDisk();

        String host = Optional.ofNullable(getArgValue(args, "--host=")).orElse(DEFAULT_HOST);
        int requestedPort = Optional.ofNullable(getArgValue(args, "--port="))
                .map(Main::parseIntOrNull)
                .orElse(DEFAULT_PORT);
        if (requestedPort < 0 || requestedPort > 65535) requestedPort = DEFAULT_PORT;

        HttpServer server = createServerWithFallback(host, requestedPort);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));

        server.createContext("/", Main::handleRoot);
        server.createContext("/static/", Main::handleStatic);
        server.createContext("/welcome", Main::handleWelcome);
        server.createContext("/login", Main::handleLogin);
        server.createContext("/logout", Main::handleLogout);
        server.createContext("/activity", Main::handleActivity);
        server.createContext("/settings", Main::handleSettings);
        server.createContext("/reports", Main::handleReports);
        server.createContext("/reports/inventory.csv", Main::handleInventoryCsv);
        server.createContext("/reports/stock.json", Main::handleStockJson);
        server.createContext("/customers", Main::handleCustomers);
        server.createContext("/customers/view", Main::handleCustomerView);
        server.createContext("/orders", Main::handleOrders);
        server.createContext("/orders/new", Main::handleOrderNew);
        server.createContext("/orders/create", Main::handleOrderCreate);
        server.createContext("/orders/receipt", Main::handleOrderReceipt);
        server.createContext("/books/new", Main::handleNewBook);
        server.createContext("/books/create", Main::handleCreateBook);
        server.createContext("/books/edit", Main::handleEditBook);
        server.createContext("/books/update", Main::handleUpdateBook);
        server.createContext("/books/delete", Main::handleDeleteBook);

        server.start();
        int actualPort = server.getAddress().getPort();
        System.out.println("Bookstore Web running: - Main.java:115");
        System.out.println("http:// - Main.java:116" + host + ":" + actualPort + "/");
        System.out.println("Static dir: - Main.java:117");
        System.out.println("  " + STATIC_DIR);
        System.out.println("Data dir: - Main.java:119");
        System.out.println("  " + DATA_DIR);
    }

    private static HttpServer createServerWithFallback(String host, int port) throws IOException {
        try {
            return HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (BindException e) {
            if (port == 0) throw e;
            System.out.println("Port - Main.java:128" + port + " is busy. Falling back to a free port...");
            return HttpServer.create(new InetSocketAddress(host, 0), 0);
        }
    }

    private static String getArgValue(String[] args, String prefix) {
        if (args == null) return null;
        for (String a : args) {
            if (a != null && a.startsWith(prefix) && a.length() > prefix.length()) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }

    // --------------------
    // Routing
    // --------------------

    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        String query = trimToNull(q.get("q"));
        int size = Optional.ofNullable(parseIntOrNull(trimToNull(q.get("size")))).orElse(10);
        if (size < 5) size = 5;
        if (size > 50) size = 50;
        int page = Optional.ofNullable(parseIntOrNull(trimToNull(q.get("page")))).orElse(1);
        if (page < 1) page = 1;

        List<Book> books = new ArrayList<>(BOOKS.values());
        books.sort(Comparator.comparingLong(b -> b.id));
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            books.removeIf(b -> !(containsIgnoreCase(b.title, needle)
                    || containsIgnoreCase(b.author, needle)
                    || containsIgnoreCase(b.category, needle)
                    || containsIgnoreCase(b.isbn, needle)));
        }

        int total = books.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        if (page > totalPages) page = totalPages;
        int from = Math.min(total, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<Book> pageBooks = books.subList(from, to);

        long lowCount = books.stream().filter(b -> b.quantity <= DEFAULT_LOW_STOCK).count();
        String lowHtml = lowCount == 0
                ? ""
                : """
                    <div class="banner warn">
                      <div><strong>Low stock:</strong> <span class="mono">%s</span> item(s) at or below <span class="mono">%s</span>.</div>
                      <a class="btn ghost" href="/reports?low=%s">View Report</a>
                    </div>
                    """.formatted(lowCount, DEFAULT_LOW_STOCK, DEFAULT_LOW_STOCK);

        String missionVisionHtml = """
                <section class="mvInfoSection">
                  <div class="mvInfoContent">
                    <div class="mvInfoItem">
                      <div class="mvInfoLabel">MISSION</div>
                      <div class="mvInfoText">To publish and share resources that form minds and hearts, support learning, and serve the community through trustworthy, accessible books.</div>
                    </div>
                    <div class="mvInfoDivider"></div>
                    <div class="mvInfoItem">
                      <div class="mvInfoLabel">VISION</div>
                      <div class="mvInfoText">To be a leading faith-rooted publisher recognized for quality, integrity, and wide-reaching impact in education and spiritual growth.</div>
                    </div>
                  </div>
                </section>
                """;

        String html = renderLayout("Books",
                """
                        <header class="topbar">
                          <div class="brand">
                            <img class="logoMark" src="/static/logo.svg" alt="Franciscan Publications" />
                            <div>
                              <div class="name">Franciscan Publications</div>
                              <div class="tag">Simple Java + HTML/CSS app</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        %s

                        <section class="panel">
                          %s
                          <form class="search" method="get" action="/">
                            <input name="q" placeholder="Search title, author, ISBN..." value="%s" />
                            <input type="hidden" name="size" value="%s" />
                            <button class="btn secondary" type="submit">Search</button>
                            <a class="btn ghost" href="/">Clear</a>
                          </form>
                        </section>

                        <section class="panel">
                          %s
                          %s
                        </section>
                        """.formatted(
                        escapeHtml(username),
                        missionVisionHtml,
                        lowHtml,
                        escapeHtml(Optional.ofNullable(query).orElse("")),
                        escapeHtml(String.valueOf(size)),
                        renderBooksTable(pageBooks),
                        renderPager(total, page, size, query)
                ));

        sendHtml(ex, 200, html);
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }

        String path = ex.getRequestURI().getPath();
        if (path == null || !path.startsWith("/static/")) {
            sendStatus(ex, 404);
            return;
        }

        String rel = path.substring("/static/".length());
        if (rel.isBlank() || rel.contains("..") || rel.contains("\\") || rel.contains(":")) {
            sendStatus(ex, 400);
            return;
        }

        Path target = STATIC_DIR.resolve(rel).normalize();
        if (!target.startsWith(STATIC_DIR) || !Files.exists(target) || Files.isDirectory(target)) {
            sendStatus(ex, 404);
            return;
        }

        String contentType = guessContentType(target.getFileName().toString());
        byte[] bytes = Files.readAllBytes(target);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleNewBook(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        String html = renderLayout("Add Book",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Add Book</div>
                              <div class="tag">Create a new record</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          %s
                        </section>
                        """.formatted(escapeHtml(username), renderBookForm(null, "/books/create", "Create")));
        sendHtml(ex, 200, html);
    }

    private static void handleEditBook(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;
        Map<String, String> q = parseQuery(ex.getRequestURI());
        Long id = parseLongOrNull(q.get("id"));
        if (id == null) {
            sendStatus(ex, 400);
            return;
        }
        Book b = BOOKS.get(id);
        if (b == null) {
            sendStatus(ex, 404);
            return;
        }

        String html = renderLayout("Edit Book",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Edit Book</div>
                              <div class="tag">Update record #%s</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          %s
                        </section>
                        """.formatted(b.id, escapeHtml(username), renderBookForm(b, "/books/update", "Save Changes")));
        sendHtml(ex, 200, html);
    }

    private static void handleCreateBook(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> form = parseForm(ex);
        BookInput in = BookInput.from(form);
        List<String> errors = validate(in);
        if (!errors.isEmpty()) {
            String html = renderLayout("Add Book",
                    """
                            <header class="topbar">
                              <div class="brand">
                                <a class="back" href="/">All Books</a>
                                <div>
                                  <div class="name">Add Book</div>
                                  <div class="tag">Fix the errors below</div>
                                </div>
                              </div>
                              <nav class="actions">
                                <div class="who">Signed in as <span class="mono">%s</span></div>
                                <a class="btn secondary" href="/">Admin Portal</a>
                                <a class="btn secondary" href="/reports">Reports</a>
                                <a class="btn secondary" href="/activity">Activity</a>
                                <a class="btn secondary" href="/customers">Customers</a>
                                <a class="btn secondary" href="/orders">Orders</a>
                                <a class="btn secondary" href="/settings">Settings</a>
                                <a class="btn ghost" href="/logout">Logout</a>
                                <a class="btn" href="/books/new">Add Book</a>
                              </nav>
                            </header>

                            <section class="panel">
                              %s
                              %s
                            </section>
                            """.formatted(escapeHtml(username), renderErrors(errors), renderBookForm(in.toBookPreview(), "/books/create", "Create", form.get("category"), form.get("category_custom"))));
            sendHtml(ex, 400, html);
            return;
        }

        long id = NEXT_ID.getAndIncrement();
        Book b = new Book(
                id,
                in.title,
                in.author,
                in.category,
                in.isbn,
                in.year,
                in.priceCents,
                in.quantity,
                Instant.now().toEpochMilli()
        );
        BOOKS.put(b.id, b);
        recordQtyPoint(b.id, b.quantity);
        recordTotalQtyPoint();
        saveBooksToDisk();
        logActivity(username, "BOOK_CREATE", "Created book #" + b.id + " (" + b.title + ")");

        redirect(ex, "/");
    }

    private static void handleUpdateBook(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> form = parseForm(ex);
        Long id = parseLongOrNull(form.get("id"));
        if (id == null) {
            sendStatus(ex, 400);
            return;
        }
        Book existing = BOOKS.get(id);
        if (existing == null) {
            sendStatus(ex, 404);
            return;
        }

        BookInput in = BookInput.from(form);
        List<String> errors = validate(in);
        if (!errors.isEmpty()) {
            Book preview = in.toBookPreview();
            preview.id = id;
            String html = renderLayout("Edit Book",
                    """
                            <header class="topbar">
                              <div class="brand">
                                <a class="back" href="/">All Books</a>
                                <div>
                                  <div class="name">Edit Book</div>
                                  <div class="tag">Fix the errors below</div>
                                </div>
                              </div>
                              <nav class="actions">
                                <div class="who">Signed in as <span class="mono">%s</span></div>
                                <a class="btn secondary" href="/">Admin Portal</a>
                                <a class="btn secondary" href="/reports">Reports</a>
                                <a class="btn secondary" href="/activity">Activity</a>
                                <a class="btn secondary" href="/customers">Customers</a>
                                <a class="btn secondary" href="/orders">Orders</a>
                                <a class="btn secondary" href="/settings">Settings</a>
                                <a class="btn ghost" href="/logout">Logout</a>
                                <a class="btn" href="/books/new">Add Book</a>
                              </nav>
                            </header>

                            <section class="panel">
                              %s
                              %s
                            </section>
                            """.formatted(escapeHtml(username), renderErrors(errors), renderBookForm(preview, "/books/update", "Save Changes", form.get("category"), form.get("category_custom"))));
            sendHtml(ex, 400, html);
            return;
        }

        Book updated = new Book(
                id,
                in.title,
                in.author,
                in.category,
                in.isbn,
                in.year,
                in.priceCents,
                in.quantity,
                existing.createdAtEpochMs
        );
        BOOKS.put(id, updated);
        recordQtyPoint(id, updated.quantity);
        recordTotalQtyPoint();
        saveBooksToDisk();
        logActivity(username, "BOOK_UPDATE", "Updated book #" + id + " (qty=" + updated.quantity + ")");
        redirect(ex, "/");
    }

    private static void handleDeleteBook(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if (!"POST".equalsIgnoreCase(method) && !"GET".equalsIgnoreCase(method)) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;
        Map<String, String> q = "POST".equalsIgnoreCase(method) ? parseForm(ex) : parseQuery(ex.getRequestURI());
        Long id = parseLongOrNull(q.get("id"));
        if (id == null) {
            sendStatus(ex, 400);
            return;
        }
        Book removed = BOOKS.remove(id);
        recordQtyPoint(id, 0);
        recordTotalQtyPoint();
        saveBooksToDisk();
        logActivity(username, "BOOK_DELETE", "Deleted book #" + id + (removed == null ? "" : (" (" + removed.title + ")")));
        redirect(ex, "/");
    }

    private static void handleLogin(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            // Already logged in? send to home.
            if (getUsernameFromRequest(ex).isPresent()) {
                redirect(ex, "/");
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI());
            String err = trimToNull(q.get("err"));
            sendHtml(ex, 200, renderLoginPage(err));
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            Map<String, String> form = parseForm(ex);
            String username = trimToNull(form.get("username"));
            String password = trimToNull(form.get("password"));
            if (adminUser.equals(username) && adminPass.equals(password)) {
                String sid = newSession(username);
                setSessionCookie(ex, sid);
                logActivity(username, "LOGIN_OK", "Login success");
                redirect(ex, "/");
                return;
            }
            logActivity(Optional.ofNullable(username).orElse("unknown"), "LOGIN_FAIL", "Login failed");
            redirect(ex, "/login?err=Invalid%20username%20or%20password");
            return;
        }

        sendStatus(ex, 405);
    }

    private static void handleWelcome(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        if (getUsernameFromRequest(ex).isPresent()) {
            redirect(ex, "/");
            return;
        }

        String html = renderLayout("Welcome",
                """
                        <header class="topbar">
                          <div class="brand">
                            <img class="logoMark" src="/static/logo.svg" alt="Franciscan Publications" />
                            <div>
                              <div class="name">Franciscan Publications</div>
                              <div class="tag">Inventory Management</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <a class="btn" href="/login">Sign In</a>
                          </nav>
                        </header>

                        <section class="panel">
                          <div class="welcomeCta">
                            <div class="welcomeText">
                              Manage your inventory, track stock trends, and export reports.
                            </div>
                            <a class="btn secondary" href="/login">Login to Dashboard</a>
                          </div>
                        </section>
                        """);
        sendHtml(ex, 200, html);
    }
    private static void handleSettings(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        if ("GET".equalsIgnoreCase(method)) {
            String username = requireAuthOrRedirect(ex);
            if (username == null) return;
            Map<String, String> q = parseQuery(ex.getRequestURI());
            String err = trimToNull(q.get("err"));
            String msg = trimToNull(q.get("msg"));

            String errHtml = err != null ? "<div class=\"errors\"><div class=\"errorsTitle\">" + escapeHtml(err) + "</div></div>" : "";
            String msgHtml = msg != null ? "<div class=\"notice\">" + escapeHtml(msg) + "</div>" : "";

            String html = renderLayout("Settings",
                    """
                            <header class="topbar">
                              <div class="brand">
                                <a class="back" href="/">Admin Portal</a>
                                <div>
                                  <div class="name">Settings</div>
                                  <div class="tag">System preferences</div>
                                </div>
                              </div>
                              <nav class="actions">
                                <div class="who">Signed in as <span class="mono">%s</span></div>
                                <a class="btn secondary" href="/">Admin Portal</a>
                                <a class="btn secondary" href="/reports">Reports</a>
                                <a class="btn secondary" href="/activity">Activity</a>
                                <a class="btn secondary" href="/customers">Customers</a>
                                <a class="btn secondary" href="/orders">Orders</a>
                                <a class="btn secondary" href="/settings">Settings</a>
                                <a class="btn ghost" href="/logout">Logout</a>
                                <a class="btn" href="#">Settings</a>
                              </nav>
                            </header>

                            <section class="panel">
                              %s
                              %s
                              <form class="form" method="post" action="/settings">
                                <div class="sectionTitle">Appearance</div>
                                <div class="grid">
                                  <label>
                                    <span>Theme</span>
                                    <select name="theme">
                                      <option value="dark" %s>Dark</option>
                                      <option value="light" %s>Light</option>
                                      <option value="cream" %s>Cream</option>
                                    </select>
                                  </label>
                                  <label>
                                    <span>Font Size</span>
                                    <select name="fontSize">
                                      <option value="12px" %s>Small (12px)</option>
                                      <option value="14px" %s>Default (14px)</option>
                                      <option value="16px" %s>Large (16px)</option>
                                    </select>
                                  </label>
                                </div>
                                <br/>
                                <div class="sectionTitle">Admin Credentials</div>
                                <div class="grid">
                                  <label>
                                    <span>Set New Username</span>
                                    <input name="adminUser" value="%s" required />
                                  </label>
                                  <label>
                                    <span>Set New Password</span>
                                    <input name="adminPass" value="%s" required type="password" />
                                  </label>
                                </div>
                                <div class="formActions">
                                  <button class="btn" type="submit">Save Settings</button>
                                </div>
                              </form>
                            </section>
                            """.formatted(
                            escapeHtml(username),
                            errHtml, msgHtml,
                            "dark".equals(currentTheme) ? "selected" : "",
                            "light".equals(currentTheme) ? "selected" : "",
                            "cream".equals(currentTheme) ? "selected" : "",
                            "12px".equals(currentFontSize) ? "selected" : "",
                            "14px".equals(currentFontSize) ? "selected" : "",
                            "16px".equals(currentFontSize) ? "selected" : "",
                            escapeHtml(adminUser),
                            escapeHtml(adminPass)
                    ));
            sendHtml(ex, 200, html);
            return;
        }

        if ("POST".equalsIgnoreCase(method)) {
            String username = requireAuthOrRedirect(ex);
            if (username == null) return;
            
            Map<String, String> form = parseForm(ex);
            String t = trimToNull(form.get("theme"));
            String f = trimToNull(form.get("fontSize"));
            String au = trimToNull(form.get("adminUser"));
            String ap = trimToNull(form.get("adminPass"));

            if (au == null || ap == null) {
                redirect(ex, "/settings?err=Username%20and%20Password%20are%20required");
                return;
            }
            
            currentTheme = t == null ? "dark" : t;
            currentFontSize = f == null ? "14px" : f;
            
            boolean userChanged = !au.equals(adminUser) || !ap.equals(adminPass);
            adminUser = au;
            adminPass = ap;

            saveSettingsToDisk();
            logActivity(username, "SETTINGS_UPDATE", "Settings updated");

            if (userChanged) {
                redirect(ex, "/logout");
                return;
            }

            redirect(ex, "/settings?msg=Settings%20saved%20successfully");
            return;
        }

        sendStatus(ex, 405);
    }


    private static void handleActivity(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        List<ActivityEvent> events = snapshotActivity();
        String html = renderLayout("Activity",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Activity</div>
                              <div class="tag">Audit trail of actions</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          %s
                        </section>
                        """.formatted(escapeHtml(username), renderActivityTable(events)));

        sendHtml(ex, 200, html);
    }

    private static void handleCustomers(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        String query = trimToNull(q.get("q"));

        List<Customer> customers = new ArrayList<>(CUSTOMERS.values());
        customers.sort(Comparator.comparingLong((Customer c) -> c.id).reversed());
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            customers.removeIf(c -> !(containsIgnoreCase(c.fullName, needle)
                    || containsIgnoreCase(Optional.ofNullable(c.email).orElse(""), needle)
                    || containsIgnoreCase(Optional.ofNullable(c.phone).orElse(""), needle)));
        }

        String html = renderLayout("Customers",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Customers</div>
                              <div class="tag">Customer directory</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          <form class="search" method="get" action="/customers">
                            <input name="q" placeholder="Search name, email, phone..." value="%s" />
                            <button class="btn secondary" type="submit">Search</button>
                            <a class="btn ghost" href="/customers">Clear</a>
                          </form>
                        </section>

                        <section class="panel">
                          %s
                        </section>
                        """.formatted(escapeHtml(username), escapeHtml(Optional.ofNullable(query).orElse("")), renderCustomersTable(customers)));
        sendHtml(ex, 200, html);
    }

    private static void handleCustomerView(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        Long id = parseLongOrNull(trimToNull(q.get("id")));
        if (id == null) {
            sendStatus(ex, 400);
            return;
        }
        Customer c = CUSTOMERS.get(id);
        if (c == null) {
            sendStatus(ex, 404);
            return;
        }
        List<Order> orders = new ArrayList<>(ORDERS.values());
        orders.removeIf(o -> o.customerId != id);
        orders.sort(Comparator.comparingLong((Order o) -> o.createdAtEpochMs).reversed());

        String html = renderLayout("Customer",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/customers">Customers</a>
                            <div>
                              <div class="name">%s</div>
                              <div class="tag">Customer #%s</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          %s
                        </section>

                        <section class="panel">
                          <div class="sectionTitle">Orders</div>
                          %s
                        </section>
                        """.formatted(
                        escapeHtml(c.fullName),
                        c.id,
                        escapeHtml(username),
                        renderCustomerCard(c),
                        renderOrdersTable(orders)
                ));
        sendHtml(ex, 200, html);
    }

    private static void handleOrders(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        String query = trimToNull(q.get("q"));

        List<Order> orders = new ArrayList<>(ORDERS.values());
        orders.sort(Comparator.comparingLong((Order o) -> o.createdAtEpochMs).reversed());
        if (query != null) {
            String needle = query.toLowerCase(Locale.ROOT);
            orders.removeIf(o -> !(containsIgnoreCase(Optional.ofNullable(o.bookTitle).orElse(""), needle)
                    || containsIgnoreCase(Optional.ofNullable(o.customerName).orElse(""), needle)
                    || containsIgnoreCase(Optional.ofNullable(o.reference).orElse(""), needle)));
        }

        String html = renderLayout("Orders",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Orders</div>
                              <div class="tag">Purchases and checkouts</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          <form class="search" method="get" action="/orders">
                            <input name="q" placeholder="Search book, customer, reference..." value="%s" />
                            <button class="btn secondary" type="submit">Search</button>
                            <a class="btn ghost" href="/orders">Clear</a>
                          </form>
                        </section>

                        <section class="panel">
                          %s
                        </section>
                        """.formatted(escapeHtml(username), escapeHtml(Optional.ofNullable(query).orElse("")), renderOrdersTable(orders)));
        sendHtml(ex, 200, html);
    }

    private static void handleOrderNew(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        Long bookId = parseLongOrNull(trimToNull(q.get("bookId")));
        if (bookId == null) {
            sendStatus(ex, 400);
            return;
        }
        Book b = BOOKS.get(bookId);
        if (b == null) {
            sendStatus(ex, 404);
            return;
        }

        String html = renderLayout("Checkout",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Checkout</div>
                              <div class="tag">Collect customer info and complete purchase</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          %s
                        </section>
                        """.formatted(escapeHtml(username), renderCheckoutForm(b, null, List.of())));

        sendHtml(ex, 200, html);
    }

    private static void handleOrderCreate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> form = parseForm(ex);
        Long bookId = parseLongOrNull(trimToNull(form.get("bookId")));
        Book b = bookId == null ? null : BOOKS.get(bookId);
        if (b == null) {
            sendStatus(ex, 400);
            return;
        }

        CheckoutInput in = CheckoutInput.from(form);
        List<String> errors = validateCheckout(in, b);
        if (!errors.isEmpty()) {
            String html = renderLayout("Checkout",
                    """
                            <header class="topbar">
                              <div class="brand">
                                <a class="back" href="/">All Books</a>
                                <div>
                                  <div class="name">Checkout</div>
                                  <div class="tag">Fix the errors below</div>
                                </div>
                              </div>
                              <nav class="actions">
                                <div class="who">Signed in as <span class="mono">%s</span></div>
                                <a class="btn secondary" href="/">Admin Portal</a>
                                <a class="btn secondary" href="/reports">Reports</a>
                                <a class="btn secondary" href="/activity">Activity</a>
                                <a class="btn secondary" href="/customers">Customers</a>
                                <a class="btn secondary" href="/orders">Orders</a>
                                <a class="btn secondary" href="/settings">Settings</a>
                                <a class="btn ghost" href="/logout">Logout</a>
                                <a class="btn" href="/books/new">Add Book</a>
                              </nav>
                            </header>

                            <section class="panel">
                              %s
                              %s
                            </section>
                            """.formatted(escapeHtml(username), renderErrors(errors), renderCheckoutForm(b, in, errors)));
            sendHtml(ex, 400, html);
            return;
        }

        Customer customer = upsertCustomer(in);
        saveCustomersToDisk();

        // Update stock
        int newQty = b.quantity - in.quantity;
        Book updated = new Book(
                b.id, b.title, b.author, b.category, b.isbn, b.year, b.priceCents, newQty, b.createdAtEpochMs
        );
        BOOKS.put(b.id, updated);
        recordQtyPoint(b.id, updated.quantity);
        recordTotalQtyPoint();
        saveBooksToDisk();

        long orderId = NEXT_ORDER_ID.getAndIncrement();
        long totalCents = safeMulCents(b.priceCents, in.quantity);
        Order order = new Order(
                orderId,
                b.id,
                b.title,
                b.priceCents,
                in.quantity,
                totalCents,
                customer.id,
                customer.fullName,
                in.paymentMethod,
                in.status,
                in.reference,
                Instant.now().toEpochMilli()
        );
        ORDERS.put(order.id, order);
        saveOrdersToDisk();

        logActivity(username, "ORDER_CREATE", "Order #" + order.id + " for book #" + b.id + " qty=" + in.quantity);
        if ("PAID".equalsIgnoreCase(order.status)) {
            redirect(ex, "/orders/receipt?id=" + order.id);
        } else {
            redirect(ex, "/orders");
        }
    }

    private static void handleLogout(HttpExchange ex) throws IOException {
        // Allow GET for convenience.
        Optional<String> u = getUsernameFromRequest(ex);
        Optional<String> sid = getCookie(ex, SESSION_COOKIE);
        sid.ifPresent(SESSIONS::remove);
        clearSessionCookie(ex);
        u.ifPresent(user -> logActivity(user, "LOGOUT", "Logged out"));
        redirect(ex, "/welcome");
    }

    private static void handleOrderReceipt(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        Long id = parseLongOrNull(trimToNull(q.get("id")));
        if (id == null) {
            sendStatus(ex, 400);
            return;
        }
        Order order = ORDERS.get(id);
        if (order == null) {
            sendStatus(ex, 404);
            return;
        }
        Customer customer = CUSTOMERS.get(order.customerId);

        String receiptNo = buildReceiptNo(order);
        String paidBadge = "PAID".equalsIgnoreCase(order.status)
                ? "<span class=\"pill ok\">PAID</span>"
                : "<span class=\"pill danger\">NOT PAID</span>";

        String html = renderLayout("Receipt " + receiptNo,
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/orders">Orders</a>
                            <div>
                              <div class="name">Receipt</div>
                              <div class="tag">Franciscan Publications</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <button class="btn secondary" type="button" onclick="window.print()">Print</button>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel receipt">
                          <div class="receiptTop">
                            <div>
                              <div class="receiptTitle">Franciscan Publications</div>
                              <div class="receiptSub">Official receipt</div>
                            </div>
                            <div class="receiptMeta">
                              <div class="kv"><div class="k">Receipt No.</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Order ID</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Issued</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Status</div><div class="v">%s</div></div>
                            </div>
                          </div>

                          <div class="receiptGrid">
                            <div class="card">
                              <div class="sectionTitle">Billed To</div>
                              <div class="kv"><div class="k">Name</div><div class="v">%s</div></div>
                              <div class="kv"><div class="k">Email</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Phone</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Address</div><div class="v">%s</div></div>
                            </div>

                            <div class="card">
                              <div class="sectionTitle">Payment</div>
                              <div class="kv"><div class="k">Method</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Reference</div><div class="v mono">%s</div></div>
                            </div>
                          </div>

                          <div class="tableWrap">
                            <table class="table receiptTable">
                              <thead>
                                <tr>
                                  <th>Item</th>
                                  <th class="num">Unit</th>
                                  <th class="num">Qty</th>
                                  <th class="num">Line Total</th>
                                </tr>
                              </thead>
                              <tbody>
                                <tr>
                                  <td>%s</td>
                                  <td class="num mono">%s</td>
                                  <td class="num mono">%s</td>
                                  <td class="num mono">%s</td>
                                </tr>
                              </tbody>
                            </table>
                          </div>

                          <div class="receiptTotals">
                            <div class="totals">
                              <div class="kv"><div class="k">Subtotal</div><div class="v mono">%s</div></div>
                              <div class="kv"><div class="k">Tax</div><div class="v mono">$0.00</div></div>
                              <div class="kv"><div class="k">Total</div><div class="v mono">%s</div></div>
                            </div>
                          </div>

                          <div class="receiptNote">
                            Thank you for your purchase.
                          </div>
                        </section>
                        """.formatted(
                        escapeHtml(username),
                        escapeHtml(receiptNo),
                        order.id,
                        escapeHtml(formatIso(order.createdAtEpochMs)),
                        paidBadge,
                        escapeHtml(customer == null ? Optional.ofNullable(order.customerName).orElse("") : customer.fullName),
                        escapeHtml(customer == null ? "" : Optional.ofNullable(customer.email).orElse("")),
                        escapeHtml(customer == null ? "" : Optional.ofNullable(customer.phone).orElse("")),
                        escapeHtml(customer == null ? "" : formatAddress(customer)),
                        escapeHtml(Optional.ofNullable(order.paymentMethod).orElse("")),
                        escapeHtml(Optional.ofNullable(order.reference).orElse("")),
                        escapeHtml(Optional.ofNullable(order.bookTitle).orElse("")),
                        escapeHtml(formatUsd(order.unitPriceCents)),
                        order.quantity,
                        escapeHtml(formatUsd(order.totalCents)),
                        escapeHtml(formatUsd(order.totalCents)),
                        escapeHtml(formatUsd(order.totalCents))
                ));

        sendHtml(ex, 200, html);
        logActivity(username, "RECEIPT_VIEW", "Viewed receipt for order #" + order.id);
    }

    private static void handleReports(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        int low = Optional.ofNullable(parseIntOrNull(trimToNull(q.get("low")))).orElse(2);
        if (low < 0) low = 0;
        if (low > 999) low = 999;

        List<Book> books = new ArrayList<>(BOOKS.values());
        books.sort(Comparator.comparingInt((Book b) -> b.quantity).thenComparingLong(b -> b.id));
        List<Book> booksByTitle = new ArrayList<>(books);
        booksByTitle.sort(Comparator.comparing(b -> b.title.toLowerCase(Locale.ROOT)));

        long titles = books.size();
        long totalQty = 0;
        long totalValueCents = 0;
        long lowCount = 0;
        for (Book b : books) {
            totalQty += Math.max(0, b.quantity);
            totalValueCents += safeMulCents(b.priceCents, b.quantity);
            if (b.quantity <= low) lowCount++;
        }

        String html = renderLayout("Reports",
                """
                        <header class="topbar">
                          <div class="brand">
                            <a class="back" href="/">All Books</a>
                            <div>
                              <div class="name">Reports</div>
                              <div class="tag">Inventory summary and exports</div>
                            </div>
                          </div>
                          <nav class="actions">
                            <div class="who">Signed in as <span class="mono">%s</span></div>
                            <a class="btn secondary" href="/">Admin Portal</a>
                            <a class="btn secondary" href="/reports">Reports</a>
                            <a class="btn secondary" href="/activity">Activity</a>
                            <a class="btn secondary" href="/customers">Customers</a>
                            <a class="btn secondary" href="/orders">Orders</a>
                            <a class="btn secondary" href="/settings">Settings</a>
                            <a class="btn ghost" href="/logout">Logout</a>
                            <a class="btn" href="/books/new">Add Book</a>
                          </nav>
                        </header>

                        <section class="panel">
                          <div class="stats">
                            <div class="stat">
                              <div class="k">Titles</div>
                              <div class="v mono">%s</div>
                            </div>
                            <div class="stat">
                              <div class="k">Total Qty</div>
                              <div class="v mono">%s</div>
                            </div>
                            <div class="stat">
                              <div class="k">Inventory Value</div>
                              <div class="v mono">%s</div>
                            </div>
                            <div class="stat">
                              <div class="k">Low Stock (<= %s)</div>
                              <div class="v mono">%s</div>
                            </div>
                          </div>
                        </section>

                        <section class="panel">
                          <div class="chartHeader">
                            <div>
                              <div class="chartTitle">Stock Trend</div>
                              <div class="chartSub">Rise and fall of stock since the server started.</div>
                            </div>
                            <label class="mini">
                              <span>Graph</span>
                              <select id="stockSelect">
                                <option value="">Total Inventory</option>
                                %s
                              </select>
                            </label>
                          </div>
                          <div class="chartWrap">
                            <canvas id="stockChart"></canvas>
                          </div>
                          <div class="chartMeta" id="chartMeta"></div>
                          <script src="/static/reports.js" defer></script>
                        </section>

                        <section class="panel">
                          <div class="reportActions">
                            <form class="inlineForm" method="get" action="/reports">
                              <label class="mini">
                                <span>Low stock threshold</span>
                                <input name="low" inputmode="numeric" value="%s" />
                              </label>
                              <button class="btn secondary" type="submit">Apply</button>
                            </form>
                            <a class="btn" href="/reports/inventory.csv?low=%s">Download CSV</a>
                          </div>
                          %s
                        </section>
                        """.formatted(
                        escapeHtml(username),
                        titles,
                        totalQty,
                        escapeHtml(formatUsd(totalValueCents)),
                        low,
                        lowCount,
                        renderBookSelectOptions(booksByTitle),
                        escapeHtml(String.valueOf(low)),
                        low,
                        renderInventoryReportTable(books, low)
                ));

        sendHtml(ex, 200, html);
    }

    private static void handleInventoryCsv(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        int low = Optional.ofNullable(parseIntOrNull(trimToNull(q.get("low")))).orElse(2);
        if (low < 0) low = 0;
        if (low > 999) low = 999;

        List<Book> books = new ArrayList<>(BOOKS.values());
        books.sort(Comparator.comparingInt((Book b) -> b.quantity).thenComparingLong(b -> b.id));

        StringBuilder csv = new StringBuilder();
        csv.append("id,title,author,category,isbn,year,price_usd,quantity,low_stock\n");
        for (Book b : books) {
            csv.append(b.id).append(',');
            csv.append(csvCell(b.title)).append(',');
            csv.append(csvCell(b.author)).append(',');
            csv.append(csvCell(Optional.ofNullable(b.category).orElse(""))).append(',');
            csv.append(csvCell(Optional.ofNullable(b.isbn).orElse(""))).append(',');
            csv.append(b.year == null ? "" : b.year).append(',');
            csv.append(csvCell(formatUsdRaw(b.priceCents))).append(',');
            csv.append(b.quantity).append(',');
            csv.append(b.quantity <= low ? "yes" : "no").append('\n');
        }

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/csv; charset=utf-8");
        h.set("Content-Disposition", "attachment; filename=\"inventory.csv\"");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        logActivity(username, "EXPORT_CSV", "Downloaded inventory.csv");
    }

    private static void handleStockJson(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendStatus(ex, 405);
            return;
        }
        String username = requireAuthOrRedirect(ex);
        if (username == null) return;

        Map<String, String> q = parseQuery(ex.getRequestURI());
        Long id = parseLongOrNull(trimToNull(q.get("id")));

        List<QtyPoint> points;
        String label;
        if (id == null) {
            points = snapshotPoints(TOTAL_QTY_HISTORY);
            label = "Total Inventory Qty";
        } else {
            points = snapshotPoints(QTY_HISTORY.getOrDefault(id, List.of()));
            Book b = BOOKS.get(id);
            label = b == null ? ("Book #" + id) : b.title;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"label\":").append(jsonString(label)).append(",\"points\":[");
        for (int i = 0; i < points.size(); i++) {
            QtyPoint p = points.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":").append(p.epochMs).append(",\"q\":").append(p.qty).append('}');
        }
        sb.append("]}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
        logActivity(username, "EXPORT_JSON", "Fetched stock.json" + (id == null ? "" : ("?id=" + id)));
    }

    // --------------------
    // Rendering
    // --------------------

    private static String renderLayout(String title, String bodyHtml) {
        return """
                <!doctype html>
                <html lang="en" class="theme-%s">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>%s</title>
                  <link rel="stylesheet" href="/static/styles.css" />
                  <style>
                    :root {
                       --root-font-size: %s;
                    }
                    body { font-size: var(--root-font-size); }
                  </style>
                </head>
                <body>
                  <div class="bg"></div>
                  <main class="shell">
                    %s
                    <footer class="footer">
                      <div>Running on Java HttpServer. Data is in-memory (resets when you restart).</div>
                    </footer>
                  </main>
                  <div id="toast" class="toast" aria-live="polite" aria-atomic="true"></div>
                  <script src="/static/app.js" defer></script>
                </body>
                </html>
                """.formatted(escapeHtml(currentTheme), escapeHtml(title), escapeHtml(currentFontSize), bodyHtml);
    }

    private static String renderLoginPage(String err) {
        String errHtml = (err == null)
                ? ""
                : """
                    <div class="errors">
                      <div class="errorsTitle">%s</div>
                      <ul class="errorsList">
                        <li>Try username <span class="mono">admin</span> and password <span class="mono">1234</span>.</li>
                      </ul>
                    </div>
                    """.formatted(escapeHtml(err));

        return renderLayout("Login",
                """
                        <header class="topbar">
                          <div class="brand">
                            <img class="logoMark" src="/static/logo.svg" alt="Franciscan Publications" />
                            <div>
                              <div class="name">Sign In</div>
                              <div class="tag">Franciscan Publications</div>
                            </div>
                          </div>
                        </header>

                        <section class="panel">
                          %s
                          <form class="form" method="post" action="/login" autocomplete="off">
                            <div class="grid">
                              <label>
                                <span>Username</span>
                                <input name="username" required maxlength="64" placeholder="admin" />
                              </label>
                              <label>
                                <span>Password</span>
                                <input name="password" type="password" required maxlength="64" placeholder="admin" />
                              </label>
                            </div>
                            <div class="formActions">
                              <button class="btn" type="submit">Login</button>
                              <a class="btn ghost" href="/welcome">Go Home</a>
                            </div>
                            <div class="hint">Demo login: <span class="mono">admin</span> / <span class="mono">1234</span></div>
                          </form>
                        </section>
                        """.formatted(errHtml));
    }

    private static String renderBooksTable(List<Book> books) {
        if (books.isEmpty()) {
            return """
                    <div class="empty">
                      <div class="emptyTitle">No books yet</div>
                      <div class="emptyBody">Add your first book to start managing your inventory.</div>
                      <a class="btn" href="/books/new">Add Book</a>
                    </div>
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div class="tableWrap">
                  <table class="table sortable">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Title</th>
                        <th>Author</th>
                        <th>Category</th>
                        <th>ISBN</th>
                        <th>Year</th>
                        <th class="num">Price</th>
                        <th class="num">Qty</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                """);

        for (Book b : books) {
            sb.append("<tr>");
            sb.append("<td class=\"mono\">").append(b.id).append("</td>");
            sb.append("<td>").append(escapeHtml(b.title)).append("</td>");
            sb.append("<td>").append(escapeHtml(b.author)).append("</td>");
            sb.append("<td>").append(escapeHtml(Optional.ofNullable(b.category).orElse(""))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(Optional.ofNullable(b.isbn).orElse(""))).append("</td>");
            sb.append("<td class=\"mono\">").append(b.year == null ? "" : b.year).append("</td>");
            sb.append("<td class=\"num mono\">").append(formatUsd(b.priceCents)).append("</td>");
            sb.append("<td class=\"num mono\">").append(b.quantity).append("</td>");
            sb.append("<td class=\"actionsCell\">");
            sb.append("<a class=\"link\" href=\"/orders/new?bookId=").append(b.id).append("\">Buy</a>");
            sb.append("<span class=\"sep\">|</span>");
            sb.append("<a class=\"link\" href=\"/books/edit?id=").append(b.id).append("\">Edit</a>");
            sb.append("<form class=\"inline\" method=\"post\" action=\"/books/delete\" onsubmit=\"return confirm('Delete this book?');\">");
            sb.append("<input type=\"hidden\" name=\"id\" value=\"").append(b.id).append("\"/>");
            sb.append("<button class=\"link danger\" type=\"submit\">Delete</button>");
            sb.append("</form>");
            sb.append("</td>");
            sb.append("</tr>");
        }

        sb.append("""
                    </tbody>
                  </table>
                </div>
                """);
        return sb.toString();
    }

    private static String renderPager(int total, int page, int size, String query) {
        if (total <= size) {
            return """
                    <div class="pager">
                      <div class="pagerMeta">%s book(s)</div>
                    </div>
                    """.formatted(total);
        }

        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        String prevHref = buildBooksHref(Math.max(1, page - 1), size, query);
        String nextHref = buildBooksHref(Math.min(totalPages, page + 1), size, query);

        String prevClass = page <= 1 ? "btn ghost disabled" : "btn ghost";
        String nextClass = page >= totalPages ? "btn ghost disabled" : "btn ghost";

        return """
                <div class="pager">
                  <div class="pagerMeta">Page <span class="mono">%s</span> of <span class="mono">%s</span> (Total <span class="mono">%s</span>)</div>
                  <div class="pagerBtns">
                    <a class="%s" href="%s" aria-disabled="%s">Back</a>
                    <a class="%s" href="%s" aria-disabled="%s">Next</a>
                  </div>
                </div>
                """.formatted(
                page, totalPages, total,
                prevClass, prevHref, page <= 1,
                nextClass, nextHref, page >= totalPages
        );
    }

    private static String buildBooksHref(int page, int size, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("/?page=").append(page).append("&size=").append(size);
        if (query != null) {
            sb.append("&q=").append(urlEncode(query));
        }
        return sb.toString();
    }

    private static String renderInventoryReportTable(List<Book> books, int low) {
        if (books.isEmpty()) {
            return """
                    <div class="empty">
                      <div class="emptyTitle">No data</div>
                      <div class="emptyBody">Add some books first, then come back to view reports.</div>
                      <a class="btn" href="/books/new">Add Book</a>
                    </div>
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div class="tableWrap">
                  <table class="table sortable">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Title</th>
                        <th>Author</th>
                        <th>Category</th>
                        <th class="num">Price</th>
                        <th class="num">Qty</th>
                        <th class="num">Line Value</th>
                        <th>Status</th>
                      </tr>
                    </thead>
                    <tbody>
                """);

        for (Book b : books) {
            boolean isLow = b.quantity <= low;
            long line = safeMulCents(b.priceCents, b.quantity);
            sb.append("<tr");
            if (isLow) sb.append(" class=\"rowLow\"");
            sb.append(">");
            sb.append("<td class=\"mono\">").append(b.id).append("</td>");
            sb.append("<td>").append(escapeHtml(b.title)).append("</td>");
            sb.append("<td>").append(escapeHtml(b.author)).append("</td>");
            sb.append("<td>").append(escapeHtml(Optional.ofNullable(b.category).orElse(""))).append("</td>");
            sb.append("<td class=\"num mono\">").append(escapeHtml(formatUsd(b.priceCents))).append("</td>");
            sb.append("<td class=\"num mono\">").append(b.quantity).append("</td>");
            sb.append("<td class=\"num mono\">").append(escapeHtml(formatUsd(line))).append("</td>");
            sb.append("<td>").append(isLow ? "<span class=\"pill danger\">Low</span>" : "<span class=\"pill ok\">OK</span>").append("</td>");
            sb.append("</tr>");
        }

        sb.append("""
                    </tbody>
                  </table>
                </div>
                """);
        return sb.toString();
    }

    private static String renderBookSelectOptions(List<Book> booksByTitle) {
        if (booksByTitle.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Book b : booksByTitle) {
            sb.append("<option value=\"").append(b.id).append("\">")
                    .append(escapeHtml(b.title))
                    .append(" (qty ")
                    .append(b.quantity)
                    .append(")</option>");
        }
        return sb.toString();
    }

    private static String renderActivityTable(List<ActivityEvent> events) {
        if (events.isEmpty()) {
            return """
                    <div class="empty">
                      <div class="emptyTitle">No activity yet</div>
                      <div class="emptyBody">Actions like login, create/update/delete, and exports will appear here.</div>
                    </div>
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div class="tableWrap">
                  <table class="table sortable">
                    <thead>
                      <tr>
                        <th>Time</th>
                        <th>User</th>
                        <th>Type</th>
                        <th>Message</th>
                      </tr>
                    </thead>
                    <tbody>
                """);
        for (ActivityEvent e : events) {
            sb.append("<tr>");
            sb.append("<td class=\"mono\">").append(escapeHtml(formatIso(e.epochMs))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(e.username)).append("</td>");
            sb.append("<td><span class=\"pill ok\">").append(escapeHtml(e.type)).append("</span></td>");
            sb.append("<td>").append(escapeHtml(e.message)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("""
                    </tbody>
                  </table>
                </div>
                """);
        return sb.toString();
    }

    private static String renderCustomersTable(List<Customer> customers) {
        if (customers.isEmpty()) {
            return """
                    <div class="empty">
                      <div class="emptyTitle">No customers yet</div>
                      <div class="emptyBody">Customers are created when you complete a checkout.</div>
                      <a class="btn" href="/">Go to Books</a>
                    </div>
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div class="tableWrap">
                  <table class="table sortable">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Name</th>
                        <th>Email</th>
                        <th>Phone</th>
                        <th>Created</th>
                        <th>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                """);
        for (Customer c : customers) {
            sb.append("<tr>");
            sb.append("<td class=\"mono\">").append(c.id).append("</td>");
            sb.append("<td>").append(escapeHtml(c.fullName)).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(Optional.ofNullable(c.email).orElse(""))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(Optional.ofNullable(c.phone).orElse(""))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(formatIso(c.createdAtEpochMs))).append("</td>");
            sb.append("<td class=\"actionsCell\">");
            sb.append("<a class=\"link\" href=\"/customers/view?id=").append(c.id).append("\">View</a>");
            sb.append("</td>");
            sb.append("</tr>");
        }
        sb.append("""
                    </tbody>
                  </table>
                </div>
                """);
        return sb.toString();
    }

    private static String renderCustomerCard(Customer c) {
        return """
                <div class="card">
                  <div class="kv">
                    <div class="k">Email</div>
                    <div class="v mono">%s</div>
                  </div>
                  <div class="kv">
                    <div class="k">Phone</div>
                    <div class="v mono">%s</div>
                  </div>
                  <div class="kv">
                    <div class="k">Organization</div>
                    <div class="v">%s</div>
                  </div>
                  <div class="kv">
                    <div class="k">Address</div>
                    <div class="v">%s</div>
                  </div>
                  <div class="kv">
                    <div class="k">Preferred Contact</div>
                    <div class="v">%s</div>
                  </div>
                  <div class="kv">
                    <div class="k">Marketing Opt-In</div>
                    <div class="v mono">%s</div>
                  </div>
                  <div class="kv">
                    <div class="k">Notes</div>
                    <div class="v">%s</div>
                  </div>
                </div>
                """.formatted(
                escapeHtml(Optional.ofNullable(c.email).orElse("")),
                escapeHtml(Optional.ofNullable(c.phone).orElse("")),
                escapeHtml(Optional.ofNullable(c.organization).orElse("")),
                escapeHtml(formatAddress(c)),
                escapeHtml(Optional.ofNullable(c.preferredContact).orElse("")),
                c.marketingOptIn,
                escapeHtml(Optional.ofNullable(c.notes).orElse(""))
        );
    }

    private static String renderOrdersTable(List<Order> orders) {
        if (orders.isEmpty()) {
            return """
                    <div class="empty">
                      <div class="emptyTitle">No orders yet</div>
                      <div class="emptyBody">Use the Buy button on a book to create an order.</div>
                      <a class="btn" href="/">Go to Books</a>
                    </div>
                    """;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div class="tableWrap">
                  <table class="table sortable">
                    <thead>
                      <tr>
                        <th>ID</th>
                        <th>Time</th>
                        <th>Book</th>
                        <th>Customer</th>
                        <th class="num">Qty</th>
                        <th class="num">Total</th>
                        <th>Payment</th>
                        <th>Status</th>
                        <th>Ref</th>
                        <th>Receipt</th>
                      </tr>
                    </thead>
                    <tbody>
                """);
        for (Order o : orders) {
            sb.append("<tr>");
            sb.append("<td class=\"mono\">").append(o.id).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(formatIso(o.createdAtEpochMs))).append("</td>");
            sb.append("<td>").append(escapeHtml(Optional.ofNullable(o.bookTitle).orElse(""))).append("</td>");
            sb.append("<td>");
            sb.append("<a class=\"link\" href=\"/customers/view?id=").append(o.customerId).append("\">")
                    .append(escapeHtml(Optional.ofNullable(o.customerName).orElse("Customer #" + o.customerId)))
                    .append("</a>");
            sb.append("</td>");
            sb.append("<td class=\"num mono\">").append(o.quantity).append("</td>");
            sb.append("<td class=\"num mono\">").append(escapeHtml(formatUsd(o.totalCents))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(Optional.ofNullable(o.paymentMethod).orElse(""))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(Optional.ofNullable(o.status).orElse(""))).append("</td>");
            sb.append("<td class=\"mono\">").append(escapeHtml(Optional.ofNullable(o.reference).orElse(""))).append("</td>");
            sb.append("<td class=\"actionsCell\">");
            if ("PAID".equalsIgnoreCase(o.status)) {
                sb.append("<a class=\"link\" href=\"/orders/receipt?id=").append(o.id).append("\">Receipt</a>");
            } else {
                sb.append("<span class=\"muted\">Receipt</span>");
            }
            sb.append("</td>");
            sb.append("</tr>");
        }
        sb.append("""
                    </tbody>
                  </table>
                </div>
                """);
        return sb.toString();
    }

    private static String renderCheckoutForm(Book b, CheckoutInput in, List<String> errors) {
        CheckoutInput v = in == null ? CheckoutInput.emptyForBook(b.id) : in;
        String notice = """
                <div class="notice">
                  <div><strong>Privacy note:</strong> This demo stores customer contact + address info for order fulfillment. Do not enter card numbers or passwords here.</div>
                </div>
                """;
        long totalCents = safeMulCents(b.priceCents, v.quantity);
        int remaining = Math.max(0, b.quantity - v.quantity);

        return """
                %s
                <div class="card">
                  <div class="sectionTitle">Book</div>
                  <div class="kv"><div class="k">Title</div><div class="v">%s</div></div>
                  <div class="kv"><div class="k">Author</div><div class="v">%s</div></div>
                  <div class="kv"><div class="k">Unit Price</div><div class="v mono">%s</div></div>
                  <div class="kv"><div class="k">Available Stock</div><div class="v mono">%s</div></div>
                </div>

                <form id="checkoutForm" class="form" method="post" action="/orders/create" autocomplete="off"
                      data-unit-cents="%s" data-available="%s">
                  <input type="hidden" name="bookId" value="%s" />

                  <div class="sectionTitle">Order</div>
                  <div class="grid">
                    <label>
                      <span>Quantity</span>
                      <input name="order_quantity" inputmode="numeric" value="%s" />
                    </label>
                    <label>
                      <span>Payment Method</span>
                      <select name="payment_method">
                        %s
                      </select>
                    </label>
                    <label>
                      <span>Status</span>
                      <select name="status">
                        %s
                      </select>
                    </label>
                    <label>
                      <span>Reference / Receipt No.</span>
                      <input name="reference" maxlength="64" value="%s" placeholder="optional" />
                    </label>
                  </div>

                  <div class="card">
                    <div class="sectionTitle">Order Summary</div>
                    <div class="kv">
                      <div class="k">Line Total</div>
                      <div class="v mono" id="checkoutTotal" data-cents="%s">%s</div>
                    </div>
                    <div class="kv">
                      <div class="k">Remaining Stock</div>
                      <div class="v mono" id="checkoutRemaining">%s</div>
                    </div>
                  </div>

                  <div class="sectionTitle">Customer</div>
                  <div class="grid">
                    <label>
                      <span>Full Name</span>
                      <input name="full_name" required maxlength="200" value="%s" />
                    </label>
                    <label>
                      <span>Organization</span>
                      <input name="organization" maxlength="200" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Email</span>
                      <input name="email" maxlength="200" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Phone</span>
                      <input name="phone" maxlength="64" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Street</span>
                      <input name="street" maxlength="200" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>City</span>
                      <input name="city" maxlength="120" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>State / Region</span>
                      <input name="state" maxlength="120" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Postal Code</span>
                      <input name="postal" maxlength="40" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Country</span>
                      <input name="country" maxlength="80" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Preferred Contact</span>
                      <select name="preferred_contact">
                        %s
                      </select>
                    </label>
                    <label class="wide">
                      <span>Notes</span>
                      <input name="notes" maxlength="500" value="%s" placeholder="optional" />
                    </label>
                  </div>

                  <div class="checkRow">
                    <label class="check">
                      <input type="checkbox" name="marketing_opt_in" %s />
                      <span>Marketing opt-in (optional)</span>
                    </label>
                  </div>
                  <div class="checkRow">
                    <label class="check">
                      <input type="checkbox" name="consent" %s />
                      <span>I consent to storing this information for order fulfillment.</span>
                    </label>
                  </div>

                  <div class="formActions">
                    <button class="btn" type="submit">Complete Purchase</button>
                    <a class="btn ghost" href="/">Cancel</a>
                  </div>
                </form>
                """.formatted(
                notice,
                escapeHtml(b.title),
                escapeHtml(b.author),
                escapeHtml(formatUsd(b.priceCents)),
                b.quantity,
                b.priceCents,
                b.quantity,
                b.id,
                escapeHtml(String.valueOf(v.quantity)),
                renderSelectOptions(List.of("cash", "card", "bank_transfer", "mobile_money"), v.paymentMethod),
                renderSelectOptions(List.of("PAID", "PENDING"), v.status),
                escapeHtml(Optional.ofNullable(v.reference).orElse("")),
                totalCents,
                escapeHtml(formatUsd(totalCents)),
                remaining,
                escapeHtml(Optional.ofNullable(v.fullName).orElse("")),
                escapeHtml(Optional.ofNullable(v.organization).orElse("")),
                escapeHtml(Optional.ofNullable(v.email).orElse("")),
                escapeHtml(Optional.ofNullable(v.phone).orElse("")),
                escapeHtml(Optional.ofNullable(v.street).orElse("")),
                escapeHtml(Optional.ofNullable(v.city).orElse("")),
                escapeHtml(Optional.ofNullable(v.state).orElse("")),
                escapeHtml(Optional.ofNullable(v.postal).orElse("")),
                escapeHtml(Optional.ofNullable(v.country).orElse("")),
                renderSelectOptions(List.of("email", "phone"), Optional.ofNullable(v.preferredContact).orElse("email")),
                escapeHtml(Optional.ofNullable(v.notes).orElse("")),
                v.marketingOptIn ? "checked" : "",
                v.consent ? "checked" : ""
        );
    }

    private static String renderSelectOptions(List<String> values, String selected) {
        String sel = Optional.ofNullable(selected).orElse("");
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            sb.append("<option value=\"").append(escapeHtml(v)).append("\"");
            if (v.equalsIgnoreCase(sel)) sb.append(" selected");
            sb.append(">").append(escapeHtml(v.replace('_', ' '))).append("</option>");
        }
        return sb.toString();
    }

    private static String renderErrors(List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"errors\"><div class=\"errorsTitle\">Please fix:</div><ul class=\"errorsList\">");
        for (String e : errors) {
            sb.append("<li>").append(escapeHtml(e)).append("</li>");
        }
        sb.append("</ul></div>");
        return sb.toString();
    }

    private static String renderBookForm(Book b, String action, String submitLabel) {
        return renderBookForm(b, action, submitLabel, null, null);
    }

    private static String renderBookForm(Book b, String action, String submitLabel, String rawCategorySelect, String rawCategoryCustom) {
        String idInput = (b != null && b.id != null)
                ? "<input type=\"hidden\" name=\"id\" value=\"" + b.id + "\"/>"
                : "";

        String title = b == null ? "" : Optional.ofNullable(b.title).orElse("");
        String author = b == null ? "" : Optional.ofNullable(b.author).orElse("");
        String category = b == null ? "" : Optional.ofNullable(b.category).orElse("");
        String isbn = b == null ? "" : Optional.ofNullable(b.isbn).orElse("");
        String year = b == null || b.year == null ? "" : String.valueOf(b.year);
        String price = b == null ? "" : formatUsdRaw(b.priceCents);
        String qty = b == null ? "0" : String.valueOf(b.quantity);
        boolean noneCategory = trimToNull(category) == null;

        List<String> categoryOptions = getBookCategoriesForSelect();
        String categorySelect = Optional.ofNullable(trimToNull(rawCategorySelect)).orElse(category);
        boolean customSelected = trimToNull(categorySelect) != null && categorySelect.equalsIgnoreCase(CATEGORY_CUSTOM_VALUE);
        boolean categoryInList = listContainsIgnoreCase(categoryOptions, category);
        if (!customSelected && !noneCategory && !categoryInList) {
            // Existing/preview category isn't one of the default/known categories: treat it as custom.
            customSelected = true;
        }
        String customValue = Optional.ofNullable(trimToNull(rawCategoryCustom)).orElse("");
        if (customSelected && trimToNull(customValue) == null && !noneCategory && !categoryInList) {
            customValue = category;
        }

        return """
                <form class="form" method="post" action="%s" autocomplete="off">
                  %s
                  <div class="grid">
                    <label>
                      <span>Title</span>
                      <input name="title" required maxlength="200" value="%s" placeholder="e.g. Clean Code" />
                    </label>
                    <label>
                      <span>Author</span>
                      <input name="author" required maxlength="200" value="%s" placeholder="e.g. Robert C. Martin" />
                    </label>
                    <label>
                      <span>Category</span>
                      <select name="category" id="categorySelect">
                        <option value=""%s>Select category (optional)</option>
                        %s
                        <option value="%s"%s>Add new category...</option>
                      </select>
                    </label>
                    <label id="categoryCustomWrap"%s>
                      <span>New Category</span>
                      <input name="category_custom" id="categoryCustom" maxlength="60" value="%s" placeholder="e.g. DEVOTIONAL" %s />
                    </label>
                    <label>
                      <span>ISBN</span>
                      <input name="isbn" maxlength="32" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Year</span>
                      <input name="year" inputmode="numeric" value="%s" placeholder="optional" />
                    </label>
                    <label>
                      <span>Price (USD)</span>
                      <input name="price" inputmode="decimal" value="%s" placeholder="e.g. 19.99" />
                    </label>
                    <label>
                      <span>Quantity</span>
                      <input name="quantity" inputmode="numeric" value="%s" />
                    </label>
                  </div>

                  <div class="formActions">
                    <button class="btn" type="submit">%s</button>
                    <a class="btn ghost" href="/">Cancel</a>
                  </div>
                </form>
                """.formatted(action, idInput,
                escapeHtml(title),
                escapeHtml(author),
                noneCategory && !customSelected ? " selected" : "",
                renderSelectOptions(categoryOptions, customSelected ? "" : category),
                escapeHtml(CATEGORY_CUSTOM_VALUE),
                customSelected ? " selected" : "",
                customSelected ? "" : " hidden",
                escapeHtml(customValue),
                customSelected ? "" : "disabled",
                escapeHtml(isbn),
                escapeHtml(year),
                escapeHtml(price),
                escapeHtml(qty),
                escapeHtml(submitLabel));
    }

    private static List<String> getBookCategoriesForSelect() {
        // Defaults + any categories already stored in books (including custom ones).
        // Deterministic order via case-insensitive sorting.
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(BOOK_CATEGORIES);
        for (Book b : BOOKS.values()) {
            String c = trimToNull(b.category);
            if (c != null) set.add(c);
        }
        return new ArrayList<>(set);
    }

    private static boolean listContainsIgnoreCase(List<String> values, String v) {
        String t = trimToNull(v);
        if (t == null) return false;
        for (String s : values) {
            if (s != null && s.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    // --------------------
    // HTTP helpers
    // --------------------

    private static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendStatus(HttpExchange ex, int status) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(303, -1);
        ex.close();
    }

    private static String requireAuthOrRedirect(HttpExchange ex) throws IOException {
        Optional<String> username = getUsernameFromRequest(ex);
        if (username.isPresent()) return username.get();
        redirect(ex, "/welcome");
        return null;
    }

    private static Optional<String> getUsernameFromRequest(HttpExchange ex) {
        Optional<String> sid = getCookie(ex, SESSION_COOKIE);
        if (sid.isEmpty()) return Optional.empty();
        Session s = SESSIONS.get(sid.get());
        if (s == null) return Optional.empty();
        return Optional.ofNullable(s.username);
    }

    private static Optional<String> getCookie(HttpExchange ex, String name) {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie == null || cookie.isBlank()) return Optional.empty();
        // Very small cookie parser: key=value; key2=value2
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int idx = p.indexOf('=');
            if (idx <= 0) continue;
            String k = p.substring(0, idx).trim();
            String v = p.substring(idx + 1).trim();
            if (name.equals(k)) return Optional.of(v);
        }
        return Optional.empty();
    }

    private static void setSessionCookie(HttpExchange ex, String sid) {
        // HttpOnly to avoid JS access. SameSite=Lax is a decent default for a demo app.
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + sid + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private static void clearSessionCookie(HttpExchange ex) {
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
    }

    private static String newSession(String username) {
        String sid = UUID.randomUUID().toString().replace("-", "");
        SESSIONS.put(sid, new Session(username, Instant.now().toEpochMilli()));
        return sid;
    }

    private static Map<String, String> parseQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return Map.of();
        return parseUrlEncoded(raw);
    }

    private static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String ct = Optional.ofNullable(ex.getRequestHeaders().getFirst("Content-Type")).orElse("");
        if (!ct.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            // Browsers submit this by default for <form method="post">.
            // If something else arrives, try anyway.
        }
        String body = readUtf8(ex.getRequestBody());
        if (body.isBlank()) return Map.of();
        return parseUrlEncoded(body);
    }

    private static Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> out = new HashMap<>();
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int idx = part.indexOf('=');
            String k = idx >= 0 ? part.substring(0, idx) : part;
            String v = idx >= 0 ? part.substring(idx + 1) : "";
            k = urlDecode(k);
            v = urlDecode(v);
            out.put(k, v);
        }
        return out;
    }

    private static String readUtf8(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String guessContentType(String filename) {
        String f = filename.toLowerCase(Locale.ROOT);
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html; charset=utf-8";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // --------------------
    // Domain + validation
    // --------------------

    private static final class Book {
        Long id;
        final String title;
        final String author;
        final String category;
        final String isbn;
        final Integer year;
        final long priceCents;
        final int quantity;
        final long createdAtEpochMs;

        private Book(Long id, String title, String author, String category, String isbn, Integer year, long priceCents, int quantity, long createdAtEpochMs) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.category = normalizeBookCategory(category);
            this.isbn = isbn;
            this.year = year;
            this.priceCents = priceCents;
            this.quantity = quantity;
            this.createdAtEpochMs = createdAtEpochMs;
        }
    }

    private static final class BookInput {
        final String title;
        final String author;
        final String category;
        final String isbn;
        final Integer year;
        final long priceCents;
        final int quantity;

        private BookInput(String title, String author, String category, String isbn, Integer year, long priceCents, int quantity) {
            this.title = title;
            this.author = author;
            this.category = normalizeBookCategory(category);
            this.isbn = isbn;
            this.year = year;
            this.priceCents = priceCents;
            this.quantity = quantity;
        }

        static BookInput from(Map<String, String> form) {
            String title = trimToNull(form.get("title"));
            String author = trimToNull(form.get("author"));
            String categorySelect = trimToNull(form.get("category"));
            String categoryCustom = trimToNull(form.get("category_custom"));
            String category = (categorySelect != null && categorySelect.equalsIgnoreCase(CATEGORY_CUSTOM_VALUE))
                    ? categoryCustom
                    : categorySelect;
            String isbn = trimToNull(form.get("isbn"));
            Integer year = parseIntOrNull(trimToNull(form.get("year")));
            long priceCents = parseMoneyToCents(trimToNull(form.get("price")));
            int qty = Optional.ofNullable(parseIntOrNull(trimToNull(form.get("quantity")))).orElse(0);
            return new BookInput(
                    Optional.ofNullable(title).orElse(""),
                    Optional.ofNullable(author).orElse(""),
                    category,
                    isbn,
                    year,
                    priceCents,
                    qty
            );
        }

        Book toBookPreview() {
            return new Book(null, title, author, category, isbn, year, priceCents, quantity, 0);
        }
    }

    private static String normalizeBookCategory(String raw) {
        String s = trimToNull(raw);
        if (s == null) return null;
        // Normalize: trim, collapse whitespace, uppercase for consistent storage.
        String collapsed = s.replaceAll("\\s+", " ").trim();
        return collapsed.toUpperCase(Locale.ROOT);
    }

    private static final class Session {
        final String username;
        final long createdAtEpochMs;

        private Session(String username, long createdAtEpochMs) {
            this.username = username;
            this.createdAtEpochMs = createdAtEpochMs;
        }
    }

    private static final class ActivityEvent {
        final long epochMs;
        final String username;
        final String type;
        final String message;

        private ActivityEvent(long epochMs, String username, String type, String message) {
            this.epochMs = epochMs;
            this.username = username;
            this.type = type;
            this.message = message;
        }
    }

    private static final class QtyPoint {
        final long epochMs;
        final int qty;

        private QtyPoint(long epochMs, int qty) {
            this.epochMs = epochMs;
            this.qty = qty;
        }
    }

    private static final class Customer {
        final long id;
        final String fullName;
        final String email;
        final String phone;
        final String organization;
        final String street;
        final String city;
        final String state;
        final String postal;
        final String country;
        final String preferredContact;
        final boolean marketingOptIn;
        final String notes;
        final long createdAtEpochMs;

        private Customer(long id, String fullName, String email, String phone, String organization,
                         String street, String city, String state, String postal, String country,
                         String preferredContact, boolean marketingOptIn, String notes, long createdAtEpochMs) {
            this.id = id;
            this.fullName = fullName;
            this.email = email;
            this.phone = phone;
            this.organization = organization;
            this.street = street;
            this.city = city;
            this.state = state;
            this.postal = postal;
            this.country = country;
            this.preferredContact = preferredContact;
            this.marketingOptIn = marketingOptIn;
            this.notes = notes;
            this.createdAtEpochMs = createdAtEpochMs;
        }
    }

    private static final class Order {
        final long id;
        final long bookId;
        final String bookTitle;
        final long unitPriceCents;
        final int quantity;
        final long totalCents;
        final long customerId;
        final String customerName;
        final String paymentMethod;
        final String status;
        final String reference;
        final long createdAtEpochMs;

        private Order(long id, long bookId, String bookTitle, long unitPriceCents, int quantity, long totalCents,
                      long customerId, String customerName, String paymentMethod, String status, String reference, long createdAtEpochMs) {
            this.id = id;
            this.bookId = bookId;
            this.bookTitle = bookTitle;
            this.unitPriceCents = unitPriceCents;
            this.quantity = quantity;
            this.totalCents = totalCents;
            this.customerId = customerId;
            this.customerName = customerName;
            this.paymentMethod = paymentMethod;
            this.status = status;
            this.reference = reference;
            this.createdAtEpochMs = createdAtEpochMs;
        }
    }

    private static final class CheckoutInput {
        final long bookId;
        final int quantity;
        final String paymentMethod;
        final String status;
        final String reference;
        final String fullName;
        final String organization;
        final String email;
        final String phone;
        final String street;
        final String city;
        final String state;
        final String postal;
        final String country;
        final String preferredContact;
        final boolean marketingOptIn;
        final boolean consent;
        final String notes;

        private CheckoutInput(long bookId, int quantity, String paymentMethod, String status, String reference,
                              String fullName, String organization, String email, String phone,
                              String street, String city, String state, String postal, String country,
                              String preferredContact, boolean marketingOptIn, boolean consent, String notes) {
            this.bookId = bookId;
            this.quantity = quantity;
            this.paymentMethod = paymentMethod;
            this.status = status;
            this.reference = reference;
            this.fullName = fullName;
            this.organization = organization;
            this.email = email;
            this.phone = phone;
            this.street = street;
            this.city = city;
            this.state = state;
            this.postal = postal;
            this.country = country;
            this.preferredContact = preferredContact;
            this.marketingOptIn = marketingOptIn;
            this.consent = consent;
            this.notes = notes;
        }

        static CheckoutInput emptyForBook(long bookId) {
            return new CheckoutInput(bookId, 1, "cash", "PAID", null,
                    null, null, null, null,
                    null, null, null, null, null,
                    "email", false, false, null);
        }

        static CheckoutInput from(Map<String, String> form) {
            Long bookId = parseLongOrNull(trimToNull(form.get("bookId")));
            int qty = Optional.ofNullable(parseIntOrNull(trimToNull(form.get("order_quantity")))).orElse(1);
            String payment = Optional.ofNullable(trimToNull(form.get("payment_method"))).orElse("cash");
            String status = Optional.ofNullable(trimToNull(form.get("status"))).orElse("PAID");
            String ref = trimToNull(form.get("reference"));

            String fullName = trimToNull(form.get("full_name"));
            String org = trimToNull(form.get("organization"));
            String email = trimToNull(form.get("email"));
            String phone = trimToNull(form.get("phone"));
            String street = trimToNull(form.get("street"));
            String city = trimToNull(form.get("city"));
            String state = trimToNull(form.get("state"));
            String postal = trimToNull(form.get("postal"));
            String country = trimToNull(form.get("country"));
            String pref = Optional.ofNullable(trimToNull(form.get("preferred_contact"))).orElse("email");
            boolean marketing = form.containsKey("marketing_opt_in");
            boolean consent = form.containsKey("consent");
            String notes = trimToNull(form.get("notes"));

            return new CheckoutInput(
                    Optional.ofNullable(bookId).orElse(0L),
                    qty,
                    payment,
                    status,
                    ref,
                    Optional.ofNullable(fullName).orElse(""),
                    org,
                    email,
                    phone,
                    street,
                    city,
                    state,
                    postal,
                    country,
                    pref,
                    marketing,
                    consent,
                    notes
            );
        }
    }

    private static List<String> validate(BookInput in) {
        List<String> errors = new ArrayList<>();
        if (in.title.isBlank()) errors.add("Title is required.");
        if (in.author.isBlank()) errors.add("Author is required.");
        if (in.category != null && in.category.equalsIgnoreCase(CATEGORY_CUSTOM_VALUE)) {
            errors.add("Category name is reserved. Choose a different category.");
        }
        if (in.category != null && in.category.length() > 60) errors.add("Category must be 60 characters or less.");
        if (in.title.length() > 200) errors.add("Title must be 200 characters or less.");
        if (in.author.length() > 200) errors.add("Author must be 200 characters or less.");
        if (in.isbn != null && in.isbn.length() > 32) errors.add("ISBN must be 32 characters or less.");
        if (in.year != null && (in.year < 0 || in.year > 3000)) errors.add("Year must be between 0 and 3000.");
        if (in.priceCents < 0) errors.add("Price must be a positive number.");
        if (in.quantity < 0) errors.add("Quantity cannot be negative.");
        return errors;
    }

    private static List<String> validateCheckout(CheckoutInput in, Book book) {
        List<String> errors = new ArrayList<>();
        if (in.bookId != book.id) errors.add("Invalid book.");
        if (in.quantity <= 0) errors.add("Quantity must be at least 1.");
        if (in.quantity > book.quantity) errors.add("Quantity cannot exceed available stock (" + book.quantity + ").");
        if (trimToNull(in.fullName) == null) errors.add("Customer full name is required.");
        if (trimToNull(in.email) == null && trimToNull(in.phone) == null) errors.add("Provide at least an email or a phone number.");
        if (in.email != null && !isLikelyEmail(in.email)) errors.add("Email does not look valid.");
        if (!in.consent) errors.add("Consent is required to store customer information.");
        if (in.reference != null && in.reference.length() > 64) errors.add("Reference must be 64 characters or less.");
        if (in.notes != null && in.notes.length() > 500) errors.add("Notes must be 500 characters or less.");
        return errors;
    }

    private static Customer upsertCustomer(CheckoutInput in) {
        String emailNorm = normalizeEmail(in.email);
        String phoneNorm = normalizePhone(in.phone);

        Customer existing = findCustomerByEmailOrPhone(emailNorm, phoneNorm);
        if (existing != null) {
            // Light "merge": keep existing, but fill in blanks.
            Customer merged = new Customer(
                    existing.id,
                    firstNonBlank(in.fullName, existing.fullName),
                    firstNonBlank(emailNorm, existing.email),
                    firstNonBlank(phoneNorm, existing.phone),
                    firstNonBlank(in.organization, existing.organization),
                    firstNonBlank(in.street, existing.street),
                    firstNonBlank(in.city, existing.city),
                    firstNonBlank(in.state, existing.state),
                    firstNonBlank(in.postal, existing.postal),
                    firstNonBlank(in.country, existing.country),
                    firstNonBlank(in.preferredContact, existing.preferredContact),
                    in.marketingOptIn || existing.marketingOptIn,
                    firstNonBlank(in.notes, existing.notes),
                    existing.createdAtEpochMs
            );
            CUSTOMERS.put(existing.id, merged);
            return merged;
        }

        long id = NEXT_CUSTOMER_ID.getAndIncrement();
        Customer c = new Customer(
                id,
                in.fullName,
                emailNorm,
                phoneNorm,
                in.organization,
                in.street,
                in.city,
                in.state,
                in.postal,
                in.country,
                in.preferredContact,
                in.marketingOptIn,
                in.notes,
                Instant.now().toEpochMilli()
        );
        CUSTOMERS.put(id, c);
        return c;
    }

    private static Customer findCustomerByEmailOrPhone(String emailNorm, String phoneNorm) {
        if (emailNorm != null) {
            for (Customer c : CUSTOMERS.values()) {
                if (emailNorm.equals(normalizeEmail(c.email))) return c;
            }
        }
        if (phoneNorm != null) {
            for (Customer c : CUSTOMERS.values()) {
                if (phoneNorm.equals(normalizePhone(c.phone))) return c;
            }
        }
        return null;
    }

    private static void seedIfEmpty() {
        if (!BOOKS.isEmpty()) return;
        putSeed("Clean Code", "Robert C. Martin", "9780132350884", 2008, 3799, 4);
        putSeed("Effective Java", "Joshua Bloch", "9780134685991", 2018, 4599, 2);
        putSeed("Design Patterns", "Erich Gamma et al.", "9780201633610", 1994, 5499, 1);
        recordTotalQtyPoint();
    }

    private static void putSeed(String title, String author, String isbn, Integer year, long priceCents, int qty) {
        long id = NEXT_ID.getAndIncrement();
        BOOKS.put(id, new Book(id, title, author, null, isbn, year, priceCents, qty, Instant.now().toEpochMilli()));
        recordQtyPoint(id, qty);
    }

    // --------------------
    // Utils
    // --------------------

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        if (haystack == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String firstNonBlank(String a, String b) {
        String ta = trimToNull(a);
        return ta != null ? ta : trimToNull(b);
    }

    private static boolean isLikelyEmail(String email) {
        String e = trimToNull(email);
        if (e == null) return false;
        int at = e.indexOf('@');
        int dot = e.lastIndexOf('.');
        return at > 0 && dot > at + 1 && dot < e.length() - 1;
    }

    private static String normalizeEmail(String email) {
        String e = trimToNull(email);
        return e == null ? null : e.toLowerCase(Locale.ROOT);
    }

    private static String normalizePhone(String phone) {
        String p = trimToNull(phone);
        if (p == null) return null;
        // Keep digits and leading '+'; good enough for a demo.
        StringBuilder sb = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isDigit(c)) sb.append(c);
            else if (c == '+' && sb.isEmpty()) sb.append(c);
        }
        String out = sb.toString();
        return out.isEmpty() ? null : out;
    }

    private static String formatAddress(Customer c) {
        List<String> parts = new ArrayList<>();
        if (trimToNull(c.street) != null) parts.add(c.street.trim());
        if (trimToNull(c.city) != null) parts.add(c.city.trim());
        if (trimToNull(c.state) != null) parts.add(c.state.trim());
        if (trimToNull(c.postal) != null) parts.add(c.postal.trim());
        if (trimToNull(c.country) != null) parts.add(c.country.trim());
        return String.join(", ", parts);
    }

    private static void recordQtyPoint(long bookId, int qty) {
        long now = Instant.now().toEpochMilli();
        List<QtyPoint> list = QTY_HISTORY.computeIfAbsent(bookId, k -> Collections.synchronizedList(new ArrayList<>()));
        list.add(new QtyPoint(now, qty));
        // Keep memory bounded for a demo app.
        if (list.size() > 1000) list.remove(0);
        appendQtyHistoryPoint(bookId, now, qty);
    }

    private static void recordTotalQtyPoint() {
        long now = Instant.now().toEpochMilli();
        int total = 0;
        for (Book b : BOOKS.values()) {
            if (b.quantity > 0) total += b.quantity;
        }
        TOTAL_QTY_HISTORY.add(new QtyPoint(now, total));
        if (TOTAL_QTY_HISTORY.size() > 2000) TOTAL_QTY_HISTORY.remove(0);
        appendTotalQtyHistoryPoint(now, total);
    }

    private static List<QtyPoint> snapshotPoints(List<QtyPoint> points) {
        if (points.isEmpty()) return List.of();
        synchronized (points) {
            return new ArrayList<>(points);
        }
    }

    private static void logActivity(String username, String type, String message) {
        long now = Instant.now().toEpochMilli();
        ACTIVITY.add(new ActivityEvent(
                now,
                Optional.ofNullable(username).orElse("unknown"),
                Optional.ofNullable(type).orElse("EVENT"),
                Optional.ofNullable(message).orElse("")
        ));
        if (ACTIVITY.size() > 400) ACTIVITY.remove(0);
    }

    private static List<ActivityEvent> snapshotActivity() {
        synchronized (ACTIVITY) {
            List<ActivityEvent> out = new ArrayList<>(ACTIVITY);
            out.sort((a, b) -> Long.compare(b.epochMs, a.epochMs));
            if (out.size() > 200) return out.subList(0, 200);
            return out;
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBooleanOrNull(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        if (t.equals("true") || t.equals("1") || t.equals("yes") || t.equals("y") || t.equals("on")) return true;
        if (t.equals("false") || t.equals("0") || t.equals("no") || t.equals("n") || t.equals("off")) return false;
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static long parseMoneyToCents(String s) {
        if (s == null || s.isBlank()) return 0;
        String t = s.trim().replace("$", "");
        // Accept "12", "12.3", "12.34"
        int dot = t.indexOf('.');
        if (dot < 0) {
            Integer dollars = parseIntOrNull(t);
            return dollars == null ? -1 : (long) dollars * 100L;
        }
        String dollarsPart = t.substring(0, dot);
        String centsPart = t.substring(dot + 1);
        Integer dollars = dollarsPart.isBlank() ? 0 : parseIntOrNull(dollarsPart);
        if (dollars == null) return -1;
        if (centsPart.length() > 2) return -1;
        while (centsPart.length() < 2) centsPart = centsPart + "0";
        Integer cents = centsPart.isBlank() ? 0 : parseIntOrNull(centsPart);
        if (cents == null) return -1;
        return dollars * 100L + cents;
    }

    private static String formatUsd(long cents) {
        if (cents <= 0) return "$0.00";
        long abs = Math.abs(cents);
        long dollars = abs / 100L;
        long rem = abs % 100L;
        String s = "$" + dollars + "." + (rem < 10 ? "0" + rem : rem);
        return cents < 0 ? "-" + s : s;
    }

    private static String formatUsdRaw(long cents) {
        if (cents <= 0) return "";
        long abs = Math.abs(cents);
        long dollars = abs / 100L;
        long rem = abs % 100L;
        String s = dollars + "." + (rem < 10 ? "0" + rem : rem);
        return cents < 0 ? "-" + s : s;
    }

    private static String csvCell(String s) {
        if (s == null) return "";
        boolean needsQuotes = s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuotes) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String formatIso(long epochMs) {
        // Good enough for audit logs.
        return Instant.ofEpochMilli(epochMs).toString();
    }

    private static String buildReceiptNo(Order order) {
        if (order.reference != null && !order.reference.isBlank()) return order.reference.trim();
        return "FP-" + order.id;
    }

    private static long safeMulCents(long priceCents, int qty) {
        // Clamp to avoid overflow in a demo app.
        long q = qty;
        if (q <= 0 || priceCents <= 0) return 0;
        if (priceCents > Long.MAX_VALUE / q) return Long.MAX_VALUE;
        return priceCents * q;
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void ensureDataDir() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException ignored) {
        }
    }

    private static void loadSettingsFromDisk() {
        if (!Files.exists(SETTINGS_TXT)) return;
        try {
            List<String> lines = Files.readAllLines(SETTINGS_TXT, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("=", 2);
                if (parts.length != 2) continue;
                String key = parts[0].trim();
                String val = parts[1].trim();
                if ("adminUser".equals(key)) adminUser = val;
                if ("adminPass".equals(key)) adminPass = val;
                if ("currentTheme".equals(key)) currentTheme = val;
                if ("currentFontSize".equals(key)) currentFontSize = val;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void saveSettingsToDisk() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("adminUser=").append(adminUser).append("\n");
            sb.append("adminPass=").append(adminPass).append("\n");
            sb.append("currentTheme=").append(currentTheme).append("\n");
            sb.append("currentFontSize=").append(currentFontSize).append("\n");
            Files.writeString(SETTINGS_TXT, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadFromDisk() {
        loadSettingsFromDisk();
        loadBooksFromDisk();
        loadCustomersFromDisk();
        loadOrdersFromDisk();
        loadHistoryFromDisk();
    }

    private static void loadBooksFromDisk() {
        if (!Files.exists(BOOKS_CSV)) return;
        try {
            List<String> lines = Files.readAllLines(BOOKS_CSV, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && line.toLowerCase(Locale.ROOT).contains("id,")) continue; // header
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 8) continue;
                Long id = parseLongOrNull(trimToNull(cols.get(0)));
                if (id == null) continue;
                String title = Optional.ofNullable(trimToNull(cols.get(1))).orElse("");
                String author = Optional.ofNullable(trimToNull(cols.get(2))).orElse("");
                String isbn = trimToNull(cols.get(3));
                Integer year = parseIntOrNull(trimToNull(cols.get(4)));
                Long priceCents = parseLongOrNull(trimToNull(cols.get(5)));
                Integer qty = parseIntOrNull(trimToNull(cols.get(6)));
                Long createdAt = parseLongOrNull(trimToNull(cols.get(7)));
                String category = cols.size() >= 9 ? trimToNull(cols.get(8)) : null;
                if (priceCents == null || qty == null || createdAt == null) continue;
                BOOKS.put(id, new Book(id, title, author, category, isbn, year, priceCents, qty, createdAt));
            }
            long maxId = BOOKS.keySet().stream().mapToLong(x -> x).max().orElse(0);
            NEXT_ID.set(Math.max(1, maxId + 1));
        } catch (IOException ignored) {
        }
    }

    private static void saveBooksToDisk() {
        ensureDataDir();
        try {
            List<Book> books = new ArrayList<>(BOOKS.values());
            books.sort(Comparator.comparingLong(b -> b.id));
            StringBuilder sb = new StringBuilder();
            sb.append("id,title,author,isbn,year,priceCents,quantity,createdAtEpochMs,category\n");
            for (Book b : books) {
                sb.append(b.id).append(',');
                sb.append(csvCell(b.title)).append(',');
                sb.append(csvCell(b.author)).append(',');
                sb.append(csvCell(Optional.ofNullable(b.isbn).orElse(""))).append(',');
                sb.append(b.year == null ? "" : b.year).append(',');
                sb.append(b.priceCents).append(',');
                sb.append(b.quantity).append(',');
                sb.append(b.createdAtEpochMs).append(',');
                sb.append(csvCell(Optional.ofNullable(b.category).orElse(""))).append('\n');
            }
            Path tmp = DATA_DIR.resolve("books.csv.tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, BOOKS_CSV, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveEx) {
                // ATOMIC_MOVE not always supported on Windows setups; fall back.
                Files.move(tmp, BOOKS_CSV, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static void loadCustomersFromDisk() {
        if (!Files.exists(CUSTOMERS_CSV)) return;
        try {
            List<String> lines = Files.readAllLines(CUSTOMERS_CSV, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && line.toLowerCase(Locale.ROOT).contains("id,")) continue; // header
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 14) continue;
                Long id = parseLongOrNull(trimToNull(cols.get(0)));
                if (id == null) continue;
                String fullName = Optional.ofNullable(trimToNull(cols.get(1))).orElse("");
                String email = trimToNull(cols.get(2));
                String phone = trimToNull(cols.get(3));
                String organization = trimToNull(cols.get(4));
                String street = trimToNull(cols.get(5));
                String city = trimToNull(cols.get(6));
                String state = trimToNull(cols.get(7));
                String postal = trimToNull(cols.get(8));
                String country = trimToNull(cols.get(9));
                String preferredContact = trimToNull(cols.get(10));
                Boolean marketingOptIn = parseBooleanOrNull(trimToNull(cols.get(11)));
                String notes = trimToNull(cols.get(12));
                Long createdAt = parseLongOrNull(trimToNull(cols.get(13)));
                if (createdAt == null) continue;
                CUSTOMERS.put(id, new Customer(
                        id, fullName, email, phone, organization,
                        street, city, state, postal, country,
                        preferredContact,
                        marketingOptIn != null && marketingOptIn,
                        notes,
                        createdAt
                ));
            }
            long maxId = CUSTOMERS.keySet().stream().mapToLong(x -> x).max().orElse(0);
            NEXT_CUSTOMER_ID.set(Math.max(1, maxId + 1));
        } catch (IOException ignored) {
        }
    }

    private static void saveCustomersToDisk() {
        ensureDataDir();
        try {
            List<Customer> customers = new ArrayList<>(CUSTOMERS.values());
            customers.sort(Comparator.comparingLong(c -> c.id));
            StringBuilder sb = new StringBuilder();
            sb.append("id,fullName,email,phone,organization,street,city,state,postal,country,preferredContact,marketingOptIn,notes,createdAtEpochMs\n");
            for (Customer c : customers) {
                sb.append(c.id).append(',');
                sb.append(csvCell(c.fullName)).append(',');
                sb.append(csvCell(Optional.ofNullable(c.email).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.phone).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.organization).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.street).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.city).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.state).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.postal).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.country).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(c.preferredContact).orElse(""))).append(',');
                sb.append(c.marketingOptIn).append(',');
                sb.append(csvCell(Optional.ofNullable(c.notes).orElse(""))).append(',');
                sb.append(c.createdAtEpochMs).append('\n');
            }
            Path tmp = DATA_DIR.resolve("customers.csv.tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, CUSTOMERS_CSV, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveEx) {
                Files.move(tmp, CUSTOMERS_CSV, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static void loadOrdersFromDisk() {
        if (!Files.exists(ORDERS_CSV)) return;
        try {
            List<String> lines = Files.readAllLines(ORDERS_CSV, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (i == 0 && line.toLowerCase(Locale.ROOT).contains("id,")) continue; // header
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 12) continue;
                Long id = parseLongOrNull(trimToNull(cols.get(0)));
                Long bookId = parseLongOrNull(trimToNull(cols.get(1)));
                String bookTitle = trimToNull(cols.get(2));
                Long unitPrice = parseLongOrNull(trimToNull(cols.get(3)));
                Integer qty = parseIntOrNull(trimToNull(cols.get(4)));
                Long total = parseLongOrNull(trimToNull(cols.get(5)));
                Long customerId = parseLongOrNull(trimToNull(cols.get(6)));
                String customerName = trimToNull(cols.get(7));
                String paymentMethod = trimToNull(cols.get(8));
                String status = trimToNull(cols.get(9));
                String reference = trimToNull(cols.get(10));
                Long createdAt = parseLongOrNull(trimToNull(cols.get(11)));
                if (id == null || bookId == null || unitPrice == null || qty == null || total == null || customerId == null || createdAt == null) continue;
                ORDERS.put(id, new Order(id, bookId, bookTitle, unitPrice, qty, total, customerId, customerName, paymentMethod, status, reference, createdAt));
            }
            long maxId = ORDERS.keySet().stream().mapToLong(x -> x).max().orElse(0);
            NEXT_ORDER_ID.set(Math.max(1, maxId + 1));
        } catch (IOException ignored) {
        }
    }

    private static void saveOrdersToDisk() {
        ensureDataDir();
        try {
            List<Order> orders = new ArrayList<>(ORDERS.values());
            orders.sort(Comparator.comparingLong(o -> o.id));
            StringBuilder sb = new StringBuilder();
            sb.append("id,bookId,bookTitle,unitPriceCents,quantity,totalCents,customerId,customerName,paymentMethod,status,reference,createdAtEpochMs\n");
            for (Order o : orders) {
                sb.append(o.id).append(',');
                sb.append(o.bookId).append(',');
                sb.append(csvCell(Optional.ofNullable(o.bookTitle).orElse(""))).append(',');
                sb.append(o.unitPriceCents).append(',');
                sb.append(o.quantity).append(',');
                sb.append(o.totalCents).append(',');
                sb.append(o.customerId).append(',');
                sb.append(csvCell(Optional.ofNullable(o.customerName).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(o.paymentMethod).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(o.status).orElse(""))).append(',');
                sb.append(csvCell(Optional.ofNullable(o.reference).orElse(""))).append(',');
                sb.append(o.createdAtEpochMs).append('\n');
            }
            Path tmp = DATA_DIR.resolve("orders.csv.tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, ORDERS_CSV, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException moveEx) {
                Files.move(tmp, ORDERS_CSV, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
        }
    }

    private static void loadHistoryFromDisk() {
        if (Files.exists(QTY_HISTORY_CSV)) {
            try {
                List<String> lines = Files.readAllLines(QTY_HISTORY_CSV, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (i == 0 && line.toLowerCase(Locale.ROOT).contains("id,")) continue;
                    if (line.isBlank()) continue;
                    List<String> cols = parseCsvLine(line);
                    if (cols.size() < 3) continue;
                    Long id = parseLongOrNull(trimToNull(cols.get(0)));
                    Long t = parseLongOrNull(trimToNull(cols.get(1)));
                    Integer q = parseIntOrNull(trimToNull(cols.get(2)));
                    if (id == null || t == null || q == null) continue;
                    List<QtyPoint> list = QTY_HISTORY.computeIfAbsent(id, k -> Collections.synchronizedList(new ArrayList<>()));
                    list.add(new QtyPoint(t, q));
                }
            } catch (IOException ignored) {
            }
        }

        if (Files.exists(TOTAL_QTY_HISTORY_CSV)) {
            try {
                List<String> lines = Files.readAllLines(TOTAL_QTY_HISTORY_CSV, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (i == 0 && line.toLowerCase(Locale.ROOT).contains("t,")) continue;
                    if (line.isBlank()) continue;
                    List<String> cols = parseCsvLine(line);
                    if (cols.size() < 2) continue;
                    Long t = parseLongOrNull(trimToNull(cols.get(0)));
                    Integer q = parseIntOrNull(trimToNull(cols.get(1)));
                    if (t == null || q == null) continue;
                    TOTAL_QTY_HISTORY.add(new QtyPoint(t, q));
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static void appendQtyHistoryPoint(long id, long t, int q) {
        ensureDataDir();
        try {
            boolean exists = Files.exists(QTY_HISTORY_CSV);
            StringBuilder sb = new StringBuilder();
            if (!exists) sb.append("id,t,q\n");
            sb.append(id).append(',').append(t).append(',').append(q).append('\n');
            Files.writeString(QTY_HISTORY_CSV, sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static void appendTotalQtyHistoryPoint(long t, int q) {
        ensureDataDir();
        try {
            boolean exists = Files.exists(TOTAL_QTY_HISTORY_CSV);
            StringBuilder sb = new StringBuilder();
            if (!exists) sb.append("t,q\n");
            sb.append(t).append(',').append(q).append('\n');
            Files.writeString(TOTAL_QTY_HISTORY_CSV, sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"') {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String escapeHtml(String s) {
        Objects.requireNonNull(s);
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}

