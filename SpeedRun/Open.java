import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public class Open extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String UI_FONT = "Leelawadee UI";
    private static final String HOME = "home";
    private static final String ONLINE = "online";
    private static final Color BACKGROUND = new Color(242, 246, 250);
    private static final Color NAVY = new Color(28, 48, 73);
    private static final Color MUTED = new Color(101, 116, 132);

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel pages = new JPanel(cardLayout);
    private final JTextField playerNameField = new JTextField("Player 1");
    private final JTextField hostField = new JTextField("127.0.0.1");
    private final JTextField portField = new JTextField("50101");
    private final JLabel onlineStatus = new JLabel("สร้างห้องเพื่อเปิด Server หรือกรอก IP/Port เพื่อเข้าร่วม", SwingConstants.CENTER);
    private JButton createButton;
    private JButton joinButton;

    public Open() {
        setTitle("Speed Run");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(480, 320));
        setSize(1280, 800);
        setLocationRelativeTo(null);

        pages.add(createHomePage(), HOME);
        pages.add(createOnlinePage(), ONLINE);
        setContentPane(pages);
        cardLayout.show(pages, HOME);
    }

    private JPanel createHomePage() {
        JPanel page = new JPanel(new BorderLayout(0, 24));
        page.setBorder(BorderFactory.createEmptyBorder(40, 60, 40, 60));

        JLabel title = new JLabel("Speed Run", SwingConstants.CENTER);
        title.setFont(new Font(UI_FONT, Font.BOLD, 32));
        page.add(title, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        JButton playButton = new JButton("เล่นเกม");
        playButton.setFont(new Font(UI_FONT, Font.PLAIN, 16));
        playButton.addActionListener(event -> openGame());
        JButton onlineButton = new JButton("เล่นออนไลน์");
        onlineButton.setFont(new Font(UI_FONT, Font.PLAIN, 16));
        onlineButton.addActionListener(event -> cardLayout.show(pages, ONLINE));
        buttons.add(playButton);
        buttons.add(onlineButton);
        page.add(buttons, BorderLayout.SOUTH);
        return page;
    }

    private JPanel createOnlinePage() {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setBorder(BorderFactory.createEmptyBorder(30, 50, 30, 50));

        JLabel title = new JLabel("เล่นออนไลน์", SwingConstants.CENTER);
        title.setFont(new Font(UI_FONT, Font.BOLD, 28));
        page.add(title, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridLayout(0, 1, 0, 8));
        form.add(createFieldLabel("ชื่อผู้เล่น"));
        styleTextField(playerNameField);
        form.add(playerNameField);

        createButton = new JButton("สร้างห้อง (เปิด Server)");
        createButton.setFont(new Font(UI_FONT, Font.PLAIN, 15));
        // ปุ่มนี้จะเปิด GameServer บนเครื่องนี้ และเชื่อมผู้เล่นที่สร้างห้องเข้าห้องเดียวกัน
        createButton.addActionListener(event -> connectOnline(true));
        form.add(createButton);

        form.add(createFieldLabel("IP ของ Server"));
        styleTextField(hostField);
        form.add(hostField);

        form.add(createFieldLabel("Port"));
        styleTextField(portField);
        form.add(portField);

        joinButton = new JButton("เข้าร่วม Server");
        joinButton.setFont(new Font(UI_FONT, Font.PLAIN, 15));
        joinButton.addActionListener(event -> connectOnline(false));
        form.add(joinButton);

        onlineStatus.setFont(new Font(UI_FONT, Font.PLAIN, 12));
        onlineStatus.setForeground(MUTED);
        form.add(onlineStatus);
        page.add(form, BorderLayout.CENTER);

        JButton backButton = new JButton("กลับหน้าแรก");
        backButton.setFont(new Font(UI_FONT, Font.PLAIN, 14));
        backButton.addActionListener(event -> cardLayout.show(pages, HOME));
        page.add(backButton, BorderLayout.SOUTH);
        return page;
    }

    private void connectOnline(boolean createServer) {
        // ตรวจสอบข้อมูลก่อนเริ่มเชื่อมต่อ เพื่อไม่ให้เปิด Server หรือพยายามต่อด้วยค่าที่ไม่ครบ
        String playerName = playerNameField.getText().trim();
        if (playerName.isEmpty()) {
            onlineStatus.setText("กรุณากรอกชื่อผู้เล่นก่อน");
            playerNameField.requestFocusInWindow();
            return;
        }
        String host = hostField.getText().trim();
        if (!createServer && host.isEmpty()) {
            onlineStatus.setText("กรุณากรอก IP หรือชื่อเครื่องของ Server");
            hostField.requestFocusInWindow();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException exception) {
            onlineStatus.setText("Port ต้องเป็นตัวเลขตั้งแต่ 1 ถึง 65535");
            portField.requestFocusInWindow();
            return;
        }

        // ปิดปุ่มระหว่างทำงาน ป้องกันการกดเริ่มเชื่อมต่อซ้ำ และแจ้งสถานะให้ผู้เล่นทราบ
        setConnectionControlsEnabled(false);
        onlineStatus.setText(createServer ? "กำลังเปิด Server และเข้าห้อง..." : "กำลังเชื่อมต่อ Server...");

        // การเปิด Server/เชื่อมต่ออาจใช้เวลา จึงทำงานเบื้องหลังแทนการบล็อก Swing UI thread
        SwingWorker<ConnectionResult, Void> worker = new SwingWorker<>() {
            @Override
            protected ConnectionResult doInBackground() throws IOException {
                // กรณีสร้างห้อง ให้เริ่ม Server ที่ Port ที่กรอกก่อน
                // กรณีเข้าร่วมห้อง server จะอยู่บนเครื่องอื่น จึงไม่สร้าง GameServer ในเครื่องนี้
                GameServer server = createServer ? new GameServer(port) : null;
                try {
                    // เจ้าของห้องเชื่อมกลับเข้าหา Server ในเครื่องตัวเองผ่าน 127.0.0.1
                    // ส่วนผู้เข้าร่วมใช้ IP/ชื่อเครื่องที่กรอกไว้
                    GameClient client = new GameClient(createServer ? "127.0.0.1" : host, port, playerName);
                    return new ConnectionResult(client, server, port);
                } catch (IOException exception) {
                    // หากเปิด Server สำเร็จแต่สร้าง Client ไม่สำเร็จ ให้ปิด Server ที่เพิ่งเปิด
                    if (server != null) {
                        server.close();
                    }
                    throw exception;
                }
            }

            @Override
            protected void done() {
                // done() ทำงานกลับมาบน Swing UI thread จึงอัปเดตหน้าจอและเปิดหน้าต่างเกมได้
                setConnectionControlsEnabled(true);
                try {
                    ConnectionResult result = get();
                    onlineStatus.setText(createServer
                            ? "เปิด Server ที่ Port " + result.port + " แล้ว"
                            : "เชื่อมต่อ Server แล้ว");
                    // แสดง IP ภายในเครือข่ายให้ผู้เล่นคนอื่นใช้เชื่อมต่อ เมื่อเราเป็นผู้สร้างห้อง
                    String serverAddress = createServer ? getLocalServerAddress() : host;
                    openOnlineGame(result.client, result.server, serverAddress, result.port);
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    onlineStatus.setText("เชื่อมต่อไม่สำเร็จ: " + cause.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void setConnectionControlsEnabled(boolean enabled) {
        createButton.setEnabled(enabled);
        joinButton.setEnabled(enabled);
    }

    private static String getLocalServerAddress() {
        // หา IPv4 ของการ์ดเครือข่ายที่ใช้งานอยู่ เพื่อแสดงที่อยู่ให้เครื่องอื่นในเครือข่ายใช้
        // ไม่เลือก loopback เพราะ 127.0.0.1 ใช้ได้เฉพาะจากเครื่องตัวเอง
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException exception) {
            System.err.println("Could not find local server address: " + exception.getMessage());
        }
        return "ไม่พบ IP ในเครือข่าย";
    }

    private void openOnlineGame(GameClient client, GameServer server, String serverAddress, int serverPort) {
        // ซ่อนหน้าเมนูและเปิดเกมออนไลน์ โดยส่ง Client รวมถึงข้อมูล Server ไปให้หน้าต่างเกม
        setVisible(false);
        Game game = new Game(client, serverAddress, serverPort);
        game.setLocationRelativeTo(null);
        game.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                // ปิดการเชื่อมต่อของผู้เล่นทุกกรณี และปิด Server เพิ่มเมื่อหน้าต่างนี้เป็นผู้สร้างห้อง
                client.close();
                if (server != null) {
                    server.close();
                    onlineStatus.setText("ปิด Server แล้ว");
                }
                setLocationRelativeTo(null);
                setVisible(true);
            }
        });
        game.setVisible(true);
    }

    private static final class ConnectionResult {
        // รวมผลจากงานเบื้องหลัง เพื่อส่ง Client, Server (ถ้ามี) และ Port กลับไปให้ done()
        private final GameClient client;
        private final GameServer server;
        private final int port;

        ConnectionResult(GameClient client, GameServer server, int port) {
            this.client = client;
            this.server = server;
            this.port = port;
        }
    }

    private void openGame() {
        setVisible(false);
        Game game = new Game();
        game.setLocationRelativeTo(null);
        game.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent event) {
                setLocationRelativeTo(null);
                setVisible(true);
            }
        });
        game.setVisible(true);
    }

    private static JLabel createFieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(UI_FONT, Font.BOLD, 14));
        label.setForeground(NAVY);
        return label;
    }

    private static void styleTextField(JTextField field) {
        field.setFont(new Font(UI_FONT, Font.PLAIN, 15));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(195, 207, 220)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Open().setVisible(true));
    }
}
