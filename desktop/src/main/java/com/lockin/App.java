package com.lockin;

import java.util.*;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

public class App extends Application {

    // ── Palette (matches the Chrome extension) ────────────────────────────────
    private static final String BG = "#0c0f14";
    private static final String PANEL = "#11151d";
    private static final String TEXT = "#edf2f7";
    private static final String MUTED = "#9aa7b8";
    private static final String ACCENT = "#38bdf8";
    private static final String ACCENT_DARK = "#06131d";
    private static final String GREEN = "#4ade80";
    private static final String RED = "#fb7185";
    private static final String DANGER_BG = "rgba(251,113,133,0.15)";
    private static final String CHIP_BG = "rgba(125,211,252,0.12)";
    private static final String CHIP_FG = "#d9f3ff";

    // ── State ─────────────────────────────────────────────────────────────────
    private final ControlServer controlServer = new ControlServer();
    private boolean enabled = true;

    // ── Live UI references ────────────────────────────────────────────────────
    private Button toggleBtn;
    private Circle ollamaDot;
    private Label ollamaLabel;
    private Label blockedValue;
    private Label scrapesValue;
    private Label profileValue;
    private Label lastScrapeValue;
    private VBox profileListBox;

    // ─────────────────────────────────────────────────────────────────────────
    // JavaFX lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void start(Stage stage) throws Exception {
        controlServer.start();

        // Rebuild profile cards whenever the extension syncs new profiles
        controlServer.setOnProfilesUpdated(() ->
            Platform.runLater(this::renderProfileCards)
        );

        ScrollPane scroll = new ScrollPane(buildRoot());
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle(
            "-fx-background: " + BG + "; -fx-background-color: " + BG + ";"
        );

        Scene scene = new Scene(scroll, 400, 720);
        scene.setFill(Color.web(BG));

        stage.setTitle("LockIn");
        stage.setScene(scene);
        stage.setMinWidth(400);
        stage.setMinHeight(500);
        stage.show();

        // Poll Ollama + refresh stats every 4 seconds
        Timeline poller = new Timeline(
            new KeyFrame(Duration.seconds(4), e -> refreshStatus())
        );
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        refreshStatus();

        stage.setOnCloseRequest(e -> {
            controlServer.stop();
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        controlServer.stop();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────────────────

    private VBox buildRoot() {
        VBox root = new VBox(20);
        root.setStyle("-fx-background-color: " + BG + ";");
        root.setPadding(new Insets(28));
        root.setAlignment(Pos.TOP_CENTER);

        root
            .getChildren()
            .addAll(
                buildHeader(),
                buildToggle(),
                buildStatusPanel(),
                buildStatsPanel(),
                buildProfilesPanel(),
                buildFooter()
            );
        return root;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private VBox buildHeader() {
        Label title = new Label("🔒  LockIn");
        title.setStyle(
            "-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: " +
                TEXT +
                ";"
        );

        Label subtitle = new Label("YouTube content filter — desktop control");
        subtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: " + MUTED + ";");

        VBox box = new VBox(4, title, subtitle);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // ── Big toggle button ─────────────────────────────────────────────────────

    private Button buildToggle() {
        toggleBtn = new Button("● ACTIVE");
        toggleBtn.setMaxWidth(Double.MAX_VALUE);
        toggleBtn.setPrefHeight(52);
        applyToggleStyle(true);

        toggleBtn.setOnAction(e -> {
            enabled = !enabled;
            controlServer.setEnabled(enabled);
            applyToggleStyle(enabled);
            toggleBtn.setText(enabled ? "● ACTIVE" : "○ PAUSED");
        });

        return toggleBtn;
    }

    private void applyToggleStyle(boolean on) {
        String bg = on ? ACCENT : DANGER_BG;
        String fg = on ? ACCENT_DARK : RED;
        String bdr = on ? ACCENT : RED;
        toggleBtn.setStyle(
            "-fx-background-color: " +
                bg +
                ";" +
                "-fx-text-fill: " +
                fg +
                ";" +
                "-fx-font-size: 15px; -fx-font-weight: bold;" +
                "-fx-background-radius: 14;" +
                "-fx-border-color: " +
                bdr +
                ";" +
                "-fx-border-width: 1.5; -fx-border-radius: 14;" +
                "-fx-cursor: hand;"
        );
    }

    // ── Runtime status panel ──────────────────────────────────────────────────

    private VBox buildStatusPanel() {
        ollamaDot = new Circle(5, Color.web(MUTED));
        ollamaLabel = new Label("Checking…");
        ollamaLabel.setStyle(
            "-fx-text-fill: " + MUTED + "; -fx-font-size: 13px;"
        );

        HBox ollamaRow = buildCardRow(
            "Ollama",
            new HBox(6, ollamaDot, ollamaLabel)
        );
        HBox classifyRow = buildCardRow(
            "Classify model",
            styledLabel("llama3.2:3b", ACCENT)
        );
        HBox tagsRow = buildCardRow(
            "Tags model",
            styledLabel("gemma4:e2b", ACCENT)
        );

        return new VBox(
            4,
            sectionHeading("RUNTIME"),
            wrapCard(ollamaRow, classifyRow, tagsRow)
        );
    }

    // ── Session stats panel ───────────────────────────────────────────────────

    private VBox buildStatsPanel() {
        blockedValue = styledLabel("—", TEXT);
        scrapesValue = styledLabel("—", TEXT);
        profileValue = styledLabel("—", ACCENT);
        lastScrapeValue = styledLabel("—", MUTED);

        HBox r1 = buildCardRow("Blocked tiles", blockedValue);
        HBox r2 = buildCardRow("Scrape cycles", scrapesValue);
        HBox r3 = buildCardRow("Active profile", profileValue);
        HBox r4 = buildCardRow("Last scrape", lastScrapeValue);

        return new VBox(
            4,
            sectionHeading("SESSION STATS"),
            wrapCard(r1, r2, r3, r4)
        );
    }

    // ── Profiles panel ────────────────────────────────────────────────────────

    private VBox buildProfilesPanel() {
        // Heading row: "PROFILES" label + "＋ New Profile" button
        Label heading = sectionHeading("PROFILES");

        Button newBtn = new Button("＋  New Profile");
        newBtn.setStyle(
            "-fx-background-color: transparent;" +
                "-fx-text-fill: " +
                ACCENT +
                ";" +
                "-fx-font-size: 12px; -fx-font-weight: bold;" +
                "-fx-border-color: rgba(56,189,248,0.35);" +
                "-fx-border-width: 1; -fx-border-radius: 8;" +
                "-fx-background-radius: 8; -fx-cursor: hand;" +
                "-fx-padding: 4 10 4 10;"
        );
        newBtn.setOnAction(e -> showNewProfileDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headingRow = new HBox(heading, spacer, newBtn);
        headingRow.setAlignment(Pos.CENTER_LEFT);

        profileListBox = new VBox(10);
        profileListBox.setFillWidth(true);

        // Show placeholder until extension syncs profiles
        Label placeholder = new Label(
            "Waiting for extension to sync profiles…"
        );
        placeholder.setStyle(
            "-fx-text-fill: " + MUTED + "; -fx-font-size: 12px;"
        );
        profileListBox.getChildren().add(placeholder);

        return new VBox(8, headingRow, profileListBox);
    }

    // ── Render profile cards (called on every sync) ───────────────────────────

    @SuppressWarnings("unchecked")
    private void renderProfileCards() {
        List<Map<String, Object>> profiles = controlServer.getProfiles();
        String activeId = controlServer.getActiveProfileId();

        profileListBox.getChildren().clear();

        if (profiles.isEmpty()) {
            Label empty = new Label("No profiles yet. Create one below.");
            empty.setStyle(
                "-fx-text-fill: " + MUTED + "; -fx-font-size: 12px;"
            );
            profileListBox.getChildren().add(empty);
            return;
        }

        for (Map<String, Object> profile : profiles) {
            String id = str(profile.get("id"));
            String name = str(profile.get("name"));
            String prompt = str(profile.get("prompt"));
            boolean active = id.equals(activeId);

            List<String> tags = new ArrayList<>();
            Object tagsObj = profile.get("tags");
            if (tagsObj instanceof List<?> rawList) {
                for (Object t : rawList) tags.add(String.valueOf(t));
            }

            profileListBox
                .getChildren()
                .add(buildProfileCard(id, name, prompt, tags, active));
        }
    }

    private VBox buildProfileCard(
        String id,
        String name,
        String prompt,
        List<String> tags,
        boolean active
    ) {
        String borderColor = active
            ? "rgba(56,189,248,0.5)"
            : "rgba(255,255,255,0.07)";
        String bgColor = active ? "rgba(56,189,248,0.06)" : PANEL;

        VBox card = new VBox(8);
        card.setPadding(new Insets(14));
        card.setStyle(
            "-fx-background-color: " +
                bgColor +
                ";" +
                "-fx-background-radius: 14;" +
                "-fx-border-color: " +
                borderColor +
                ";" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 14;"
        );

        // ── Name row ──────────────────────────────────────────────────────────
        Label nameLbl = new Label(name.isBlank() ? "Unnamed Profile" : name);
        nameLbl.setStyle(
            "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " +
                TEXT +
                ";"
        );

        Label activeBadge = new Label("★ Active");
        activeBadge.setStyle(
            "-fx-font-size: 11px; -fx-font-weight: bold;" +
                "-fx-text-fill: " +
                ACCENT +
                ";" +
                "-fx-background-color: rgba(56,189,248,0.12);" +
                "-fx-background-radius: 6;" +
                "-fx-padding: 2 7 2 7;"
        );
        activeBadge.setVisible(active);

        Region nameSpace = new Region();
        HBox.setHgrow(nameSpace, Priority.ALWAYS);
        HBox nameRow = new HBox(nameLbl, nameSpace, activeBadge);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        // ── Prompt preview ────────────────────────────────────────────────────
        String promptText = prompt.isBlank() ? "No prompt set." : prompt;
        if (promptText.length() > 90) promptText =
            promptText.substring(0, 87) + "…";
        Label promptLbl = new Label(promptText);
        promptLbl.setStyle(
            "-fx-font-size: 12px; -fx-text-fill: " +
                MUTED +
                "; -fx-wrap-text: true;"
        );
        promptLbl.setMaxWidth(Double.MAX_VALUE);

        // ── Tags chips ────────────────────────────────────────────────────────
        FlowPane tagRow = new FlowPane(6, 4);
        for (String tag : tags) {
            if (tag.isBlank()) continue;
            Label chip = new Label(tag);
            chip.setStyle(
                "-fx-background-color: " +
                    CHIP_BG +
                    ";" +
                    "-fx-text-fill: " +
                    CHIP_FG +
                    ";" +
                    "-fx-font-size: 11px;" +
                    "-fx-background-radius: 999;" +
                    "-fx-padding: 3 8 3 8;" +
                    "-fx-border-color: rgba(125,211,252,0.2);" +
                    "-fx-border-width: 1; -fx-border-radius: 999;"
            );
            tagRow.getChildren().add(chip);
        }

        // ── Activate button ───────────────────────────────────────────────────
        HBox bottomRow = new HBox();
        bottomRow.setAlignment(Pos.CENTER_RIGHT);

        if (!active) {
            Button activateBtn = new Button("Set Active");
            activateBtn.setStyle(
                "-fx-background-color: transparent;" +
                    "-fx-text-fill: " +
                    ACCENT +
                    ";" +
                    "-fx-font-size: 12px; -fx-font-weight: bold;" +
                    "-fx-border-color: rgba(56,189,248,0.35);" +
                    "-fx-border-width: 1; -fx-border-radius: 8;" +
                    "-fx-background-radius: 8; -fx-cursor: hand;" +
                    "-fx-padding: 4 12 4 12;"
            );
            activateBtn.setOnAction(e -> {
                controlServer.setPendingActiveProfile(id);
                // Optimistic re-render
                renderProfileCards();
            });
            bottomRow.getChildren().add(activateBtn);
        }

        card.getChildren().addAll(nameRow, promptLbl);
        if (!tagRow.getChildren().isEmpty()) card.getChildren().add(tagRow);
        if (!bottomRow.getChildren().isEmpty()) card
            .getChildren()
            .add(bottomRow);

        return card;
    }

    // ── New Profile dialog ────────────────────────────────────────────────────

    private void showNewProfileDialog() {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("New Profile");
        dialog.setHeaderText(null);

        DialogPane pane = dialog.getDialogPane();
        pane.setStyle("-fx-background-color: " + PANEL + ";");

        ButtonType createType = new ButtonType(
            "Create",
            ButtonBar.ButtonData.OK_DONE
        );
        pane.getButtonTypes().addAll(createType, ButtonType.CANCEL);

        // Style buttons
        Button createBtn = (Button) pane.lookupButton(createType);
        createBtn.setStyle(
            "-fx-background-color: " +
                ACCENT +
                "; -fx-text-fill: " +
                ACCENT_DARK +
                ";" +
                "-fx-font-weight: bold; -fx-background-radius: 10;"
        );
        Button cancelBtn = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancelBtn.setStyle(
            "-fx-background-color: transparent; -fx-text-fill: " +
                MUTED +
                ";" +
                "-fx-border-color: rgba(255,255,255,0.12); -fx-border-radius: 10;" +
                "-fx-background-radius: 10;"
        );

        // Form fields
        TextField nameField = styledTextField("e.g. Computer science student");
        TextArea promptArea = styledTextArea(
            "What do you want to watch? e.g. Focus on DSA, ML, system design."
        );
        promptArea.setPrefRowCount(3);
        TextField tagsField = styledTextField("dsa, ml, python, system design");

        VBox form = new VBox(
            12,
            fieldGroup("Profile name", nameField),
            fieldGroup("Prompt (drives LLM filtering)", promptArea),
            fieldGroup(
                "Tags — comma separated (drives heuristic fallback)",
                tagsField
            )
        );
        form.setPadding(new Insets(4, 0, 4, 0));
        form.setPrefWidth(360);

        pane.setContent(form);

        // Disable Create if name is empty
        createBtn.setDisable(true);
        nameField
            .textProperty()
            .addListener((obs, o, n) ->
                createBtn.setDisable(n.trim().isEmpty())
            );

        dialog.setResultConverter(btn -> {
            if (btn != createType) return null;

            String id = "profile-" + UUID.randomUUID();
            List<String> tags = new ArrayList<>();
            for (String t : tagsField.getText().split("[,\\n]")) {
                String trimmed = t.trim().toLowerCase();
                if (!trimmed.isEmpty()) tags.add(trimmed);
            }

            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", id);
            profile.put("name", nameField.getText().trim());
            profile.put("prompt", promptArea.getText().trim());
            profile.put("tags", tags);
            profile.put("active", false);
            return profile;
        });

        dialog
            .showAndWait()
            .ifPresent(profile -> {
                controlServer.addPendingProfile(profile);
                renderProfileCards();
            });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status refresh
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshStatus() {
        OllamaMonitor.checkAsync().thenAccept(running ->
            Platform.runLater(() -> {
                if (running) {
                    ollamaDot.setFill(Color.web(GREEN));
                    ollamaLabel.setText("Running");
                    ollamaLabel.setStyle(
                        "-fx-text-fill: " + GREEN + "; -fx-font-size: 13px;"
                    );
                } else {
                    ollamaDot.setFill(Color.web(RED));
                    ollamaLabel.setText("Stopped");
                    ollamaLabel.setStyle(
                        "-fx-text-fill: " + RED + "; -fx-font-size: 13px;"
                    );
                }

                blockedValue.setText(
                    controlServer.getTotalBlocked() + " tiles"
                );
                scrapesValue.setText(
                    String.valueOf(controlServer.getTotalScrapes())
                );

                String prof = controlServer.getLastProfile();
                profileValue.setText(prof.isBlank() ? "—" : prof);

                String at = controlServer.getLastScrapeAt();
                lastScrapeValue.setText(at.isBlank() ? "—" : formatTime(at));
            })
        );
    }

    // ── Footer ────────────────────────────────────────────────────────────────

    private Label buildFooter() {
        Label note = new Label(
            "Profiles sync automatically every scrape cycle via the extension."
        );
        note.setStyle(
            "-fx-font-size: 11px; -fx-text-fill: " +
                MUTED +
                "; -fx-wrap-text: true;"
        );
        note.setMaxWidth(Double.MAX_VALUE);
        return note;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Label sectionHeading(String text) {
        Label l = new Label(text);
        l.setStyle(
            "-fx-font-size: 10px; -fx-font-weight: bold;" +
                "-fx-text-fill: " +
                MUTED +
                ";" +
                "-fx-padding: 0 0 2 2;"
        );
        return l;
    }

    private HBox buildCardRow(String labelText, javafx.scene.Node valueNode) {
        Label lbl = new Label(labelText);
        lbl.setStyle("-fx-text-fill: " + MUTED + "; -fx-font-size: 13px;");
        lbl.setMinWidth(130);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(lbl, spacer, valueNode);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));
        return row;
    }

    private VBox wrapCard(HBox... rows) {
        VBox card = new VBox();
        card.setStyle(
            "-fx-background-color: " +
                PANEL +
                ";" +
                "-fx-background-radius: 14;" +
                "-fx-border-color: rgba(255,255,255,0.07);" +
                "-fx-border-width: 1; -fx-border-radius: 14;"
        );
        for (int i = 0; i < rows.length; i++) {
            card.getChildren().add(rows[i]);
            if (i < rows.length - 1) {
                Region div = new Region();
                div.setPrefHeight(1);
                div.setStyle("-fx-background-color: rgba(255,255,255,0.05);");
                card.getChildren().add(div);
            }
        }
        return card;
    }

    private Label styledLabel(String text, String color) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 13px;");
        return l;
    }

    private TextField styledTextField(String placeholder) {
        TextField f = new TextField();
        f.setPromptText(placeholder);
        f.setStyle(
            "-fx-background-color: #0c0f14; -fx-text-fill: " +
                TEXT +
                ";" +
                "-fx-prompt-text-fill: #64748b;" +
                "-fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10;" +
                "-fx-padding: 8 12 8 12; -fx-font-size: 13px;"
        );
        return f;
    }

    private TextArea styledTextArea(String placeholder) {
        TextArea a = new TextArea();
        a.setPromptText(placeholder);
        a.setWrapText(true);
        a.setStyle(
            "-fx-background-color: #0c0f14; -fx-text-fill: " +
                TEXT +
                ";" +
                "-fx-prompt-text-fill: #64748b;" +
                "-fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1;" +
                "-fx-border-radius: 10; -fx-background-radius: 10;" +
                "-fx-padding: 8 12 8 12; -fx-font-size: 13px;"
        );
        return a;
    }

    private VBox fieldGroup(String label, javafx.scene.Node field) {
        Label lbl = new Label(label);
        lbl.setStyle(
            "-fx-font-size: 11px; -fx-font-weight: bold;" +
                "-fx-text-fill: " +
                MUTED +
                "; -fx-padding: 0 0 4 0;"
        );
        VBox group = new VBox(4, lbl, field);
        group.setFillWidth(true);
        return group;
    }

    private String formatTime(String iso) {
        try {
            int t = iso.indexOf('T');
            if (t < 0) return iso;
            String time = iso.substring(t + 1);
            if (time.length() > 8) time = time.substring(0, 8);
            return time + " UTC";
        } catch (Exception e) {
            return iso;
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        launch(args);
    }
}
