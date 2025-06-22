module org.example.ff {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.desktop;


    opens org.example.ff to javafx.fxml;
    exports org.example.ff;
}