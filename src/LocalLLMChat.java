import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * A small, dependency-free Swing client for the Apple Foundation Models CLI.
 *
 * <p>Each prompt starts an {@code /usr/bin/fm respond} child process, reads its
 * standard output incrementally, and forwards generated text to Swing's Event
 * Dispatch Thread (EDT). The fm transcript preserves model context, while a
 * separate message file lets the UI restore chat bubbles efficiently.</p>
 *
 * <p>Java 21 or later is required because virtual threads are used for blocking
 * process I/O.</p>
 */
@SuppressWarnings("serial") // Swing base classes implement Serializable; this UI is never serialized.
public final class LocalLLMChat extends JFrame {
    // Shared palette. Keeping colors centralized makes later theme work easier.
    private static final Color BG = new Color(246, 247, 249);
    private static final Color ASSISTANT_BG = Color.WHITE;
    private static final Color USER_BG = new Color(33, 109, 246);
    private static final Color TEXT = new Color(34, 38, 45);
    private static final Color MUTED = new Color(105, 112, 123);
    private static final Pattern ANSI = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");

    // Main conversation widgets.
    private final JPanel messages = new JPanel();
    private final JScrollPane messageScroll;
    private final JTextArea input = new JTextArea(3, 20);
    private final JButton sendButton = new JButton("送信");
    private final JButton stopButton = new JButton("停止");
    private final JButton newChatButton = new JButton("新しい会話");
    private final JLabel status = new JLabel("確認中…");
    // Sidebar model and the on-disk location used for durable chat history.
    private final DefaultListModel<ChatSession> historyModel = new DefaultListModel<>();
    private final JList<ChatSession> historyList = new JList<>(historyModel);
    private final Path chatStore = Path.of(System.getProperty("user.home"), "Library", "Application Support",
            "Mac Local LLM Chat", "chats");
    private ChatSession currentChat;
    // Accessed by both the EDT and a virtual worker thread, hence volatile/atomic.
    private volatile Process currentProcess;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public LocalLLMChat() throws IOException {
        super("Mac ローカルLLM Chat");
        Files.createDirectories(chatStore);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(680, 620));
        setSize(820, 760);
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        setContentPane(root);

        root.add(createHeader(), BorderLayout.NORTH);

        messages.setLayout(new BoxLayout(messages, BoxLayout.Y_AXIS));
        messages.setBackground(BG);
        messages.setBorder(new EmptyBorder(18, 18, 18, 18));
        messageScroll = new JScrollPane(messages);
        messageScroll.setBorder(null);
        messageScroll.getVerticalScrollBar().setUnitIncrement(18);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, createSidebar(), messageScroll);
        split.setDividerLocation(245);
        split.setDividerSize(1);
        split.setBorder(null);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);

        root.add(createComposer(), BorderLayout.SOUTH);
        loadHistory();
        if (historyModel.isEmpty()) {
            createNewChat();
        } else {
            openChat(historyModel.get(0));
        }
        bindKeys();
        checkAvailability();

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                stopGeneration();
            }
        });
    }

    private JComponent createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout(0, 10));
        sidebar.setBackground(new Color(238, 240, 243));
        sidebar.setBorder(new EmptyBorder(14, 10, 14, 10));
        sidebar.setMinimumSize(new Dimension(185, 200));

        JButton add = new JButton("＋  新しい会話");
        add.setHorizontalAlignment(SwingConstants.LEFT);
        add.setFocusPainted(false);
        add.addActionListener(e -> startNewChat());
        sidebar.add(add, BorderLayout.NORTH);

        historyList.setBackground(sidebar.getBackground());
        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setFixedCellHeight(58);
        historyList.setCellRenderer(new HistoryRenderer());
        historyList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && currentProcess == null) {
                ChatSession selected = historyList.getSelectedValue();
                if (selected != null && selected != currentChat) openChat(selected);
            }
        });
        JScrollPane scroll = new JScrollPane(historyList);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(sidebar.getBackground());
        sidebar.add(scroll, BorderLayout.CENTER);

        JButton delete = new JButton("選択した履歴を削除");
        delete.setForeground(new Color(175, 55, 55));
        delete.setFocusPainted(false);
        delete.addActionListener(e -> deleteSelectedChat());
        sidebar.add(delete, BorderLayout.SOUTH);
        return sidebar;
    }

    private JComponent createHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(Color.WHITE);
        header.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel title = new JLabel("Mac ローカルLLM");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        header.add(title, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        status.setForeground(MUTED);
        newChatButton.addActionListener(e -> startNewChat());
        right.add(status);
        right.add(newChatButton);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent createComposer() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG);
        outer.setBorder(new EmptyBorder(8, 18, 18, 18));

        JPanel composer = new JPanel(new BorderLayout(10, 0));
        composer.setBackground(Color.WHITE);
        composer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(218, 221, 226)),
                new EmptyBorder(10, 12, 10, 10)));

        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setFont(input.getFont().deriveFont(15f));
        input.setBorder(null);
        input.setToolTipText("Returnで送信、Shift+Returnで改行");
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setBorder(null);
        composer.add(inputScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(2, 1, 0, 6));
        buttons.setOpaque(false);
        stylePrimaryButton(sendButton);
        stopButton.setEnabled(false);
        sendButton.addActionListener(e -> send());
        stopButton.addActionListener(e -> stopGeneration());
        buttons.add(sendButton);
        buttons.add(stopButton);
        composer.add(buttons, BorderLayout.EAST);

        JLabel hint = new JLabel("Return：送信　　Shift + Return：改行");
        hint.setForeground(MUTED);
        hint.setBorder(new EmptyBorder(7, 4, 0, 0));
        outer.add(composer, BorderLayout.CENTER);
        outer.add(hint, BorderLayout.SOUTH);
        return outer;
    }

    private void stylePrimaryButton(JButton button) {
        button.setBackground(USER_BG);
        button.setForeground(Color.WHITE);
        button.setOpaque(true);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
    }

    private void bindKeys() {
        // Follow chat conventions: Enter sends and Shift+Enter inserts a line.
        InputMap map = input.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actions = input.getActionMap();
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        actions.put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { send(); }
        });
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
    }

    private void addWelcomeMessage() {
        addMessage(false, "こんにちは。Mac内のApple Foundation Modelと会話できます。\n下の欄に質問を入力してください。");
    }

    private void checkAvailability() {
        // Process I/O is blocking, so it must never run on Swing's EDT.
        Thread.ofVirtual().start(() -> {
            String result;
            try {
                Process process = new ProcessBuilder("/usr/bin/fm", "available")
                        .redirectErrorStream(true).start();
                result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exit = process.waitFor();
                String clean = cleanOutput(result).trim().toLowerCase();
                if (exit == 0 && (clean.contains("available") || clean.contains("利用可能"))) {
                    setStatus("● 利用可能", new Color(35, 145, 85));
                } else {
                    setStatus("モデルを確認してください", new Color(190, 95, 30));
                }
            } catch (Exception e) {
                setStatus("fm を利用できません", new Color(190, 65, 65));
            }
        });
    }

    private void setStatus(String text, Color color) {
        // Swing components may only be updated safely on the EDT.
        SwingUtilities.invokeLater(() -> {
            status.setText(text);
            status.setForeground(color);
        });
    }

    private void send() {
        String prompt = input.getText().trim();
        if (prompt.isEmpty() || currentProcess != null) return;

        input.setText("");
        addMessage(true, prompt);
        currentChat.messages.add(new ChatMessage(true, prompt));
        if (currentChat.messages.size() == 1) {
            // The first sent prompt turns an unsaved draft into a history item.
            currentChat.title = makeTitle(prompt);
            if (!historyModel.contains(currentChat)) historyModel.insertElementAt(currentChat, 0);
        }
        currentChat.updated = System.currentTimeMillis();
        saveCurrentChat();
        refreshHistory();
        StreamingBubble answer = addStreamingMessage();
        setBusy(true);
        cancelled.set(false);

        Thread.ofVirtual().start(() -> runModel(prompt, answer));
    }

    /**
     * Executes one model turn and streams child-process output into an existing
     * assistant bubble. All blocking work stays off the EDT.
     */
    private void runModel(String prompt, StreamingBubble answer) {
        StringBuilder allOutput = new StringBuilder();
        try {
            List<String> command = new ArrayList<>();
            command.add("/usr/bin/fm");
            command.add("respond");
            command.add("--stream");
            Path transcript = currentChat.transcript();
            if (Files.exists(transcript) && Files.size(transcript) > 0) {
                // --resume restores both the conversation and its instructions.
                command.add("--resume");
                command.add(transcript.toString());
            } else {
                // Instructions become part of the first saved transcript. Supplying them
                // again together with --resume is rejected by fm as a duplicate.
                command.add("--instructions");
                command.add("日本語で、明確かつ親切に答えてください。Markdownは最小限にしてください。");
            }
            command.add("--save-transcript");
            command.add(transcript.toString());
            command.add(prompt);

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            currentProcess = process;
            ModelOutputFilter outputFilter = new ModelOutputFilter(text -> {
                // Persist and display the same filtered model text.
                allOutput.append(text);
                answer.append(text);
            });
            try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] buffer = new char[256];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    String chunk = cleanOutput(new String(buffer, 0, count));
                    outputFilter.accept(chunk);
                }
            }
            outputFilter.finish();
            int exit = process.waitFor();
            if (!cancelled.get() && exit != 0) {
                showModelError(answer, allOutput.toString(), exit);
            } else if (!cancelled.get() && allOutput.toString().trim().isEmpty()) {
                answer.append("応答がありませんでした。Apple Intelligenceの設定を確認してください。");
            }
            if (!cancelled.get()) {
                String savedAnswer = allOutput.toString().trim();
                if (savedAnswer.isEmpty()) savedAnswer = "応答がありませんでした。";
                currentChat.messages.add(new ChatMessage(false, savedAnswer));
                currentChat.updated = System.currentTimeMillis();
                saveCurrentChat();
                refreshHistory();
            }
        } catch (Exception e) {
            if (!cancelled.get()) answer.append("\n接続エラー: " + e.getMessage());
        } finally {
            currentProcess = null;
            SwingUtilities.invokeLater(() -> setBusy(false));
        }
    }

    private void showModelError(StreamingBubble answer, String output, int exit) {
        String lower = output.toLowerCase();
        if (lower.contains("license") || lower.contains("legal") || lower.contains("terms")) {
            answer.append("\n\n初回利用の設定が必要です。ターミナルで「fm license」を一度実行し、表示される案内を確認してください。");
        } else {
            answer.append("\n\nモデルの実行に失敗しました（終了コード " + exit + "）。");
        }
    }

    private void stopGeneration() {
        // Ask the process to stop gracefully, then force termination if needed.
        cancelled.set(true);
        Process process = currentProcess;
        if (process != null) {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
        currentProcess = null;
        setBusy(false);
        setStatus("生成を停止しました", MUTED);
    }

    private void setBusy(boolean busy) {
        sendButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        newChatButton.setEnabled(!busy);
        input.setEnabled(!busy);
        if (!busy) {
            input.requestFocusInWindow();
            checkAvailability();
        } else {
            setStatus("生成中…", MUTED);
        }
    }

    private void startNewChat() {
        if (currentProcess != null) return;
        createNewChat();
    }

    private void createNewChat() {
        currentChat = new ChatSession(UUID.randomUUID().toString(), "新しい会話", System.currentTimeMillis());
        // An empty draft is intentionally not added to history. It becomes a
        // history item only after the user sends the first message.
        historyList.clearSelection();
        messages.removeAll();
        addWelcomeMessage();
        messages.revalidate();
        messages.repaint();
        input.requestFocusInWindow();
    }

    private void deleteSelectedChat() {
        if (currentProcess != null) return;
        ChatSession selected = historyList.getSelectedValue();
        if (selected == null) return;

        int choice = JOptionPane.showConfirmDialog(this,
                "「" + selected.title + "」を削除しますか？\nこの操作は取り消せません。",
                "会話履歴の削除",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        try {
            Path directory = selected.directory().normalize();
            Path store = chatStore.normalize();
            // Recursive deletion is limited to a child of the known chat store.
            if (directory.startsWith(store) && !directory.equals(store) && Files.exists(directory)) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            historyModel.removeElement(selected);
            if (historyModel.isEmpty()) {
                createNewChat();
            } else {
                openChat(historyModel.get(0));
            }
            setStatus("会話履歴を削除しました", MUTED);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "会話履歴を削除できませんでした。\n" + e.getMessage(),
                    "削除エラー",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openChat(ChatSession chat) {
        currentChat = chat;
        historyList.setSelectedValue(chat, true);
        messages.removeAll();
        if (chat.messages.isEmpty()) {
            addWelcomeMessage();
        } else {
            for (ChatMessage message : chat.messages) addMessage(message.user, message.text);
        }
        messages.revalidate();
        messages.repaint();
        SwingUtilities.invokeLater(() -> messageScroll.getVerticalScrollBar().setValue(
                messageScroll.getVerticalScrollBar().getMaximum()));
    }

    private void loadHistory() {
        // A malformed history entry must not prevent the application from opening.
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(chatStore)) {
            List<ChatSession> chats = new ArrayList<>();
            for (Path dir : directories) {
                if (!Files.isDirectory(dir)) continue;
                Path metadata = dir.resolve("metadata.properties");
                if (!Files.exists(metadata)) continue;
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                ChatSession chat = new ChatSession(dir.getFileName().toString(),
                        properties.getProperty("title", "過去の会話"),
                        Long.parseLong(properties.getProperty("updated", "0")));
                chat.messages.addAll(readMessages(dir.resolve("messages.tsv")));
                chats.add(chat);
            }
            chats.sort(Comparator.comparingLong((ChatSession c) -> c.updated).reversed());
            for (ChatSession chat : chats) historyModel.addElement(chat);
        } catch (Exception ignored) {}
    }

    private List<ChatMessage> readMessages(Path path) throws IOException {
        List<ChatMessage> result = new ArrayList<>();
        if (!Files.exists(path)) return result;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String[] parts = line.split("\\t", 2);
            if (parts.length != 2) continue;
            try {
                // Base64 safely preserves tabs and newlines in this simple TSV.
                String text = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                result.add(new ChatMessage(parts[0].equals("user"), text));
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private void saveCurrentChat() {
        ChatSession chat = currentChat;
        if (chat == null || chat.messages.isEmpty()) return;
        try {
            Files.createDirectories(chat.directory());
            Properties properties = new Properties();
            // Keep searchable metadata separate from encoded message bodies.
            properties.setProperty("title", chat.title);
            properties.setProperty("updated", Long.toString(chat.updated));
            try (Writer writer = Files.newBufferedWriter(chat.directory().resolve("metadata.properties"),
                    StandardCharsets.UTF_8)) {
                properties.store(writer, "Mac Local LLM Chat");
            }
            List<String> lines = new ArrayList<>();
            for (ChatMessage message : chat.messages) {
                String encoded = Base64.getEncoder().encodeToString(message.text.getBytes(StandardCharsets.UTF_8));
                lines.add((message.user ? "user" : "assistant") + "\t" + encoded);
            }
            Files.write(chat.directory().resolve("messages.tsv"), lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            setStatus("履歴を保存できませんでした", new Color(190, 65, 65));
        }
    }

    private String makeTitle(String prompt) {
        String oneLine = prompt.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 22 ? oneLine : oneLine.substring(0, 22) + "…";
    }

    private void refreshHistory() {
        // Swing models must be updated on the EDT.
        SwingUtilities.invokeLater(() -> {
            List<ChatSession> chats = new ArrayList<>();
            for (int i = 0; i < historyModel.size(); i++) chats.add(historyModel.get(i));
            chats.sort(Comparator.comparingLong((ChatSession c) -> c.updated).reversed());
            historyModel.clear();
            for (ChatSession chat : chats) historyModel.addElement(chat);
            historyList.setSelectedValue(currentChat, true);
            historyList.repaint();
        });
    }

    private void addMessage(boolean user, String text) {
        SwingUtilities.invokeLater(() -> {
            JPanel row = bubbleRow(user);
            JTextPane pane = textPane(user);
            pane.setText(text);
            row.add(wrapBubble(pane, user));
            messages.add(row);
            messages.add(Box.createVerticalStrut(12));
            messages.revalidate();
            scrollToBottom();
        });
    }

    private StreamingBubble addStreamingMessage() {
        StreamingBubble bubble = new StreamingBubble();
        SwingUtilities.invokeLater(() -> {
            JPanel row = bubbleRow(false);
            row.add(wrapBubble(bubble.pane, false));
            messages.add(row);
            messages.add(Box.createVerticalStrut(12));
            messages.revalidate();
            scrollToBottom();
        });
        return bubble;
    }

    private JPanel bubbleRow(boolean user) {
        JPanel row = new JPanel(new FlowLayout(user ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private JComponent wrapBubble(JTextPane pane, boolean user) {
        JPanel bubble = new JPanel(new BorderLayout());
        bubble.setBackground(user ? USER_BG : ASSISTANT_BG);
        bubble.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(user ? USER_BG : new Color(226, 228, 232)),
                new EmptyBorder(11, 14, 11, 14)));
        bubble.add(pane, BorderLayout.CENTER);
        bubble.setPreferredSize(new Dimension(Math.min(610, Math.max(220, pane.getPreferredSize().width + 30)),
                pane.getPreferredSize().height + 24));
        return bubble;
    }

    private JTextPane textPane(boolean user) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setOpaque(false);
        pane.setForeground(user ? Color.WHITE : TEXT);
        pane.setFont(pane.getFont().deriveFont(15f));
        pane.setBorder(null);
        StyledDocument doc = pane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(attrs, 0.18f);
        doc.setParagraphAttributes(0, 0, attrs, false);
        return pane;
    }

    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = messageScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private static String cleanOutput(String value) {
        // Terminal colors would otherwise appear as garbage in a Swing bubble.
        return ANSI.matcher(value).replaceAll("").replace("\r", "");
    }

    private interface TextConsumer {
        void accept(String text);
    }

    /**
     * Removes fm's transcript-location notice without sacrificing streamed
     * output. A short suffix is retained so a marker split across reads is
     * still recognized.
     */
    private static final class ModelOutputFilter {
        private static final String MARKER = "Transcript saved to:";
        private final TextConsumer destination;
        private final StringBuilder pending = new StringBuilder();
        private boolean discardingNotice;

        ModelOutputFilter(TextConsumer destination) {
            this.destination = destination;
        }

        void accept(String chunk) {
            if (chunk.isEmpty()) return;
            if (discardingNotice) return;
            pending.append(chunk);

            int markerAt = pending.indexOf(MARKER);
            if (markerAt >= 0) {
                String before = pending.substring(0, markerAt).stripTrailing();
                if (!before.isEmpty()) destination.accept(before);
                pending.setLength(0);
                discardingNotice = true;
                return;
            }

            // Keep just enough trailing text to recognize a marker split across read chunks.
            int safeLength = pending.length() - (MARKER.length() - 1);
            if (safeLength > 0) {
                destination.accept(pending.substring(0, safeLength));
                pending.delete(0, safeLength);
            }
        }

        void finish() {
            if (!discardingNotice && !pending.isEmpty()) destination.accept(pending.toString());
            pending.setLength(0);
        }
    }

    /** One user or assistant message as displayed in the conversation pane. */
    private record ChatMessage(boolean user, String text) {}

    /** In-memory representation of one sidebar item and its persisted files. */
    private final class ChatSession {
        final String id;
        String title;
        long updated;
        final List<ChatMessage> messages = new ArrayList<>();

        ChatSession(String id, String title, long updated) {
            this.id = id;
            this.title = title;
            this.updated = updated;
        }

        Path directory() { return chatStore.resolve(id); }
        Path transcript() { return directory().resolve("fm-transcript.json"); }
        @Override public String toString() { return title; }
    }

    @SuppressWarnings("serial")
    private static final class HistoryRenderer extends JPanel implements ListCellRenderer<ChatSession> {
        private final JLabel title = new JLabel();
        private final JLabel date = new JLabel();
        private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M月d日  HH:mm")
                .withZone(ZoneId.systemDefault());

        HistoryRenderer() {
            setLayout(new BorderLayout(0, 3));
            setBorder(new EmptyBorder(8, 10, 8, 10));
            title.setFont(title.getFont().deriveFont(Font.PLAIN, 13.5f));
            date.setFont(date.getFont().deriveFont(11f));
            date.setForeground(MUTED);
            add(title, BorderLayout.CENTER);
            add(date, BorderLayout.SOUTH);
        }

        @Override public Component getListCellRendererComponent(JList<? extends ChatSession> list,
                ChatSession value, int index, boolean selected, boolean focus) {
            title.setText(value.title);
            date.setText(formatter.format(Instant.ofEpochMilli(value.updated)));
            setBackground(selected ? Color.WHITE : list.getBackground());
            title.setForeground(TEXT);
            return this;
        }
    }

    private final class StreamingBubble {
        private final JTextPane pane = textPane(false);

        void append(String text) {
            if (text.isEmpty()) return;
            // Model output arrives off the EDT; document updates must not.
            SwingUtilities.invokeLater(() -> {
                try {
                    pane.getDocument().insertString(pane.getDocument().getLength(), text, null);
                    Container bubble = pane.getParent();
                    if (bubble instanceof JComponent component) {
                        int width = Math.min(610, Math.max(220, pane.getPreferredSize().width + 30));
                        component.setPreferredSize(new Dimension(width, pane.getPreferredSize().height + 24));
                    }
                    messages.revalidate();
                    scrollToBottom();
                } catch (BadLocationException ignored) {}
            });
        }
    }

    public static void main(String[] args) {
        // Apple-specific properties make Swing integrate more naturally on macOS.
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "Mac ローカルLLM Chat");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                new LocalLLMChat().setVisible(true);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "起動できませんでした: " + e.getMessage(),
                        "エラー", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}
