package org.example.ff;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class FileFinderFX extends Application {

    /* ------------------------ modelo + controles ----------------------- */
    private final ObservableList<Result> data = FXCollections.observableArrayList();
    private final TableView<Result> table = new TableView<>(data);
    private final TextField txtQuery = new TextField();
    private final TextField txtRoot = new TextField();
    private final ProgressBar progress = new ProgressBar();
    private final Label lblStatus = new Label(" Listo ");
    private final Button btnSearch = new Button("Buscar");

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {

        /* top bar */
        txtQuery.setPromptText("Término o regex…");
        txtRoot.setPromptText("Directorio raíz…");
        txtRoot.setEditable(false);

        Button btnBrowse = new Button("…");
        btnBrowse.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Elige directorio raíz");
            Path initial = txtRoot.getText().isBlank() ? systemRoot() : Paths.get(txtRoot.getText());
            dc.setInitialDirectory(initial.toFile());
            var dir = dc.showDialog(stage);
            if (dir != null) txtRoot.setText(dir.getAbsolutePath());
        });

        btnSearch.setOnAction(e -> runSearch());

        ToolBar bar = new ToolBar(txtQuery, new Separator(), txtRoot, btnBrowse, btnSearch);

        /* tabla */
        TableColumn<Result, String> cPath = new TableColumn<>("Ruta");
        cPath.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().path()));

        TableColumn<Result, Number> cSize = new TableColumn<>("Tamaño");
        cSize.setCellValueFactory(d -> new SimpleLongProperty(d.getValue().sizeBytes()));
        cSize.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number n, boolean empty) {
                super.updateItem(n, empty);
                setText(empty ? "" : human(n.longValue()));
            }
        });

        TableColumn<Result, String> cDate = new TableColumn<>("Modificado");
        cDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().date()));

        table.setPlaceholder(new Label("Sin resultados"));

        table.getColumns().addAll(cPath, cSize, cDate);
        double SB = 18;
        cPath.prefWidthProperty().bind(table.widthProperty().subtract(SB).multiply(0.70));
        cSize.prefWidthProperty().bind(table.widthProperty().subtract(SB).multiply(0.15));
        cDate.prefWidthProperty().bind(table.widthProperty().subtract(SB).multiply(0.15));

        /* -------------- RowFactory con menú contextual -------------- */
        table.setRowFactory(tv -> {
            TableRow<Result> row = new TableRow<>();

            /* menú contextual */
            MenuItem miOpen = new MenuItem("Abrir archivo");
            MenuItem miOpenDir = new MenuItem("Abrir carpeta contenedora");
            MenuItem miCopyPath = new MenuItem("Copiar ruta");
            MenuItem miRemove = new MenuItem("Quitar de la lista");

            ContextMenu menu = new ContextMenu(miOpen, miOpenDir, miCopyPath, new SeparatorMenuItem(), miRemove);

            miOpen.setOnAction(e -> openFile(row.getItem().path()));
            miOpenDir.setOnAction(e -> openDir(row.getItem().path()));
            miCopyPath.setOnAction(e -> copyToClipboard(row.getItem().path()));
            miRemove.setOnAction(e -> data.remove(row.getItem()));

            /* solo activar en filas no vacías */
            row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));

            /* doble clic tradicional */
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    openFile(row.getItem().path());
                }
            });
            return row;
        });

        /* status bar */
        progress.setMinHeight(14);
        progress.setVisible(false);
        progress.setPrefWidth(140);
        HBox status = new HBox(10, progress, lblStatus);
        status.setPadding(new Insets(4));
        status.setStyle("-fx-background-color:-fx-control-inner-background;" + "-fx-border-color:lightgray; -fx-border-width:1 0 0 0;");

        /* escena */
        BorderPane root = new BorderPane(table);
        root.setTop(bar);
        root.setBottom(status);
        BorderPane.setMargin(table, new Insets(5));

        stage.setScene(new Scene(root, 850, 500));
        stage.setTitle("FileFinder FX");
        stage.show();
    }

    /* ------------------------ búsqueda (igual que tu versión) ------------------------ */
    private void runSearch() {
        data.clear();

        final Pattern regex = Pattern.compile(txtQuery.getText(), Pattern.CASE_INSENSITIVE);
        final Path root = txtRoot.getText().isBlank() ? systemRoot() : Paths.get(txtRoot.getText());

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() {
                final AtomicLong scanned = new AtomicLong();
                final AtomicLong hits = new AtomicLong();
                final AtomicLong skipped = new AtomicLong();   // carpetas sin permiso
                final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                updateMessage("Buscando…");
                updateProgress(-1, 1);               // barra indeterminada

                /* --- VISITAR EL ÁRBOL --- */
                try {
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {

                        /* Saltamos enlaces simbólicos para evitar bucles */
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            return isCancelled() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (isCancelled()) return FileVisitResult.TERMINATE;

                            long done = scanned.incrementAndGet();
                            boolean match = regex.matcher(file.getFileName().toString()).find();

                            if (match) {
                                long hm = hits.incrementAndGet();
                                Platform.runLater(() -> data.add(new Result(file.toString(), attrs.size(), fmt.format(attrs.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault())))));
                                updateMessage(String.format("Escaneados: %,d   Coincidencias: %,d", done, hm));
                            } else if (done % 1_000 == 0) {
                                updateMessage(String.format("Escaneados: %,d   Coincidencias: %,d", done, hits.get()));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            skipped.incrementAndGet();          // sin permisos
                            return FileVisitResult.CONTINUE;     // seguimos
                        }
                    });
                } catch (IOException e) {
                    updateMessage("Error crítico: " + e.getMessage());
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait());
                    return null;
                }

                updateProgress(1, 1); // llena la barra
                String resumen = String.format("Completado. %,d archivos, %,d coincidencias%s.", scanned.get(), hits.get(), skipped.get() == 0 ? "" : String.format(" (%,d carpetas sin permiso)", skipped.get()));
                updateMessage(resumen);
                return null;
            }
        };

        /* -------- Bindings UI ↔ Task -------- */
        progress.progressProperty().bind(task.progressProperty());
        progress.visibleProperty().bind(task.runningProperty());
        lblStatus.textProperty().bind(task.messageProperty());
        btnSearch.disableProperty().bind(task.runningProperty());

        Thread th = new Thread(task, "search-thread");
        th.setDaemon(true);
        th.start();
    }

    /* ------------------------ UTILIDADES ------------------------ */
    private static void openFile(String path) {
        try {
            Desktop.getDesktop().open(Path.of(path).toFile());
        } catch (IOException ignored) {
        }
    }

    private static void openDir(String path) {
        try {
            Path p = Path.of(path);
            if (isWindows()) {
                new ProcessBuilder("explorer.exe", "/select,", p.toString()).start();
            } else if (isMac()) {
                new ProcessBuilder("open", "-R", p.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", p.getParent().toString()).start();
            }
        } catch (IOException ignored) {
        }
    }

    private static void copyToClipboard(String text) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private static boolean isMac() {
        return System.getProperty("os.name").startsWith("Mac");
    }

    private static Path systemRoot() {
        Iterator<Path> it = FileSystems.getDefault().getRootDirectories().iterator();
        return it.hasNext() ? it.next() : Paths.get("/");
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.1f %s", bytes / Math.pow(1024, exp), unit);
    }

    /* ---------------- DTO ---------------- */
    private record Result(String path, long sizeBytes, String date) {
        public String path() {
            return path;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public String date() {
            return date;
        }
    }
}
