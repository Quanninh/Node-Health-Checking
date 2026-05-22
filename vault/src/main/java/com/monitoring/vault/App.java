package com.monitoring.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class App extends Application {

    private static final Path ACCOUNT_FILE = Paths.get("accounts.txt");

    private TextField usernameTF;
    private PasswordField passwordTF;
    private Button confirmBtn;
    private Label statusLbl;
    private VBox root;
    private Scene mainScene;

    @Override
    public void start(Stage primaryStage) {
        usernameTF = new TextField();
        usernameTF.setPromptText("Username");

        passwordTF = new PasswordField();
        passwordTF.setPromptText("Password");

        confirmBtn = new Button("Confirm");
        confirmBtn.setOnAction(event -> {
            String username = usernameTF.getText().trim();
            String password = passwordTF.getText();

            if (username.isEmpty() || password.isEmpty()) {
                statusLbl.setText("Username/password cannot be empty");
                return;
            }

            if (!password.matches("^[a-zA-Z0-9]{5}$")) {
                statusLbl.setText("Password must be exactly 5 characters (a-z, A-Z, 0-9 only)");
                return;
            }

            try {
                Map<String, String> accounts = loadAccounts();
                String hashedPassword = sha256(password);

                if (!accounts.containsKey(username)) {
                    String line = username + ":" + hashedPassword + System.lineSeparator();

                    Files.writeString(ACCOUNT_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    statusLbl.setText("Account creation complete");
                } else {
                    String storedHash = accounts.get(username);

                    if (storedHash.equals(hashedPassword)) {
                        statusLbl.setText("Login successful");
                    } else {
                        statusLbl.setText("Login failed, incorrect password");
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                statusLbl.setText("Error occurred");
            }
        });

        statusLbl = new Label("");

        root = new VBox(usernameTF, passwordTF, confirmBtn, statusLbl);

        mainScene = new Scene(root);
        mainScene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());

        primaryStage.setScene(mainScene);
        primaryStage.setTitle("Password Vault");
        primaryStage.setOnCloseRequest(event -> {
        });
        primaryStage.show();
    }

    private static Map<String, String> loadAccounts() throws IOException {
        Map<String, String> accounts = new HashMap<>();

        if (!Files.exists(ACCOUNT_FILE)) {
            return accounts;
        }

        List<String> lines = Files.readAllLines(ACCOUNT_FILE);

        for (String line : lines) {
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                accounts.put(parts[0], parts[1]);
            }
        }

        return accounts;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();

            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        launch();
    }

}