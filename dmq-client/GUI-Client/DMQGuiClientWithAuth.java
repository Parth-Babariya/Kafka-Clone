import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Minimal GUI Client for DMQ System with JWT Authentication
 * Wraps CLI commands for easy testing and visualization
 */
public class DMQGuiClientWithAuth extends JFrame {
    
    // Try multiple possible locations for the CLI JAR
    private static final String[] CLI_JAR_PATHS = {
        "../target/mycli.jar",                           
        "../../dmq-client/target/mycli.jar",             
        System.getProperty("user.dir") + "/../target/mycli.jar"  
    };
    
    private static String CLI_JAR = null;
    
    static {
        // Find the first existing CLI JAR
        for (String path : CLI_JAR_PATHS) {
            File jarFile = new File(path);
            if (jarFile.exists()) {
                CLI_JAR = path;
                break;
            }
        }
        
        // Fallback to first path if none found
        if (CLI_JAR == null) {
            CLI_JAR = CLI_JAR_PATHS[0];
        }
    }
    
    // Authentication state
    private String jwtToken = null;
    private String currentUsername = null;
    
    // UI Components
    private JTabbedPane tabbedPane;
    private JTextArea outputArea;
    private JTextField metadataServiceUrl;
    private JCheckBox prettyModeCheckbox;
    private JButton getLeaderBtn;
    private JLabel authStatusLabel;
    private JButton loginBtn;
    private JButton logoutBtn;
    private JButton refreshTokenBtn;
    
    // Producer Tab
    private JTextField producerTopic;
    private JTextField producerKey;
    private JTextArea producerValue;
    private JTextField producerPartition;
    private JComboBox<String> producerAcks;
    private JButton produceSingleBtn;
    private JButton produceBatchBtn;
    
    // Consumer Tab
    private JTextField consumerTopic;
    private JTextField consumerGroupId;
    private JTextField consumerPartition;
    private JTextField consumerOffset;
    private JTextField consumerMaxMessages;
    private JButton consumeBtn;
    private JButton consumeGroupBtn;
    private JButton consumeGroupContinuousBtn;
    private JButton stopConsumeBtn;
    private volatile Process currentConsumeProcess = null;
    
    // Topic Management Tab
    private JTextField topicName;
    private JTextField topicPartitions;
    private JTextField topicReplication;
    private JButton createTopicBtn;
    private JButton listTopicsBtn;
    private JButton describeTopicBtn;
    
    // Consumer Groups Tab
    private JTextField groupTopic;
    private JTextField groupAppId;
    private JButton listGroupsBtn;
    private JButton describeGroupBtn;
    
    public DMQGuiClientWithAuth() {
        setTitle("DMQ GUI Client - With JWT Authentication");
        setSize(1200, 850);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        initComponents();
        loadStoredToken();
        
        setVisible(true);
    }
    
    private void initComponents() {
        // Main layout
        setLayout(new BorderLayout(10, 10));
        
        // Top panel - Metadata Service URL and Controls
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(new EmptyBorder(10, 10, 5, 10));
        topPanel.add(new JLabel("Metadata Service URL:"));
        metadataServiceUrl = new JTextField("http://localhost:9092", 20);
        topPanel.add(metadataServiceUrl);
        
        // Auth status label
        authStatusLabel = new JLabel("Not Logged In");
        authStatusLabel.setForeground(Color.RED);
        topPanel.add(authStatusLabel);
        
        // Login button
        loginBtn = new JButton("Login");
        loginBtn.addActionListener(e -> showLoginDialog());
        topPanel.add(loginBtn);
        
        // Logout button
        logoutBtn = new JButton("Logout");
        logoutBtn.setEnabled(false);
        logoutBtn.addActionListener(e -> logout());
        topPanel.add(logoutBtn);
        
        // Refresh Token button
        refreshTokenBtn = new JButton("Refresh Token");
        refreshTokenBtn.setEnabled(false);
        refreshTokenBtn.setToolTipText("Renew JWT token expiration");
        refreshTokenBtn.addActionListener(e -> refreshToken());
        topPanel.add(refreshTokenBtn);
        
        // Pretty mode toggle
        prettyModeCheckbox = new JCheckBox("Pretty Mode", true);
        prettyModeCheckbox.setToolTipText("Show only essential output without decorations");
        topPanel.add(prettyModeCheckbox);
        
        // Get Leader button
        getLeaderBtn = new JButton("Get Raft Leader");
        getLeaderBtn.addActionListener(e -> getRaftLeader());
        topPanel.add(getLeaderBtn);
        
        add(topPanel, BorderLayout.NORTH);
        
        // Center - Tabbed Pane (reuse existing panels from original DMQGuiClient)
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Producer", createProducerPanel());
        tabbedPane.addTab("Consumer", createConsumerPanel());
        tabbedPane.addTab("Topics", createTopicPanel());
        tabbedPane.addTab("Consumer Groups", createConsumerGroupsPanel());
        add(tabbedPane, BorderLayout.CENTER);
        
        // Bottom - Output Area
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(new TitledBorder("Output"));
        outputArea = new JTextArea(20, 100);
        outputArea.setEditable(false);
        outputArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setPreferredSize(new Dimension(1180, 350));
        bottomPanel.add(scrollPane, BorderLayout.CENTER);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearBtn = new JButton("Clear Output");
        clearBtn.addActionListener(e -> outputArea.setText(""));
        controlPanel.add(clearBtn);
        bottomPanel.add(controlPanel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Show login dialog
     */
    private void showLoginDialog() {
        JDialog loginDialog = new JDialog(this, "Login to DMQ", true);
        loginDialog.setLayout(new BorderLayout(10, 10));
        loginDialog.setSize(400, 250);
        loginDialog.setLocationRelativeTo(this);
        
        // Form panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new EmptyBorder(20, 20, 10, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Username
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField usernameField = new JTextField(15);
        formPanel.add(usernameField, gbc);
        
        // Password
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JPasswordField passwordField = new JPasswordField(15);
        formPanel.add(passwordField, gbc);
        
        // Info label
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        JLabel infoLabel = new JLabel("<html><small>Available users: admin, producer1, consumer1, app1</small></html>");
        infoLabel.setForeground(Color.GRAY);
        formPanel.add(infoLabel, gbc);
        
        loginDialog.add(formPanel, BorderLayout.CENTER);
        
        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        JButton okButton = new JButton("Login");
        JButton cancelButton = new JButton("Cancel");
        
        okButton.addActionListener(e -> {
            String username = usernameField.getText().trim();
            String password = new String(passwordField.getPassword());
            
            if (username.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(loginDialog, "Username and password required", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            // Perform login via CLI
            loginDialog.dispose();
            performLogin(username, password);
        });
        
        cancelButton.addActionListener(e -> loginDialog.dispose());
        
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        loginDialog.add(buttonPanel, BorderLayout.SOUTH);
        
        // Enter key triggers login
        passwordField.addActionListener(e -> okButton.doClick());
        
        loginDialog.setVisible(true);
    }
    
    /**
     * Perform login using CLI command
     */
    private void performLogin(String username, String password) {
        String command = "login --username " + username + " --password " + password;
        
        displayCommandOutput("Authenticating...", "");
        
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", CLI_JAR);
                String[] args = parseCommandLine(command);
                for (String arg : args) {
                    pb.command().add(arg);
                }
                
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );
                
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                int exitCode = process.waitFor();
                
                if (exitCode == 0) {
                    // Login successful, load token
                    loadStoredToken();
                    SwingUtilities.invokeLater(() -> {
                        displayResult(output.toString(), true, exitCode);
                        if (jwtToken != null) {
                            authStatusLabel.setText("Logged in as: " + currentUsername);
                            authStatusLabel.setForeground(new Color(0, 128, 0));
                            loginBtn.setEnabled(false);
                            logoutBtn.setEnabled(true);
                            refreshTokenBtn.setEnabled(true);
                        }
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        displayResult(output.toString(), false, exitCode);
                        JOptionPane.showMessageDialog(this, "Login failed. Check output.", "Error", JOptionPane.ERROR_MESSAGE);
                    });
                }
                
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    displayError("Login error: " + e.getMessage());
                    JOptionPane.showMessageDialog(this, "Login error: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }
    
    /**
     * Load stored JWT token from ~/.dmq/token.properties
     */
    private void loadStoredToken() {
        try {
            String tokenFile = System.getProperty("user.home") + File.separator + ".dmq" + File.separator + "token.properties";
            File file = new File(tokenFile);
            
            if (file.exists()) {
                Properties props = new Properties();
                try (FileReader reader = new FileReader(file)) {
                    props.load(reader);
                }
                
                jwtToken = props.getProperty("token");
                currentUsername = props.getProperty("username");
                
                if (jwtToken != null && !jwtToken.isEmpty()) {
                    authStatusLabel.setText("Logged in as: " + currentUsername);
                    authStatusLabel.setForeground(new Color(0, 128, 0));
                    loginBtn.setEnabled(false);
                    logoutBtn.setEnabled(true);
                    refreshTokenBtn.setEnabled(true);
                }
            }
        } catch (Exception e) {
            // Ignore errors loading token
        }
    }
    
    /**
     * Logout - clear token
     */
    private void logout() {
        executeCliCommand("logout");
        jwtToken = null;
        currentUsername = null;
        authStatusLabel.setText("Not Logged In");
        authStatusLabel.setForeground(Color.RED);
        loginBtn.setEnabled(true);
        logoutBtn.setEnabled(false);
        refreshTokenBtn.setEnabled(false);
    }
    
    /**
     * Refresh token - renew JWT token expiry
     */
    private void refreshToken() {
        if (!logoutBtn.isEnabled()) {
            JOptionPane.showMessageDialog(this, "Not logged in. Please login first.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        displayCommandOutput("Refreshing token...", "");
        
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", CLI_JAR, "refresh-token");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );
                
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                int exitCode = process.waitFor();
                
                if (exitCode == 0) {
                    // Refresh successful, reload token
                    loadStoredToken();
                    SwingUtilities.invokeLater(() -> {
                        displayResult(output.toString(), true, exitCode);
                        JOptionPane.showMessageDialog(this, "Token refreshed successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
                    });
                } else {
                    SwingUtilities.invokeLater(() -> {
                        displayResult(output.toString(), false, exitCode);
                        JOptionPane.showMessageDialog(this, 
                            "Token refresh failed. You may need to login again.", 
                            "Error", 
                            JOptionPane.ERROR_MESSAGE);
                    });
                }
                
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    displayError("Refresh error: " + e.getMessage());
                    JOptionPane.showMessageDialog(this, 
                        "Refresh error: " + e.getMessage(), 
                        "Error", 
                        JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }
    
    // All other methods from original DMQGuiClient remain the same
    // (createProducerPanel, createConsumerPanel, createTopicPanel, createConsumerGroupsPanel,
    //  produceSingleMessage, produceBatchMessages, consumeMessages, etc.)
    // Since CLI now handles JWT automatically via TokenManager, no changes needed to command execution
    
    // Create Topic Panel with all buttons
    private JPanel createTopicPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Form Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Topic Name
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Topic Name:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        topicName = new JTextField(20);
        formPanel.add(topicName, gbc);
        
        // Partitions
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Partitions:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        topicPartitions = new JTextField("3", 20);
        formPanel.add(topicPartitions, gbc);
        
        // Replication Factor
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Replication Factor:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        topicReplication = new JTextField("2", 20);
        formPanel.add(topicReplication, gbc);
        
        panel.add(formPanel, BorderLayout.CENTER);
        
        // Button Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        
        createTopicBtn = new JButton("Create Topic");
        createTopicBtn.addActionListener(e -> createTopic());
        buttonPanel.add(createTopicBtn);
        
        listTopicsBtn = new JButton("List Topics");
        listTopicsBtn.addActionListener(e -> listTopics());
        buttonPanel.add(listTopicsBtn);
        
        describeTopicBtn = new JButton("Describe Topic");
        describeTopicBtn.addActionListener(e -> describeTopic());
        buttonPanel.add(describeTopicBtn);
        
        JButton deleteTopicBtn = new JButton("Delete Topic");
        deleteTopicBtn.addActionListener(e -> deleteTopic());
        buttonPanel.add(deleteTopicBtn);
        
        JButton listBrokersBtn = new JButton("List Brokers");
        listBrokersBtn.addActionListener(e -> listBrokers());
        buttonPanel.add(listBrokersBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private void createTopic() {
        String name = topicName.getText().trim();
        String partitions = topicPartitions.getText().trim();
        String replication = topicReplication.getText().trim();
        
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Topic name is required!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        executeCliCommand("create-topic --name " + name + " --partitions " + partitions + " --replication-factor " + replication);
    }
    
    private void listTopics() {
        String metadataUrl = metadataServiceUrl.getText().trim();
        executeCliCommand("list-topics --metadata-url " + metadataUrl);
    }
    
    private void describeTopic() {
        String name = topicName.getText().trim();
        
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Topic name is required!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        executeCliCommand("describe-topic --name " + name);
    }
    
    private void deleteTopic() {
        String name = topicName.getText().trim();
        
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Topic name is required!", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        // Confirm deletion
        int result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to delete topic '" + name + "'?\nThis action cannot be undone.",
            "Confirm Delete Topic",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        
        if (result != JOptionPane.YES_OPTION) {
            displayResult("Delete operation cancelled", false, 0);
            return;
        }
        
        // Use CLI command - provide "yes" as input
        displayCommandOutput("Deleting topic...", "delete-topic --name " + name);
        
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", CLI_JAR, "delete-topic", "--name", name);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Send "yes" to confirm deletion
                try (java.io.PrintWriter writer = new java.io.PrintWriter(process.getOutputStream())) {
                    writer.println("yes");
                    writer.flush();
                }
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                int exitCode = process.waitFor();
                String deleteResult = output.toString();
                
                SwingUtilities.invokeLater(() -> displayResult(deleteResult, exitCode == 0, exitCode));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> displayError("Error deleting topic: " + e.getMessage()));
            }
        });
    }
    
    private void listBrokers() {
        String metadataUrl = metadataServiceUrl.getText().trim();
        executeCliCommand("list-brokers --metadata-url " + metadataUrl);
    }
    
    private void getRaftLeader() {
        String metadataUrl = metadataServiceUrl.getText().trim();
        executeCliCommand("get-leader --metadata-url " + metadataUrl);
    }
    
    private void executeCliCommand(String cmd) {
        displayCommandOutput("Executing command...", cmd);
        
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", CLI_JAR);
                String[] args = parseCommandLine(cmd);
                for (String arg : args) {
                    pb.command().add(arg);
                }
                
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                int exitCode = process.waitFor();
                String result = output.toString();
                
                SwingUtilities.invokeLater(() -> displayResult(result, exitCode == 0, exitCode));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> displayError("Error executing command: " + e.getMessage()));
            }
        });
    }
    
    private void displayCommandOutput(String desc, String cmd) {
        outputArea.append("========================================\n");
        outputArea.append(desc + "\n");
        outputArea.append("Command: " + cmd + "\n");
        outputArea.append("========================================\n");
    }
    
    private void displayResult(String result, boolean success, int code) {
        outputArea.append(result);
        outputArea.append("\n");
        if (success) {
            outputArea.append("[Exit Code: " + code + " - SUCCESS]\n");
        } else {
            outputArea.append("[Exit Code: " + code + " - FAILED]\n");
        }
        outputArea.append("\n");
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private void displayError(String error) {
        outputArea.append("[ERROR] " + error + "\n\n");
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private String[] parseCommandLine(String command) {
        java.util.List<String> args = new java.util.ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(command);
        while (m.find()) {
            args.add(m.group(1).replace("\"", ""));
        }
        return args.toArray(new String[0]);
    }
    
    // Helper methods for action handlers
    private int getSelectedAcks() {
        String selected = (String) producerAcks.getSelectedItem();
        if (selected.startsWith("0")) return 0;
        if (selected.startsWith("-1")) return -1;
        return 1;
    }
    
    private String escapeQuotes(String str) {
        return str.replace("\"", "\\\"");
    }
    
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
    
    private void appendOutput(String text) {
        outputArea.append(text);
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private void setConsumeButtonsEnabled(boolean enabled) {
        consumeBtn.setEnabled(enabled);
        consumeGroupBtn.setEnabled(enabled);
        consumeGroupContinuousBtn.setEnabled(enabled);
    }
    
    private void executeCliCommandBackground(String command) {
        displayCommandOutput("CLI Command (Continuous): ", command);
        appendOutput("[INFO] Starting continuous consumer... Press 'Stop' to terminate.\n\n");
        
        // Disable consume buttons, enable stop button
        setConsumeButtonsEnabled(false);
        stopConsumeBtn.setEnabled(true);
        
        // Execute in background thread with streaming output
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", CLI_JAR);
                
                // Add command arguments properly
                String[] args = parseCommandLine(command);
                for (String arg : args) {
                    pb.command().add(arg);
                }
                
                pb.redirectErrorStream(true);
                currentConsumeProcess = pb.start();
                
                // Read output in real-time
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(currentConsumeProcess.getInputStream())
                );
                
                String line;
                while ((line = reader.readLine()) != null) {
                    final String outputLine = line + "\n";
                    SwingUtilities.invokeLater(() -> appendOutput(outputLine));
                }
                
                int exitCode = currentConsumeProcess.waitFor();
                
                SwingUtilities.invokeLater(() -> {
                    if (exitCode != 0 && currentConsumeProcess.isAlive()) {
                        displayError("Consumer process terminated with exit code: " + exitCode);
                    }
                    setConsumeButtonsEnabled(true);
                    stopConsumeBtn.setEnabled(false);
                    currentConsumeProcess = null;
                });
                
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    displayError("CLI Error: " + e.getMessage());
                    setConsumeButtonsEnabled(true);
                    stopConsumeBtn.setEnabled(false);
                    currentConsumeProcess = null;
                });
            }
        });
    }
    
    // Action handler methods
    private void produceSingleMessage() {
        String topic = producerTopic.getText().trim();
        String key = producerKey.getText().trim();
        String value = producerValue.getText().trim();
        String partition = producerPartition.getText().trim();
        int acks = getSelectedAcks();
        
        if (topic.isEmpty() || value.isEmpty()) {
            showError("Topic and Value are required!");
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        cmd.append("produce --topic ").append(topic);
        
        if (!key.isEmpty()) {
            cmd.append(" --key \"").append(escapeQuotes(key)).append("\"");
        }
        
        cmd.append(" --value \"").append(escapeQuotes(value)).append("\"");
        
        if (!partition.isEmpty()) {
            cmd.append(" --partition ").append(partition);
        }
        
        cmd.append(" --acks ").append(acks);
        
        executeCliCommand(cmd.toString());
    }
    
    private void produceBatchMessages() {
        String topic = producerTopic.getText().trim();
        String partition = producerPartition.getText().trim();
        int acks = getSelectedAcks();
        
        if (topic.isEmpty()) {
            showError("Topic is required!");
            return;
        }
        
        // File chooser for batch file
        JFileChooser fileChooser = new JFileChooser(".");
        fileChooser.setDialogTitle("Select Batch Messages File");
        int result = fileChooser.showOpenDialog(this);
        
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            
            StringBuilder cmd = new StringBuilder();
            cmd.append("produce --topic ").append(topic);
            cmd.append(" --batch-file \"").append(file.getAbsolutePath()).append("\"");
            
            if (!partition.isEmpty()) {
                cmd.append(" --partition ").append(partition);
            }
            
            cmd.append(" --acks ").append(acks);
            
            executeCliCommand(cmd.toString());
        }
    }
    
    private void consumeMessages() {
        String topic = consumerTopic.getText().trim();
        String partition = consumerPartition.getText().trim();
        String offset = consumerOffset.getText().trim();
        String maxMessages = consumerMaxMessages.getText().trim();
        
        if (topic.isEmpty() || partition.isEmpty() || offset.isEmpty()) {
            showError("Topic, Partition, and Offset are required!");
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        cmd.append("consume --topic ").append(topic);
        cmd.append(" --partition ").append(partition);
        cmd.append(" --offset ").append(offset);
        cmd.append(" --max-messages ").append(maxMessages.isEmpty() ? "10" : maxMessages);
        
        executeCliCommand(cmd.toString());
    }
    
    private void consumeWithGroup() {
        String topic = consumerTopic.getText().trim();
        String groupId = consumerGroupId.getText().trim();
        String maxMessages = consumerMaxMessages.getText().trim();
        
        if (topic.isEmpty()) {
            showError("Topic is required!");
            return;
        }
        
        if (groupId.isEmpty()) {
            showError("Group ID / App ID is required for group-based consumption!");
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        cmd.append("consume-group --topic ").append(topic);
        cmd.append(" --app-id ").append(groupId);
        cmd.append(" --max-messages ").append(maxMessages.isEmpty() ? "100" : maxMessages);
        cmd.append(" --metadata-url ").append(metadataServiceUrl.getText().trim());
        
        executeCliCommand(cmd.toString());
    }
    
    private void consumeWithGroupContinuous() {
        String topic = consumerTopic.getText().trim();
        String groupId = consumerGroupId.getText().trim();
        String maxMessages = consumerMaxMessages.getText().trim();
        
        if (topic.isEmpty()) {
            showError("Topic is required!");
            return;
        }
        
        if (groupId.isEmpty()) {
            showError("Group ID / App ID is required for group-based consumption!");
            return;
        }
        
        StringBuilder cmd = new StringBuilder();
        cmd.append("consume-group --topic ").append(topic);
        cmd.append(" --app-id ").append(groupId);
        cmd.append(" --max-messages ").append(maxMessages.isEmpty() ? "100" : maxMessages);
        cmd.append(" --metadata-url ").append(metadataServiceUrl.getText().trim());
        cmd.append(" --continuous");  // Enable continuous mode
        
        executeCliCommandBackground(cmd.toString());
    }
    
    private void stopContinuousConsume() {
        if (currentConsumeProcess != null && currentConsumeProcess.isAlive()) {
            currentConsumeProcess.destroy();
            appendOutput("\n[STOPPED] Consumer process terminated\n");
            currentConsumeProcess = null;
            setConsumeButtonsEnabled(true);
            stopConsumeBtn.setEnabled(false);
        }
    }
    
    private void listConsumerGroups() {
        String metadataUrl = metadataServiceUrl.getText().trim();
        executeCliCommand("list-groups --metadata-url " + metadataUrl);
    }
    
    private void describeConsumerGroup() {
        String topic = groupTopic.getText().trim();
        String appId = groupAppId.getText().trim();
        
        if (topic.isEmpty() || appId.isEmpty()) {
            showError("Topic and App ID are required!");
            return;
        }
        
        String metadataUrl = metadataServiceUrl.getText().trim();
        
        StringBuilder cmd = new StringBuilder();
        cmd.append("describe-group --topic ").append(topic);
        cmd.append(" --app-id ").append(appId);
        cmd.append(" --metadata-url ").append(metadataUrl);
        
        executeCliCommand(cmd.toString());
    }
    
    // Panel creation methods - full implementation
    private JPanel createProducerPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Form Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Topic
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Topic:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        producerTopic = new JTextField(20);
        formPanel.add(producerTopic, gbc);
        
        // Key
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Key:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        producerKey = new JTextField(20);
        formPanel.add(producerKey, gbc);
        
        // Value
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Value:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        producerValue = new JTextArea(5, 20);
        producerValue.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        formPanel.add(new JScrollPane(producerValue), gbc);
        
        // Partition
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0; gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        formPanel.add(new JLabel("Partition (optional):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        producerPartition = new JTextField(20);
        producerPartition.setToolTipText("Leave empty for auto-selection");
        formPanel.add(producerPartition, gbc);
        
        // Acks
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Acks:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        producerAcks = new JComboBox<>(new String[]{"1 (Leader)", "0 (None)", "-1 (All ISR)"});
        formPanel.add(producerAcks, gbc);
        
        panel.add(formPanel, BorderLayout.CENTER);
        
        // Button Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        
        produceSingleBtn = new JButton("Produce Single Message");
        produceSingleBtn.addActionListener(e -> produceSingleMessage());
        buttonPanel.add(produceSingleBtn);
        
        produceBatchBtn = new JButton("Produce Batch (File)");
        produceBatchBtn.addActionListener(e -> produceBatchMessages());
        buttonPanel.add(produceBatchBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createConsumerPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Form Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Topic
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Topic:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        consumerTopic = new JTextField(20);
        formPanel.add(consumerTopic, gbc);
        
        // Group ID / App ID
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Group ID / App ID (optional):"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        consumerGroupId = new JTextField(20);
        consumerGroupId.setToolTipText("For group-based consumption. Leave empty for simple consume.");
        formPanel.add(consumerGroupId, gbc);
        
        // Partition
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Partition:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        consumerPartition = new JTextField("0", 20);
        formPanel.add(consumerPartition, gbc);
        
        // Offset
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Start Offset:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        consumerOffset = new JTextField("0", 20);
        formPanel.add(consumerOffset, gbc);
        
        // Max Messages
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Max Messages:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        consumerMaxMessages = new JTextField("10", 20);
        formPanel.add(consumerMaxMessages, gbc);
        
        panel.add(formPanel, BorderLayout.CENTER);
        
        // Button Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        
        consumeBtn = new JButton("Consume (Partition)");
        consumeBtn.setToolTipText("Direct partition consumption");
        consumeBtn.addActionListener(e -> consumeMessages());
        buttonPanel.add(consumeBtn);
        
        consumeGroupBtn = new JButton("Consume (Group - Once)");
        consumeGroupBtn.setToolTipText("Consumer group - single poll");
        consumeGroupBtn.addActionListener(e -> consumeWithGroup());
        buttonPanel.add(consumeGroupBtn);
        
        consumeGroupContinuousBtn = new JButton("Consume (Group - Continuous)");
        consumeGroupContinuousBtn.setToolTipText("Consumer group - continuous polling with heartbeat");
        consumeGroupContinuousBtn.addActionListener(e -> consumeWithGroupContinuous());
        buttonPanel.add(consumeGroupContinuousBtn);
        
        stopConsumeBtn = new JButton("Stop");
        stopConsumeBtn.setEnabled(false);
        stopConsumeBtn.setForeground(Color.RED);
        stopConsumeBtn.addActionListener(e -> stopContinuousConsume());
        buttonPanel.add(stopConsumeBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createConsumerGroupsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Form Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Topic
        gbc.gridx = 0; gbc.gridy = 0;
        formPanel.add(new JLabel("Topic:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        groupTopic = new JTextField(20);
        groupTopic.setToolTipText("Topic name for the consumer group");
        formPanel.add(groupTopic, gbc);
        
        // App ID
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        formPanel.add(new JLabel("App ID:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        groupAppId = new JTextField(20);
        groupAppId.setToolTipText("Application ID (consumer group identifier)");
        formPanel.add(groupAppId, gbc);
        
        panel.add(formPanel, BorderLayout.CENTER);
        
        // Button Panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        
        listGroupsBtn = new JButton("List All Consumer Groups");
        listGroupsBtn.addActionListener(e -> listConsumerGroups());
        buttonPanel.add(listGroupsBtn);
        
        describeGroupBtn = new JButton("Describe Consumer Group");
        describeGroupBtn.addActionListener(e -> describeConsumerGroup());
        buttonPanel.add(describeGroupBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default look and feel
        }
        
        SwingUtilities.invokeLater(() -> new DMQGuiClientWithAuth());
    }
}
