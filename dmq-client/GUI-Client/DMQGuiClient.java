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
 * Minimal GUI Client for DMQ System
 * Wraps CLI commands for easy testing and visualization
 * Now supports JWT authentication
 */
public class DMQGuiClient extends JFrame {
    
    // Try multiple possible locations for the CLI JAR
    private static final String[] CLI_JAR_PATHS = {
        "../target/mycli.jar",                           // Original location (if built separately)
        "../../dmq-client/target/mycli.jar",             // From GUI-Client to dmq-client
        System.getProperty("user.dir") + "/../target/mycli.jar"  // Relative to current directory
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
    
    public DMQGuiClient() {
        setTitle("DMQ GUI Client - Enhanced with JWT Auth");
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
        
        // Center - Tabbed Pane
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Producer", createProducerPanel());
        tabbedPane.addTab("Consumer", createConsumerPanel());
        tabbedPane.addTab("Topics", createTopicPanel());
        tabbedPane.addTab("Consumer Groups", createConsumerGroupsPanel());
        add(tabbedPane, BorderLayout.CENTER);
        
        // Bottom - Output Area (LARGER)
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(new TitledBorder("Output"));
        outputArea = new JTextArea(20, 100);  // Increased from 10, 80
        outputArea.setEditable(false);
        outputArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setPreferredSize(new Dimension(1180, 350));  // Larger size
        bottomPanel.add(scrollPane, BorderLayout.CENTER);
        
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton clearBtn = new JButton("Clear Output");
        clearBtn.addActionListener(e -> outputArea.setText(""));
        controlPanel.add(clearBtn);
        bottomPanel.add(controlPanel, BorderLayout.SOUTH);
        
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
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
    
    private JPanel createConsumerGroupsPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Form Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Group ID
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
    
    private void createTopic() {
        String name = topicName.getText().trim();
        String partitions = topicPartitions.getText().trim();
        String replication = topicReplication.getText().trim();
        
        if (name.isEmpty() || partitions.isEmpty() || replication.isEmpty()) {
            showError("All fields are required!");
            return;
        }
        
        String cmd = String.format("create-topic --name %s --partitions %s --replication-factor %s",
                name, partitions, replication);
        
        executeCliCommand(cmd);
    }
    
    private void listTopics() {
        // Use CLI command
        executeCliCommand("list-topics");
    }
    
    private void describeTopic() {
        String name = topicName.getText().trim();
        
        if (name.isEmpty()) {
            showError("Topic name is required!");
            return;
        }
        
        // Use CLI command
        executeCliCommand("describe-topic --name " + name);
    }
    
    private void deleteTopic() {
        String name = topicName.getText().trim();
        
        if (name.isEmpty()) {
            showError("Topic name is required!");
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
        
        // Use CLI command - but we need to bypass the confirmation prompt
        // We'll handle this by providing "yes" as input
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
        // Use CLI command with metadata URL
        executeCliCommand("list-brokers --metadata-url " + metadataUrl);
    }
    
    private void listConsumerGroups() {
        String metadataUrl = metadataServiceUrl.getText().trim();
        // Use CLI command with metadata URL
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
        
        // Use CLI command with topic and app-id
        StringBuilder cmd = new StringBuilder();
        cmd.append("describe-group --topic ").append(topic);
        cmd.append(" --app-id ").append(appId);
        cmd.append(" --metadata-url ").append(metadataUrl);
        
        executeCliCommand(cmd.toString());
    }
    
    private void getRaftLeader() {
        // Get metadata URL from text field
        String metadataUrl = metadataServiceUrl.getText().trim();
        if (metadataUrl.isEmpty()) {
            metadataUrl = "http://localhost:9091";
        }
        
        // Use CLI command with metadata URL
        executeCliCommand("get-leader --metadata-url " + metadataUrl);
    }
    
    private void executeCliCommand(String command) {
        displayCommandOutput("CLI Command: " + command, "");
        
        // Disable buttons during execution
        setButtonsEnabled(false);
        
        // Execute in background thread
        CompletableFuture.runAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("java", "-jar", CLI_JAR);
                
                // Add command arguments properly
                String[] args = parseCommandLine(command);
                for (String arg : args) {
                    pb.command().add(arg);
                }
                
                pb.redirectErrorStream(true);
                Process process = pb.start();
                
                // Read output
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
                );
                
                StringBuilder output = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
                
                int exitCode = process.waitFor();
                
                String result = output.toString();
                if (result.isEmpty()) {
                    result = "(No output)";
                }
                
                final String finalResult = result;
                final boolean success = (exitCode == 0);
                final int finalExitCode = exitCode;
                
                SwingUtilities.invokeLater(() -> {
                    displayResult(finalResult, success, finalExitCode);
                });
                
            } catch (Exception e) {
                final String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                SwingUtilities.invokeLater(() -> {
                    displayError("CLI Error: " + errorMsg);
                });
            } finally {
                SwingUtilities.invokeLater(() -> setButtonsEnabled(true));
            }
        });
    }
    
    private void executeCliCommandBackground(String command) {
        displayCommandOutput("CLI Command (Continuous): " + command, "");
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
                    final String outputLine = line;
                    SwingUtilities.invokeLater(() -> {
                        if (prettyModeCheckbox.isSelected()) {
                            String cleaned = cleanOutput(outputLine);
                            if (!cleaned.trim().isEmpty()) {
                                appendOutput(cleaned + "\n");
                            }
                        } else {
                            appendOutput(outputLine + "\n");
                        }
                    });
                }
                
                int exitCode = currentConsumeProcess.waitFor();
                
                final int finalExitCode = exitCode;
                SwingUtilities.invokeLater(() -> {
                    if (finalExitCode == 0) {
                        appendOutput("\n[INFO] Consumer stopped gracefully\n");
                    } else {
                        appendOutput("\n[INFO] Consumer stopped with exit code: " + finalExitCode + "\n");
                    }
                    currentConsumeProcess = null;
                    setConsumeButtonsEnabled(true);
                    stopConsumeBtn.setEnabled(false);
                });
                
            } catch (Exception e) {
                final String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                SwingUtilities.invokeLater(() -> {
                    appendOutput("[ERROR] " + errorMsg + "\n");
                    currentConsumeProcess = null;
                    setConsumeButtonsEnabled(true);
                    stopConsumeBtn.setEnabled(false);
                });
            }
        });
    }
    
    private void appendOutput(String text) {
        outputArea.append(text);
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private String[] parseCommandLine(String command) {
        // Parse command line respecting quotes
        java.util.List<String> args = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]*)\"|([^\\s]+)").matcher(command);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                args.add(matcher.group(1)); // Quoted argument
            } else {
                args.add(matcher.group(2)); // Unquoted argument
            }
        }
        return args.toArray(new String[0]);
    }
    
    private void displayCommandOutput(String description, String command) {
        if (prettyModeCheckbox.isSelected()) {
            // Pretty mode - minimal output
            outputArea.append("\n> " + description + "\n");
        } else {
            // Full mode - with decorations
            outputArea.append("\n" + "=".repeat(80) + "\n");
            outputArea.append(description + "\n");
            if (!command.isEmpty()) {
                outputArea.append("Command: " + command + "\n");
            }
            outputArea.append("=".repeat(80) + "\n");
        }
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private void displayResult(String result, boolean success, int code) {
        if (prettyModeCheckbox.isSelected()) {
            // Pretty mode - show only essential content
            String cleaned = cleanOutput(result);
            outputArea.append(cleaned + "\n");
        } else {
            // Full mode - show everything
            if (success) {
                outputArea.append("[OK] SUCCESS (Code: " + code + ")\n");
            } else {
                outputArea.append("[X] FAILED (Code: " + code + ")\n");
            }
            outputArea.append("-".repeat(80) + "\n");
            outputArea.append(result + "\n");
        }
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private void displayError(String error) {
        if (prettyModeCheckbox.isSelected()) {
            outputArea.append("[X] ERROR: " + error + "\n");
        } else {
            outputArea.append("[X] ERROR\n");
            outputArea.append("-".repeat(80) + "\n");
            outputArea.append(error + "\n");
        }
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }
    
    private String cleanOutput(String output) {
        // Remove ANSI codes, emojis, and decorative elements for pretty mode
        String cleaned = output;
        
        // Remove ANSI escape codes
        cleaned = cleaned.replaceAll("\\u001B\\[[;\\d]*m", "");
        
        // Remove box drawing characters
        cleaned = cleaned.replaceAll("[═╔╚╗╝║]", "");
        
        // Remove unsupported Unicode characters that show as "?"
        cleaned = cleaned.replaceAll("[✓✗▶●ℹ⚠]", "");
        cleaned = cleaned.replaceAll("\\? ", "");
        
        // Remove leading/trailing whitespace from each line and filter logs
        String[] lines = cleaned.split("\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Skip separator lines (3 or more consecutive - or =)
            if (trimmed.matches("^[-=]{3,}$")) {
                continue;
            }
            
            // Skip log lines (INFO, DEBUG, WARN, etc.)
            if (trimmed.matches("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\[.*\\]\\s+(INFO|DEBUG|WARN|ERROR|TRACE).*")) {
                continue;
            }
            
            // Skip common verbose messages
            if (trimmed.startsWith("Trying to load config") ||
                trimmed.startsWith("Loaded service configuration") ||
                trimmed.startsWith("Connected to controller")) {
                continue;
            }
            
            // Skip "Error: null" messages
            if (trimmed.equals("Error: null")) {
                continue;
            }
            
            if (!trimmed.isEmpty()) {
                result.append(trimmed).append("\n");
            }
        }
        
        return result.toString().trim();
    }
    
    private int getSelectedAcks() {
        String selected = (String) producerAcks.getSelectedItem();
        if (selected.startsWith("0")) return 0;
        if (selected.startsWith("-1")) return -1;
        return 1;
    }
    
    private void setButtonsEnabled(boolean enabled) {
        produceSingleBtn.setEnabled(enabled);
        produceBatchBtn.setEnabled(enabled);
        consumeBtn.setEnabled(enabled);
        consumeGroupBtn.setEnabled(enabled);
        consumeGroupContinuousBtn.setEnabled(enabled);
        createTopicBtn.setEnabled(enabled);
        listTopicsBtn.setEnabled(enabled);
        describeTopicBtn.setEnabled(enabled);
        listGroupsBtn.setEnabled(enabled);
        describeGroupBtn.setEnabled(enabled);
        getLeaderBtn.setEnabled(enabled);
    }
    
    private void setConsumeButtonsEnabled(boolean enabled) {
        consumeBtn.setEnabled(enabled);
        consumeGroupBtn.setEnabled(enabled);
        consumeGroupContinuousBtn.setEnabled(enabled);
    }
    
    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
    
    private String escapeQuotes(String str) {
        return str.replace("\"", "\\\"");
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
            
            loginDialog.dispose();
            performLogin(username, password);
        });
        
        cancelButton.addActionListener(e -> loginDialog.dispose());
        
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        loginDialog.add(buttonPanel, BorderLayout.SOUTH);
        
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
     * Refresh JWT token
     */
    private void refreshToken() {
        executeCliCommand("refresh-token");
        loadStoredToken();
    }
    
    public static void main(String[] args) {
        // Set look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // Use default look and feel
        }
        
        SwingUtilities.invokeLater(() -> new DMQGuiClient());
    }
}
